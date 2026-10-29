import { randomUUID } from 'node:crypto'
import { createServer } from 'node:http'
import { loadConfig } from '@streamrtc/core/config'
import { verifyRoom } from '@streamrtc/core/auth'
import { startControl } from './server.js'
import { startRelay } from '@streamrtc/media-relay/server'

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
const publicRelayUrl = required('PUBLIC_RELAY_URL')

// Same host serves both control and relay; relay is mounted under /relay.
const relayUrl = publicRelayUrl.endsWith('/relay') ? publicRelayUrl : `${publicRelayUrl.replace(/\/$/, '')}/relay`
const httpServer = createServer()

const control = await startControl({
  port,
  server: httpServer,
  deps: {
    apps,
    now: () => Date.now(),
    id: () => randomUUID(),
    publicRelayUrl: relayUrl,
  },
})

const relay = await startRelay({
  port,
  server: httpServer,
  path: '/relay',
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

// Route WebSocket upgrades by path.
httpServer.on('upgrade', (req, socket, head) => {
  const url = req.url ?? '/'
  const pathname = url.split('?')[0]
  if (pathname === '/relay' || pathname.startsWith('/relay/')) {
    if (!relay.handleUpgrade(req, socket, head)) {
      socket.destroy()
    }
  } else {
    control.handleUpgrade(req, socket, head)
  }
})

console.log(JSON.stringify({ event: 'started', service: 'combined', port, publicRelayUrl: relayUrl }))

let stopping = false
const shutdown = async (signal: string): Promise<void> => {
  if (stopping) return
  stopping = true
  await control.close()
  await relay.close()
  process.exit(0)
}
process.on('SIGTERM', () => void shutdown('SIGTERM'))
process.on('SIGINT', () => void shutdown('SIGINT'))