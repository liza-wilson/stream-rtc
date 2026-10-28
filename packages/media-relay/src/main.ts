import { loadConfig } from '@streamrtc/core/config'
import { verifyRoom } from '@streamrtc/core/auth'
import { startRelay } from './server.js'

function required(name: string): string {
  const value = process.env[name]
  if (!value) {
    console.error(`missing required env var: ${name}`)
    process.exit(1)
  }
  return value
}

const port = Number(process.env['PORT'] ?? 8080)
const apps = loadConfig(required('STREAMRTC_CONFIG_FILE'))

const relay = await startRelay({
  port,
  deps: {
    verify: (token) => verifyRoom(token, apps, Date.now()),
    now: () => Date.now(),
    limits: (appId) => {
      const app = apps.get(appId)
      if (!app) return undefined
      return {
        maxParticipants: app.maxRoomParticipants,
        maxVideo: app.maxVideoParticipants,
      }
    },
  },
})

console.log(JSON.stringify({ event: 'started', service: 'relay', port }))

let stopping = false
const shutdown = async (signal: string): Promise<void> => {
  if (stopping) return
  stopping = true
  console.log(JSON.stringify({ event: 'stopping', service: 'relay', signal }))
  await relay.close()
  process.exit(0)
}

process.on('SIGTERM', () => void shutdown('SIGTERM'))
process.on('SIGINT', () => void shutdown('SIGINT'))
