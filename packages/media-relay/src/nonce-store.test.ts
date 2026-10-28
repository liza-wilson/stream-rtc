import { describe, expect, it } from 'vitest'
import { NonceStore } from './nonce-store.js'

describe('NonceStore', () => {
  it('burns a nonce once and refuses the replay', () => {
    const s = new NonceStore(600_000)
    expect(s.burn('n1', 0)).toBe(true)
    expect(s.burn('n1', 1000)).toBe(false)
  })

  it('forgets nonces after their TTL', () => {
    const s = new NonceStore(1000)
    s.burn('n1', 0)
    expect(s.burn('n1', 1001)).toBe(true)
  })
})
