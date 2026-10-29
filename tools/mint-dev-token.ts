import jwt from 'jsonwebtoken'
import { readFileSync } from 'node:fs'

export interface AppConfig {
  appId: string
  secret: string
  maxRoomParticipants: number
  maxVideoParticipants: number
}

export interface SessionMintArgs {
  appId: string
  secret: string
}

export function mintSession(
  app: AppConfig,
  userId: string,
  ttlSeconds: number,
  nowSec: number = Math.floor(Date.now() / 1000),
): string {
  return jwt.sign(
    {
      v: 1,
      appId: app.appId,
      userId,
      iat: nowSec,
      exp: nowSec + ttlSeconds,
    },
    app.secret,
    { algorithm: 'HS256' },
  )
}

export interface RoomMintArgs {
  userId: string
  roomId: string
  role: 'member' | 'host'
  video?: boolean
}

export function mintRoom(
  app: AppConfig,
  claims: RoomMintArgs,
  ttlSeconds: number,
  nonce: string,
  nowSec: number = Math.floor(Date.now() / 1000),
): string {
  const payload: Record<string, unknown> = {
    v: 1,
    appId: app.appId,
    userId: claims.userId,
    roomId: claims.roomId,
    role: claims.role,
    nonce,
    iat: nowSec,
    exp: nowSec + ttlSeconds,
  }
  if (claims.video !== undefined) payload.video = claims.video
  return jwt.sign(payload, app.secret, { algorithm: 'HS256' })
}

function parseArgs(argv: string[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a.startsWith('--')) {
      const key = a.slice(2)
      const val = argv[i + 1]
      if (val !== undefined && !val.startsWith('--')) {
        out[key] = val
        i++
      } else {
        out[key] = 'true'
      }
    }
  }
  return out
}

export function main(argv: string[] = process.argv.slice(2)): void {
  const args = parseArgs(argv)
  const configPath = args.config ?? './streamrtc.config.json'
  const raw = JSON.parse(readFileSync(configPath, 'utf8')) as Record<string, unknown>
  const apps = (raw.apps ?? raw) as Record<string, unknown>
  const appKey = args.app ?? Object.keys(apps)[0]
  const appRaw = (apps[appKey] ?? apps) as Record<string, unknown>
  const app: AppConfig = {
    appId: String(appRaw.appId ?? appKey),
    secret: String(appRaw.secret),
    maxRoomParticipants: Number(appRaw.maxRoomParticipants ?? 16),
    maxVideoParticipants: Number(appRaw.maxVideoParticipants ?? 8),
  }
  const kind = args.kind ?? 'session'
  const ttl = Number(args.ttl ?? (kind === 'room' ? 300 : 3600))
  const nowSec = args.now ? Number(args.now) : Math.floor(Date.now() / 1000)
  let token: string
  if (kind === 'room') {
    token = mintRoom(
      app,
      {
        userId: args.user ?? 'u1',
        roomId: args.room ?? 'r1',
        role: (args.role as 'member' | 'host') ?? 'member',
        video: args.video !== 'false',
      },
      ttl,
      args.nonce ?? `nonce-${Date.now()}`,
      nowSec,
    )
  } else {
    token = mintSession(app, args.user ?? 'u1', ttl, nowSec)
  }
  process.stdout.write(token + '\n')
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/'))
if (isMain) {
  main()
}
