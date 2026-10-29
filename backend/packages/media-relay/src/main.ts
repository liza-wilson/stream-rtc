import { loadConfig } from '@streamrtc/core/config'
import { verifyRoom } from '@streamrtc/core/auth'
import { startRelay } from './server.js'

const port = Number(process.env['PORT'] ?? 8080)
const configFile = process.env['STREAMRTC_CONFIG_FILE'] || '/etc/secrets/streamrtc.config.json'
const apps = loadConfig(configFile)

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
