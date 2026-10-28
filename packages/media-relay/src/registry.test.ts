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
    expect(reg.join('app', 'r1', 'a', sock(), { hasVideo: true }).member.slot).toBe(0)
    expect(reg.join('app', 'r1', 'b', sock(), { hasVideo: true }).member.slot).toBe(1)
    expect(reg.join('app', 'r1', 'c', sock(), { hasVideo: true }).member.slot).toBe(2)
  })

  it('separates rooms that share a roomId across apps', () => {
    const a = sock()
    const b = sock()
    reg.join('app1', 'r1', 'a', a, { hasVideo: true })
    reg.join('app2', 'r1', 'b', b, { hasVideo: true })
    expect(reg.members('app1', 'r1').map((m) => m.userId)).toEqual(['a'])
    expect(reg.peers('app1', 'r1', 'someone')).toHaveLength(1)
    expect(reg.peers('app2', 'r1', 'someone').map((m) => m.userId)).toEqual(['b'])
  })

  it('evicts the older socket when the same user rejoins', () => {
    const first = sock()
    const second = sock()
    reg.join('app', 'r1', 'a', first, { hasVideo: true })
    const res = reg.join('app', 'r1', 'a', second, { hasVideo: true })
    expect(res.evicted?.ws).toBe(first)
    expect(first.closed).toBe(1008)
    expect(reg.members('app', 'r1')).toHaveLength(1)
  })

  it('enforces the participant limit', () => {
    const r = new RelayRegistry({ now: () => now, maxParticipants: 2 })
    r.join('app', 'r1', 'a', sock(), { hasVideo: true })
    r.join('app', 'r1', 'b', sock(), { hasVideo: true })
    try {
      r.join('app', 'r1', 'c', sock(), { hasVideo: true })
      expect.unreachable('should have thrown')
    } catch (e) {
      expect((e as RoomError).code).toBe('room_full')
    }
  })

  it('applies per-join limits from app config', () => {
    reg.join('app', 'r1', 'a', sock(), { hasVideo: true, maxParticipants: 2 })
    reg.join('app', 'r1', 'b', sock(), { hasVideo: true, maxParticipants: 2 })
    expect(() => reg.join('app', 'r1', 'c', sock(), { hasVideo: true, maxParticipants: 2 })).toThrow(
      /room_full/,
    )
  })

  it('keeps the existing session when a rejoin would exceed the video limit', () => {
    const r = new RelayRegistry({ now: () => now, maxVideo: 1 })
    r.join('app', 'r1', 'a', sock(), { hasVideo: true })
    const b = sock()
    r.join('app', 'r1', 'b', b, { hasVideo: false })
    expect(() => r.join('app', 'r1', 'b', sock(), { hasVideo: true })).toThrow(/video_slots_full/)
    expect(b.closed).toBeUndefined()
    expect(r.members('app', 'r1').map((m) => m.userId).sort()).toEqual(['a', 'b'])
  })

  it('lets audio-only members in when the video slots are full', () => {
    const r = new RelayRegistry({ now: () => now, maxVideo: 1 })
    r.join('app', 'r1', 'a', sock(), { hasVideo: true })
    expect(r.join('app', 'r1', 'b', sock(), { hasVideo: false }).member.slot).toBe(1)
    expect(() => r.join('app', 'r1', 'c', sock(), { hasVideo: true })).toThrow(/video_slots_full/)
  })

  it('reuses the reserved slot within the 30 s grace period', () => {
    const a = sock()
    reg.join('app', 'r1', 'a', a, { hasVideo: true })
    reg.leave(a)
    expect(reg.join('app', 'r1', 'a', sock(), { hasVideo: true }).member.slot).toBe(0)
  })

  it('does not reuse the reserved slot after the grace period', () => {
    const a = sock()
    reg.join('app', 'r1', 'a', a, { hasVideo: true })
    reg.leave(a)
    now = 30_001
    reg.join('app', 'r1', 'b', sock(), { hasVideo: true })
    expect(reg.join('app', 'r1', 'c', sock(), { hasVideo: true }).member.slot).toBe(1)
  })

  it('drops empty rooms once the grace period expires', () => {
    const a = sock()
    reg.join('app', 'r1', 'a', a, { hasVideo: true })
    reg.leave(a)
    expect(reg.stats().rooms).toBe(1)
    now = 30_001
    reg.prune(now)
    expect(reg.stats().rooms).toBe(0)
  })

  it('reports peers excluding the given user', () => {
    reg.join('app', 'r1', 'a', sock(), { hasVideo: true })
    reg.join('app', 'r1', 'b', sock(), { hasVideo: true })
    expect(reg.peers('app', 'r1', 'a').map((m) => m.userId)).toEqual(['b'])
  })
})
