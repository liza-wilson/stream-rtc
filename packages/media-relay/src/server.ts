import { createServer } from 'node:http'
import { WebSocketServer, type WebSocket } from 'ws'
import { Budget } from './budget.js'
import { handleClientFrame, requestKeyframe } from './fanout.js'
import { NonceStore } from './nonce-store.js'
import { RelayRegistry } from './registry.js'
import type { RelayMember, WsLike } from './types.js'

export interface RelayRoomClaims {
  appId: string
  userId: string
  roomId: string
  role: 'member' | 'host'
  nonce: string
  exp: number
  video?: boolean
}

export interface RelayDeps {
  verify(token: string): RelayRoomClaims
  now(): number
  registry?: RelayRegistry
  limits?(appId: string): { maxParticipants: number; maxVideo: number } | undefined
}

export interface RelayHandle {
  close(): Promise<void>
  registry: RelayRegistry
  address: string
  httpUrl: string
}

interface SocketState {
  member?: RelayMember
  budget: Budget
  registerTimer: NodeJS.Timeout
}

const REGISTER_TIMEOUT_MS = 5_000
const NONCE_TTL_MS = 10 * 60_000
const MAX_MEDIA_BYTES = 512 * 1024
const SWEEP_INTERVAL_MS = 10_000
const DEFAULT_LIMITS = { maxParticipants: 16, maxVideo: 8 }

function sendJson(ws: WsLike, message: Record<string, unknown>): void {
  try {
    ws.send(JSON.stringify(message))
  } catch {
    // closed sockets surface through the close handler
  }
}

export async function startRelay(opts: {
  port: number
  host?: string
  deps: RelayDeps
}): Promise<RelayHandle> {
  const registry = opts.deps.registry ?? new RelayRegistry({ now: opts.deps.now })
  const nonces = new NonceStore(NONCE_TTL_MS)

  const http = createServer((req, res) => {
    const url = req.url ?? '/'
    if (url === '/healthz') {
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ status: 'ok' }))
      return
    }
    if (url === '/readyz') {
      const stats = registry.stats()
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ status: 'ready', ...stats }))
      return
    }
    res.writeHead(404, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: 'not_found' }))
  })

  const wss = new WebSocketServer({ server: http, maxPayload: MAX_MEDIA_BYTES + 64 })

  await new Promise<void>((resolve) =>
    http.listen(opts.port, opts.host ?? '0.0.0.0', () => resolve()),
  )

  const address = http.address()
  const port = typeof address === 'object' && address !== null ? address.port : opts.port

  const states = new Map<WebSocket, SocketState>()

  wss.on('connection', (socket: WebSocket) => {
    const state: SocketState = {
      budget: new Budget(),
      registerTimer: setTimeout(() => {
        socket.close(4401, 'register_timeout')
      }, REGISTER_TIMEOUT_MS),
    }
    states.set(socket, state)

    socket.on('message', (data, isBinary) => {
      const current = states.get(socket)
      if (!current) return

      if (isBinary) {
        if (!current.member) {
          socket.close(4401, 'not_registered')
          return
        }
        const frame = Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer)
        if (frame.length > MAX_MEDIA_BYTES) {
          socket.close(1009, 'frame_too_large')
          return
        }
        if (!current.budget.tryConsume('media', opts.deps.now())) {
          socket.close(1008, 'media_rate_limit')
          return
        }
        try {
          handleClientFrame(registry, current.member, frame, {
            now: opts.deps.now,
          })
        } catch {
          socket.close(1011, 'relay_error')
        }
        return
      }

      if (!current.budget.tryConsume('control', opts.deps.now())) {
        socket.close(1008, 'control_rate_limit')
        return
      }

      let message: unknown
      try {
        message = JSON.parse(data.toString())
      } catch {
        return
      }
      if (typeof message !== 'object' || message === null) return
      const typed = message as { t?: unknown; token?: unknown }
      if (typed.t !== 'register') return
      if (current.member) return

      let claims: RelayRoomClaims
      try {
        if (typeof typed.token !== 'string' || typed.token.length === 0) throw new Error('missing token')
        claims = opts.deps.verify(typed.token)
      } catch {
        socket.close(4401, 'invalid_token')
        return
      }

      const now = opts.deps.now()
      if (nonces.has(claims.nonce)) {
        socket.close(4401, 'replayed_token')
        return
      }

      const limits = opts.deps.limits?.(claims.appId) ?? DEFAULT_LIMITS
      let joined
      try {
        joined = registry.join(claims.appId, claims.roomId, claims.userId, socket, {
          hasVideo: claims.video === true,
          maxParticipants: limits.maxParticipants,
          maxVideo: limits.maxVideo,
        })
      } catch {
        socket.close(4409, 'room_unavailable')
        return
      }
      nonces.burn(claims.nonce, now)

      clearTimeout(current.registerTimer)
      current.member = joined.member

      const peers = registry.peers(claims.appId, claims.roomId, claims.userId)
      sendJson(socket, {
        t: 'registered',
        slot: joined.member.slot,
        peers: peers.map((p) => p.slot),
        limits: { maxParticipants: limits.maxParticipants, maxVideoParticipants: limits.maxVideo },
      })

      for (const peer of peers) {
        sendJson(peer.ws, { t: 'peer_joined', slot: joined.member.slot, userId: claims.userId })
      }
      requestKeyframe(peers)
    })

    const drop = (): void => {
      const current = states.get(socket)
      if (!current) return
      states.delete(socket)
      clearTimeout(current.registerTimer)
      if (!current.member) return
      const left = registry.leave(socket)
      if (!left) return
      for (const peer of registry.peers(left.appId, left.roomId, left.userId)) {
        sendJson(peer.ws, { t: 'peer_left', slot: left.slot, userId: left.userId })
      }
    }

    socket.on('close', drop)
    socket.on('error', drop)
  })

  const sweep = setInterval(() => {
    const now = opts.deps.now()
    nonces.prune(now)
    registry.prune(now)
  }, SWEEP_INTERVAL_MS)
  sweep.unref?.()

  return {
    registry,
    address: `ws://127.0.0.1:${port}`,
    httpUrl: `http://127.0.0.1:${port}`,
    close: async () => {
      clearInterval(sweep)
      for (const socket of states.keys()) {
        try {
          socket.terminate()
        } catch {
          // already gone
        }
      }
      states.clear()
      await new Promise<void>((resolve) => wss.close(() => resolve()))
      http.closeAllConnections()
      await new Promise<void>((resolve) => http.close(() => resolve()))
    },
  }
}
