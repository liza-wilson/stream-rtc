import jwt from 'jsonwebtoken'
import { z } from 'zod'
import type { AppEntry, AppRegistry } from './config.js'

export type TokenErrorCode = 'invalid_signature' | 'expired' | 'unknown_app' | 'bad_claims'

export class TokenError extends Error {
  readonly code: TokenErrorCode

  constructor(code: TokenErrorCode, message: string = code) {
    super(message)
    this.name = 'TokenError'
    this.code = code
  }
}

export interface SessionClaims {
  appId: string
  userId: string
  displayName?: string
  exp: number
}

export interface RoomClaims {
  appId: string
  userId: string
  roomId: string
  role: 'member' | 'host'
  nonce: string
  exp: number
  /** true when this participant publishes video; the relay enforces video slots on it */
  video?: boolean
}

const sessionSchema = z.strictObject({
  v: z.literal(1),
  appId: z.string().min(1),
  userId: z.string().min(1),
  displayName: z.string().min(1).optional(),
  iat: z.number().optional(),
  exp: z.number(),
})

const roomSchema = z.object({
  v: z.literal(1),
  appId: z.string().min(1),
  userId: z.string().min(1),
  roomId: z.string().min(1),
  role: z.enum(['member', 'host']),
  nonce: z.string().min(1),
  exp: z.number(),
  video: z.boolean().optional(),
})

function readPayload(token: string): Record<string, unknown> {
  const decoded = jwt.decode(token)
  if (!decoded || typeof decoded !== 'object') {
    throw new TokenError('bad_claims', 'bad_claims: token payload is not an object')
  }
  return decoded as Record<string, unknown>
}

function appFor(payload: Record<string, unknown>, apps: AppRegistry): AppEntry {
  const appId = payload['appId']
  if (typeof appId !== 'string' || appId.length === 0) {
    throw new TokenError('bad_claims', 'bad_claims: missing appId')
  }
  const app = apps.get(appId)
  if (!app) throw new TokenError('unknown_app', `unknown_app: ${appId}`)
  return app
}

function checkSignature(
  token: string,
  app: AppEntry,
  nowMs: number,
): Record<string, unknown> {
  const clockTimestamp = Math.floor(nowMs / 1000)
  const secrets = app.secretNext ? [app.secret, app.secretNext] : [app.secret]
  let expired = false
  for (const key of secrets) {
    try {
      const verified = jwt.verify(token, key, {
        algorithms: ['HS256'],
        clockTimestamp,
      })
      if (!verified || typeof verified !== 'object') {
        throw new TokenError('bad_claims', 'bad_claims: token payload is not an object')
      }
      return verified as Record<string, unknown>
    } catch (error) {
      if (error instanceof TokenError) throw error
      if (error instanceof jwt.TokenExpiredError) {
        expired = true
        continue
      }
      if (error instanceof jwt.JsonWebTokenError) continue
      throw error
    }
  }
  if (expired) throw new TokenError('expired', 'expired')
  throw new TokenError('invalid_signature', 'invalid_signature')
}

export function verifySession(token: string, apps: AppRegistry, nowMs: number = Date.now()): SessionClaims {
  const pre = readPayload(token)
  const app = appFor(pre, apps)
  const payload = checkSignature(token, app, nowMs)
  const parsed = sessionSchema.safeParse(payload)
  if (!parsed.success) throw new TokenError('bad_claims', 'bad_claims: invalid session claims')
  const { appId, userId, displayName, exp } = parsed.data
  const claims: SessionClaims = { appId, userId, exp }
  if (displayName !== undefined) claims.displayName = displayName
  return claims
}

export function verifyRoom(token: string, apps: AppRegistry, nowMs: number = Date.now()): RoomClaims {
  const pre = readPayload(token)
  const app = appFor(pre, apps)
  const payload = checkSignature(token, app, nowMs)
  const parsed = roomSchema.safeParse(payload)
  if (!parsed.success) throw new TokenError('bad_claims', 'bad_claims: invalid room claims')
  return parsed.data
}

export function mintRoomToken(
  app: AppEntry,
  claims: Omit<RoomClaims, 'exp' | 'nonce'>,
  ttlSeconds: number,
  nonce: string,
): string {
  return jwt.sign(
    {
      v: 1,
      appId: claims.appId,
      userId: claims.userId,
      roomId: claims.roomId,
      role: claims.role,
      ...(claims.video === undefined ? {} : { video: claims.video }),
      nonce,
      exp: Math.floor(Date.now() / 1000) + ttlSeconds,
    },
    app.secret,
    { algorithm: 'HS256' },
  )
}
