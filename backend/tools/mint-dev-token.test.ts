import { describe, expect, it } from 'vitest'
import jwt from 'jsonwebtoken'
import { mintSession, mintRoom } from './mint-dev-token.js'

const app = { appId: 'app', secret: 's'.repeat(32), maxRoomParticipants: 16, maxVideoParticipants: 8 }

describe('dev tokens', () => {
  it('mints a strict session token', () => {
    const token = mintSession(app, 'u1', 3600, 1_700_000_000)
    const payload = jwt.decode(token) as Record<string, unknown>
    expect(payload['v']).toBe(1)
    expect(payload['appId']).toBe('app')
    expect(payload['userId']).toBe('u1')
    expect(payload['exp']).toBe(1_700_003_600)
  })

  it('mints a room token with a fresh nonce and the video claim', () => {
    const token = mintRoom(app, { userId: 'u1', roomId: 'r1', role: 'member', video: true }, 300, 'nonce-1', 1_700_000_000)
    const payload = jwt.decode(token) as Record<string, unknown>
    expect(payload['roomId']).toBe('r1')
    expect(payload['nonce']).toBe('nonce-1')
    expect(payload['video']).toBe(true)
  })
})
