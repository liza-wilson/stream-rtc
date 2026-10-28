import { describe, expect, it } from 'vitest'
import { decodeRelayFrame, encodeClientFrame, FLAG_KEYFRAME } from '@streamrtc/protocol'
import { handleClientFrame } from './fanout.js'
import { RelayRegistry } from './registry.js'
import type { WsLike } from './types.js'

const sock = (): WsLike & { sent: unknown[] } => ({
  sent: [],
  send(d) {
    ;(this as { sent: unknown[] }).sent.push(d)
  },
  close() {},
  bufferedAmount: 0,
})
const deps = { now: () => 0 }

describe('handleClientFrame', () => {
  it('forwards to every other member with the sender slot, never to the sender', () => {
    const reg = new RelayRegistry({ now: () => 0 })
    const a = sock()
    const b = sock()
    const c = sock()
    const ma = reg.join('app', 'r1', 'a', a, { hasVideo: true }).member
    reg.join('app', 'r1', 'b', b, { hasVideo: true })
    reg.join('app', 'r1', 'c', c, { hasVideo: true })

    handleClientFrame(
      reg,
      ma,
      encodeClientFrame({ type: 0x20, flags: FLAG_KEYFRAME, mediaTs: 5, payload: Buffer.from([1]) }),
      deps,
    )

    expect(a.sent).toHaveLength(0)
    expect(b.sent).toHaveLength(1)
    expect(decodeRelayFrame(b.sent[0] as Buffer)).toMatchObject({ senderSlot: 0, mediaTs: 5 })
    expect(c.sent).toHaveLength(1)
  })

  it('drops malformed frames without throwing or forwarding', () => {
    const reg = new RelayRegistry({ now: () => 0 })
    const a = sock()
    const b = sock()
    const ma = reg.join('app', 'r1', 'a', a, { hasVideo: true }).member
    reg.join('app', 'r1', 'b', b, { hasVideo: true })
    expect(() => handleClientFrame(reg, ma, Buffer.alloc(3), deps)).not.toThrow()
    expect(b.sent).toHaveLength(0)
  })

  it('drops video for a degraded subscriber but keeps audio', () => {
    const reg = new RelayRegistry({ now: () => 0 })
    const a = sock()
    const slow = sock()
    slow.bufferedAmount = 600 * 1024
    const ma = reg.join('app', 'r1', 'a', a, { hasVideo: true }).member
    reg.join('app', 'r1', 'slow', slow, { hasVideo: true })

    handleClientFrame(reg, ma, encodeClientFrame({ type: 0x20, flags: 0, mediaTs: 1, payload: Buffer.from([1]) }), deps)
    handleClientFrame(reg, ma, encodeClientFrame({ type: 0x10, flags: 0, mediaTs: 2, payload: Buffer.from([2]) }), deps)

    expect(slow.sent.filter((x) => Buffer.isBuffer(x))).toHaveLength(1)
  })

  it('closes a subscriber above the hard buffer limit with 1013', () => {
    const reg = new RelayRegistry({ now: () => 0 })
    const a = sock()
    let closed: number | undefined
    const dead: WsLike & { sent: unknown[] } = {
      sent: [],
      send() {},
      close(c?: number) {
        closed = c
      },
      bufferedAmount: 3 * 1024 * 1024,
    }
    const ma = reg.join('app', 'r1', 'a', a, { hasVideo: true }).member
    reg.join('app', 'r1', 'dead', dead, { hasVideo: true })

    handleClientFrame(reg, ma, encodeClientFrame({ type: 0x20, flags: 0, mediaTs: 1, payload: Buffer.from([1]) }), deps)
    expect(closed).toBe(1013)
  })
})
