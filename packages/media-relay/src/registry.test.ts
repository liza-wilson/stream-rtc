import { beforeEach, describe, expect, it } from 'vitest'
import { RelayRegistry, RoomError } from './registry.js'
import type { WsLike } from './types.js'

const sock = (): WsLike & { closed?: number } => ({
  send() {},
  close(c?: number) {
    ;(this as { closed?: number }).closed = c
  },
  bufferedAmount: 0,
})

let now = 0
let reg: RelayRegistry

beforeEach(() => {
  now = 0
  reg = new RelayRegistry({ now: () => now })
})

describe('RelayRegistry', () => {
  it('assigns slots 0,1,2 in join order', () => {
    expect(reg.join('r1', 'a', sock(), { hasVideo: true }).member.slot).toBe(0)
    expect(reg.join('r1', 'b', sock(), { hasVideo: true }).member.slot).toBe(1)
    expect(reg.join('r1', 'c', sock(), { hasVideo: true }).member.slot).toBe(2)
  })

  it('evicts the older socket when the same user rejoins', () => {
    const first = sock()
    const second = sock()
    reg.join('r1', 'a', first, { hasVideo: true })
    const res = reg.join('r1', 'a', second, { hasVideo: true })
    expect(res.evicted?.ws).toBe(first)
    expect(first.closed).toBe(1008)
    expect(reg.members('r1')).toHaveLength(1)
  })

  it('enforces the participant limit', () => {
    const r = new RelayRegistry({ now: () => now, maxParticipants: 2 })
    r.join('r1', 'a', sock(), { hasVideo: true })
    r.join('r1', 'b', sock(), { hasVideo: true })
    try {
      r.join('r1', 'c', sock(), { hasVideo: true })
      expect.unreachable('should have thrown')
    } catch (e) {
      expect((e as RoomError).code).toBe('room_full')
    }
  })

  it('reuses the reserved slot within the 30 s grace period', () => {
    const a = sock()
    reg.join('r1', 'a', a, { hasVideo: true })
    reg.leave(a)
    expect(reg.join('r1', 'a', sock(), { hasVideo: true }).member.slot).toBe(0)
  })

  it('does not reuse the reserved slot after the grace period', () => {
    const a = sock()
    reg.join('r1', 'a', a, { hasVideo: true })
    reg.leave(a)
    now = 30_001
    reg.join('r1', 'b', sock(), { hasVideo: true })
    expect(reg.join('r1', 'c', sock(), { hasVideo: true }).member.slot).toBe(1)
  })

  it('reports peers excluding the given user', () => {
    reg.join('r1', 'a', sock(), { hasVideo: true })
    reg.join('r1', 'b', sock(), { hasVideo: true })
    expect(reg.peers('r1', 'a').map((m) => m.userId)).toEqual(['b'])
  })
})
