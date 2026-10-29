import { describe, expect, it } from 'vitest'
import { loadConfigFromObject } from './config.js'

const valid = {
  apps: {
    'com.acme.calls': {
      secret: 'a'.repeat(32),
      secretNext: 'b'.repeat(32),
      maxRoomParticipants: 16,
      maxVideoParticipants: 8,
    },
  },
}

describe('loadConfigFromObject', () => {
  it('returns an app entry with defaults applied', () => {
    const reg = loadConfigFromObject({ apps: { 'com.x': { secret: 'a'.repeat(32) } } })
    const app = reg.get('com.x')
    expect(app?.maxRoomParticipants).toBe(16)
    expect(app?.maxVideoParticipants).toBe(8)
  })

  it('accepts secretNext for rotation and exposes both', () => {
    const app = loadConfigFromObject(valid).get('com.acme.calls')
    expect(app?.secret).toBe('a'.repeat(32))
    expect(app?.secretNext).toBe('b'.repeat(32))
  })

  it('returns undefined for an unknown appId', () => {
    expect(loadConfigFromObject(valid).get('com.other')).toBeUndefined()
  })

  it('throws when a secret is shorter than 32 chars', () => {
    expect(() => loadConfigFromObject({ apps: { 'com.x': { secret: 'short' } } })).toThrow(/secret/)
  })
})
