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
}

export interface RelayDeps {
  verify(token: string): RelayRoomClaims
  now(): number
  registry?: RelayRegistry
}

export interface RelayHandle {
  close(): Promise<void>
  registry: RelayRegistry
  address: string
}

interface SocketState {
  member?: RelayMember
  budget: Budget
  registerTimer: NodeJS.Timeout
}

const REGISTER_TIMEOUT_MS = 5_000
const NONCE_TTL_MS = 10 * 60_000

function sendJson(ws: WsLike, message: Record<string, unknown>): void {
  try {
    ws.send(JSON.stringify(message))
  } catch {
    // closed sockets surface through the close handler
  }
}

export async function startRelay(opts: { port: number; deps: RelayDeps }): Promise<RelayHandle> {
  const registry = opts.deps.registry ?? new RelayRegistry({ now: opts.deps.now })
  const nonces = new NonceStore(NONCE_TTL_MS)
  const wss = new WebSocketServer({ port: opts.port })

  await new Promise<void>((resolve) => wss.once('listening', () => resolve()))

  const address = wss.address()
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
        if (!current.budget.tryConsume('media', opts.deps.now())) {
          socket.close(1008, 'media_rate_limit')
          return
        }
        try {
          handleClientFrame(registry, current.member, Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer), {
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

      if (!nonces.burn(claims.nonce, opts.deps.now())) {
        socket.close(4401, 'replayed_token')
        return
      }

      let joined
      try {
        joined = registry.join(claims.roomId, claims.userId, socket, { hasVideo: true })
      } catch {
        socket.close(4409, 'room_unavailable')
        return
      }

      clearTimeout(current.registerTimer)
      current.member = joined.member

      const peers = registry.peers(claims.roomId, claims.userId)
      sendJson(socket, {
        t: 'registered',
        slot: joined.member.slot,
        peers: peers.map((p) => p.slot),
        limits: { maxParticipants: 16, maxVideoParticipants: 8 },
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
      for (const peer of registry.peers(left.roomId, left.userId)) {
        sendJson(peer.ws, { t: 'peer_left', slot: left.slot, userId: left.userId })
      }
    }

    socket.on('close', drop)
    socket.on('error', drop)
  })

  return {
    registry,
    address: `ws://127.0.0.1:${port}`,
    close: async () => {
      for (const socket of states.keys()) {
        try {
          socket.terminate()
        } catch {
          // already gone
        }
      }
      states.clear()
      await new Promise<void>((resolve) => wss.close(() => resolve()))
    },
  }
}
