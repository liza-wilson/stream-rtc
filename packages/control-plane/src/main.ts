import { randomUUID } from 'node:crypto'
import { loadConfig } from '@streamrtc/core/config'
import { startControl } from './server.js'

function required(name: string): string {
  const value = process.env[name]
  if (!value) {
    console.error(`missing required env var: ${name}`)
    process.exit(1)
  }
  return value
}

const port = Number(process.env['PORT'] ?? 8080)
const apps = loadConfig(required('STREAMRTC_CONFIG'))
const publicRelayUrl = required('PUBLIC_RELAY_URL')

const control = await startControl({
  port,
  deps: {
    apps,
    now: () => Date.now(),
    id: () => randomUUID(),
    publicRelayUrl,
  },
})

console.log(
  JSON.stringify({
    event: 'started',
    service: 'control',
    port,
    publicRelayUrl,
    apps: apps.appIds(),
  }),
)

let stopping = false
const shutdown = async (signal: string): Promise<void> => {
  if (stopping) return
  stopping = true
  console.log(JSON.stringify({ event: 'stopping', service: 'control', signal }))
  await control.close()
  process.exit(0)
}

process.on('SIGTERM', () => void shutdown('SIGTERM'))
process.on('SIGINT', () => void shutdown('SIGINT'))
