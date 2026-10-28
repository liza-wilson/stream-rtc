import { afterEach, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { startRelay } from './server.js'

let stop: (() => Promise<void>) | undefined
afterEach(async () => {
  await stop?.()
  stop = undefined
})

const claims = {
  appId: 'app',
  userId: 'a',
  roomId: 'r1',
  role: 'member' as const,
  video: true,
  nonce: 'n1',
  exp: 9e9,
}

interface RelayClient {
  ws: WebSocket
  next(): Promise<Record<string, unknown>>
  register(): Promise<Record<string, unknown>>
}

/**
 * Test client that buffers every relay message from the moment it connects, so
 * a message can never be lost between connection and listener attachment.
 */
function relayClient(url: string): RelayClient {
  const ws = new WebSocket(url)
  const queue: Record<string, unknown>[] = []
  const waiters: Array<(message: Record<string, unknown>) => void> = []

  ws.on('message', (data) => {
    const message = JSON.parse(data.toString()) as Record<string, unknown>
    const waiter = waiters.shift()
    if (waiter) waiter(message)
    else queue.push(message)
  })

  const next = (): Promise<Record<string, unknown>> => {
    const queued = queue.shift()
    if (queued) return Promise.resolve(queued)
    return new Promise((resolve, reject) => {
      let settled = false
      const waiter = (message: Record<string, unknown>): void => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        resolve(message)
      }
      const timer = setTimeout(() => {
        if (settled) return
        settled = true
        reject(new Error(`timed out waiting for a relay message from ${url}`))
      }, 2_000)
      waiters.push(waiter)
    })
  }

  return {
    ws,
    next,
    async register() {
      if (ws.readyState !== WebSocket.OPEN) {
        await new Promise<void>((resolve) => ws.once('open', () => resolve()))
      }
      ws.send(JSON.stringify({ t: 'register', token: 'x' }))
      for (;;) {
        const message = await next()
        if (message['t'] === 'registered') return message
      }
    },
  }
}

describe('relay server', () => {
  it('registers a valid room token and reports the slot', async () => {
    let n = 0
    const s = await startRelay({
      port: 0,
      deps: { verify: () => ({ ...claims, nonce: `n${++n}` }), now: () => 0 },
    })
    stop = s.close
    const ws = new WebSocket(s.address)
    const msg = await new Promise<Record<string, unknown>>((res) => {
      ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token: 'x' })))
      ws.on('message', (d) => res(JSON.parse(d.toString())))
    })
    expect(msg['t']).toBe('registered')
    expect(msg['slot']).toBe(0)
    ws.close()
  })

  it('closes with 4401 when verify throws', async () => {
    const s = await startRelay({
      port: 0,
      deps: {
        verify: () => {
          throw new Error('bad')
        },
        now: () => 0,
      },
    })
    stop = s.close
    const ws = new WebSocket(s.address)
    const code = await new Promise<number>((res) => {
      ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token: 'x' })))
      ws.on('close', (c) => res(c))
    })
    expect(code).toBe(4401)
  })

  it('serves healthz and readyz over http', async () => {
    const s = await startRelay({
      port: 0,
      deps: { verify: () => ({ ...claims, nonce: `h${Date.now()}` }), now: () => 0 },
    })
    stop = s.close
    const health = await fetch(`${s.httpUrl}/healthz`)
    expect(health.status).toBe(200)
    expect(await health.json()).toEqual({ status: 'ok' })
    const ready = await fetch(`${s.httpUrl}/readyz`)
    expect(ready.status).toBe(200)
    expect(await ready.json()).toMatchObject({ status: 'ready', rooms: 0, participants: 0 })
  })

  it('closes with 1009 when a media frame exceeds 512 KiB', async () => {
    const s = await startRelay({
      port: 0,
      deps: { verify: () => ({ ...claims, nonce: 'big' }), now: () => 0 },
    })
    stop = s.close
    const ws = new WebSocket(s.address)
    await new Promise<void>((res) => {
      ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token: 'x' })))
      ws.on('message', () => res())
    })
    const code = await new Promise<number>((res) => {
      ws.on('close', (c) => res(c))
      ws.send(Buffer.alloc(512 * 1024 + 10))
    })
    expect(code).toBe(1009)
  })

  it('reports the per-app limits supplied by the operator', async () => {
    const s = await startRelay({
      port: 0,
      deps: {
        verify: () => ({ ...claims, video: false, nonce: 'limits' }),
        now: () => 0,
        limits: () => ({ maxParticipants: 4, maxVideo: 2 }),
      },
    })
    stop = s.close
    const ws = new WebSocket(s.address)
    const msg = await new Promise<Record<string, unknown>>((res) => {
      ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token: 'x' })))
      ws.on('message', (d) => res(JSON.parse(d.toString())))
    })
    expect(msg['limits']).toEqual({ maxParticipants: 4, maxVideoParticipants: 2 })
    ws.close()
  })

  it('rejects a replayed nonce', async () => {
    const s = await startRelay({ port: 0, deps: { verify: () => claims, now: () => 0 } })
    stop = s.close
    const open = () =>
      new Promise<WebSocket>((res) => {
        const w = new WebSocket(s.address)
        w.on('open', () => res(w))
      })

    const a = await open()
    const first = await new Promise<Record<string, unknown>>((res) => {
      a.on('message', (d) => res(JSON.parse(d.toString())))
      a.send(JSON.stringify({ t: 'register', token: 'x' }))
    })
    expect(first['t']).toBe('registered')

    const b = await open()
    const code = await new Promise<number>((res) => {
      b.on('close', (c) => res(c))
      b.send(JSON.stringify({ t: 'register', token: 'x' }))
    })
    expect(code).toBe(4401)
  })

  it('reports peer identity on register', async () => {
    let n = 0
    const s = await startRelay({
      port: 0,
      deps: { verify: () => ({ ...claims, userId: `u${++n}`, nonce: `n${n}` }), now: () => 0 },
    })
    stop = s.close
    const a = relayClient(s.address)
    const b = relayClient(s.address)
    const first = await a.register()
    const second = await b.register()
    expect(first['peerInfo']).toEqual([])
    expect(second['peers']).toEqual([0])
    expect(second['peerInfo']).toEqual([{ slot: 0, userId: 'u1' }])
    a.ws.close()
    b.ws.close()
  })

  it('forwards a keyframe request to the slot owner', async () => {
    let n = 0
    const s = await startRelay({
      port: 0,
      deps: { verify: () => ({ ...claims, userId: `u${++n}`, nonce: `n${n}` }), now: () => 0 },
    })
    stop = s.close
    const a = relayClient(s.address)
    const b = relayClient(s.address)
    await a.register()
    await b.register()

    // Registering b already makes the relay send peer_joined and a
    // join-triggered need_keyframe to a. Drain both before requesting, so only
    // a message produced by the explicit request can satisfy the assertion.
    const drained = new Set<string>()
    while (!drained.has('peer_joined') || !drained.has('need_keyframe')) {
      drained.add((await a.next())['t'] as string)
    }

    b.ws.send(JSON.stringify({ t: 'keyframe', d: { slot: 0 } }))
    const forwarded = await a.next()
    expect(forwarded['t']).toBe('need_keyframe')
    a.ws.close()
    b.ws.close()
  })
})
