import { readFileSync } from 'node:fs'
import { z } from 'zod'

export interface AppEntry {
  appId: string
  secret: string
  secretNext?: string
  maxRoomParticipants: number
  maxVideoParticipants: number
}

export interface AppRegistry {
  get(appId: string): AppEntry | undefined
  appIds(): string[]
}

const appSchema = z.strictObject({
  secret: z.string().min(32, 'secret must be at least 32 characters'),
  secretNext: z.string().min(32, 'secretNext must be at least 32 characters').optional(),
  maxRoomParticipants: z.number().int().positive().default(16),
  maxVideoParticipants: z.number().int().positive().default(8),
})

const configSchema = z.strictObject({
  apps: z.record(z.string().min(1), appSchema),
})

export function loadConfigFromObject(obj: unknown): AppRegistry {
  const parsed = configSchema.parse(obj)
  const apps = new Map<string, AppEntry>()
  for (const [appId, entry] of Object.entries(parsed.apps)) {
    apps.set(appId, { appId, ...entry })
  }
  return {
    get: (appId: string) => apps.get(appId),
    appIds: () => [...apps.keys()],
  }
}

export function loadConfig(filePath: string): AppRegistry {
  let raw: string
  try {
    raw = readFileSync(filePath, 'utf8')
  } catch (error) {
    throw new Error(`cannot read config file ${filePath}: ${(error as Error).message}`)
  }
  let json: unknown
  try {
    json = JSON.parse(raw)
  } catch (error) {
    throw new Error(`invalid JSON in config file ${filePath}: ${(error as Error).message}`)
  }
  return loadConfigFromObject(json)
}
