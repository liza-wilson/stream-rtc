/**
 * Live smoke test: starts the built control plane and relay as child processes
 * (exactly like Render does), signs real tokens, runs a full 1:1 call, relays
 * media both ways and reports latency. Exits non-zero on the first failure.
 *
 *   npm run smoke
 */
import { spawn, type ChildProcess } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import jwt from 'jsonwebtoken'
import WebSocket from 'ws'
import { verifyRoom } from '@streamrtc/core/auth'
import { decodeRelayFrame, encodeClientFrame, FLAG_KEYFRAME, TYPE_H264, TYPE_OPUS } from '@streamrtc/protocol'

const SECRET = 'smoke-secret-'.padEnd(40, 'x')
const APP_ID = 'com.example.calls'
const CONTROL_PORT = 18180
const RELAY_PORT = 18181

let failures = 0

function check(name: string, ok: boolean, detail = ''): void {
  const mark = ok ? 'ok  ' : 'FAIL'
  console.log(`[${mark}] ${name}${detail ? ` — ${detail}` : ''}`)
  if (!ok) failures++
}

function log(line: string): void {
  console.log(line)
}

async function waitForHealth(url: string, child: ChildProcess, label: string): Promise<void> {
  const deadline = Date.now() + 10_000
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`${label} exited with code ${child.exitCode}`)
    try {
      const res = await fetch(url)
      if (res.ok) return
    } catch {
      // not up yet
    }
    await new Promise((r) => setTimeout(r, 100))
  }
  throw new Error(`${label} did not become healthy`)
}

type Message = { t: string; d?: Record<string, unknown> } & Record<string, unknown>

interface Client {
  ws: WebSocket
  next(): Promise<Message>
  media(): Promise<Buffer>
  close(): void
}

function connect(url: string): Promise<Client> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url)
    const messages: Message[] = []
    const waiters: ((m: Message) => void)[] = []
    const frames: Buffer[] = []
    const frameWaiters: ((b: Buffer) => void)[] = []

    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer)
        const waiter = frameWaiters.shift()
        if (waiter) waiter(buf)
        else frames.push(buf)
        return
      }
      const msg = JSON.parse(data.toString()) as Message
      const waiter = waiters.shift()
      if (waiter) waiter(msg)
      else messages.push(msg)
    })
    let closeInfo = ''
    ws.on('close', (code, reason) => {
      closeInfo = `closed ${code} ${reason.toString()}`
    })
    ws.once('error', reject)
    ws.once('open', () =>
      resolve({
        ws,
        next: () => {
          const queued = messages.shift()
          if (queued) return Promise.resolve(queued)
          return Promise.race([
            new Promise<Message>((res) => waiters.push(res)),
            new Promise<Message>((_, rej) =>
              setTimeout(() => rej(new Error(`timeout waiting for a message on ${url}${closeInfo ? ` (${closeInfo})` : ''}`)), 5_000),
            ),
          ])
        },
        media: () => {
          const queued = frames.shift()
          if (queued) return Promise.resolve(queued)
          return Promise.race([
            new Promise<Buffer>((res) => frameWaiters.push(res)),
            new Promise<Buffer>((_, rej) =>
              setTimeout(() => rej(new Error(`timeout waiting for media on ${url}`)), 5_000),
            ),
          ])
        },
        close: () => ws.close(),
      }),
    )
  })
}

function expectClose(url: string, send: (ws: WebSocket) => void): Promise<number> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url)
    const timer = setTimeout(() => reject(new Error(`no close from ${url}`)), 5_000)
    ws.on('open', () => send(ws))
    ws.on('close', (code) => {
      clearTimeout(timer)
      resolve(code)
    })
    ws.on('error', reject)
  })
}

function registerAndWait(
  url: string,
  token: string,
): Promise<{ ws: WebSocket; slot: number; peers: number[] }> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url)
    ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token })))
    ws.on('message', (d) => {
      const m = JSON.parse(d.toString()) as Message
      if (m.t === 'registered') {
        resolve({ ws, slot: m['slot'] as number, peers: m['peers'] as number[] })
      }
    })
    ws.on('error', reject)
  })
}

function sessionToken(userId: string): string {
  return jwt.sign(
    { v: 1, appId: APP_ID, userId, displayName: userId, exp: Math.floor(Date.now() / 1000) + 900 },
    SECRET,
    { algorithm: 'HS256' },
  )
}

async function hello(client: Client, userId: string): Promise<void> {
  client.ws.send(
    JSON.stringify({
      t: 'hello',
      d: {
        token: sessionToken(userId),
        deviceId: `smoke-${userId}`,
        platform: 'android',
        codecs: { audio: ['opus', 'pcm16'], video: ['h264'] },
      },
    }),
  )
  const reply = await client.next()
  check(`hello ${userId}`, reply.t === 'hello.ok', reply.t)
}

async function main(): Promise<void> {
  const dir = mkdtempSync(join(tmpdir(), 'streamrtc-smoke-'))
  const configPath = join(dir, 'streamrtc.config.json')
  writeFileSync(
    configPath,
    JSON.stringify({
      apps: {
        [APP_ID]: { secret: SECRET, maxRoomParticipants: 8, maxVideoParticipants: 4 },
      },
    }),
  )

  const env = { ...process.env, STREAMRTC_CONFIG_FILE: configPath }
  const children: ChildProcess[] = []
  const relay = spawn('node', ['dist/relay/main.js'], {
    env: { ...env, PORT: String(RELAY_PORT) },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const control = spawn('node', ['dist/control/main.js'], {
    env: {
      ...env,
      PORT: String(CONTROL_PORT),
      PUBLIC_RELAY_URL: `ws://127.0.0.1:${RELAY_PORT}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  children.push(relay, control)
  relay.stderr.on('data', (d) => log(`[relay] ${d.toString().trim()}`))
  control.stderr.on('data', (d) => log(`[control] ${d.toString().trim()}`))

  try {
    await waitForHealth(`http://127.0.0.1:${RELAY_PORT}/healthz`, relay, 'relay')
    await waitForHealth(`http://127.0.0.1:${CONTROL_PORT}/healthz`, control, 'control')
    check('relay /healthz', true)
    check('control /healthz', true)

    const ready = await (await fetch(`http://127.0.0.1:${CONTROL_PORT}/readyz`)).json()
    check('control /readyz', ready.status === 'ready' && ready.apps === 1, JSON.stringify(ready))

    const metrics = await (await fetch(`http://127.0.0.1:${CONTROL_PORT}/metrics`)).text()
    check('control /metrics', metrics.includes('rtc_call_setup_seconds'))

    const alice = await connect(`ws://127.0.0.1:${CONTROL_PORT}/ws`)
    const bob = await connect(`ws://127.0.0.1:${CONTROL_PORT}/ws`)
    await hello(alice, 'alice')
    await hello(bob, 'bob')

    alice.ws.send(
      JSON.stringify({
        t: 'call.invite',
        d: { toUserId: 'bob', roomId: 'smoke-room', media: { audio: true, video: true } },
      }),
    )
    const incoming = await bob.next()
    check('call.incoming reaches bob', incoming.t === 'call.incoming')
    const callId = (incoming.d ?? {})['callId'] as string

    bob.ws.send(JSON.stringify({ t: 'call.accept', d: { callId } }))
    const accepted = await alice.next()
    check('call.accepted reaches alice', accepted.t === 'call.accepted')

    const tokenAlice = await alice.next()
    const tokenBob = await bob.next()
    check('room tokens minted for both', tokenAlice.t === 'room.token' && tokenBob.t === 'room.token')

    const claims = verifyRoom(tokenBob.d?.['token'] as string, {
      get: (id) =>
        id === APP_ID
          ? { appId: APP_ID, secret: SECRET, maxRoomParticipants: 8, maxVideoParticipants: 4 }
          : undefined,
      appIds: () => [APP_ID],
    })
    check(
      'relay token verifies with app secret',
      claims.roomId === 'smoke-room' && claims.video === true,
      `room=${claims.roomId} video=${claims.video}`,
    )

    const relayUrl = tokenAlice.d?.['relayUrl'] as string
    const mediaAlice = await connect(relayUrl)
    const mediaBob = await connect(relayUrl)
    mediaAlice.ws.send(JSON.stringify({ t: 'register', token: tokenAlice.d?.['token'] }))
    const regAlice = await mediaAlice.next()
    mediaBob.ws.send(JSON.stringify({ t: 'register', token: tokenBob.d?.['token'] }))
    const regBob = await mediaBob.next()
    const slotAlice = regAlice['slot'] as number
    check(
      'both registered with distinct slots',
      regAlice.t === 'registered' &&
        regBob.t === 'registered' &&
        regAlice['slot'] !== regBob['slot'],
      `slots ${String(regAlice['slot'])}/${String(regBob['slot'])}`,
    )
    check(
      'relay reports configured limits',
      JSON.stringify(regAlice['limits']) === '{"maxParticipants":8,"maxVideoParticipants":4}',
    )

    const replay = await expectClose(relayUrl, (ws) =>
      ws.send(JSON.stringify({ t: 'register', token: tokenAlice.d?.['token'] })),
    )
    check('replayed room token rejected', replay === 4401, `close ${replay}`)

    const badSession = await expectClose(`ws://127.0.0.1:${CONTROL_PORT}/ws`, (ws) =>
      ws.send(
        JSON.stringify({
          t: 'hello',
          d: {
            token: jwt.sign({ v: 1, appId: APP_ID, userId: 'mallory', exp: 9e9 }, 'x'.repeat(40), {
              algorithm: 'HS256',
            }),
            deviceId: 'bad',
            platform: 'android',
            codecs: { audio: ['opus'], video: ['h264'] },
          },
        }),
      ),
    )
    check('wrong-secret session token rejected', badSession === 4401, `close ${badSession}`)

    const latencies: number[] = []
    for (let i = 0; i < 20; i++) {
      const start = process.hrtime.bigint()
      mediaAlice.ws.send(
        encodeClientFrame({
          type: TYPE_H264,
          flags: i === 0 ? FLAG_KEYFRAME : 0,
          mediaTs: i * 33,
          payload: Buffer.alloc(1200, i),
        }),
      )
      const got = await mediaBob.media()
      const decoded = decodeRelayFrame(got)
      latencies.push(Number(process.hrtime.bigint() - start) / 1e6)
      if (!decoded || decoded.senderSlot !== 0) {
        check('video frame relay', false, 'bad relay frame')
        break
      }
    }
    check('20 video frames alice -> bob', latencies.length === 20, 'senderSlot=0')
    check('20 audio frames bob -> alice', await (async () => {
      let received = 0
      for (let i = 0; i < 20; i++) {
        mediaBob.ws.send(
          encodeClientFrame({ type: TYPE_OPUS, flags: 0, mediaTs: i * 20, payload: Buffer.alloc(80, i) }),
        )
        const got = await mediaAlice.media()
        const decoded = decodeRelayFrame(got)
        if (decoded && decoded.senderSlot === 1 && decoded.type === TYPE_OPUS) received++
      }
      return received === 20
    })())

    latencies.sort((a, b) => a - b)
    const p50 = latencies[10]!.toFixed(2)
    const p95 = latencies[18]!.toFixed(2)
    log('')
    log(`relay latency over loopback: p50 ${p50} ms, p95 ${p95} ms (encode+decode excluded)`)

    // A fresh call.token must let the same user re-register and keep their slot.
    // This evicts the earlier media socket (close 1008), so it runs after the
    // media checks.
    alice.ws.send(JSON.stringify({ t: 'call.token', d: { callId } }))
    const refreshed = await alice.next()
    check('call.token returns a token', refreshed.t === 'room.token')
    if (refreshed.t !== 'room.token') throw new Error('call.token did not return a token')
    const reRegistered = await registerAndWait(relayUrl, refreshed.d?.['token'] as string)
    check(
      'refreshed token can rejoin the same slot',
      reRegistered.slot === slotAlice,
      `slot ${String(reRegistered.slot)}`,
    )

    const readyRoom = await (await fetch(`http://127.0.0.1:${RELAY_PORT}/readyz`)).json()
    check('relay reports the live room', readyRoom.rooms === 1 && readyRoom.participants === 2, JSON.stringify(readyRoom))

    reRegistered.ws.close()
    mediaAlice.close()
    mediaBob.close()
    await new Promise((r) => setTimeout(r, 300))
    const afterLeave = await (await fetch(`http://127.0.0.1:${RELAY_PORT}/readyz`)).json()
    check('participants cleaned up on close', afterLeave.participants === 0, JSON.stringify(afterLeave))
  } finally {
    for (const child of children) child.kill('SIGTERM')
  }

  log('')
  log(failures === 0 ? 'SMOKE PASS' : `SMOKE FAIL (${failures} checks failed)`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
