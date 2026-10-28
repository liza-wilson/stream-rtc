# StreamRTC Android SDK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `io.streamrtc:rtc:0.1.0`, a Gradle library that takes an Android app from zero to 1:1 video and 4-way video rooms over the StreamRTC WebSocket relay, with capture, encode, transport, decode, render, echo cancellation, reconnect and a foreground service all inside the library.

**Architecture:** Three additive backend messages first (`call.token`, relay `keyframe`, `registered.peerInfo`), then a standalone Gradle build at `sdk-android/` containing one module `:rtc`. Media is PCM16 16 kHz mono over binary WebSocket frames with an 8/9-byte header; H.264 comes from `MediaCodec` fed by a CameraX `Preview` surface. Public API is a small façade (`StreamRtc`, `Call`, `Room`) with `StateFlow` everywhere; render targets are plain `SurfaceView`s so the library never depends on Compose.

**Tech Stack:** Kotlin 2.1.0, AGP 9.1.0, Gradle 9.3.1, `compileSdk` 36 (minor API level 1), `minSdk 24`, Java 11 bytecode / toolchain 17, OkHttp 4.12.0, kotlinx-coroutines 1.9.0, kotlinx-serialization-json 1.7.3, CameraX 1.6.0, `android.media.MediaCodec`, JUnit 4, MockWebServer, vitest for the backend.

**Spec:** `docs/superpowers/specs/2026-10-29-streamrtc-android-sdk-design.md`

## Global Constraints

- Library module lives at `sdk-android/rtc`, package `io.streamrtc`, coordinates `io.streamrtc:rtc:0.1.0`.
- `minSdk 24`, `compileSdk` written as AGP 9 minor-API form `release(36) { minorApiLevel = 1 }` (only `android-36.1` is installed).
- Kotlin 2.1.0, `jvmToolchain(17)`, `sourceCompatibility`/`targetCompatibility`/`jvmTarget` = Java 11.
- **No Compose dependency** anywhere in `:rtc`. Views are `SurfaceView` subclasses.
- Audio: PCM16, 16 kHz, mono, 20 ms frames (640 bytes). Frame types `0x10` opus, `0x11` pcm16, `0x20` h264. Flags `1` keyframe, `2` eos, `4` screen.
- Header layouts are fixed by `packages/protocol/src/frame.ts`: client 8 bytes (`magic 0x53, version 1, type, flags, mediaTs BE u32`), relay 9 bytes with `senderSlot` at offset 4 and `mediaTs` at 5.
- Caps: control text ≤ 64 KiB, media ≤ 512 KiB, relay slot ≤ 15, video decoders per client ≤ 4.
- No database. The host app signs tokens and owns users, meetings and history.
- Every `MediaCodec`, camera and `AudioRecord`/`AudioTrack` call happens off the main thread; public API is main-safe.
- Backend tests run with `npx vitest run <path>`; Android tests run with the Gradle wrapper in `sdk-android/`.
- Android build environment on this machine: `JAVA_HOME=/home/sived/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`, SDK at `/home/sived/Android/Sdk`.
- Code and docs in English; comments explain *why*, not *what*.

## Review Focus

1. Relay socket drops at minute 12 of a 1:1 call (room token TTL already expired) — the SDK must fetch a fresh token via `call.token` and recover, never sit in `Reconnecting` forever.
2. A 17th participant or a 9th video publisher joins — the joining client must get `RoomFull` / `VideoSlotsFull`, not a hang or a silent close.
3. Camera or mic is taken by another app (or permission revoked) mid-call — the session must end with a typed error and release every resource, with no ANR.
4. Video arrives before any keyframe, or a decoder resets mid-stream — no crash, no garbage frame, `RemoteVideoState.WaitingForKeyframe` until an IDR arrives.
5. App backgrounded on API 34+ (FGS start restriction, screen off, rotation) — audio keeps playing, camera is released on rotation without tearing down the session.

---

## File Structure

**Backend (modified):**

| File | Responsibility |
|---|---|
| `packages/protocol/src/messages.ts` | Add `call.token` and relay `keyframe` to the client union |
| `packages/protocol/src/frame.vectors.test.ts` | New: read `testdata/frames.json` and assert codec output |
| `packages/protocol/testdata/frames.json` | New: shared vectors for vitest and the Kotlin codec |
| `packages/control-plane/src/handlers.ts` | `call.token` branch, extracted `sendRoomToken` |
| `packages/control-plane/src/server.test.ts` | Token-refresh tests |
| `packages/media-relay/src/registry.ts` | `memberBySlot` |
| `packages/media-relay/src/server.ts` | `keyframe` forwarding, `peerInfo` |
| `packages/media-relay/src/server.test.ts` | Keyframe + peerInfo tests |
| `tools/smoke.ts` | Reconnect-after-refresh check |

**SDK (new), under `sdk-android/`:**

| File | Responsibility |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradlew` | Standalone Gradle build |
| `rtc/build.gradle.kts` | Library config, publishing, dependencies |
| `rtc/src/main/AndroidManifest.xml` | Permissions + service declaration |
| `rtc/src/main/kotlin/io/streamrtc/api/*.kt` | Public types only |
| `rtc/src/main/kotlin/io/streamrtc/StreamRtc.kt` | Façade and lifecycle |
| `rtc/src/main/kotlin/io/streamrtc/protocol/FrameCodec.kt` | Binary headers |
| `rtc/src/main/kotlin/io/streamrtc/protocol/Messages.kt` | JSON builders and envelope parser |
| `rtc/src/main/kotlin/io/streamrtc/protocol/H264AnnexB.kt` | NAL split, SPS/PPS, keyframe detection |
| `rtc/src/main/kotlin/io/streamrtc/transport/Backoff.kt` | Exponential backoff with jitter |
| `rtc/src/main/kotlin/io/streamrtc/transport/CloseCodes.kt` | Close code → action |
| `rtc/src/main/kotlin/io/streamrtc/transport/ControlTransport.kt` | Interface + OkHttp implementation |
| `rtc/src/main/kotlin/io/streamrtc/transport/RelayTransport.kt` | Interface + OkHttp implementation |
| `rtc/src/main/kotlin/io/streamrtc/session/ParticipantRegistry.kt` | Slot ↔ userId ↔ video state |
| `rtc/src/main/kotlin/io/streamrtc/session/CallSession.kt` | 1:1 state machine |
| `rtc/src/main/kotlin/io/streamrtc/session/RoomSession.kt` | Room state machine |
| `rtc/src/main/kotlin/io/streamrtc/session/SessionManager.kt` | Owns sessions, service, clock |
| `rtc/src/main/kotlin/io/streamrtc/media/Sync.kt` | `SyncEstimator`, `VideoScheduler` |
| `rtc/src/main/kotlin/io/streamrtc/media/audio/JitterBuffer.kt` | Adaptive PCM jitter buffer |
| `rtc/src/main/kotlin/io/streamrtc/media/audio/PcmCapture.kt` | `AudioRecord` loop |
| `rtc/src/main/kotlin/io/streamrtc/media/audio/PcmPlayback.kt` | Per-slot `AudioTrack` |
| `rtc/src/main/kotlin/io/streamrtc/media/audio/AudioRouter.kt` | Focus, mode, route |
| `rtc/src/main/kotlin/io/streamrtc/media/video/CameraController.kt` | CameraX preview → encoder surface |
| `rtc/src/main/kotlin/io/streamrtc/media/video/VideoEncoder.kt` | H.264 send pipeline |
| `rtc/src/main/kotlin/io/streamrtc/media/video/VideoDecoder.kt` | Per-slot receive pipeline |
| `rtc/src/main/kotlin/io/streamrtc/service/CallService.kt` | Foreground service |
| `rtc/src/main/kotlin/io/streamrtc/view/RtcPreviewView.kt` | Local camera surface |
| `rtc/src/main/kotlin/io/streamrtc/view/RtcRemoteVideoView.kt` | Remote decode surface |
| `rtc/src/test/kotlin/io/streamrtc/**` | JVM tests |
| `rtc/src/androidTest/kotlin/io/streamrtc/**` | Device tests |
| `tools/mint-dev-token.ts` | Local token minting for debug builds |
| `sdk-android/README.md` | Integration guide |

---

### Task 1: Shared protocol deltas and frame vectors

**Files:**
- Modify: `packages/protocol/src/messages.ts`
- Create: `packages/protocol/testdata/frames.json`
- Create: `packages/protocol/src/frame.vectors.test.ts`
- Test: `packages/protocol/src/messages.test.ts`

**Interfaces:**
- Consumes: existing `ClientMessage` union, `parseClientMessage`, `encodeClientFrame`, `encodeRelayFrame`.
- Produces: `ClientMessage` gains `{ t: 'call.token'; d: { callId: string } }` and `{ t: 'keyframe'; d: { slot: number } }`. `testdata/frames.json` is an array of `{ name, type, flags, mediaTs, senderSlot, payloadHex, clientHex, relayHex }`.

- [ ] **Step 1: Write the failing message tests**

Add to `packages/protocol/src/messages.test.ts`:

```ts
it('parses call.token and relay keyframe requests', () => {
  expect(parseClientMessage(JSON.stringify({ t: 'call.token', d: { callId: 'c1' } }))?.t).toBe('call.token')
  expect(parseClientMessage(JSON.stringify({ t: 'keyframe', d: { slot: 3 } }))?.t).toBe('keyframe')
})

it('rejects malformed call.token and keyframe payloads', () => {
  expect(parseClientMessage(JSON.stringify({ t: 'call.token', d: {} }))).toBeNull()
  expect(parseClientMessage(JSON.stringify({ t: 'keyframe', d: { slot: -1 } }))).toBeNull()
  expect(parseClientMessage(JSON.stringify({ t: 'keyframe', d: { slot: 16 } }))).toBeNull()
})
```

`slot` validation: `z.number().int().min(0).max(15)`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run packages/protocol/src/messages.test.ts`
Expected: FAIL — both messages parse to `null`.

- [ ] **Step 3: Add the messages**

In `packages/protocol/src/messages.ts`, add near the other definitions:

```ts
const callTokenD = z.strictObject({ callId: z.string().min(1) })
const keyframeD = z.strictObject({ slot: z.number().int().min(0).max(15) })
```

Add to `clientSchema`:

```ts
z.strictObject({ t: z.literal('call.token'), d: callTokenD }),
z.strictObject({ t: z.literal('keyframe'), d: keyframeD }),
```

Add both to the `ClientMessage` union.

- [ ] **Step 4: Create the shared frame vectors**

Create `packages/protocol/testdata/frames.json` with at least these five entries. Derive `clientHex`/`relayHex` from `encodeClientFrame`/`encodeRelayFrame` once (the test below asserts them, so copy the output after the first green run):

```json
[
  { "name": "pcm16-20ms", "type": 17, "flags": 0, "mediaTs": 1234, "senderSlot": 2, "payloadHex": "00010203", "clientHex": "", "relayHex": "" },
  { "name": "h264-keyframe", "type": 32, "flags": 1, "mediaTs": 4294967295, "senderSlot": 0, "payloadHex": "000000016742001e", "clientHex": "", "relayHex": "" },
  { "name": "h264-delta", "type": 32, "flags": 0, "mediaTs": 0, "senderSlot": 15, "payloadHex": "0000000141", "clientHex": "", "relayHex": "" },
  { "name": "opus-empty", "type": 16, "flags": 0, "mediaTs": 65535, "senderSlot": 7, "payloadHex": "", "clientHex": "", "relayHex": "" },
  { "name": "screen-eos", "type": 32, "flags": 6, "mediaTs": 16777216, "senderSlot": 1, "payloadHex": "ff", "clientHex": "", "relayHex": "" }
]
```

- [ ] **Step 5: Write the failing vector test**

Create `packages/protocol/src/frame.vectors.test.ts`:

```ts
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { decodeClientFrame, decodeRelayFrame, encodeClientFrame, encodeRelayFrame } from './frame.js'

const vectors = JSON.parse(
  readFileSync(new URL('../testdata/frames.json', import.meta.url), 'utf8'),
) as Array<{
  name: string; type: number; flags: number; mediaTs: number; senderSlot: number
  payloadHex: string; clientHex: string; relayHex: string
}>

describe('frame vectors', () => {
  for (const v of vectors) {
    it(`${v.name} matches the committed bytes`, () => {
      const payload = Buffer.from(v.payloadHex, 'hex')
      const client = encodeClientFrame({ type: v.type as never, flags: v.flags, mediaTs: v.mediaTs, payload })
      expect(client.toString('hex')).toBe(v.clientHex)
      expect(decodeClientFrame(client)).toMatchObject({ type: v.type, flags: v.flags, mediaTs: v.mediaTs })
      const relay = encodeRelayFrame({ type: v.type as never, flags: v.flags, mediaTs: v.mediaTs, senderSlot: v.senderSlot, payload })
      expect(relay.toString('hex')).toBe(v.relayHex)
      expect(decodeRelayFrame(relay)).toMatchObject({ senderSlot: v.senderSlot, type: v.type })
    })
  }
})
```

- [ ] **Step 6: Run the vector test, fill the hex fields, run again**

Run: `npx vitest run packages/protocol/src/frame.vectors.test.ts`
Expected: FAIL with the actual hex in the message. Paste each actual value into `clientHex`/`relayHex`, then re-run.
Expected after the fill: PASS, 5 tests.

- [ ] **Step 7: Commit**

```bash
git add packages/protocol/src/messages.ts packages/protocol/src/messages.test.ts packages/protocol/src/frame.vectors.test.ts packages/protocol/testdata/frames.json
git commit -m "feat(protocol): call.token, relay keyframe, shared frame vectors"
```

---

### Task 2: `call.token` on the control plane

**Files:**
- Modify: `packages/control-plane/src/handlers.ts`
- Test: `packages/control-plane/src/server.test.ts`

**Interfaces:**
- Consumes: `ctx.calls.get(callId)`, `mintRoomToken(app, claims, ttl, nonce)`, `ROOM_TOKEN_TTL_SECONDS`, `sendToUser`.
- Produces: `sendRoomToken(ctx, call, userId, role)` used by both `call.accept` and `call.token`. `call.token` replies `room.token` with a fresh nonce for either participant of an `active` call.

- [ ] **Step 1: Write the failing test**

Add to `packages/control-plane/src/server.test.ts` (the file already has `start`, `connect`, `hello` helpers):

```ts
it('re-mints a room token for an active call with a fresh nonce', async () => {
  const s = await start()
  const alice = await connect(s.address)
  const bob = await connect(s.address)
  await hello(alice, 'alice')
  await hello(bob, 'bob')

  alice.ws.send(JSON.stringify({ t: 'call.invite', d: { toUserId: 'bob', roomId: 'r1', media: { audio: true, video: true } } }))
  const incoming = await bob.next()
  const callId = incoming.d['callId'] as string
  bob.ws.send(JSON.stringify({ t: 'call.accept', d: { callId } }))

  const bobToken = await bob.next()
  const aliceToken = await alice.next()
  expect(bobToken.t).toBe('room.token')
  expect(aliceToken.t).toBe('room.token')

  alice.ws.send(JSON.stringify({ t: 'call.token', d: { callId } }))
  const refreshed = await alice.next()
  expect(refreshed.t).toBe('room.token')
  const first = jwt.decode(aliceToken.d['token'] as string) as { nonce: string; role: string; exp: number }
  const second = jwt.decode(refreshed.d['token'] as string) as { nonce: string; role: string; exp: number }
  expect(second.nonce).not.toBe(first.nonce)
  expect(second.role).toBe(first.role)
  expect(second.exp).toBeGreaterThan(Math.floor(Date.now() / 1000))
})

it('rejects call.token for a ringing call and for a stranger', async () => {
  const s = await start()
  const alice = await connect(s.address)
  const bob = await connect(s.address)
  const eve = await connect(s.address)
  await hello(alice, 'alice')
  await hello(bob, 'bob')
  await hello(eve, 'eve')

  alice.ws.send(JSON.stringify({ t: 'call.invite', d: { toUserId: 'bob', roomId: 'r1', media: { audio: true, video: true } } }))
  const incoming = await bob.next()
  const callId = incoming.d['callId'] as string

  alice.ws.send(JSON.stringify({ t: 'call.token', d: { callId } }))
  expect((await alice.next()).t).toBe('error')
  eve.ws.send(JSON.stringify({ t: 'call.token', d: { callId } }))
  expect((await eve.next()).t).toBe('error')
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx vitest run packages/control-plane/src/server.test.ts`
Expected: FAIL — the server never answers `call.token`, so the awaits time out.

- [ ] **Step 3: Extract the token sender and add the branch**

In `handlers.ts`, add above `handleMessage`:

```ts
function sendRoomToken(
  ctx: ControlContext,
  call: { id: string; roomId: string; callerId: string; calleeId: string; media?: { video: boolean } },
  userId: string,
  role: 'host' | 'member',
): void {
  const app = ctx.apps.get(/* appId comes from the call owner */)
  // signature: sendRoomToken(ctx, appId, call, userId, role)
}
```

Use the final signature `sendRoomToken(ctx: ControlContext, appId: string, call: Call, userId: string, role: 'host' | 'member'): void`. It looks up the app, mints with `ROOM_TOKEN_TTL_SECONDS` and `ctx.id()`, and sends `room.token` with `{ token, relayUrl: ctx.publicRelayUrl, roomId: call.roomId, role, expiresIn: ROOM_TOKEN_TTL_SECONDS, peers: [] }`.

Replace the inline loop in `call.accept` with two `sendRoomToken` calls (`call.callerId` → `host`, `call.calleeId` → `member`).

Add the branch after `call.end`:

```ts
case 'call.token': {
  const call = ctx.calls.get(message.d.callId)
  if (
    !call ||
    call.appId !== session.appId ||
    call.state !== 'active' ||
    (call.callerId !== session.userId && call.calleeId !== session.userId)
  ) {
    fail(conn, 'not_found')
    return
  }
  const role = call.callerId === session.userId ? 'host' : 'member'
  sendRoomToken(ctx, session.appId, call, session.userId, role)
  return
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx vitest run packages/control-plane/src/server.test.ts`
Expected: PASS, including the two new cases.

- [ ] **Step 5: Run the whole backend suite and typecheck**

Run: `npx vitest run && npm run typecheck`
Expected: all green, no type errors.

- [ ] **Step 6: Commit**

```bash
git add packages/control-plane/src/handlers.ts packages/control-plane/src/server.test.ts
git commit -m "feat(control): call.token mints a fresh room token for active calls"
```

---

### Task 3: Relay keyframe forwarding and peer identity

**Files:**
- Modify: `packages/media-relay/src/registry.ts`
- Modify: `packages/media-relay/src/server.ts`
- Test: `packages/media-relay/src/registry.test.ts`
- Test: `packages/media-relay/src/server.test.ts`
- Modify: `tools/smoke.ts`

**Interfaces:**
- Consumes: `RelayRegistry.peers`, `RelayMember.slot`, `requestKeyframe`.
- Produces: `RelayRegistry.memberBySlot(appId: string, roomId: string, slot: number): RelayMember | undefined`; `registered` gains `peerInfo: Array<{ slot: number; userId: string }>`; a registered socket may send `{ t: 'keyframe', d: { slot } }`.

- [ ] **Step 1: Write the failing registry test**

Add to `packages/media-relay/src/registry.test.ts`:

```ts
it('finds a member by slot', () => {
  const now = () => 0
  const r = new RelayRegistry({ now })
  const wsA = { send: () => {}, close: () => {}, bufferedAmount: 0 }
  const wsB = { send: () => {}, close: () => {}, bufferedAmount: 0 }
  r.join('app', 'r1', 'a', wsA, { hasVideo: true })
  const b = r.join('app', 'r1', 'b', wsB, { hasVideo: true }).member
  expect(r.memberBySlot('app', 'r1', b.slot)?.userId).toBe('b')
  expect(r.memberBySlot('app', 'r1', 9)).toBeUndefined()
  expect(r.memberBySlot('other', 'r1', b.slot)).toBeUndefined()
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx vitest run packages/media-relay/src/registry.test.ts`
Expected: FAIL — `memberBySlot` is not a function.

- [ ] **Step 3: Implement `memberBySlot`**

In `registry.ts`:

```ts
memberBySlot(appId: string, roomId: string, slot: number): RelayMember | undefined {
  return this.rooms.get(this.key(appId, roomId))?.members.get(slot)
}
```

- [ ] **Step 4: Write the failing relay server tests**

Add to `packages/media-relay/src/server.test.ts`:

```ts
it('reports peer identity on register', async () => {
  let n = 0
  const s = await startRelay({ port: 0, deps: { verify: () => ({ ...claims, userId: `u${++n}`, nonce: `n${n}` }), now: () => 0 } })
  stop = s.close
  const a = new WebSocket(s.address)
  const b = new WebSocket(s.address)
  const first = await new Promise<Record<string, unknown>>((res) => {
    a.on('open', () => a.send(JSON.stringify({ t: 'register', token: 'x' })))
    a.on('message', (d) => res(JSON.parse(d.toString())))
  })
  const second = await new Promise<Record<string, unknown>>((res) => {
    b.on('open', () => b.send(JSON.stringify({ t: 'register', token: 'y' })))
    b.on('message', (d) => res(JSON.parse(d.toString())))
  })
  expect(first['peerInfo']).toEqual([])
  expect(second['peers']).toEqual([0])
  expect(second['peerInfo']).toEqual([{ slot: 0, userId: 'u1' }])
  a.close(); b.close()
})

it('forwards a keyframe request to the slot owner', async () => {
  let n = 0
  const s = await startRelay({ port: 0, deps: { verify: () => ({ ...claims, userId: `u${++n}`, nonce: `n${n}` }), now: () => 0 } })
  stop = s.close
  const a = new WebSocket(s.address)
  const b = new WebSocket(s.address)
  const registered = (ws: WebSocket) => new Promise<Record<string, unknown>>((res) => {
    ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token: 'x' })))
    ws.on('message', function once(d) { ws.off('message', once); res(JSON.parse(d.toString())) })
  })
  await registered(a)
  await registered(b)
  const need = new Promise<Record<string, unknown>>((res) => {
    a.on('message', (d) => res(JSON.parse(d.toString())))
  })
  b.send(JSON.stringify({ t: 'keyframe', d: { slot: 0 } }))
  expect((await need)['t']).toBe('need_keyframe')
  a.close(); b.close()
})
```

- [ ] **Step 5: Run to verify they fail**

Run: `npx vitest run packages/media-relay/src/server.test.ts`
Expected: FAIL — `peerInfo` is undefined and `need_keyframe` never arrives.

- [ ] **Step 6: Implement in `server.ts`**

In the `registered` payload add:

```ts
peerInfo: peers.map((p) => ({ slot: p.slot, userId: p.userId })),
```

Replace `if (typed.t !== 'register') return` with a small dispatch. For `keyframe`:

```ts
if (typed.t === 'keyframe') {
  if (!current.member) return
  const d = (message as { d?: { slot?: unknown } }).d
  const slot = typeof d?.slot === 'number' ? d.slot : -1
  if (!Number.isInteger(slot) || slot < 0 || slot > 15) return
  const target = registry.memberBySlot(current.member.appId, current.member.roomId, slot)
  if (!target || target.slot === current.member.slot) return
  requestKeyframe([target])
  return
}
if (typed.t !== 'register') return
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `npx vitest run packages/media-relay/src/server.test.ts packages/media-relay/src/registry.test.ts`
Expected: PASS.

- [ ] **Step 8: Extend the smoke harness**

In `tools/smoke.ts`, after the replay check, add a fresh-token reconnect check: request `call.token`, register a new socket with it, and assert `registered` returns the same slot as before. Adapt the variable names to the ones already in the file.

```ts
alice.ws.send(JSON.stringify({ t: 'call.token', d: { callId } }))
const refreshed = await alice.next()
check('call.token returns a token', refreshed.t === 'room.token')
const reReg = await registerAndWait(relayUrl, refreshed.d['token'] as string)
check('refreshed token can rejoin the same slot', reReg.slot === slotAlice)
```

Add the helper if absent:

```ts
const registerAndWait = (url: string, token: string) =>
  new Promise<{ slot: number; peers: number[] }>((resolve, reject) => {
    const ws = new WebSocket(url)
    ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token })))
    ws.on('message', (d) => { const m = JSON.parse(d.toString()); if (m.t === 'registered') resolve(m) })
    ws.on('error', reject)
  })
```

- [ ] **Step 9: Run the full suite and smoke**

Run: `npx vitest run && npm run smoke`
Expected: all tests pass, `SMOKE PASS` with the new checks.

- [ ] **Step 10: Commit**

```bash
git add packages/media-relay/src/registry.ts packages/media-relay/src/server.ts packages/media-relay/src/registry.test.ts packages/media-relay/src/server.test.ts tools/smoke.ts
git commit -m "feat(relay): peer identity on register and client keyframe requests"
```

### Task 4: Gradle build scaffold

**Files:**
- Create: `sdk-android/settings.gradle.kts`
- Create: `sdk-android/build.gradle.kts`
- Create: `sdk-android/gradle/libs.versions.toml`
- Create: `sdk-android/gradle/wrapper/gradle-wrapper.properties`, `sdk-android/gradlew`, `sdk-android/gradlew.bat` (copy from `/home/sived/StudioProjects/Research-Hub-Vaptrol`)
- Create: `sdk-android/rtc/build.gradle.kts`
- Create: `sdk-android/rtc/src/main/AndroidManifest.xml`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/api/Config.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/api/Logger.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/api/VideoConfigTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `StreamRtcConfig(controlUrl, tokenProvider, displayName, appVersion, roomTokenProvider, video, audio, foregroundService)`, `VideoConfig(width, height, fps, bitrate, keyframeIntervalSec)` with `Low`/`Standard`/`High`, `AudioConfig(sampleRate, frameMs, echoCancellation, noiseSuppression)` with `Default`, `CallMedia(audio, video)` with `audioVideo`/`audioOnly`, `RtcLogger` with `d`/`e` and `RtcLogger.None`.

- [ ] **Step 1: Create the Gradle files**

Copy the wrapper from the host app so the distribution is already cached on this machine:

```bash
mkdir -p sdk-android/gradle/wrapper
cp -r /home/sived/StudioProjects/Research-Hub-Vaptrol/gradle/wrapper/. sdk-android/gradle/wrapper/
cp /home/sived/StudioProjects/Research-Hub-Vaptrol/gradlew /home/sived/StudioProjects/Research-Hub-Vaptrol/gradlew.bat sdk-android/
chmod +x sdk-android/gradlew
```

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "streamrtc-android"
include(":rtc")
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.1.0"
kotlin = "2.1.0"
coroutines = "1.9.0"
serialization = "1.7.3"
okhttp = "4.12.0"
cameraX = "1.6.0"
coreKtx = "1.18.0"
lifecycle = "2.10.0"
junit = "4.13.2"
androidxJunit = "1.2.1"
testRunner = "1.6.2"

[libraries]
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
camera-core = { group = "androidx.camera", name = "camera-core", version.ref = "cameraX" }
camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "cameraX" }
camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "cameraX" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-test-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "testRunner" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

`rtc/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
}

android {
    namespace = "io.streamrtc"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { jvmToolchain(17) }
    testOptions { unitTests.isReturnDefaultValues = true }

    sourceSets.getByName("test").resources.srcDirs("../../packages/protocol/testdata")

    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            groupId = "io.streamrtc"
            artifactId = "rtc"
            version = "0.1.0"
        }
    }
}
```

`AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
</manifest>
```

- [ ] **Step 2: Write the failing config test**

```kotlin
package io.streamrtc.api

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoConfigTest {
    @Test
    fun presetsMatchTheSpec() {
        assertEquals(640, VideoConfig.Standard.width)
        assertEquals(360, VideoConfig.Standard.height)
        assertEquals(24, VideoConfig.Standard.fps)
        assertEquals(700_000, VideoConfig.Standard.bitrate)
        assertEquals(320, VideoConfig.Low.width)
        assertEquals(1_500_000, VideoConfig.High.bitrate)
        assertEquals(2, VideoConfig.Standard.keyframeIntervalSec)
    }

    @Test
    fun audioDefaultsToPcm16TwentyMillisecondFrames() {
        assertEquals(16_000, AudioConfig.Default.sampleRate)
        assertEquals(20, AudioConfig.Default.frameMs)
        assertEquals(640, AudioConfig.Default.frameMs * AudioConfig.Default.sampleRate / 1000 * 2)
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run:

```bash
cd sdk-android && JAVA_HOME=/home/sived/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew :rtc:testDebugUnitTest --tests 'io.streamrtc.api.VideoConfigTest'
```

Expected: FAIL — unresolved reference `VideoConfig`.

- [ ] **Step 4: Implement the config types**

`api/Config.kt`:

```kotlin
package io.streamrtc.api

data class VideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val keyframeIntervalSec: Int = 2,
) {
    companion object {
        val Low = VideoConfig(320, 180, 15, 300_000)
        val Standard = VideoConfig(640, 360, 24, 700_000)
        val High = VideoConfig(1280, 720, 30, 1_500_000)
    }
}

data class AudioConfig(
    val sampleRate: Int = 16_000,
    val frameMs: Int = 20,
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
) {
    companion object { val Default = AudioConfig() }
    /** Bytes in one mono PCM16 frame. */
    val frameBytes: Int get() = sampleRate / 1000 * frameMs * 2
}

data class CallMedia(val audio: Boolean, val video: Boolean) {
    companion object {
        val audioVideo = CallMedia(audio = true, video = true)
        val audioOnly = CallMedia(audio = true, video = false)
    }
}

data class StreamRtcConfig(
    val controlUrl: String,
    val tokenProvider: suspend () -> String,
    val displayName: String,
    val appVersion: String? = null,
    val roomTokenProvider: (suspend (String) -> String)? = null,
    val video: VideoConfig = VideoConfig.Standard,
    val audio: AudioConfig = AudioConfig.Default,
    val foregroundService: Boolean = true,
)
```

`api/Logger.kt`:

```kotlin
package io.streamrtc.api

interface RtcLogger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, error: Throwable? = null)

    companion object {
        val None: RtcLogger = object : RtcLogger {
            override fun d(tag: String, message: String) = Unit
            override fun e(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}
```

- [ ] **Step 5: Run the test and the dependency check**

Run:

```bash
cd sdk-android && JAVA_HOME=/home/sived/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew :rtc:testDebugUnitTest --tests 'io.streamrtc.api.VideoConfigTest'
```

Expected: PASS, 2 tests.

Run:

```bash
JAVA_HOME=/home/sived/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew :rtc:dependencies --configuration releaseCompileClasspath | grep -ci compose
```

Expected: `0` (the library must not pull Compose).

- [ ] **Step 6: Publish to Maven Local**

Run:

```bash
JAVA_HOME=/home/sived/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew :rtc:publishToMavenLocal
```

Expected: BUILD SUCCESSFUL; `~/.m2/repository/io/streamrtc/rtc/0.1.0/` contains the AAR.

- [ ] **Step 7: Commit**

```bash
git add sdk-android
git commit -m "build(android): standalone Gradle build for :rtc"
```

---

### Task 5: Frame and message codecs

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/protocol/FrameCodec.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/protocol/Messages.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/protocol/FrameCodecTest.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/protocol/MessagesTest.kt`

**Interfaces:**
- Consumes: `packages/protocol/testdata/frames.json` (Task 1).
- Produces: `FrameCodec.encodeClient/decodeClient/encodeRelay/decodeRelay`, `ClientFrame`, `RelayFrame`, protocol constants; `ServerEvent(t, d)`, `Messages.parseServer`, `ClientMessages.hello/invite/accept/reject/end/callToken/register/keyframe/ping`.

- [ ] **Step 1: Write the failing frame test**

```kotlin
package io.streamrtc.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCodecTest {
    private val vectors = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("frames.json")!!.readText(),
    ).jsonArray

    @Test
    fun clientAndRelayHeadersMatchTheSharedVectors() {
        for (element in vectors) {
            val v = element.jsonObject
            fun str(key: String) = v.getValue(key).jsonPrimitive.content
            fun num(key: String) = v.getValue(key).jsonPrimitive.int
            val payload = hex(str("payloadHex"))
            val client = FrameCodec.encodeClient(num("type"), num("flags"), num("mediaTs").toLong(), payload)
            assertEquals(str("name"), str("clientHex"), hex(client))
            val relay = FrameCodec.encodeRelay(num("type"), num("flags"), num("senderSlot"), num("mediaTs").toLong(), payload)
            assertEquals(str("name"), str("relayHex"), hex(relay))
        }
    }

    @Test
    fun mediaTsRoundTripsAcrossTheFullUnsignedRange() {
        val big = FrameCodec.decodeRelay(
            FrameCodec.encodeRelay(FrameCodec.TYPE_H264, 0, 9, 4_294_967_295L, ByteArray(0)),
        )!!
        assertEquals(4_294_967_295L, big.mediaTs)
    }

    @Test
    fun decodesWhatItEncodesAndRejectsBadHeaders() {
        val payload = byteArrayOf(1, 2, 3)
        val client = FrameCodec.decodeClient(FrameCodec.encodeClient(FrameCodec.TYPE_PCM16, 0, 1234, payload))!!
        assertEquals(1234L, client.mediaTs)
        assertArrayEquals(payload, client.payload)

        val relay = FrameCodec.decodeRelay(FrameCodec.encodeRelay(FrameCodec.TYPE_H264, FrameCodec.FLAG_KEYFRAME, 15, 42, payload))!!
        assertEquals(15, relay.senderSlot)
        assertEquals(FrameCodec.FLAG_KEYFRAME, relay.flags)

        assertNull(FrameCodec.decodeClient(ByteArray(7)))
        assertNull(FrameCodec.decodeClient(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(FrameCodec.decodeRelay(byteArrayOf(0x53, 1, FrameCodec.TYPE_H264.toByte(), 0, 16, 0, 0, 0, 0)))
        assertNull(FrameCodec.decodeRelay(byteArrayOf(0x53, 1, 0x30, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun timestampsWrapAsUnsigned32() {
        val f = FrameCodec.decodeClient(FrameCodec.encodeClient(FrameCodec.TYPE_OPUS, 0, 0x1_0000_0005L, ByteArray(0)))!!
        assertEquals(5L, f.mediaTs)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hex(text: String) = if (text.isEmpty()) ByteArray(0) else ByteArray(text.length / 2) {
        text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd sdk-android && JAVA_HOME=/home/sived/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2 ./gradlew :rtc:testDebugUnitTest --tests 'io.streamrtc.protocol.FrameCodecTest'`
Expected: FAIL — unresolved `FrameCodec`.

- [ ] **Step 3: Implement `FrameCodec`**

Constants and layout exactly as `packages/protocol/src/frame.ts`. Use a `ByteBuffer.allocate(...).order(ByteOrder.BIG_ENDIAN)`; write `mediaTs` with `putInt((mediaTs and 0xffffffffL).toInt())`; read back with `getInt().toLong() and 0xffffffffL`. Validation: length ≥ header, magic, version, type in `0x10/0x11/0x20`, and for relay `senderSlot <= 15`. `payload` is a copy of the remaining bytes.

- [ ] **Step 4: Run the frame test**

Expected: PASS, 3 tests.

- [ ] **Step 5: Write the failing message test**

```kotlin
package io.streamrtc.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun objOf(text: String) = obj(text).getValue("d").jsonObject

    @Test fun parsesHelloOkAndRoomToken() {
        val ok = Messages.parseServer("""{"t":"hello.ok","d":{"userId":"u1","appId":"a1","serverTime":5}}""")!!
        assertEquals("hello.ok", ok.t)
        assertEquals("u1", ok.d.getValue("userId").jsonPrimitive.content)

        val token = Messages.parseServer("""{"t":"room.token","d":{"token":"jwt","relayUrl":"wss://r","roomId":"r1","role":"host","expiresIn":300,"peers":[]}}""")!!
        assertEquals("wss://r", token.d.getValue("relayUrl").jsonPrimitive.content)
    }

    @Test fun returnsNullForGarbageUnknownAndOversizedMessages() {
        assertNull(Messages.parseServer("not json"))
        assertNull(Messages.parseServer("""{"t":""}"""))
        assertNull(Messages.parseServer("""{"d":{}}"""))
        assertNull(Messages.parseServer("""{"t":"pong","d":{"pad":"${"x".repeat(70_000)}"}}"""))
    }

    @Test fun buildsClientMessagesWithTheExactSchema() {
        val hello = obj(ClientMessages.hello("tok", "device-1", "android", "1.0", listOf("pcm16"), listOf("h264")))
        assertEquals("hello", hello.getValue("t").jsonPrimitive.content)
        assertEquals("tok", objOf(hello.toString()).getValue("token").jsonPrimitive.content)
        assertEquals("android", objOf(hello.toString()).getValue("platform").jsonPrimitive.content)

        val invite = obj(ClientMessages.invite("u2", "r1", audio = true, video = false))
        assertEquals("call.invite", invite.getValue("t").jsonPrimitive.content)
        val media = objOf(invite.toString()).getValue("media").jsonObject
        assertEquals(false, media.getValue("video").jsonPrimitive.boolean)

        assertEquals("call.token", obj(ClientMessages.callToken("c1")).getValue("t").jsonPrimitive.content)
        assertEquals(3, objOf(ClientMessages.keyframe(3)).getValue("slot").jsonPrimitive.int)
        assertEquals("register", obj(ClientMessages.register("jwt")).getValue("t").jsonPrimitive.content)
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Expected: FAIL — unresolved `Messages`.

- [ ] **Step 7: Implement `Messages` and `ClientMessages`**

`Messages.parseServer(text: String): ServerEvent?` returns null when the text exceeds 64 KiB, is not JSON, or has no non-empty `t`. `d` defaults to an empty object.

Build everything with `kotlinx-serialization-json`, never `org.json` (the latter is an empty stub in JVM unit tests):

- `Messages.parseServer` → `Json.parseToJsonElement(text).jsonObject`, read `t` and `d` with `jsonPrimitive.content` / `jsonObject`.
- `ClientMessages.*` → `buildJsonObject { put("t", "call.invite"); putJsonObject("d") { ... } }.toString()`, so the emitted string matches the TS schema byte for byte in the fields the server validates.
- `ClientMessages.hello(token, deviceId, platform, appVersion, audioCodecs, videoCodecs)` writes `codecs: { audio: [...], video: [...] }` and omits `appVersion` when null.

- [ ] **Step 8: Run the message test**

Expected: PASS, 3 tests.

- [ ] **Step 9: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/protocol sdk-android/rtc/src/test/kotlin/io/streamrtc/protocol
git commit -m "feat(sdk): frame and message codecs with shared vectors"
```

---

### Task 6: Backoff, close-code policy, participant registry

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/Backoff.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/CloseCodes.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/ParticipantRegistry.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/transport/BackoffTest.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/transport/CloseCodesTest.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/session/ParticipantRegistryTest.kt`

**Interfaces:**
- Consumes: `Participant` from `io.streamrtc.api`.
- Produces: `Backoff(baseMs, maxMs, jitter, random)` with `nextDelayMs()`/`reset()`; `CloseAction` + `actionForCloseCode(code)`; `ParticipantRegistry` with `onRegistered(self, peerInfo)`, `onPeerJoined(slot, userId)`, `onPeerLeft(slot)`, `setHasVideo(slot, hasVideo)`, `get(slot)`, `snapshot()`.

- [ ] **Step 1: Write the failing tests**

`BackoffTest`:

```kotlin
package io.streamrtc.transport

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test fun doublesUpToTheCap() {
        val b = Backoff(baseMs = 250, maxMs = 8000, jitter = 0.0, random = Random(1))
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 8000L), List(7) { b.nextDelayMs() })
    }

    @Test fun jitterStaysWithinTwentyPercent() {
        val b = Backoff(baseMs = 1000, maxMs = 8000, jitter = 0.2, random = Random(7))
        repeat(50) {
            val d = b.nextDelayMs()
            assertTrue(d in 800..1200)
        }
    }

    @Test fun resetStartsOver() {
        val b = Backoff(baseMs = 250, jitter = 0.0, random = Random(1))
        b.nextDelayMs(); b.nextDelayMs(); b.reset()
        assertEquals(250L, b.nextDelayMs())
    }
}
```

`CloseCodesTest`:

```kotlin
package io.streamrtc.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class CloseCodesTest {
    @Test fun mapsRelayAndControlCloses() {
        assertEquals(CloseAction.REFRESH_TOKEN, actionForCloseCode(4401))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(4409))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(1008))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(1009))
        assertEquals(CloseAction.REDUCE_BITRATE, actionForCloseCode(1013))
        assertEquals(CloseAction.RECONNECT, actionForCloseCode(1011))
        assertEquals(CloseAction.RECONNECT, actionForCloseCode(1006))
    }
}
```

`ParticipantRegistryTest`:

```kotlin
package io.streamrtc.session

import io.streamrtc.api.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParticipantRegistryTest {
    private val self = Participant(slot = 0, userId = "me", hasVideo = true)

    @Test fun mergesRegisteredPeersAndJoins() {
        val r = ParticipantRegistry()
        r.onRegistered(self, listOf(1 to "a", 2 to "b"))
        r.onPeerJoined(3, "c")
        assertEquals(listOf(1, 2, 3), r.snapshot().filter { it.slot != 0 }.map { it.slot })
        assertEquals("b", r.get(2)?.userId)
    }

    @Test fun removesLeaversAndReplacesAReusedSlot() {
        val r = ParticipantRegistry()
        r.onRegistered(self, listOf(1 to "a"))
        r.onPeerLeft(1)
        assertNull(r.get(1))
        r.onPeerJoined(1, "someone-else")
        assertEquals("someone-else", r.get(1)?.userId)
    }

    @Test fun tracksVideoIntentDiscoveredFromFrames() {
        val r = ParticipantRegistry()
        r.onRegistered(self, listOf(1 to "a"))
        assertEquals(false, r.get(1)?.hasVideo)
        r.setHasVideo(1, true)
        assertEquals(true, r.get(1)?.hasVideo)
    }
}
```

`Participant` is added to `api/Models.kt` in this task.

- [ ] **Step 2: Run to verify they fail**

Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement**

`Backoff`: `nextDelayMs()` returns `min(baseMs shl attempt, maxMs)` scaled by `1 + random.nextDouble(-jitter, jitter)` and increments `attempt`.

`CloseCodes`: return the table above with `RECONNECT` as the default.

`Participant`: `data class Participant(val slot: Int, val userId: String?, val hasVideo: Boolean = false, val speaking: Boolean = false)`.

`ParticipantRegistry` keeps a `LinkedHashMap<Int, Participant>`; `onRegistered` replaces the whole map with `self` plus `peerInfo`; `onPeerJoined` overwrites the slot; `snapshot()` returns self first then ascending slots.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :rtc:testDebugUnitTest --tests 'io.streamrtc.transport.*' --tests 'io.streamrtc.session.*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/transport sdk-android/rtc/src/main/kotlin/io/streamrtc/session sdk-android/rtc/src/main/kotlin/io/streamrtc/api/Models.kt sdk-android/rtc/src/test
git commit -m "feat(sdk): backoff, close-code policy, participant registry"
```

---

### Task 7: Control transport and client

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/ControlTransport.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/ControlClient.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/api/ConnectionState.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/transport/FakeControlTransport.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/transport/OkHttpControlTransportTest.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/transport/ControlClientTest.kt`

**Interfaces:**
- Consumes: `ClientMessages.hello`, `Messages.parseServer`, `Backoff`, `actionForCloseCode`.
- Produces: `ConnectionState` (`Idle`, `Connecting`, `Online`, `Reconnecting(attempt: Int)`, `Failed(code: String)`); `ControlTransport` with `connect(onOpen, onMessage, onClosed)`, `send(text): Boolean`, `close()`; `OkHttpControlTransport(http, url)`; `ControlClient(transportFactory, tokenProvider, scope, deviceId, appVersion, logger, backoff)` with `state: StateFlow<ConnectionState>`, `events: SharedFlow<ServerEvent>`, `start()`, `send(text): Boolean`, `stop()`.

- [ ] **Step 1: Write the fake and the OkHttp transport test**

`FakeControlTransport` (test source): records `sent: MutableList<String>`, exposes `emitOpen()`, `emitMessage(text)`, `emitClosed(code, reason)`, and a `closed` flag.

`OkHttpControlTransportTest`:

```kotlin
@Test fun carriesTextBothWays() {
    val server = MockWebServer()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(ws: WebSocket, text: String) { ws.send("echo:$text") }
    }))
    server.start()
    val transport = OkHttpControlTransport(OkHttpClient(), server.url("/ws").toString().replace("http", "ws"))
    val messages = LinkedBlockingQueue<String>()
    val opened = CountDownLatch(1)
    transport.connect({ opened.countDown() }, { messages.put(it) }, { _, _ -> })
    assertTrue(opened.await(2, TimeUnit.SECONDS))
    transport.send("hi")
    assertEquals("echo:hi", messages.poll(2, TimeUnit.SECONDS))
    transport.close()
    server.shutdown()
}
```

Add `okhttp3.mockwebserver.MockWebServer` imports; note the URL must be converted to `ws://`.

- [ ] **Step 2: Run it to verify it fails**

Expected: FAIL — unresolved `OkHttpControlTransport`.

- [ ] **Step 3: Implement `ControlTransport` + `OkHttpControlTransport`**

```kotlin
interface ControlTransport {
    fun connect(onOpen: () -> Unit, onMessage: (String) -> Unit, onClosed: (code: Int, reason: String) -> Unit)
    fun send(text: String): Boolean
    fun close()
}
```

`OkHttpControlTransport` builds `Request.Builder().url(url).build()` and `http.newWebSocket(request, listener)`. The client must be built with `pingInterval(20, TimeUnit.SECONDS)` and `readTimeout(0, TimeUnit.MILLISECONDS)`; do that in `SessionManager` (Task 11) and accept the `OkHttpClient` here.

- [ ] **Step 4: Write the failing client tests**

```kotlin
@Test fun sendsHelloOnOpenAndGoesOnline() = runTest {
    val t = FakeControlTransport()
    val tokens = mutableListOf("t1", "t2")
    val client = ControlClient({ t }, { tokens.removeFirst() }, backgroundScope, "dev-1", null, RtcLogger.None, Backoff(250, 8000, 0.0, Random(1)))
    client.start()
    t.emitOpen()
    advanceUntilIdle()
    assertTrue(t.sent.single().contains("\"t\":\"hello\""))
    t.emitMessage("""{"t":"hello.ok","d":{"userId":"u1","appId":"a1","serverTime":1}}""")
    advanceUntilIdle()
    assertEquals(ConnectionState.Online, client.state.value)
}

@Test fun refreshesTheTokenOnA4401AndFailsAfterASecondOne() = runTest {
    val t = FakeControlTransport()
    val calls = mutableListOf<Int>()
    var n = 0
    val client = ControlClient({ t }, { calls.add(++n); "tok$n" }, backgroundScope, "dev-1", null, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
    client.start()
    t.emitOpen(); advanceUntilIdle()
    t.emitClosed(4401, "invalid_token"); advanceUntilIdle()
    t.emitOpen(); advanceUntilIdle()
    assertEquals(2, calls.size)
    t.emitClosed(4401, "invalid_token"); advanceUntilIdle()
    assertTrue(client.state.value is ConnectionState.Failed)
}

@Test fun reconnectsWithBackoffOnTransportErrors() = runTest {
    val t = FakeControlTransport()
    val client = ControlClient({ t }, { "tok" }, backgroundScope, "dev-1", null, RtcLogger.None, Backoff(10, 20, 0.0, Random(1)))
    client.start(); t.emitOpen(); advanceUntilIdle()
    t.emitClosed(1011, "boom"); advanceUntilIdle()
    assertTrue(client.state.value is ConnectionState.Reconnecting)
    advanceTimeBy(11)
    assertEquals(2, t.connectCount)
}
```

- [ ] **Step 5: Run to verify they fail**

Expected: FAIL — unresolved `ControlClient`.

- [ ] **Step 6: Implement `ControlClient`**

On `start()`: set `Connecting`, launch `connectLoop()`. The loop calls `tokenProvider()` (catching failure → `Failed("token")`), creates a transport, sends `ClientMessages.hello(token, deviceId, "android", appVersion, listOf("pcm16"), listOf("h264"))` in `onOpen`, and sets `Online` on `hello.ok`. On close: `TERMINAL` → `Failed`; `REFRESH_TOKEN` → increment the 4401 counter (fail at 2) and reconnect immediately; `RECONNECT`/`REDUCE_BITRATE` → `Reconnecting(attempt)` and `delay(backoff.nextDelayMs())`. Emit every parsed event on `events`; `pong` refreshes a `lastPongMs` value that `SessionManager` may read.

- [ ] **Step 7: Run the tests**

Expected: PASS, 4 tests.

- [ ] **Step 8: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/transport sdk-android/rtc/src/main/kotlin/io/streamrtc/api/ConnectionState.kt sdk-android/rtc/src/test/kotlin/io/streamrtc/transport
git commit -m "feat(sdk): control websocket client with token refresh and backoff"
```

---

### Task 8: 1:1 call state machine

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/api/CallState.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/CallSession.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/MessageSink.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/RoomToken.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/session/CallSessionTest.kt`

**Interfaces:**
- Consumes: `MessageSink`, `ServerEvent`, `ClientMessages`, `Backoff`.
- Produces: `EndReason` (`RemoteEnded`, `LocalEnded`, `Rejected`, `NoAnswer`, `CalleeOffline`, `NetworkFailed`, `PermissionDenied`, `Error(code)`), `CallState` (`Dialing`, `Ringing`, `Connecting`, `Active`, `Ended(reason)`), `RoomToken(token, relayUrl, roomId, role, expiresIn)`, `CallSession(callId, peerUserId, media, sink, now)` with `state`, `token`, `dial(roomId)`, `onIncoming(event)`, `accept()`, `reject(reason)`, `end(reason)`, `onServerEvent(event)`, `refreshToken()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.streamrtc.session

import io.streamrtc.api.CallMedia
import io.streamrtc.api.CallState
import io.streamrtc.api.EndReason
import io.streamrtc.protocol.Messages
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionTest {
    private val sink = RecordingSink()
    private fun session(callId: String? = "c1") =
        CallSession(callId, "peer", CallMedia.audioVideo, sink, now = { 1_000 })

    private fun event(json: String) = Messages.parseServer(json)!!

    @Test fun dialSendsInviteAndSurfacesCalleeOffline() = runTest {
        val s = session(callId = null)
        s.dial("r1")
        assertEquals(CallState.Dialing, s.state.value)
        assertTrue(sink.single().contains("\"t\":\"call.invite\""))
        s.onServerEvent(event("""{"t":"callee_offline","d":{"toUserId":"peer"}}"""))
        assertEquals(CallState.Ended(EndReason.CalleeOffline), s.state.value)
    }

    @Test fun callerMovesToConnectingOnAcceptedThenReceivesAToken() = runTest {
        val s = session()
        s.dial("r1")
        s.onServerEvent(event("""{"t":"call.accepted","d":{"callId":"c1","byUserId":"peer","roomId":"r1"}}"""))
        assertEquals(CallState.Connecting, s.state.value)
        s.onServerEvent(event("""{"t":"room.token","d":{"token":"jwt","relayUrl":"wss://r","roomId":"r1","role":"host","expiresIn":300,"peers":[]}}"""))
        assertEquals("jwt", s.token.value?.token)
        assertEquals("host", s.token.value?.role)
    }

    @Test fun calleeAcceptsAndRejects() = runTest {
        val s = session()
        s.onIncoming(event("""{"t":"call.incoming","d":{"callId":"c1","fromUserId":"peer","roomId":"r1","media":{"audio":true,"video":true}}}"""))
        assertEquals(CallState.Ringing, s.state.value)
        s.accept()
        assertTrue(sink.single().contains("\"t\":\"call.accept\""))
        assertEquals(CallState.Connecting, s.state.value)

        val other = session()
        other.onIncoming(event("""{"t":"call.incoming","d":{"callId":"c1","fromUserId":"peer","roomId":"r1","media":{"audio":true,"video":true}}}"""))
        other.reject("busy")
        assertEquals(CallState.Ended(EndReason.Rejected), other.state.value)
    }

    @Test fun remoteEndWins() = runTest {
        val s = session()
        s.dial("r1")
        s.onServerEvent(event("""{"t":"call.ended","d":{"callId":"c1","reason":"ended"}}"""))
        assertEquals(CallState.Ended(EndReason.RemoteEnded), s.state.value)
    }

    @Test fun errorsMapToTypedEndReasons() = runTest {
        val s = session(callId = null)
        s.dial("r1")
        s.onServerEvent(event("""{"t":"error","d":{"code":"self_call"}}"""))
        assertEquals(CallState.Ended(EndReason.Error("self_call")), s.state.value)
    }

    private class RecordingSink : MessageSink {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean { sent.add(text); return true }
        fun single() = sent.single()
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Expected: FAIL — unresolved `CallSession`.

- [ ] **Step 3: Implement the state machine**

`MessageSink` is `interface MessageSink { fun send(text: String): Boolean }`.

`CallSession` rules:

- `dial(roomId)`: only from `Dialing`; send `ClientMessages.invite(peerUserId, roomId, media.audio, media.video)`.
- `onIncoming(event)`: set `Ringing` when `d.callId` matches.
- `accept()`: from `Ringing` → send `call.accept`, set `Connecting`.
- `reject(reason)`: from `Ringing` → send `call.reject`, `Ended(Rejected)`.
- `onServerEvent`: `call.accepted` → `Connecting`; `room.token` → `token.value = RoomToken(...)`, and if state is `Connecting` stay there (relay connect is Task 12); `call.rejected` → `Ended(Rejected)`; `call.ended` → `Ended(RemoteEnded)`, except `reason == "timeout"` → `Ended(NoAnswer)`; `callee_offline` → `Ended(CalleeOffline)`; `error` → `Ended(Error(code))`.
- `end()` → send `call.end`, `Ended(LocalEnded)`.
- Every terminal transition is idempotent (`if (state.value is CallState.Ended) return`).
- `refreshToken()`: sends `ClientMessages.callToken(callId)` (Task 18 calls this on a 4401).

- [ ] **Step 4: Run to verify they pass**

Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/api/CallState.kt sdk-android/rtc/src/main/kotlin/io/streamrtc/session sdk-android/rtc/src/test/kotlin/io/streamrtc/session
git commit -m "feat(sdk): 1:1 call signalling state machine"
```

### Task 9: Jitter buffer and A/V sync math

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/audio/JitterBuffer.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/Sync.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/media/audio/JitterBufferTest.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/media/SyncTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `JitterBuffer(frameBytes, frameMs, capacityFrames, clockMs)` with `push(mediaTs, payload)`, `poll(): ByteArray?`, `targetDepthMs`, `depthMs`, `reset()`; `SyncEstimator(minSamples)` with `onAudioArrival(mediaTs, arrivalMs)`, `offsetMs(): Long?`, `reset()`; `VideoScheduler(maxReorderFrames, lateDropMs)` with `action(dueAtMs, nowMs): VideoAction`; `enum class VideoAction { Render, Drop, Hold }`.

- [ ] **Step 1: Write the failing jitter buffer tests**

```kotlin
package io.streamrtc.media.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {
    private var now = 0L
    private fun buf() = JitterBuffer(frameBytes = 4, frameMs = 20, capacityFrames = 10, clockMs = { now })

    @Test fun emitsNothingUntilTheTargetDepthIsReached() {
        val b = buf()
        b.push(0, byteArrayOf(1, 0, 0, 0))
        assertNull(b.poll())
        now = 40
        b.push(20, byteArrayOf(2, 0, 0, 0))
        b.push(40, byteArrayOf(3, 0, 0, 0))
        assertEquals(3, b.depthMs / 20)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), b.poll())
    }

    @Test fun dropsFramesOlderThanTheLastDeliveredOne() {
        val b = buf()
        repeat(5) { i -> b.push(i * 20L, byteArrayOf(i.toByte())) }
        now = 200
        b.poll()
        b.poll()
        b.push(10, byteArrayOf(99))
        var seen = mutableListOf<Byte>()
        while (true) { val f = b.poll() ?: break; seen.add(f[0]) }
        assertEquals(listOf<Byte>(2, 3, 4), seen)
    }

    @Test fun targetDepthGrowsWithArrivalJitter() {
        val b = buf()
        repeat(6) { i ->
            if (i % 2 == 0) now += 20 else now += 120   // alternating on-time and late
            b.push(i * 20L, ByteArray(4))
        }
        assertEquals(true, b.targetDepthMs > 40)
        assertEquals(true, b.targetDepthMs <= 200)
    }

    @Test fun resetClearsEverything() {
        val b = buf()
        repeat(5) { i -> b.push(i * 20L, ByteArray(4)) }
        now = 100
        b.reset()
        assertEquals(0, b.depthMs)
        assertNull(b.poll())
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Expected: FAIL — unresolved `JitterBuffer`.

- [ ] **Step 3: Implement the jitter buffer**

Store frames in an `ArrayDeque<Pair<Long, ByteArray>>`. `mediaTs` may wrap; treat the first frame as the base and compare with a signed 32-bit delta. Target depth: `40 + 3 * emaDeviation`, clamped to `40..200`, where the deviation is measured against `baseArrival + (mediaTs - baseTs)`. `depthMs` = (`lastTs - firstTs`) + `frameMs` using the same wrap-safe deltas. Drop on push when `mediaTs` is not newer than the last delivered `mediaTs`.

- [ ] **Step 4: Run the jitter tests**

Expected: PASS, 4 tests.

- [ ] **Step 5: Write the failing sync tests**

```kotlin
package io.streamrtc.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTest {
    @Test fun offsetIsTheMedianOfAudioArrivalDeltas() {
        val s = SyncEstimator(minSamples = 5)
        repeat(5) { i -> s.onAudioArrival(i * 20L, 1_000 + i * 20L + if (i == 2) 300 else 40) }
        assertEquals(40L, s.offsetMs())
    }

    @Test fun reportsNullUntilEnoughSamples() {
        val s = SyncEstimator(minSamples = 5)
        s.onAudioArrival(0, 40)
        assertNull(s.offsetMs())
    }

    @Test fun schedulerRendersOnTimeDropsLateAndHoldsEarly() {
        val v = VideoScheduler(lateDropMs = 100)
        assertEquals(VideoAction.Render, v.action(dueAtMs = 1_000, nowMs = 1_050))
        assertEquals(VideoAction.Hold, v.action(dueAtMs = 1_200, nowMs = 1_050))
        assertEquals(VideoAction.Drop, v.action(dueAtMs = 900, nowMs = 1_050))
    }

    @Test fun resetForgetsTheEstimate() {
        val s = SyncEstimator(minSamples = 1)
        s.onAudioArrival(0, 40)
        s.reset()
        assertNull(s.offsetMs())
    }
}
```

- [ ] **Step 6: Run to verify they fail, then implement**

Run: `./gradlew :rtc:testDebugUnitTest --tests 'io.streamrtc.media.*'`
Expected: FAIL, then PASS after implementing `SyncEstimator` (keep the last 32 deltas, median via sorted copy) and `VideoScheduler` (three-branch rule above).

- [ ] **Step 7: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/media sdk-android/rtc/src/test/kotlin/io/streamrtc/media
git commit -m "feat(sdk): jitter buffer and A/V sync math"
```

---

### Task 10: Audio capture, playback and routing

**Files:**
- Modify: `sdk-android/gradle/libs.versions.toml` (add `androidx-test-rules`)
- Modify: `sdk-android/rtc/build.gradle.kts` (add the rules dependency)
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/audio/PcmCapture.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/audio/PcmPlayback.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/audio/AudioRouter.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/audio/AudioDevice.kt`
- Test: `sdk-android/rtc/src/androidTest/kotlin/io/streamrtc/media/audio/PcmCaptureTest.kt`
- Test: `sdk-android/rtc/src/androidTest/kotlin/io/streamrtc/media/audio/PcmPlaybackTest.kt`

**Interfaces:**
- Consumes: `AudioConfig` (frameBytes, sampleRate, frameMs), `JitterBuffer`, `RtcLogger`.
- Produces: `PcmCapture(config, logger).start(onFrame: (ByteArray, Long) -> Unit)` / `.stop()`; `PcmPlayback(sampleRate, frameBytes).start()` / `.write(bytes)` / `.stop()`; `AudioRouter(context)` with `start(speakerphone: Boolean)`, `stop()`, `setSpeakerphone(Boolean)`, `route: StateFlow<AudioRoute>`; `AudioDevice` interface implemented by a `DefaultAudioDevice`.

- [ ] **Step 1: Add the rules dependency**

`libs.versions.toml`: `androidx-test-rules = { group = "androidx.test", name = "rules", version.ref = "testRunner" }`.
`rtc/build.gradle.kts`: `androidTestImplementation(libs.androidx.test.rules)`.

- [ ] **Step 2: Write the failing instrumented tests**

`PcmCaptureTest`:

```kotlin
@RunWith(AndroidJUnit4::class)
class PcmCaptureTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun producesTwentyMillisecondPcmFrames() {
        val frames = LinkedBlockingQueue<ByteArray>()
        val capture = PcmCapture(AudioConfig.Default, RtcLogger.None)
        capture.start { bytes, _ -> frames.put(bytes) }
        try {
            val first = frames.poll(5, TimeUnit.SECONDS)!!
            assertEquals(640, first.size)
        } finally {
            capture.stop()
        }
    }
}
```

`PcmPlaybackTest`:

```kotlin
@Test fun acceptsSilenceWithoutThrowing() {
    val playback = PcmPlayback(16_000, 640)
    playback.start()
    try {
        repeat(10) { playback.write(ByteArray(640)) }
    } finally {
        playback.stop()
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd sdk-android && ./gradlew :rtc:connectedDebugAndroidTest --tests 'io.streamrtc.media.audio.*'`
Expected: FAIL — unresolved `PcmCapture` (requires a running emulator/device).

- [ ] **Step 4: Implement capture, playback and routing**

`PcmCapture`: `AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, max(minBufferSize, frameBytes * 4))`. Start on a `Thread(name = "streamrtc-capture")` with `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)`. Attach `AcousticEchoCanceler` and `NoiseSuppressor` to `audioSessionId` when `config.echoCancellation`/`noiseSuppression` and `isAvailable`. Read exactly `frameBytes` with a read loop handling partial reads; stamp the timestamp with `SystemClock.elapsedRealtime()`.

`AudioDevice`:

```kotlin
interface AudioDevice {
    fun startCapture(onFrame: (ByteArray, Long) -> Unit)
    fun stopCapture()
    fun write(slot: Int, payload: ByteArray)
    fun removeSlot(slot: Int)
    fun setSpeakerphone(on: Boolean)
    fun close()
}
```

`DefaultAudioDevice`: one `PcmPlayback` per slot created lazily, removed on `removeSlot`; owns a single `AudioRouter`.

`AudioRouter`: audio focus `AUDIOFOCUS_GAIN_TRANSIENT` with `OnAudioFocusChangeListener` (pause capture on `LOSS_TRANSIENT`, end the session on `LOSS`), `MODE_IN_COMMUNICATION`, speaker via `setCommunicationDevice` on API 31+ and `isSpeakerphoneOn` below, `AudioDeviceCallback` updating `route`.

- [ ] **Step 5: Run the instrumented tests**

Run: `./gradlew :rtc:connectedDebugAndroidTest --tests 'io.streamrtc.media.audio.*'`
Expected: PASS, 2 tests on the emulator.

- [ ] **Step 6: Commit**

```bash
git add sdk-android/gradle/libs.versions.toml sdk-android/rtc/build.gradle.kts sdk-android/rtc/src/main/kotlin/io/streamrtc/media/audio sdk-android/rtc/src/androidTest
git commit -m "feat(sdk): pcm capture, per-slot playback and audio routing"
```

---

### Task 11: Relay client and media routing

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/RelayTransport.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/RelayClient.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/Links.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/RelayEvent.kt`
- Modify: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/ControlClient.kt` (implement `ControlLink`)
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/transport/FakeRelayTransport.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/transport/RelayClientTest.kt`

**Interfaces:**
- Consumes: `ClientMessages.register`, `ClientMessages.keyframe`, `Messages.parseServer`, `FrameCodec`, `Backoff`, `RtcLogger`, `ConnectionState`, `MessageSink`.
- Produces: `ControlLink : MessageSink` with `state`, `events`, `start()`, `stop()`; `RelayLink` with `state`, `events`, `connect(token, url)`, `send(ByteArray)`, `requestKeyframe(slot)`, `queuedBytes(): Long`, `close()`; `RelayState` (`Idle`, `Connecting`, `Active`, `Reconnecting(attempt)`, `Failed(code)`); `RelayTransport` (text+binary+`queuedBytes`); `RelayClient(transportFactory, scope, logger, backoff)` implementing `RelayLink`; `RelayEvent` = `Registered(slot, peers, limits: Limits)`, `PeerJoined(slot, userId)`, `PeerLeft(slot)`, `KeyframeNeeded`, `Media(RelayFrame)`.

- [ ] **Step 1: Write the fake and the failing tests**

`FakeRelayTransport` records `sentText`, `sentBinary`, exposes `emitOpen()`, `emitText()`, `emitBinary()`, `emitClosed(code, reason)`, `queuedBytes`.

`RelayClientTest`:

```kotlin
@Test fun registersAndEmitsTheSlotAndPeerInfo() = runTest {
    val t = FakeRelayTransport()
    val client = RelayClient({ t }, backgroundScope, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
    val events = mutableListOf<RelayEvent>()
    val job = launch { client.events.collect { events.add(it) } }
    client.connect("jwt", "wss://relay")
    t.emitOpen()
    advanceUntilIdle()
    assertEquals("""{"t":"register","token":"jwt"}""", t.sentText.first())
    t.emitText("""{"t":"registered","slot":2,"peers":[0,1],"peerInfo":[{"slot":0,"userId":"a"},{"slot":1,"userId":"b"}],"limits":{"maxParticipants":16,"maxVideoParticipants":8}}""")
    advanceUntilIdle()
    val reg = events.filterIsInstance<RelayEvent.Registered>().single()
    assertEquals(2, reg.slot)
    assertEquals(listOf(0, 1), reg.peers)
    assertEquals(8, reg.limits.maxVideo)
    job.cancel()
}

@Test fun routesBinaryFramesAndIgnoresMalformedJson() = runTest {
    val t = FakeRelayTransport()
    val client = RelayClient({ t }, backgroundScope, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
    val events = mutableListOf<RelayEvent>()
    val job = launch { client.events.collect { events.add(it) } }
    client.connect("jwt", "wss://relay"); t.emitOpen()
    t.emitText("""{"t":"registered","slot":0,"peers":[],"peerInfo":[],"limits":{"maxParticipants":16,"maxVideoParticipants":8}}""")
    val frame = FrameCodec.encodeRelay(FrameCodec.TYPE_PCM16, 0, 3, 20, byteArrayOf(1, 2, 3))
    t.emitBinary(frame)
    t.emitText("not json")
    advanceUntilIdle()
    assertEquals(3, events.filterIsInstance<RelayEvent.Media>().single().frame.senderSlot)
    job.cancel()
}

@Test fun sendsKeyframeRequestsToASlot() = runTest {
    val t = FakeRelayTransport()
    val client = RelayClient({ t }, backgroundScope, RtcLogger.None, Backoff(1, 2, 0.0, Random(1)))
    client.connect("jwt", "wss://relay"); t.emitOpen()
    client.requestKeyframe(4)
    assertEquals("""{"t":"keyframe","d":{"slot":4}}""", t.sentText.last())
}
```

- [ ] **Step 2: Run to verify they fail**

Expected: FAIL — unresolved `RelayClient`.

- [ ] **Step 3: Implement `RelayTransport` and `RelayClient`**

`OkHttpRelayTransport` mirrors `OkHttpControlTransport` but implements `onMessage(ws, bytes)` and `onMessage(ws, text)`, plus `queuedBytes() = webSocket.queueSize().toLong()`.

`RelayClient.connect(token, url)` creates the transport, sends `register` on open, sets `Connecting`; `registered` → `Active`; `peer_joined`/`peer_left`/`need_keyframe` → events; binary → `FrameCodec.decodeRelay` → `Media`, silently dropping undecodable buffers. A close sets `Reconnecting` or `Failed` from `actionForCloseCode` and surfaces the close to `SessionManager`, which owns the fresh-token retry (Task 18).

- [ ] **Step 4: Run the tests**

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/RelayTransport.kt sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/RelayClient.kt sdk-android/rtc/src/main/kotlin/io/streamrtc/session/RelayEvent.kt sdk-android/rtc/src/test/kotlin/io/streamrtc/transport
git commit -m "feat(sdk): relay websocket client and media event routing"
```

### Task 12: Public façade, session manager and foreground service

**Files:**
- Modify: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/RelayClient.kt` (implement `RelayLink`)
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/api/Call.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/api/Room.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/api/VideoTypes.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/ErrorMapper.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/service/ServiceControl.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/SessionManager.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/StreamRtc.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/service/CallService.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/util/AndroidLogger.kt`
- Modify: `sdk-android/rtc/src/main/AndroidManifest.xml` (declare the service)
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/session/Fakes.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/session/SessionManagerTest.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/session/ErrorMapperTest.kt`

**Interfaces:**
- Consumes: `ControlLink`, `RelayLink`, `RelayClient`, `CallSession`, `AudioDevice`, `ParticipantRegistry`, `FrameCodec`, `ClientMessages`, `AudioConfig`.
- Produces: `StreamRtc.get(context, config)`, `.start()`, `.stop()`, `.connection`, `.calls`; `Calls.dial(userId, media): Call`, `.incoming: SharedFlow<IncomingCall>`, `.active`; `Call.state/audio/end`; `SessionAudio.muted/speakerphone/route`; `SessionManager(config, control, relayFactory, audio, service, scope, clock, onQualityChange)` with `start/stop/dial/joinRoom/acceptIncoming/rejectIncoming/endAll/toggleMute`; `ErrorMapper.toEndReason(throwable)`; `CallService` with `ACTION_END`/`ACTION_TOGGLE_MUTE`.

- [ ] **Step 1: Confirm the link interfaces and add the error mapper**

`ControlLink`, `RelayLink` and `RelayState` were created in Task 11 at `session/Links.kt`. Task 11 already made `ControlClient` implement `ControlLink`; make `RelayClient` implement `RelayLink` now (one-line class header change, `override` on the members).

`ErrorMapper` maps platform failures to typed ends, so a camera taken by another app ends the call instead of crashing:

```kotlin
object ErrorMapper {
    fun toEndReason(error: Throwable): EndReason
}
```

Rules: `CameraAccessException` / `IllegalStateException` containing `"camera"` → `PermissionDenied`; `SecurityException` → `PermissionDenied`; `IOException` → `NetworkFailed`; anything else → `Error(error.javaClass.simpleName)`.

`ServiceControl` is the foreground-service seam so `SessionManager` stays JVM-testable:

```kotlin
interface ServiceControl { fun start(); fun stop() }

class CallServiceControl(context: Context) : ServiceControl { /* start/stop CallService */ }
```

- [ ] **Step 2: Write the failing session manager test**

```kotlin
package io.streamrtc.session

import io.streamrtc.api.*
import io.streamrtc.protocol.FrameCodec
import io.streamrtc.protocol.Messages
import io.streamrtc.transport.Backoff
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SessionManagerTest {
    private val control = FakeControlLink()
    private val relay = FakeRelayLink()
    private val audio = FakeAudioDevice()
    private val service = FakeServiceControl()
    private var now = 0L

    private fun manager() = SessionManager(
        config = StreamRtcConfig("https://c", { "tok" }, "me"),
        control = control,
        relayFactory = { relay },
        audio = audio,
        service = service,
        scope = backgroundScope,
        clock = { now },
    )

    @Test fun dialSendsInviteAndConnectsTheRelayOnceTheTokenArrives() = runTest {
        val m = manager()
        m.start()
        val call = m.dial("peer", CallMedia.audioVideo)
        assertEquals(CallState.Dialing, call.state.value)
        assertTrue(control.sent.single().contains("call.invite"))

        control.emit(Messages.parseServer("""{"t":"call.accepted","d":{"callId":"c1","byUserId":"peer","roomId":"r1"}}""")!!)
        control.emit(Messages.parseServer("""{"t":"room.token","d":{"token":"jwt","relayUrl":"wss://r","roomId":"r1","role":"host","expiresIn":300,"peers":[]}}""")!!)
        assertEquals("jwt", relay.token)
        assertEquals("wss://r", relay.url)
    }

    @Test fun registrationStartsTheServiceAndCaptureAndCapturedAudioIsSent() = runTest {
        val m = manager()
        m.start()
        m.dial("peer", CallMedia.audioVideo)
        control.emit(tokenEvent())
        relay.emit(RelayEvent.Registered(slot = 0, peers = emptyList(), limits = Limits(16, 8)))
        assertEquals(1, service.starts)
        assertTrue(audio.capturing)

        val payload = ByteArray(640)
        audio.emitFrame(payload, 1_000)
        val sent = relay.sentBinary.single()
        val frame = FrameCodec.decodeClient(sent)!!
        assertEquals(FrameCodec.TYPE_PCM16, frame.type)
        assertArrayEquals(payload, frame.payload)
    }

    @Test fun remoteAudioIsWrittenToTheRightSlotAndPeersAreRemoved() = runTest {
        val m = manager()
        m.start()
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        relay.emit(RelayEvent.Media(FrameCodec.decodeRelay(FrameCodec.encodeRelay(FrameCodec.TYPE_PCM16, 0, 3, 20, ByteArray(640)))!!))
        assertEquals(listOf(3), audio.written.keys.toList())
        relay.emit(RelayEvent.PeerLeft(3))
        assertEquals(listOf(3), audio.removed)
    }

    @Test fun endingTheCallStopsEverythingInOrder() = runTest {
        val m = manager()
        m.start()
        m.dial("peer", CallMedia.audioVideo)
        control.emit(tokenEvent())
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        m.endAll()
        assertEquals(1, service.stops)
        assertFalse(audio.capturing)
        assertTrue(relay.closed)
    }

    private fun tokenEvent() = Messages.parseServer("""{"t":"room.token","d":{"token":"jwt","relayUrl":"wss://r","roomId":"r1","role":"host","expiresIn":300,"peers":[]}}""")!!
}
```

`ErrorMapperTest`:

```kotlin
package io.streamrtc.session

import io.streamrtc.api.EndReason
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMapperTest {
    @Test fun cameraAndMicFailuresBecomePermissionDenied() {
        assertEquals(EndReason.PermissionDenied, ErrorMapper.toEndReason(SecurityException("mic")))
        assertEquals(EndReason.PermissionDenied, ErrorMapper.toEndReason(IllegalStateException("camera in use")))
    }

    @Test fun networkAndUnknownFailuresKeepTheirIdentity() {
        assertEquals(EndReason.NetworkFailed, ErrorMapper.toEndReason(IOException("reset")))
        assertEquals(EndReason.Error("IllegalArgumentException"), ErrorMapper.toEndReason(IllegalArgumentException("x")))
    }
}
```

Provide the fakes in the same test file: `FakeControlLink` (records `sent`, `MutableSharedFlow<ServerEvent>`), `FakeRelayLink` (records `token`, `url`, `sentBinary`, `closed`, plus a mutable `queued` returned by `queuedBytes()`), `FakeAudioDevice` (`capturing`, `frameSink`, `written`, `removed`), `FakeServiceControl` (`starts`, `stops`).

- [ ] **Step 3: Run it to verify it fails**

Expected: FAIL — unresolved `SessionManager`.

- [ ] **Step 4: Implement the public API and `SessionManager`**

`api/Call.kt`:

```kotlin
interface Calls {
    val incoming: SharedFlow<IncomingCall>
    val active: StateFlow<Call?>
    suspend fun dial(userId: String, media: CallMedia = CallMedia.audioVideo): Call
}

class IncomingCall internal constructor(
    val callId: String, val fromUserId: String, val displayName: String?,
    val roomId: String, val media: CallMedia, private val accept: suspend () -> Call, private val rejectFn: suspend (String?) -> Unit,
) {
    suspend fun accept(): Call = accept()
    suspend fun reject(reason: String? = null) = rejectFn(reason)
}

interface Call {
    val id: String
    val peerUserId: String
    val state: StateFlow<CallState>
    val audio: SessionAudio
    suspend fun end(reason: String? = null)
}

interface SessionAudio {
    var muted: Boolean
    var speakerphone: Boolean
    val route: StateFlow<AudioRoute>
}

enum class AudioRoute { Earpiece, Speaker, Headset, Bluetooth }
```

`api/Room.kt` (types only in this task; implementation in Task 17):

```kotlin
interface Rooms { suspend fun join(roomId: String, options: JoinOptions = JoinOptions()): Room }

data class JoinOptions(val video: Boolean = true, val displayName: String? = null)

interface Room {
    val roomId: String
    val self: Participant
    val state: StateFlow<RoomState>
    val participants: StateFlow<List<Participant>>
    val audio: SessionAudio
    fun remoteVideo(slot: Int): RemoteVideo?
    suspend fun leave()
}

sealed interface RoomState {
    data object Connecting : RoomState
    data object Active : RoomState
    data class Reconnecting(val attempt: Int) : RoomState
    data class Closed(val reason: EndReason) : RoomState
}
```

`api/VideoTypes.kt`:

```kotlin
data class VideoStats(val framesSent: Long = 0, val framesReceived: Long = 0, val dropped: Long = 0, val bitrate: Int = 0)
data class Limits(val maxParticipants: Int, val maxVideo: Int)
enum class RemoteVideoState { Idle, WaitingForKeyframe, Playing, Stalled }
interface RemoteVideo { val slot: Int; val userId: String?; val state: StateFlow<RemoteVideoState>; val stats: StateFlow<VideoStats> }
interface LocalVideo { fun setEnabled(on: Boolean); fun switchCamera(); val stats: StateFlow<VideoStats> }
```

`SessionManager` wires: control events → `CallSession`; `call.token` responses; `RelayLink` connect on token; `Registered` → `service.start()` + `audio.startCapture { bytes, ts -> relay.send(FrameCodec.encodeClient(TYPE_PCM16, 0, ts, bytes)) }`; `Media` with `TYPE_PCM16` → `audio.write(slot, payload)`; `PeerLeft` → `audio.removeSlot(slot)`; `stop()` in the fixed order from the spec §7.8.

`StreamRtc` builds the real `OkHttpClient` (`pingInterval(20 s)`, `readTimeout(0)`), a `ControlClient`, `AndroidLogger`, `DefaultAudioDevice`, `CallServiceControl`, and exposes `calls`.

`CallService` + manifest entry:

```xml
<service android:name="io.streamrtc.service.CallService" android:exported="false"
         android:foregroundServiceType="camera|microphone" />
```

- [ ] **Step 5: Run the JVM tests**

Run: `./gradlew :rtc:testDebugUnitTest --tests 'io.streamrtc.session.SessionManagerTest' --tests 'io.streamrtc.session.ErrorMapperTest'`
Expected: PASS, 6 tests.

- [ ] **Step 6: Verify the service starts on a device**

Install the sample harness from Task 19, start a 1:1 call, then run:

```bash
adb shell dumpsys activity services io.streamrtc | grep -i foreground
```

Expected: the `CallService` record shows `isForeground=true` while the call is active and disappears after `end()`.

- [ ] **Step 7: Commit**

```bash
git add sdk-android/rtc/src/main sdk-android/rtc/src/test/kotlin/io/streamrtc/session
git commit -m "feat(sdk): public facade, session manager and call foreground service"
```

---

### Task 13: H.264 Annex-B parsing

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/protocol/H264AnnexB.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/protocol/H264AnnexBTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `H264AnnexB.nalUnits(data): List<NalUnit>` (`NalUnit(type, offset, length)`), `H264AnnexB.csd(data): H264Csd?` (`H264Csd(sps, pps)`, each with its 4-byte start code), `H264AnnexB.isKeyframe(data): Boolean`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.streamrtc.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AnnexBTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun splitsThreeAndFourByteStartCodes() {
        val data = bytes(0,0,0,1, 0x67,1,2, 0,0,1, 0x68,3, 0,0,0,1, 0x65,4,5,6)
        assertEquals(listOf(7, 8, 5), H264AnnexB.nalUnits(data).map { it.type })
    }

    @Test fun doesNotSplitOnEmulationPreventionBytes() {
        val data = bytes(0,0,0,1, 0x65, 0,0,3,1, 0,0,3,2)
        assertEquals(listOf(5), H264AnnexB.nalUnits(data).map { it.type })
    }

    @Test fun extractsSpsAndPpsWithStartCodes() {
        val data = bytes(0,0,0,1, 0x67, 0x42, 0, 0x1e, 0,0,0,1, 0x68, 0xce, 0,0,0,1, 0x65, 9)
        val csd = H264AnnexB.csd(data)!!
        assertEquals(listOf(0,0,0,1,0x67,0x42,0,0x1e), csd.sps.map { it.toInt() and 0xff })
        assertEquals(listOf(0,0,0,1,0x68,0xce), csd.pps.map { it.toInt() and 0xff })
    }

    @Test fun detectsKeyframesAndMissingCsd() {
        assertTrue(H264AnnexB.isKeyframe(bytes(0,0,0,1, 0x65, 1)))
        assertEquals(false, H264AnnexB.isKeyframe(bytes(0,0,0,1, 0x41, 1)))
        assertNull(H264AnnexB.csd(bytes(0,0,0,1, 0x41, 1)))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Expected: FAIL — unresolved `H264AnnexB`.

- [ ] **Step 3: Implement**

Scan for `00 00 01` and `00 00 00 01`; a NAL unit ends at the next start code. `type = first byte and 0x1f`; include the start code in the byte range only when building `csd` (slice from the start-code offset, not the NAL byte). Keyframe when any NAL type is 5 (IDR). Types are enumerated in the test.

- [ ] **Step 4: Run the tests**

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/protocol/H264AnnexB.kt sdk-android/rtc/src/test/kotlin/io/streamrtc/protocol/H264AnnexBTest.kt
git commit -m "feat(sdk): annex-b parsing and sps/pps extraction"
```

---

### Task 14: Quality ladder and camera/encoder pipeline

**Files:**
- Modify: `sdk-android/gradle/libs.versions.toml` (add `androidx-activity`)
- Modify: `sdk-android/rtc/build.gradle.kts` (`androidTestImplementation(libs.androidx.activity)`)
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/video/QualityLadder.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/video/VideoEncoder.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/video/CameraController.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/view/RtcPreviewView.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/media/video/QualityLadderTest.kt`
- Test: `sdk-android/rtc/src/androidTest/kotlin/io/streamrtc/media/video/EncoderCameraTest.kt`
- Test: `sdk-android/rtc/src/androidTest/kotlin/io/streamrtc/media/video/CameraErrorTest.kt`

**Interfaces:**
- Consumes: `VideoConfig`, `H264AnnexB`, `FrameCodec`, `RtcLogger`, CameraX `Preview`.
- Produces: `QualityLadder(start, steps)` with `onQueueSample(queuedBytes, nowMs): VideoConfig?`; `VideoEncoder(config, onFrame: (bytes: ByteArray, keyframe: Boolean, mediaTs: Long) -> Unit, logger)` with `surface: Surface`, `start()`, `requestKeyframe()`, `reconfigure(VideoConfig)`, `stop()`; `CameraController(context, lifecycleOwner, onError: (Throwable) -> Unit = {})` with `bindTo(encoderSurface: Surface)`, `unbind()`, `switchCamera()`; `RtcPreviewView` with `setSource(local: LocalVideo?)`.

- [ ] **Step 1: Write the failing quality ladder test**

```kotlin
package io.streamrtc.media.video

import io.streamrtc.api.VideoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityLadderTest {
    @Test fun stepsDownAfterTwoSecondsOfBacklog() {
        val q = QualityLadder(VideoConfig.Standard)
        assertNull(q.onQueueSample(80_000, 0))
        assertNull(q.onQueueSample(80_000, 1_000))
        assertEquals(VideoConfig.Low, q.onQueueSample(80_000, 2_000))
    }

    @Test fun stepsUpAfterFifteenSecondsOfHeadroom() {
        val q = QualityLadder(VideoConfig.High)
        assertEquals(VideoConfig.Standard, q.onQueueSample(1_000, 15_000))
    }

    @Test fun neverGoesBelowLowOrAboveTheCeiling() {
        val q = QualityLadder(VideoConfig.Low)
        q.onQueueSample(80_000, 1_000); q.onQueueSample(80_000, 2_000)
        assertNull(q.onQueueSample(80_000, 3_000))
        assertEquals(VideoConfig.Low, q.current)
    }
}
```

- [ ] **Step 2: Run it to verify it fails, then implement the ladder**

Rules: keep `backlogSinceMs`/`headroomSinceMs`. Above 64 KiB continuously for 2 s → next lower step, reset both timers. Below 8 KiB continuously for 15 s → next higher step, never above `start`. A sample that breaks a window resets its timer. `current` exposes the active config.

Add `androidx-activity = { group = "androidx.activity", name = "activity", version = "1.13.0" }` to the version catalog and wire it as `androidTestImplementation` so `ComponentActivity` and `ActivityScenarioRule` resolve in the tests.

Run: `./gradlew :rtc:testDebugUnitTest --tests 'io.streamrtc.media.video.QualityLadderTest'`
Expected: PASS, 3 tests.

- [ ] **Step 3: Write the failing camera/encoder instrumented test**

```kotlin
@RunWith(AndroidJUnit4::class)
class EncoderCameraTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val scenario = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun emitsAnAnnexBKeyframeWithinTenSeconds() {
        val frames = LinkedBlockingQueue<Pair<ByteArray, Boolean>>()
        val encoder = VideoEncoder(VideoConfig.Low, { bytes, key, _ -> frames.put(bytes to key) }, RtcLogger.None)
        encoder.start()
        val controller = CameraController(
            ApplicationProvider.getApplicationContext(),
            scenario.scenario as androidx.lifecycle.LifecycleOwner,
        )
        controller.bindTo(encoder.surface)
        try {
            val (bytes, key) = frames.poll(10, TimeUnit.SECONDS)!!
            assertTrue(key)
            assertEquals(true, H264AnnexB.csd(bytes) != null)
        } finally {
            controller.unbind(); encoder.stop()
        }
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `./gradlew :rtc:connectedDebugAndroidTest --tests 'io.streamrtc.media.video.EncoderCameraTest'`
Expected: FAIL — unresolved `VideoEncoder`.

`CameraErrorTest` (same files, deterministic): build a `LifecycleRegistry` in `DESTROYED` state, call `CameraController(context, registry) { errors.add(it) }.bindTo(surface)`, and assert `errors.single()` is delivered within 2 s instead of a crash. This is the Review Focus #3 path: camera unavailable → typed error, no ANR.

- [ ] **Step 5: Implement encoder, camera and preview view**

`VideoEncoder`: `MediaFormat.createVideoFormat("video/avc", w, h)` with `KEY_COLOR_FORMAT = COLOR_FormatSurface`, `KEY_BIT_RATE`, `KEY_FRAME_RATE`, `KEY_I_FRAME_INTERVAL = 2`, `KEY_BITRATE_MODE = CBR`, profile `AVCProfileBaseline`. Create input surface, start, then loop `dequeueOutputBuffer(10_000)` on a `HandlerThread("streamrtc-encoder")`, copying payloads with `ByteBuffer.get(ByteArray(size))` and stamping `SystemClock.elapsedRealtime()`. `requestKeyframe()` executes `setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })` on the same thread. `reconfigure` stops, rebuilds with the new config and forces a keyframe.

`CameraController`: `ProcessCameraProvider.getInstance(context)` → `Preview.Builder().build()` with `RtcPreviewView` as `SurfaceProvider`; `bindTo` stores the encoder surface and provides it through the `SurfaceRequest`; `switchCamera` flips `CameraSelector.DEFAULT_FRONT_CAMERA`/`BACK_CAMERA` and rebinds; `unbind` unbinds all use cases on the main thread.

`RtcPreviewView`: `SurfaceView` implementing `Preview.SurfaceProvider`; `setSource(local)` registers itself; `provideSurface` passes a `Surface` to whichever `LocalVideo` is attached and calls `request.provideSurface(...)` with `executor = mainExecutor`; on surface destruction it calls `local.onPreviewSurfaceLost()`.

- [ ] **Step 6: Run the instrumented tests**

Run: `./gradlew :rtc:connectedDebugAndroidTest --tests 'io.streamrtc.media.video.*'`
Expected: PASS, 2 tests on a device/emulator with a camera.

- [ ] **Step 7: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/media/video sdk-android/rtc/src/main/kotlin/io/streamrtc/view sdk-android/rtc/src/test/kotlin/io/streamrtc/media/video sdk-android/rtc/src/androidTest
git commit -m "feat(sdk): quality ladder, camera and h264 encoder"
```

---

### Task 15: Video decoder and remote views

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/video/VideoDecoder.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/RemoteVideoImpl.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/view/RtcRemoteVideoView.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/view/SurfaceSink.kt`
- Test: `sdk-android/rtc/src/androidTest/kotlin/io/streamrtc/media/video/DecoderLoopTest.kt`

**Interfaces:**
- Consumes: `H264AnnexB`, `FrameCodec`, `SyncEstimator`, `VideoScheduler`, `RemoteVideo`, `RemoteVideoState`.
- Produces: `VideoDecoder(slot, surface, onState, onNeedKeyframe, logger)` with `push(frame: RelayFrame)`, `val state: RemoteVideoState`, `stop()`; `SurfaceSink { fun attach(surface: Surface); fun detach() }`; `RemoteVideoImpl(slot, userId, sinkFactory)` implementing `RemoteVideo` and `SurfaceSink`; `RtcRemoteVideoView.setSource(remote: RemoteVideo?)`.

- [ ] **Step 1: Write the failing decoder loop test**

```kotlin
@RunWith(AndroidJUnit4::class)
class DecoderLoopTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.CAMERA)
    @get:Rule val scenario = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun decodesAnEncoderKeyframeAndReportsPlaying() {
        val encoded = LinkedBlockingQueue<Pair<ByteArray, Boolean>>()
        val encoder = VideoEncoder(VideoConfig.Low, { b, k, _ -> encoded.put(b to k) }, RtcLogger.None)
        encoder.start()
        val controller = CameraController(
            ApplicationProvider.getApplicationContext(),
            scenario.scenario as androidx.lifecycle.LifecycleOwner,
        )
        controller.bindTo(encoder.surface)

        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val states = LinkedBlockingQueue<RemoteVideoState>()
        val decoder = VideoDecoder(0, surface, { states.put(it) }, {}, RtcLogger.None)
        try {
            val first = encoded.poll(10, TimeUnit.SECONDS)!!
            decoder.push(FrameCodec.decodeRelay(FrameCodec.encodeRelay(FrameCodec.TYPE_H264, if (first.second) 1 else 0, 0, 100, first.first))!!)
            assertEquals(RemoteVideoState.Playing, states.poll(10, TimeUnit.SECONDS))
        } finally {
            decoder.stop(); texture.release(); surface.release(); controller.unbind(); encoder.stop()
        }
    }

    @Test fun ignoresDeltaFramesUntilAKeyframeArrives() {
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val states = LinkedBlockingQueue<RemoteVideoState>()
        val decoder = VideoDecoder(0, surface, { states.put(it) }, {}, RtcLogger.None)
        try {
            val delta = FrameCodec.decodeRelay(
                FrameCodec.encodeRelay(FrameCodec.TYPE_H264, 0, 0, 100, byteArrayOf(0, 0, 0, 1, 0x41, 1, 2)),
            )!!
            decoder.push(delta)
            assertNull(states.poll(1, TimeUnit.SECONDS))
            assertEquals(RemoteVideoState.WaitingForKeyframe, decoder.state)
        } finally {
            decoder.stop(); texture.release(); surface.release()
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Expected: FAIL — unresolved `VideoDecoder`.

- [ ] **Step 3: Implement the decoder**

On construction: async mode with `setCallback` on a `HandlerThread("streamrtc-decode-$slot")`. On the first frame, `H264AnnexB.csd(payload)` → `KEY_CSD_0`/`KEY_CSD_1`, then `configure(format, surface, null, 0)` and `start()`. Queue each payload as an input buffer with `mediaTs * 1_000` as presentation time. `INFO_OUTPUT_FORMAT_CHANGED` → `RemoteVideoState.Playing` once, then release output buffers. `ERROR_*` or 1 s with no output while inputs flow → reset, emit `WaitingForKeyframe`, call `onNeedKeyframe` once per second. `stop()` joins the thread and releases.

- [ ] **Step 4: Implement `RemoteVideoImpl`, `SurfaceSink` and the view**

`RemoteVideoImpl` lazily creates a `VideoDecoder` on `attach(surface)` and destroys it on `detach()`; `state`/`stats` are `MutableStateFlow`s. `RtcRemoteVideoView` is a `SurfaceView`; `holder.addCallback` → `source.attach(holder.surface)` on create, `source.detach()` on destroy; `setSource(null)` detaches the previous source.

- [ ] **Step 5: Run the instrumented tests**

Expected: PASS, 2 tests — `Playing` within 10 s for the encoder loop, and `WaitingForKeyframe` with no output for the delta-only case.

- [ ] **Step 6: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/media/video/VideoDecoder.kt sdk-android/rtc/src/main/kotlin/io/streamrtc/session/RemoteVideoImpl.kt sdk-android/rtc/src/main/kotlin/io/streamrtc/view sdk-android/rtc/src/androidTest
git commit -m "feat(sdk): per-slot h264 decoder and remote video views"
```

### Task 16: A/V sync and keyframe recovery wiring

**Files:**
- Modify: `sdk-android/rtc/src/main/kotlin/io/streamrtc/media/Sync.kt` (add `FrameGate`)
- Modify: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/RemoteVideoImpl.kt`
- Modify: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/SessionManager.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/media/FrameGateTest.kt`

**Interfaces:**
- Consumes: `SyncEstimator`, `VideoScheduler`, `VideoAction`, `RelayLink.requestKeyframe`.
- Produces: `FrameGate(sync, scheduler)` with `onAudio(mediaTs, arrivalMs)` and `videoAction(mediaTs, arrivalMs, nowMs): VideoAction`; `RemoteVideoImpl.onAudioFrame(...)`; `SessionManager` routes `TYPE_PCM16` frames to both playback and the slot's `FrameGate`, and stale decoders request a keyframe at most once per second.

- [ ] **Step 1: Write the failing gate test**

```kotlin
package io.streamrtc.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGateTest {
    @Test fun rendersVideoBeforeAnyAudioHasArrived() {
        val gate = FrameGate()
        assertEquals(VideoAction.Render, gate.videoAction(mediaTs = 5_000, arrivalMs = 5_000, nowMs = 5_000))
    }

    @Test fun holdsEarlyAndDropsLateOnceTheOffsetIsKnown() {
        val gate = FrameGate()
        repeat(20) { i -> gate.onAudio(i * 20L, 1_000 + i * 20 + 40) }
        assertEquals(VideoAction.Hold, gate.videoAction(mediaTs = 1_200, arrivalMs = 1_240, nowMs = 1_200))
        assertEquals(VideoAction.Drop, gate.videoAction(mediaTs = 500, arrivalMs = 540, nowMs = 1_200))
        assertEquals(VideoAction.Render, gate.videoAction(mediaTs = 1_000, arrivalMs = 1_040, nowMs = 1_040))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Expected: FAIL — unresolved `FrameGate`.

- [ ] **Step 3: Implement and wire**

`FrameGate` delegates: `videoAction` returns `Render` while `sync.offsetMs()` is null, otherwise `scheduler.action(mediaTs + offset, nowMs)`.

`RemoteVideoImpl` feeds every `TYPE_PCM16` frame it sees for its slot into its gate, and in `onVideoFrame` calls `scheduler` before queueing to the decoder; `Drop` increments `stats.dropped`, `Hold` queues with a 20 ms retry, `Render` pushes immediately.

Keyframe recovery: when `VideoDecoder` reports a stall, `RemoteVideoImpl` calls the session's `onNeedKeyframe(slot)`, which forwards to `RelayLink.requestKeyframe(slot)` with a 1 s debounce per slot.

- [ ] **Step 4: Run the JVM tests**

Expected: PASS, 2 tests. Then run `DecoderLoopTest` again to confirm the decoder still reaches `Playing` through the gate.

- [ ] **Step 5: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/media/Sync.kt sdk-android/rtc/src/main/kotlin/io/streamrtc/session sdk-android/rtc/src/test
git commit -m "feat(sdk): a/v sync gate and keyframe recovery"
```

---

### Task 17: Rooms and the 4-video grid

**Files:**
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/RoomSession.kt`
- Modify: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/SessionManager.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/session/RoomSessionTest.kt`

**Interfaces:**
- Consumes: `Rooms`, `Room`, `JoinOptions`, `ParticipantRegistry`, `RelayLink`, `RemoteVideoImpl`, `roomTokenProvider`.
- Produces: `RoomSession.join(...)` and `RemoteVideo` exposure per slot; `SessionManager.rooms: Rooms`; decoder cap of 4 enforced in `RemoteVideoImpl.attach`.

- [ ] **Step 1: Write the failing room tests**

```kotlin
package io.streamrtc.session

import io.streamrtc.api.*
import io.streamrtc.protocol.Messages
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RoomSessionTest {
    private val relay = FakeRelayLink()
    private val control = FakeControlLink()
    private val config = StreamRtcConfig("https://c", { "session" }, "me", roomTokenProvider = { roomId -> "room-$roomId" })

    private fun manager() = SessionManager(
        config, control, { relay }, FakeAudioDevice(), FakeServiceControl(), backgroundScope, clock = { 0 },
    )

    @Test fun joinUsesTheHostMintedTokenAndPublishesParticipants() = runTest {
        val m = manager()
        val room = m.joinRoom("r1", JoinOptions(video = true))
        assertEquals("room-r1", relay.token)
        relay.emit(RelayEvent.Registered(slot = 0, peers = listOf(1), limits = Limits(16, 8)))
        assertEquals(RoomState.Active, room.state.value)
        assertEquals(listOf(0, 1), room.participants.value.map { it.slot })
    }

    @Test fun joinsAndLeavesUpdateTheParticipantList() = runTest {
        val m = manager()
        val room = m.joinRoom("r1", JoinOptions(video = true))
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        relay.emit(RelayEvent.PeerJoined(2, "u2"))
        assertEquals(listOf(0, 2), room.participants.value.map { it.slot })
        relay.emit(RelayEvent.PeerLeft(2))
        assertEquals(listOf(0), room.participants.value.map { it.slot })
    }

    @Test fun leavingClosesTheRelayAndTheService() = runTest {
        val m = manager()
        val room = m.joinRoom("r1", JoinOptions(video = true))
        relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
        room.leave()
        assertEquals(RoomState.Closed(EndReason.LocalEnded), room.state.value)
        assertTrue(relay.closed)
    }

    @Test fun videoIsExposedOnlyForKnownSlots() = runTest {
        val m = manager()
        val room = m.joinRoom("r1", JoinOptions(video = true))
        relay.emit(RelayEvent.Registered(0, listOf(1, 2), Limits(16, 8)))
        assertNull(room.remoteVideo(9))
        assertEquals(1, room.remoteVideo(1)?.slot)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Expected: FAIL — unresolved `joinRoom` / `RoomSession`.

- [ ] **Step 3: Implement `RoomSession`**

`SessionManager.joinRoom(roomId, options)` is a suspend function: calls `config.roomTokenProvider(roomId)` (throwing `StreamRtcException.NotConfigured` when null), creates a `RoomSession`, connects the relay, and returns the `Room`. `RoomSession` maps relay events to `ParticipantRegistry` and `RoomState`, starts capture on `registered`, and stops everything on `leave()`. `remoteVideo(slot)` returns the existing `RemoteVideoImpl` or null.

`RemoteVideoImpl.attach` asks a shared `DecoderBudget(max = 4)`; when exhausted it logs and leaves the state `Idle` instead of throwing (a fifth tile shows a placeholder).

- [ ] **Step 4: Run the room tests**

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/session sdk-android/rtc/src/test/kotlin/io/streamrtc/session
git commit -m "feat(sdk): room sessions and participant grid"
```

---

### Task 18: Reconnect, token refresh and bitrate adaptation

**Files:**
- Modify: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/SessionManager.kt`
- Modify: `sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/RelayClient.kt`
- Create: `sdk-android/rtc/src/main/kotlin/io/streamrtc/session/ReconnectPolicy.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/session/ReconnectPolicyTest.kt`
- Test: `sdk-android/rtc/src/test/kotlin/io/streamrtc/session/ReconnectTest.kt`

**Interfaces:**
- Consumes: `CloseAction`, `Backoff`, `QualityLadder`, `CallSession.refreshToken()`, `roomTokenProvider`.
- Produces: `ReconnectPolicy.next(closeCode, attempt): ReconnectDecision` (`Retry(delayMs, refreshToken, reduceQuality)`, `GiveUp(reason)`); `SessionManager` supplies fresh tokens per attempt and samples `queuedBytes` every 500 ms for the ladder.

- [ ] **Step 1: Write the failing policy test**

```kotlin
package io.streamrtc.session

import io.streamrtc.transport.Backoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {
    private val policy = ReconnectPolicy(Backoff(250, 8000, 0.0, Random(1)))

    @Test fun tokenErrorsRetryImmediatelyWithAFreshToken() {
        val d = policy.next(4401, attempt = 1) as ReconnectDecision.Retry
        assertEquals(0L, d.delayMs)
        assertTrue(d.refreshToken)
        assertEquals(false, d.reduceQuality)
    }

    @Test fun slowConsumerLowersQuality() {
        val d = policy.next(1013, attempt = 0) as ReconnectDecision.Retry
        assertTrue(d.reduceQuality)
    }

    @Test fun terminalCodesGiveUp() {
        assertTrue(policy.next(4409, 0) is ReconnectDecision.GiveUp)
        assertTrue(policy.next(1008, 0) is ReconnectDecision.GiveUp)
    }

    @Test fun giveUpCarriesAStableCodeForTheHost() {
        assertEquals("room_unavailable", (policy.next(4409, 0) as ReconnectDecision.GiveUp).code)
        assertEquals("duplicate_session", (policy.next(1008, 0) as ReconnectDecision.GiveUp).code)
    }

    @Test fun transportErrorsBackOff() {
        assertEquals(250L, (policy.next(1011, 0) as ReconnectDecision.Retry).delayMs)
        assertEquals(500L, (policy.next(1011, 1) as ReconnectDecision.Retry).delayMs)
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

`ReconnectDecision` sealed interface; `4401` → `Retry(0, refreshToken = true, reduceQuality = false)`, `1013` → `Retry(backoff.nextDelayMs(), false, true)`, terminal codes → `GiveUp`, everything else → `Retry(backoff.nextDelayMs(), false, false)`.

Run: `./gradlew :rtc:testDebugUnitTest --tests 'io.streamrtc.session.ReconnectPolicyTest'`
Expected: PASS, 5 tests.

- [ ] **Step 3: Write the failing integration test**

```kotlin
@Test fun a4401FetchesANewTokenThroughCallTokenAndReconnects() = runTest {
    val control = FakeControlLink()
    val relays = mutableListOf<FakeRelayLink>()
    val m = SessionManager(
        StreamRtcConfig("https://c", { "tok" }, "me"), control,
        { FakeRelayLink().also(relays::add) }, FakeAudioDevice(), FakeServiceControl(),
        backgroundScope, clock = { 0 },
    )
    m.start()
    m.dial("peer", CallMedia.audioVideo)
    control.emit(Messages.parseServer("""{"t":"call.accepted","d":{"callId":"c1","byUserId":"peer","roomId":"r1"}}""")!!)
    control.emit(tokenEvent("t1"))
    assertEquals("t1", relays[0].token)

    relays[0].emitClosed(4401, "replayed_token")
    advanceUntilIdle()
    assertTrue(control.sent.any { it.contains("\"t\":\"call.token\"") })

    control.emit(tokenEvent("t2"))
    advanceUntilIdle()
    assertEquals("t2", relays.last().token)
    assertEquals(2, relays.size)
}

@Test fun aSlowConsumerStepsTheEncoderDown() = runTest {
    val relay = FakeRelayLink()
    val encoderSteps = mutableListOf<io.streamrtc.api.VideoConfig>()
    val m = SessionManager(
        StreamRtcConfig("https://c", { "tok" }, "me", video = io.streamrtc.api.VideoConfig.Standard),
        FakeControlLink(), { relay }, FakeAudioDevice(), FakeServiceControl(), backgroundScope,
        clock = { testScheduler.currentTime }, onQualityChange = { encoderSteps.add(it) },
    )
    m.start(); m.dial("peer", CallMedia.audioVideo)
    relay.emit(RelayEvent.Registered(0, emptyList(), Limits(16, 8)))
    relay.queued = 80_000
    advanceTimeBy(2_100)
    assertEquals(io.streamrtc.api.VideoConfig.Low, encoderSteps.last())
}
```

- [ ] **Step 4: Wire it in `SessionManager`**

On a relay close, call `policy.next(code, attempt)`; `GiveUp` → typed `EndReason`/`RoomState.Closed`; `Retry` → optionally `CallSession.refreshToken()` (1:1) or `config.roomTokenProvider(roomId)` (rooms), wait `delayMs`, then `relay.connect(newToken, url)` and reset decoders. On `Active`, reset the attempt counter and the backoff.

Start a 500 ms ticker while `Active` that reads `relay.queuedBytes()` into `QualityLadder`; on a change, call `onQualityChange(config)` (default: `VideoEncoder.reconfigure`), which forces a keyframe.

- [ ] **Step 5: Run the tests and the full Android unit suite**

Run: `./gradlew :rtc:testDebugUnitTest`
Expected: all green, including the two new integration tests and the five policy cases.

- [ ] **Step 6: Commit**

```bash
git add sdk-android/rtc/src/main/kotlin/io/streamrtc/session sdk-android/rtc/src/main/kotlin/io/streamrtc/transport/RelayClient.kt sdk-android/rtc/src/test
git commit -m "feat(sdk): reconnect policy, token refresh and bitrate adaptation"
```

---

### Task 19: Dev token tool, sample app, live verification

**Files:**
- Create: `tools/mint-dev-token.ts`
- Create: `tools/mint-dev-token.test.ts`
- Modify: `package.json` (add `"mint": "esbuild tools/mint-dev-token.ts --bundle --platform=node --format=esm --target=node22 --external:jsonwebtoken --outfile=dist/mint.js && node dist/mint.js"`)
- Modify: `sdk-android/gradle/libs.versions.toml` (`android-application` plugin alias)
- Modify: `sdk-android/build.gradle.kts` (declare the alias with `apply false`)
- Create: `sdk-android/sample/build.gradle.kts`
- Create: `sdk-android/sample/src/main/AndroidManifest.xml`
- Create: `sdk-android/sample/src/main/kotlin/io/streamrtc/sample/MainActivity.kt`
- Create: `sdk-android/sample/src/main/res/layout/activity_main.xml`
- Modify: `sdk-android/settings.gradle.kts` (`include(":sample")`)

**Interfaces:**
- Consumes: the whole public API.
- Produces: `npx tsx`-free CLI `npm run mint -- --app app --user u1 --kind session --ttl 3600 --config ./streamrtc.config.json`; a six-widget sample app that dials a peer id and renders a 4-tile grid.

- [ ] **Step 1: Write the failing mint test**

```ts
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
```

- [ ] **Step 2: Run to verify it fails, then implement the tool**

Export pure functions (`mintSession`, `mintRoom`) plus a `main()` guarded by `process.argv[1]` that loads the config, parses flags, and prints only the token to stdout. Use `jwt.sign(..., { algorithm: 'HS256' })`.

Run: `npx vitest run tools/mint-dev-token.test.ts`
Expected: PASS, 2 tests.

- [ ] **Step 3: Build the sample app**

`sample/build.gradle.kts`: application plugin, `namespace = "io.streamrtc.sample"`, `minSdk 24`, `compileSdk` as in `:rtc`, dependency `implementation(project(":rtc"))`, `buildConfigField("String", "CONTROL_URL", "\"${localProp("rtcControlUrl", "http://10.0.2.2:8080")}\"")` and `rtcSessionToken`/`rtcRoomToken` from `local.properties`.

`MainActivity` (classic Views, no Compose): an `EditText` for the peer id, a "Call" button, a "Join room" button, a "Hang up" button, an `RtcPreviewView`, and a `GridLayout` of four `RtcRemoteVideoView`s. Wire:

```kotlin
val rtc = StreamRtc.get(this, StreamRtcConfig(
    controlUrl = BuildConfig.CONTROL_URL,
    tokenProvider = { BuildConfig.RTC_SESSION_TOKEN },
    displayName = "sample",
    roomTokenProvider = { _ -> BuildConfig.RTC_ROOM_TOKEN },
))
rtc.start()
```

Verification checklist (two emulators, one backend on the host):

1. `npm run dev:control` and `npm run dev:relay` with a config whose `PUBLIC_RELAY_URL` is `ws://10.0.2.2:8081`.
2. `adb -s emulator-5554 reverse tcp:8080 tcp:8080` and `adb -s emulator-5556 reverse tcp:8080 tcp:8080` (same for 8081).
3. Install the sample on both, mint two session tokens, dial from A to B.
4. Expect `CallState.Active` on both, audio both ways within 2 s, and `dumpsys activity services` showing the FGS.
5. Rotate one device mid-call: audio must not glitch and the preview must reattach within 1 s.
6. Kill the relay process; expect `RoomState.Reconnecting` then `Active` again after the relay restarts, without re-dialling.

- [ ] **Step 4: Commit**

```bash
git add tools/mint-dev-token.ts tools/mint-dev-token.test.ts package.json sdk-android/sample sdk-android/settings.gradle.kts
git commit -m "feat: dev token minting and sample app"
```

---

### Task 20: Integration documentation

**Files:**
- Create: `sdk-android/README.md`
- Modify: `README.md` (repo root: add an Android SDK section)

**Interfaces:**
- Consumes: the public API from Task 12.
- Produces: documentation only.

- [ ] **Step 1: Write `sdk-android/README.md`**

Sections, in this order:

1. Requirements (`minSdk 24`, JDK 17 toolchain, `mavenLocal()`).
2. Install (`publishToMavenLocal`, the dependency line).
3. Tokens: the exact claims for session and room tokens, the fresh-nonce rule, and a 20-line Node example that mints both.
4. 1:1 quickstart (the snippet from the design doc §5.1–5.3, with real imports).
5. Rooms/grid quickstart, including the Compose `AndroidView` wrapper.
6. Permissions and the foreground service.
7. Lifecycle: `start()`/`stop()`, what happens on process death.
8. Troubleshooting: the close-code table from the design doc §7.1, `CalleeOffline`, `RoomFull`, decoder cap 4, PCM16 bandwidth.
9. Limits table from the design doc §9.

- [ ] **Step 2: Add the root README section**

`## Android SDK` with a two-line summary and a link to `sdk-android/README.md`, plus `./gradlew :rtc:publishToMavenLocal` in the "Development" section.

- [ ] **Step 3: Verify the docs compile**

Run:

```bash
cd sdk-android && ./gradlew :sample:assembleDebug
grep -q "publishToMavenLocal" README.md && grep -q "roomTokenProvider" README.md && grep -q "CalleeOffline" README.md && echo DOCS-OK
```

Expected: `BUILD SUCCESSFUL`, then `DOCS-OK`.

- [ ] **Step 4: Commit**

```bash
git add sdk-android/README.md README.md
git commit -m "docs: android sdk integration guide"
```

---

## Self-Review

**Spec coverage**

| Spec section | Task |
|---|---|
| §3.1 `call.token` | 1, 2, 18 |
| §3.2 relay keyframe | 1, 3, 16 |
| §3.3 `peerInfo` | 1, 3, 17 |
| §4 module layout and distribution | 4 |
| §5 public API | 8, 12, 17 |
| §5.4 surfaces and 4-decoder cap | 15, 17 |
| §6 wire protocol | 1, 5 |
| §7.1 transport and close codes | 6, 7, 18 |
| §7.2 session state machine | 8 |
| §7.3 video send | 14 |
| §7.4 video receive | 15, 16 |
| §7.5 audio | 9, 10 |
| §7.6 clock and sync | 9, 16 |
| §7.7 foreground service | 12 |
| §7.8 teardown order | 11, 12 |
| §8 error model | 8, 12, 18 |
| §9 limits | 5, 14, 17 |
| §10 testing | every task |
| §11 milestones | M0 = tasks 1–3, M1 = 4–12, M2 = 13–15, M3 = 16–18, M4 = 19–20 |
| §12 host prerequisites | 19, 20 |

**Type consistency check**

`MessageSink.send(String): Boolean` is shared by `ControlLink` and `CallSession`; `RelayLink` uses `send(ByteArray)` — different name spaces, no clash. `Limits(maxParticipants, maxVideo)` is used by `RelayEvent.Registered` and `VideoStats`; `RoomSessionTest` and `SessionManagerTest` both construct `Limits(16, 8)`. `Participant.slot/userId/hasVideo` matches `ParticipantRegistry` and `Room.participants`. `RemoteVideoImpl` implements both `RemoteVideo` and `SurfaceSink`. `QualityLadder.current` is the field the Task 18 test reads.

**Review Focus coverage**

1. Relay drop at minute 12 → Task 18 `a4401FetchesANewTokenThroughCallTokenAndReconnects`.
2. Room/video slot exhaustion → Task 18 `giveUpCarriesAStableCodeForTheHost` (4409 → `room_unavailable`) plus `ReconnectDecision.GiveUp` surfaced as `RoomState.Closed`.
3. Camera or mic taken → Task 12 `ErrorMapperTest` + Task 14 `CameraErrorTest` (deterministic destroyed-lifecycle failure).
4. Video before a keyframe → Task 15 `ignoresDeltaFramesUntilAKeyframeArrives`.
5. Background/rotation → Task 12 `dumpsys` check + Task 19 checklist item 5.

**Gaps found and fixed inline**

- Task 5 originally used `org.json`, which is a stub in JVM tests — replaced with `kotlinx-serialization`.
- `ControlLink`/`RelayLink`/`RelayState` moved from Task 12 to Task 11, before the first class that implements them; Task 12 now only modifies `RelayClient`.
- `ServiceControl` added explicitly so `SessionManager` has no `Context` dependency and stays JVM-testable.
- The `SessionManager` constructor order was inconsistent between Tasks 12, 17 and 18; it is now `(config, control, relayFactory, audio, service, scope, clock, onQualityChange)` everywhere.
- `EndReason` gained `LocalEnded` for `Call.end()`/`Room.leave()`, so a local hang-up is not reported as a remote one.
- Task 19 needed the `com.android.application` plugin alias and `androidx.activity` for `ActivityScenarioRule`; both are now explicit steps.
- The Task 12 fakes moved to a shared `Fakes.kt` because Tasks 17 and 18 reuse them.
