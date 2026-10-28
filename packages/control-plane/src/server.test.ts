import { afterEach, describe, expect, it } from 'vitest'
import jwt from 'jsonwebtoken'
import WebSocket from 'ws'
import { startControl } from './server.js'

const secret = 'a'.repeat(32)
const otherSecret = 'b'.repeat(32)
const apps = {
  get: (id: string) =>
    id === 'app'
      ? { appId: 'app', secret, maxRoomParticipants: 16, maxVideoParticipants: 8 }
      : id === 'other'
        ? { appId: 'other', secret: otherSecret, maxRoomParticipants: 16, maxVideoParticipants: 8 }
        : undefined,
  appIds: () => ['app', 'other'],
}
const token = (userId: string, appId = 'app', key = secret) =>
  jwt.sign({ v: 1, appId, userId, displayName: userId, exp: 9e9 }, key, {
    algorithm: 'HS256',
  })

let stop: (() => Promise<void>) | undefined
afterEach(async () => {
  await stop?.()
  stop = undefined
})

const start = async () => {
  const s = await startControl({
    port: 0,
    deps: { apps, now: () => 1000, id: () => 'id1', publicRelayUrl: 'ws://relay.test/media' },
  })
  stop = s.close
  return s
}

const connect = (address: string) =>
  new Promise<{ ws: WebSocket; next: () => Promise<{ t: string; d: Record<string, unknown> }> }>(
    (res) => {
      const ws = new WebSocket(address)
      const queue: { t: string; d: Record<string, unknown> }[] = []
      const waiters: ((v: { t: string; d: Record<string, unknown> }) => void)[] = []
      ws.on('message', (d) => {
        const m = JSON.parse(d.toString()) as { t: string; d: Record<string, unknown> }
        const waiter = waiters.shift()
        if (waiter) waiter(m)
        else queue.push(m)
      })
      ws.on('open', () =>
        res({
          ws,
          next: () => {
            const queued = queue.shift()
            if (queued) return Promise.resolve(queued)
            return new Promise((resolve) => waiters.push(resolve))
          },
        }),
      )
    },
  )

const hello = (
  c: { ws: WebSocket; next: () => Promise<{ t: string }> },
  userId: string,
  appId = 'app',
  key = secret,
) =>
  c.ws.send(
    JSON.stringify({
      t: 'hello',
      d: {
        token: token(userId, appId, key),
        deviceId: 'd',
        platform: 'android',
        codecs: { audio: ['opus'], video: ['h264'] },
      },
    }),
  )

describe('control plane', () => {
  it('responds hello.ok for a valid session token', async () => {
    const s = await start()
    const a = await connect(s.address)
    hello(a, 'alice')
    expect((await a.next()).t).toBe('hello.ok')
    a.ws.close()
  })

  it('closes an unauthenticated socket with 4401', async () => {
    const s = await start()
    const ws = new WebSocket(s.address)
    const code = await new Promise<number>((res) => {
      ws.on('open', () => ws.send(JSON.stringify({ t: 'room.state', d: { roomId: 'r' } })))
      ws.on('close', (c) => res(c))
    })
    expect(code).toBe(4401)
  })

  it('routes call.invite to the callee and mints room tokens on accept', async () => {
    const s = await start()
    const a = await connect(s.address)
    const b = await connect(s.address)
    for (const [c, u] of [
      [a, 'alice'],
      [b, 'bob'],
    ] as const) {
      hello(c, u)
      await c.next()
    }

    a.ws.send(
      JSON.stringify({
        t: 'call.invite',
        d: { toUserId: 'bob', roomId: 'r1', media: { audio: true, video: true } },
      }),
    )
    const incoming = await b.next()
    expect(incoming.t).toBe('call.incoming')

    b.ws.send(JSON.stringify({ t: 'call.accept', d: { callId: incoming.d['callId'] } }))
    const accepted = await a.next()
    expect(accepted.t).toBe('call.accepted')

    const tokenA = await a.next()
    const tokenB = await b.next()
    expect(tokenA.t).toBe('room.token')
    expect(tokenB.t).toBe('room.token')
    expect(tokenA.d['relayUrl']).toBe('ws://relay.test/media')
    expect(tokenA.d['peers']).toEqual([])
    a.ws.close()
    b.ws.close()
  })

  it('answers callee_offline when the callee has no socket', async () => {
    const s = await start()
    const a = await connect(s.address)
    hello(a, 'alice')
    await a.next()
    a.ws.send(
      JSON.stringify({
        t: 'call.invite',
        d: { toUserId: 'ghost', roomId: 'r1', media: { audio: true, video: true } },
      }),
    )
    expect((await a.next()).t).toBe('callee_offline')
    a.ws.close()
  })

  it('answers ping with pong', async () => {
    const s = await start()
    const a = await connect(s.address)
    hello(a, 'alice')
    await a.next()
    a.ws.send(JSON.stringify({ t: 'ping', d: { ts: 42 } }))
    const pong = await a.next()
    expect(pong.t).toBe('pong')
    expect(pong.d['ts']).toBe(42)
    expect(pong.d['serverTime']).toBe(1000)
    a.ws.close()
  })

  it('serves healthz and readyz', async () => {
    const s = await start()
    const health = await fetch(`${s.httpUrl}/healthz`)
    expect(health.status).toBe(200)
    expect(await health.json()).toEqual({ status: 'ok' })
    const ready = await fetch(`${s.httpUrl}/readyz`)
    expect(ready.status).toBe(200)
    expect(await ready.json()).toEqual({ status: 'ready', apps: 2 })
  })

  it('closes sockets above the 64 KiB payload limit with 1009', async () => {
    const s = await start()
    const ws = new WebSocket(s.address)
    const code = await new Promise<number>((res) => {
      ws.on('open', () =>
        ws.send(JSON.stringify({ t: 'ping', d: { ts: 1, pad: 'x'.repeat(70 * 1024) } })),
      )
      ws.on('close', (c) => res(c))
    })
    expect(code).toBe(1009)
  })

  it('refuses call.end and call.reject from another app', async () => {
    const s = await start()
    const a = await connect(s.address)
    const b = await connect(s.address)
    const eve = await connect(s.address)
    hello(a, 'alice')
    await a.next()
    hello(b, 'bob')
    await b.next()
    hello(eve, 'eve', 'other', otherSecret)
    await eve.next()

    a.ws.send(
      JSON.stringify({
        t: 'call.invite',
        d: { toUserId: 'bob', roomId: 'r1', media: { audio: true, video: true } },
      }),
    )
    const incoming = await b.next()
    const callId = incoming.d['callId']
    b.ws.send(JSON.stringify({ t: 'call.accept', d: { callId } }))
    await a.next()
    await a.next()
    await b.next()

    eve.ws.send(JSON.stringify({ t: 'call.end', d: { callId, reason: 'nope' } }))
    expect((await eve.next()).d['code']).toBe('not_found')
    eve.ws.send(JSON.stringify({ t: 'call.reject', d: { callId, reason: 'nope' } }))
    expect((await eve.next()).d['code']).toBe('not_found')

    a.ws.send(JSON.stringify({ t: 'call.end', d: { callId, reason: 'hangup' } }))
    expect((await b.next()).t).toBe('call.ended')
  })

  it('notifies the callee when the caller cancels the invitation', async () => {
    const s = await start()
    const a = await connect(s.address)
    const b = await connect(s.address)
    hello(a, 'alice')
    await a.next()
    hello(b, 'bob')
    await b.next()

    a.ws.send(
      JSON.stringify({
        t: 'call.invite',
        d: { toUserId: 'bob', roomId: 'r1', media: { audio: true, video: true } },
      }),
    )
    const incoming = await b.next()
    a.ws.send(
      JSON.stringify({ t: 'call.reject', d: { callId: incoming.d['callId'], reason: 'cancel' } }),
    )
    const ended = await b.next()
    expect(ended.t).toBe('call.rejected')
    expect(ended.d['reason']).toBe('cancel')
  })
})
