import { beforeEach, describe, expect, it } from 'vitest'
import { CallError, CallRegistry } from './calls.js'

let now = 1000
let n = 0
let reg: CallRegistry

beforeEach(() => {
  now = 1000
  n = 0
  reg = new CallRegistry({ now: () => now, id: () => `c${++n}` })
})

describe('CallRegistry', () => {
  it('creates a ringing call and activates it on accept', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    expect(c.state).toBe('ringing')
    expect(reg.accept(c.id, 'b').state).toBe('active')
  })

  it('rejects accept by a third party', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    expect(() => reg.accept(c.id, 'x')).toThrow(/forbidden/)
  })

  it('rejects an invalid transition from ended', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    reg.end(c.id, 'a', 'hangup')
    try {
      reg.accept(c.id, 'b')
      expect.unreachable('should have thrown')
    } catch (e) {
      expect((e as CallError).code).toBe('invalid_transition')
    }
  })

  it('records the reject reason', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    const out = reg.reject(c.id, 'b', 'busy')
    expect(out.state).toBe('rejected')
    expect(out.endReason).toBe('busy')
  })

  it('reports ringing calls older than 45 s', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    expect(reg.silentSince(now + 46_000).map((x) => x.id)).toEqual([c.id])
    reg.accept(c.id, 'b')
    expect(reg.silentSince(now + 46_000)).toEqual([])
  })

  it('reports active calls older than the idle TTL', () => {
    const c = reg.create({ appId: 'app', roomId: 'r1', callerId: 'a', calleeId: 'b' })
    reg.accept(c.id, 'b')
    expect(reg.staleActive(now + 3_600_000, 4 * 3_600_000)).toEqual([])
    expect(reg.staleActive(now + 5 * 3_600_000, 4 * 3_600_000).map((x) => x.id)).toEqual([c.id])
  })
})
