import { describe, expect, it } from 'vitest'
import { Presence } from './presence.js'
import type { WsLike } from './types.js'

const sock = (): WsLike => ({ send() {}, close() {}, bufferedAmount: 0 })

describe('Presence', () => {
  it('tracks multiple sockets for one user', () => {
    const p = new Presence()
    const a = sock()
    const b = sock()
    p.add('app', 'u1', a)
    p.add('app', 'u1', b)
    expect(p.sockets('app', 'u1')).toHaveLength(2)
    expect(p.online('app', 'u1')).toBe(true)
  })

  it('removes only the given socket and reports offline after the last one', () => {
    const p = new Presence()
    const a = sock()
    const b = sock()
    p.add('app', 'u1', a)
    p.add('app', 'u1', b)
    p.remove('app', 'u1', a)
    expect(p.sockets('app', 'u1')).toEqual([b])
    p.remove('app', 'u1', b)
    expect(p.online('app', 'u1')).toBe(false)
  })

  it('isolates the same userId across apps', () => {
    const p = new Presence()
    p.add('app1', 'u1', sock())
    expect(p.online('app2', 'u1')).toBe(false)
    expect(p.count()).toBe(1)
  })
})
