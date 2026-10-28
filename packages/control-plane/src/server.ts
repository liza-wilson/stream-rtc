import Fastify, { type FastifyInstance } from 'fastify'
import { WebSocketServer, type WebSocket } from 'ws'
import { endTimedOutCalls, handleMessage, type Conn, type ControlContext } from './handlers.js'
import { CallRegistry } from './calls.js'
import { metricsText, registry } from './metrics.js'
import { Presence } from './presence.js'
import { MAX_MESSAGE_BYTES, parseClientMessage } from '@streamrtc/protocol'
import type { AppRegistry } from '@streamrtc/core'
import type { WsLike } from './types.js'

export interface ControlDeps {
  apps: AppRegistry
  presence?: Presence
  calls?: CallRegistry
  now(): number
  id(): string
  publicRelayUrl: string
}

export interface ControlHandle {
  close(): Promise<void>
  address: string
  httpUrl: string
  presence: Presence
  calls: CallRegistry
}

const AUTH_TIMEOUT_MS = 5_000
const SWEEP_INTERVAL_MS = 5_000

function toWsLike(socket: WebSocket): WsLike {
  return {
    send: (data: string | Buffer) => socket.send(data),
    close: (code?: number, reason?: string) => socket.close(code, reason),
    get bufferedAmount() {
      return socket.bufferedAmount
    },
  }
}

export async function startControl(opts: {
  port: number
  host?: string
  deps: ControlDeps
}): Promise<ControlHandle> {
  const presence = opts.deps.presence ?? new Presence()
  const calls =
    opts.deps.calls ?? new CallRegistry({ now: opts.deps.now, id: opts.deps.id })
  const ctx: ControlContext = {
    apps: opts.deps.apps,
    presence,
    calls,
    now: opts.deps.now,
    id: opts.deps.id,
    publicRelayUrl: opts.deps.publicRelayUrl,
  }

  const app: FastifyInstance = Fastify({ logger: false })
  app.get('/healthz', async () => ({ status: 'ok' }))
  app.get('/readyz', async () => ({ status: 'ready', apps: ctx.apps.appIds().length }))
  app.get('/metrics', async (_request, reply) => {
    reply.header('content-type', registry.contentType)
    return metricsText()
  })

  await app.listen({ port: opts.port, host: opts.host ?? '0.0.0.0' })

  const httpAddress = app.server.address()
  const port = typeof httpAddress === 'object' && httpAddress !== null ? httpAddress.port : opts.port

  const wss = new WebSocketServer({ server: app.server, maxPayload: MAX_MESSAGE_BYTES })

  wss.on('connection', (socket: WebSocket) => {
    const ws = toWsLike(socket)
    const conn: Conn = {
      ws,
      close: (code, reason) => socket.close(code, reason),
    }
    const authTimer = setTimeout(() => {
      if (!conn.session) socket.close(4401, 'auth_timeout')
    }, AUTH_TIMEOUT_MS)

    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        socket.close(1003, 'text_only')
        return
      }
      const message = parseClientMessage(data.toString())
      if (!message) {
        socket.close(1007, 'invalid_message')
        return
      }
      try {
        handleMessage(ctx, conn, message)
      } catch {
        socket.close(1011, 'handler_error')
      }
    })

    const drop = (): void => {
      clearTimeout(authTimer)
      if (!conn.session) return
      presence.remove(conn.session.appId, conn.session.userId, ws)
    }

    socket.on('close', drop)
    socket.on('error', drop)
  })

  const sweep = setInterval(() => endTimedOutCalls(ctx), SWEEP_INTERVAL_MS)
  sweep.unref?.()

  return {
    presence,
    calls,
    address: `ws://127.0.0.1:${port}/ws`,
    httpUrl: `http://127.0.0.1:${port}`,
    close: async () => {
      clearInterval(sweep)
      for (const socket of wss.clients) socket.terminate()
      await new Promise<void>((resolve) => wss.close(() => resolve()))
      await app.close()
    },
  }
}
