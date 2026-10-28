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
  nonce: 'n1',
  exp: 9e9,
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
})
