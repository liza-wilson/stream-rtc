import { randomUUID } from 'node:crypto'
import { createServer } from 'node:http'
import { loadConfig } from '@streamrtc/core/config'
import { verifyRoom } from '@streamrtc/core/auth'
import { startControl } from './server.js'
import { startRelay } from '@streamrtc/media-relay/server'

const port = Number(process.env['PORT'] ?? 8080)
const configFile = process.env['STREAMRTC_CONFIG_FILE'] || '/etc/secrets/streamrtc.config.json'
const apps = loadConfig(configFile)
const rawRelayUrl = process.env['PUBLIC_RELAY_URL'] || process.env['RENDER_EXTERNAL_URL'] || 'https://streamrtc-relay.onrender.com'

// Same host serves both control and relay; relay is mounted under /relay.
const relayUrl = rawRelayUrl.endsWith('/relay') ? rawRelayUrl : `${rawRelayUrl.replace(/\/$/, '')}/relay`
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
  } else if (pathname === '/ws' || pathname.startsWith('/ws/')) {
    control.handleUpgrade(req, socket, head)
  } else {
    // If connecting to root or unknown path, try relay first; fallback to control
    if (!relay.handleUpgrade(req, socket, head)) {
      control.handleUpgrade(req, socket, head)
    }
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
