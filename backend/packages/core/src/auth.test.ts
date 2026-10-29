import { describe, expect, it } from 'vitest'
import jwt from 'jsonwebtoken'
import { mintRoomToken, TokenError, verifyRoom, verifySession } from './auth.js'

const secret = 'a'.repeat(32)
const apps = {
  get: (id: string) =>
    id === 'com.acme.calls'
      ? { appId: id, secret, maxRoomParticipants: 16, maxVideoParticipants: 8 }
      : undefined,
  appIds: () => ['com.acme.calls'],
}

const sign = (payload: object, key = secret) => jwt.sign(payload, key, { algorithm: 'HS256' })

describe('verifySession', () => {
  it('accepts a valid session token', () => {
    const t = sign({ v: 1, appId: 'com.acme.calls', userId: 'u1', displayName: 'Aman', exp: 2000 })
    expect(verifySession(t, apps, 1_000_000)).toMatchObject({
      appId: 'com.acme.calls',
      userId: 'u1',
    })
  })

  it('rejects expired tokens with code expired', () => {
    const t = sign({ v: 1, appId: 'com.acme.calls', userId: 'u1', exp: 1 })
    try {
      verifySession(t, apps, 2_000_000)
      expect.unreachable('should have thrown')
    } catch (e) {
      expect((e as TokenError).code).toBe('expired')
    }
  })

  it('rejects a wrong signature', () => {
    const t = sign({ v: 1, appId: 'com.acme.calls', userId: 'u1', exp: 9999999999 }, 'b'.repeat(32))
    expect(() => verifySession(t, apps, 1_000_000)).toThrow(/invalid_signature/)
  })

  it('rejects an unknown appId', () => {
    const t = sign({ v: 1, appId: 'com.other', userId: 'u1', exp: 9999999999 })
    try {
      verifySession(t, apps, 1_000_000)
      expect.unreachable('should have thrown')
    } catch (e) {
      expect((e as TokenError).code).toBe('unknown_app')
    }
  })
})

describe('room tokens', () => {
  it('mints and verifies a room token with its nonce', () => {
    const app = apps.get('com.acme.calls')!
    const t = mintRoomToken(
      app,
      { appId: 'com.acme.calls', userId: 'u1', roomId: 'r1', role: 'member' },
      300,
      'n1',
    )
    expect(verifyRoom(t, apps, Date.now())).toMatchObject({ roomId: 'r1', nonce: 'n1' })
  })

  it('rejects a room token missing a nonce', () => {
    const t = sign({
      v: 1,
      appId: 'com.acme.calls',
      userId: 'u1',
      roomId: 'r1',
      role: 'member',
      exp: 9999999999,
    })
    try {
      verifyRoom(t, apps, 1_000_000)
      expect.unreachable('should have thrown')
    } catch (e) {
      expect((e as TokenError).code).toBe('bad_claims')
    }
  })

  it('does not accept a room token as a session token', () => {
    const app = apps.get('com.acme.calls')!
    const t = mintRoomToken(
      app,
      { appId: 'com.acme.calls', userId: 'u1', roomId: 'r1', role: 'member' },
      300,
      'n1',
    )
    try {
      verifySession(t, apps, Date.now())
      expect.unreachable('should have thrown')
    } catch (e) {
      expect((e as TokenError).code).toBe('bad_claims')
    }
  })

  it('rejects a session token with the wrong version', () => {
    const t = sign({ v: 2, appId: 'com.acme.calls', userId: 'u1', exp: 9999999999 })
    try {
      verifySession(t, apps, 1_000_000)
      expect.unreachable('should have thrown')
    } catch (e) {
      expect((e as TokenError).code).toBe('bad_claims')
    }
  })
})
