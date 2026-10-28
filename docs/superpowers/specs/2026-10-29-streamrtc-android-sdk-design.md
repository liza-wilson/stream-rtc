# StreamRTC Android SDK — Design

**Status:** Draft for approval
**Date:** 2026-10-29
**Scope:** Approach C — 1:1 video + up to 4-way video grid, PCM16 audio v1
**First consumer:** Research-Hub (`com.urbanappstudio.researchhub`)

---

## 1. Purpose

A single Gradle library that turns the StreamRTC backend into:

```kotlin
val call = rtc.calls.dial("user_42")
call.state.collect { /* Dialing → Ringing → Connecting → Active → Ended */ }
```

and

```kotlin
val room = rtc.rooms.join("r_9f3c")
room.participants.collect { tiles -> /* one RtcRemoteVideoView per slot */ }
```

The library owns capture, encode, transport, decode, render, echo cancellation,
reconnect and the foreground service. Integration code never touches a codec,
a socket, or a frame header.

Non-goals: it is not a WebRTC stack, not a recording system, and it stores no
users, meetings or history. The host app remains the system of record.

---

## 2. Consumer constraints (measured, not assumed)

From `Research-Hub-Vaptrol`:

| Item | Value | Consequence |
|---|---|---|
| `minSdk` | 24 | Library `minSdk 24`; PCM16 path only (no NDK present on this machine) |
| Kotlin | 2.1.0 | Library compiles with 2.1.0, JVM target 11 |
| AGP | 9.1.0 / Gradle 9.3.1 | Library build uses the same; `compileSdk` uses AGP 9 minor-API form (`release(36) { minorApiLevel = 1 }`) because only `android-36.1` is installed |
| Compose BOM | 2026.02.01 | **Library must not depend on Compose** — no version conflicts, no forced BOM |
| OkHttp | 4.12.0 | Library depends on `okhttp:4.12.0` (same major) |
| Existing call code | `network/call/StreamCallClient.kt` (911 lines, WebRTC mesh) | Deleted after migration; `MeetingRoomViewModel` (319 lines) rewired to the SDK |
| Host backend | **none** | Token minting is an integration prerequisite (§12) |

Camera, mic, camera-in-use and notification permissions are already requested by
the host app; the library declares them in its manifest so merging is automatic.

---

## 3. Backend deltas (blocking — do these first)

The current backend cannot support a production reconnect or a usable grid.
Three additive changes, all covered by new tests:

### 3.1 `call.token` on the control plane

Problem: room tokens are single-use (`nonce`) with a 300 s TTL, and
`call.accept` on an already-active call throws `invalid_transition`. A 1:1 call
that loses its relay socket after the first minute can never reconnect.

```
client → { "t": "call.token", "d": { "callId": "..." } }
server → { "t": "room.token", "d": { token, relayUrl, roomId, role, expiresIn, peers } }
```

Rules: caller or callee only; call must exist, belong to the caller's `appId`,
and be `active`; fresh `nonce`; `role` and `video` preserved from the original
call. Unknown call → `error { code: "not_found" }`. Rate: existing control budget.

### 3.2 Relay keyframe request

Problem: `need_keyframe` only fires on peer join. A decoder that binds later
(second tile appears, user scrolls, decode error) has no way to ask, so it waits
up to the 2 s GOP.

```
client → { "t": "keyframe", "d": { "slot": 2 } }      // JSON, registered sockets only
relay  → forwards { "t": "need_keyframe", "d": {} } to the owner of slot 2
```

Unknown/self slot is ignored. Bounded by the existing control budget (60/10 s);
the SDK sends at most one request per second per slot.

### 3.3 Peer identity in `registered`

Problem: `registered.peers` is `number[]`, so tiles for members who joined
before us have a slot but no `userId` — no name, no avatar.

```jsonc
{ "t": "registered", "slot": 0,
  "peers": [1, 2],                                  // unchanged, kept for compatibility
  "peerInfo": [ { "slot": 1, "userId": "u_7" }, { "slot": 2, "userId": "u_9" } ],
  "limits": { "maxParticipants": 16, "maxVideoParticipants": 8 } }
```

`peer_joined` already carries `userId`, so no change there.

All three are implemented in `packages/protocol` (types) plus the control and
relay handlers, with unit tests and a smoke check. No database, no migration.

---

## 4. Module layout, build and distribution

```
sdk-android/                      # standalone Gradle build (not part of the npm workspace)
├── settings.gradle.kts           # includes :rtc (and :sample later)
├── gradlew / gradle/wrapper      # Gradle 9.3.1
├── gradle/libs.versions.toml
└── rtc/
    ├── build.gradle.kts
    └── src/main/kotlin/io/streamrtc/
        ├── api/                  # public surface — the only package integrators import
        ├── protocol/             # frame + message codecs (mirror of packages/protocol)
        ├── transport/            # control socket, relay socket, backoff
        ├── media/video/          # camera, encoder, sender decoder, NAL parsing
        ├── media/audio/          # capture, playback, jitter, focus, routing
        ├── session/              # CallSession, RoomSession, SessionManager
        ├── service/              # foreground service
        ├── view/                 # RtcPreviewView, RtcRemoteVideoView
        └── util/
    └── src/test/                 # JVM tests (no device)
    └── src/androidTest/          # codec + live backend tests
```

**Distribution for v1:** `./gradlew :rtc:publishToMavenLocal` → host adds
`mavenLocal()` and `implementation("io.streamrtc:rtc:0.1.0")`. JitPack is
configured later when the repo gets a remote; the artifact coordinates do not
change.

**No Compose anywhere** in `:rtc`. Compose integration is an `AndroidView`
wrapper written in the host app (documented, 6 lines).

**Manifest merging:** the library declares `INTERNET`, `ACCESS_NETWORK_STATE`,
`CAMERA`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`,
`FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, and its own service.
Runtime permission requests stay the host's responsibility.

---

## 5. Public API

Everything below is in `io.streamrtc`. Concrete names may shift during
planning; the shape is the contract.

### 5.1 Initialisation

```kotlin
data class StreamRtcConfig(
    val controlUrl: String,                        // https://host → wss://host/ws
    val tokenProvider: suspend () -> String,       // host-signed session JWT (15 min)
    val displayName: String,
    val appVersion: String? = null,
    val roomTokenProvider: (suspend (roomId: String) -> String)? = null, // groups/meetings
    val video: VideoConfig = VideoConfig.Standard,
    val audio: AudioConfig = AudioConfig.Default,
    val foregroundService: Boolean = true,
)

class StreamRtc private constructor(...) {
    companion object { fun get(context: Context, config: StreamRtcConfig): StreamRtc }

    val connection: StateFlow<ConnectionState>   // Idle, Connecting, Online, Reconnecting, Failed
    val calls: Calls
    val rooms: Rooms

    fun start(scope: CoroutineScope? = null)     // opens the control socket, keeps it alive
    fun stop()
}
```

`tokenProvider` is called on every (re)connect, so expiry needs no extra API.
`roomTokenProvider` is called on every room join and every room reconnect — each
call must return a token with a **fresh nonce** (see §3.1 for why 1:1 does not
need it).

### 5.2 Calls

```kotlin
interface Calls {
    val incoming: SharedFlow<IncomingCall>
    val active: StateFlow<Call?>

    suspend fun dial(userId: String, media: CallMedia = CallMedia.video(audio = true)): Call
}

class IncomingCall(
    val callId: String, val fromUserId: String, val displayName: String?,
    val roomId: String, val media: CallMedia,
) {
    suspend fun accept(): Call
    suspend fun reject(reason: String? = null)
}

interface Call : MediaSession {
    val id: String
    val peerUserId: String
    val state: StateFlow<CallState>              // Dialing, Ringing, Connecting, Active, Ended(reason)
    suspend fun end(reason: String? = null)
}
```

`CallState.Ended` carries a typed reason: `RemoteEnded`, `Rejected`,
`CalleeOffline`, `Timeout`, `NetworkFailed`, `PermissionDenied`, `Error(code)`.
`CalleeOffline` arrives synchronously from `call.invite` — no ringing 45 s for
nothing.

### 5.3 Rooms

```kotlin
interface Rooms {
    suspend fun join(roomId: String, options: JoinOptions = JoinOptions()): Room
}

interface Room : MediaSession {
    val roomId: String
    val self: Participant
    val state: StateFlow<RoomState>              // Connecting, Active, Reconnecting, Closed(reason)
    val participants: StateFlow<List<Participant>>
    fun remoteVideo(slot: Int): RemoteVideo?     // null for unknown slots
    suspend fun leave()
}

data class Participant(val slot: Int, val userId: String?, val hasVideo: Boolean, val speaking: Boolean)
```

`Participant.userId` is null only for slots that appeared in `peers` before the
3.3 change is deployed, or for a `peer_joined` race; the SDK updates it in place.

### 5.4 Media surfaces

```kotlin
interface MediaSession {
    val localVideo: LocalVideo
    val audio: SessionAudio
}

interface LocalVideo {
    fun setEnabled(on: Boolean)
    fun switchCamera()
    val stats: StateFlow<VideoStats>
}

interface SessionAudio {
    var muted: Boolean
    var speakerphone: Boolean
    val route: StateFlow<AudioRoute>             // Earpiece, Speaker, Headset, Bluetooth
}

interface RemoteVideo {
    val slot: Int
    val userId: String?
    val state: StateFlow<RemoteVideoState>       // Idle, WaitingForKeyframe, Playing, Stalled
    val stats: StateFlow<VideoStats>
}
```

Views are plain `SurfaceView` subclasses, so both View and Compose hosts work:

```kotlin
previewView.setSource(session.localVideo)
remoteView.setSource(room.remoteVideo(slot))
```

`setSource(null)` (or detach) releases the decoder. **Decoding happens only for
slots with an attached view** — the 4-video limit is enforced by the UI binding
rather than by a separate subscription API, and power is spent on what is
visible.

Compose, host side:

```kotlin
AndroidView(factory = { RtcRemoteVideoView(it) },
            update = { it.setSource(room.remoteVideo(slot)) })
```

### 5.5 Threading contract

All public API is main-thread safe and non-blocking. `suspend` functions run on
`Dispatchers.Default` internally. State flows are conflated and never block.
Callbacks (`tokenProvider`) are invoked off the main thread.

---

## 6. Wire protocol (mirror of `packages/protocol`)

The Kotlin codec must be byte-identical to the TS one, verified by shared test
vectors (§10). Frame layouts, from `frame.ts`:

```
client frame (8 B header)          relay frame (9 B header)
0  magic 0x53                      0  magic 0x53
1  version 1                       1  version 1
2  type (0x10 opus, 0x11 pcm16,    2  type
        0x20 h264)                 3  flags
3  flags (1 keyframe, 2 eos,       4  senderSlot (0..15)
        4 screen)                  5..8 mediaTs BE u32
4..7 mediaTs BE u32                9..  payload
8..  payload
```

Control messages (JSON text): `hello`, `call.invite`, `call.accept`,
`call.reject`, `call.end`, `call.token`, `ping`; server `hello.ok`, `hello.err`,
`call.incoming`, `call.accepted`, `call.rejected`, `call.ended`,
`callee_offline`, `room.token`, `error`, `pong`.

Relay messages (JSON text): `register` → `registered` / `peer_joined` /
`peer_left` / `need_keyframe`; client `keyframe` (§3.2).

Caps enforced client-side too: control text ≤ 64 KiB, media ≤ 512 KiB; larger
payloads are dropped locally instead of closing the socket.

Codecs advertised in `hello`: `audio: ["pcm16"]`, `video: ["h264"]`.

---

## 7. Internal architecture

### 7.1 Transport

Two `OkHttpClient` instances per process (shared dispatcher and connection
pool), `pingInterval(20 s)`, `readTimeout(0)`.

- `ControlClient` — `wss://…/ws`; owns `hello`, call signalling, and the
  presence heartbeat; reconnects forever while `start()`ed.
- `RelayClient` — `wss://relay-host/`; `register` with a fresh room token; a
  pure binary pipe after `registered`. One instance per session.

Both listeners post to a single-threaded `SerialExecutor` per session so
`StateFlow` updates and media start/stop never interleave.

Backoff: `250 ms · 2^n` capped at 8 s, ±20 % jitter, reset on a successful
`hello.ok` / `registered`. Close-code handling:

| Code | Meaning | Action |
|---|---|---|
| 4401 | bad/expired/replayed token | fetch a new token once, retry; second failure → `Failed` |
| 4409 | room full / video slots full | terminal, typed error |
| 1008 | duplicate session (other device) | terminal, do not reconnect |
| 1009 | frame too large | bug + terminal, logged with payload size |
| 1013 | slow consumer | halve bitrate, reconnect |
| 1011 | relay error | reconnect |

### 7.2 Session state machine

```
Idle → Dialing → Ringing → Negotiating → Connecting → Active → Ended
                                    ↘ Failed
```

`Negotiating` is the window between `call.accepted` / `room.token` and
`registered`: camera and mic start here, so the first frame is ready when the
relay accepts the socket. Encoder warm-up (~150–300 ms) is therefore hidden
behind signalling rather than added to it.

### 7.3 Video send

1. `Preview` use case (CameraX) with our `RtcPreviewView` as `SurfaceProvider`.
2. We hand CameraX the `MediaCodec` encoder's input `Surface`.
3. Encoder: H.264 Baseline, `COLOR_FormatSurface`, CBR,
   `KEY_I_FRAME_INTERVAL = 2`, `KEY_LATENCY = 1` where supported,
   `KEY_PRIORITY = 0` (realtime) on API 30+.
4. Synchronous dequeue loop on a dedicated `HandlerThread`, `dequeueOutputBuffer(10_000 µs)`.
5. Annex-B output is sent as-is; `BUFFER_FLAG_KEY_FRAME` sets `FLAG_KEYFRAME`.
6. `need_keyframe` → `setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)`.
7. Rotation: `PARAMETER_KEY_ROTATION` (API 23+), debounced 300 ms, followed by a
   forced keyframe. CameraX keeps the preview upright; the encoded stream
   carries the rotation hint so remote surfaces render correctly.
8. Front camera: mirror locally only; the transmitted stream is not mirrored.

Presets:

| Preset | Resolution | FPS | Bitrate |
|---|---|---|---|
| `Low` | 320×180 | 15 | 300 kbps |
| `Standard` (default) | 640×360 | 24 | 700 kbps |
| `High` | 1280×720 | 30 | 1.5 Mbps |

Bitrate adaptation (v1, deliberately simple): if `WebSocket.queueSize()` stays
above 64 KiB for 2 s, step down one preset; if it stays under 8 KiB for 15 s,
step up (never above the configured preset). Keyframes after every step.

### 7.4 Video receive

Per bound slot:

1. First H.264 frame: scan Annex-B for SPS (type 7) and PPS (type 8), build
   `csd-0`/`csd-1`, configure `MediaCodec` (`video/avc`, `Surface` from the view),
   start async mode (`setCallback`).
2. Subsequent frames are queued to the decoder input; `mediaTs` is mapped to a
   due time (§7.6).
3. `INFO_OUTPUT_FORMAT_CHANGED` → first frame rendered; `RemoteVideoState.Playing`.
4. Decoder error / 1 s without output while input flows → reset, request keyframe
   (§3.2), enter `WaitingForKeyframe`.
5. Unbind → stop, release, remove from the registry.

Decoders are created lazily and capped at 4 concurrent; a fifth bound view stays
`Idle` and logs a warning (the UI should not bind more than 4).

### 7.5 Audio

**Send:** `AudioRecord`, `VOICE_COMMUNICATION`, 16 kHz mono PCM16, 20 ms frames
(640 B). `AcousticEchoCanceler` + `NoiseSuppressor` enabled when available;
`AutomaticGainControl` off by default (it fights AEC on some devices). Capture
runs on a `THREAD_PRIORITY_URGENT_AUDIO` thread, reads exactly 640 B, stamps
`mediaTs`, writes the 8-byte header, sends.

**Receive:** one `AudioTrack` per remote slot (`USAGE_VOICE_COMMUNICATION`,
`CONTENT_TYPE_SPEECH`, 16 kHz mono, streaming mode), so the OS mixer handles
multiple speakers and the relay stays dumb. A per-slot writer thread drains the
jitter buffer.

**Jitter buffer:** 20 ms units, 1.5 s ring. Adaptive target = clamp(40 ms + 3 ×
EMA(deviation), 40 ms, 200 ms). Late packets are dropped; a 20 ms hole is filled
with the previous frame faded out (cheap PLC), never a hard click.

**Routing and focus:** `AudioManager.MODE_IN_COMMUNICATION` for the session;
audio focus `AUDIOFOCUS_GAIN_TRANSIENT`; speaker toggle via
`setCommunicationDevice` (API 31+) and `isSpeakerphoneOn` below; route changes
observed through `AudioDeviceCallback`; everything restored on session end.

Documented limitation: AEC quality on speakerphone varies by device; earpiece
and headset are the recommended default.

### 7.6 Clock and A/V sync

- `mediaTs` is the sender's `elapsedRealtime()` truncated to u32, monotonic.
- Per sender, the receiver estimates `offset = arrivalMs − mediaTs` from the
  audio stream (continuous, cheap to model) over the first 20 audio frames
  (median, not mean — one late frame must not shift the clock).
- Audio is the master. A video frame is due at `mediaTs + offset`; early frames
  wait in a 4-frame / 200 ms reorder queue, frames more than 100 ms late are
  dropped (never queued).
- No global clock sync, no NTP. Per-sender offset is enough for lip-sync and
  survives reconnect because it is recomputed on the first audio frames after
  re-register.

### 7.7 Foreground service

`io.streamrtc.service.CallService`:

- Started when the first session becomes `Connecting`, stopped with the last one.
- `foregroundServiceType="camera|microphone"`, notification channel
  `streamrtc_call` (low importance, ongoing, silent).
- Actions: mute/unmute, end call, open app (host-supplied `contentIntent`
  optional).
- `START_NOT_STICKY`; the SDK never auto-restarts a call after process death.
- If `foregroundService = false`, the SDK skips the service and the host is
  responsible for keeping the process alive (documented, not recommended).

### 7.8 Lifecycle and teardown

Every pipeline implements one `close()` that is idempotent and non-blocking,
called from a single `SessionScope` cancellation. Order matters and is fixed:
encoder stop → camera unbind → audio capture stop → relay socket close →
decoders stop → audio tracks stop → FGS stop → state `Ended`. This is the
Home-SPY ANR-immune pattern: no HAL or `MediaCodec` call ever runs on the main
thread.

---

## 8. Error model

`StreamRtcException(code, message, cause)` with codes:
`NotConfigured`, `NotConnected`, `PermissionDenied`, `CalleeOffline`,
`SelfCall`, `RoomFull`, `VideoSlotsFull`, `TokenExpired`, `TokenRejected`,
`RoomClosed`, `CodecUnavailable`, `CameraBusy`, `Network`, `Protocol`.
Server `error.code` strings map 1:1, unknown values fall through as
`Protocol("…")` so a backend addition never crashes an old client.

---

## 9. Limits

| Limit | Value | Source |
|---|---|---|
| Participants per room | 16 | backend config |
| Video publishers per room | 8 | backend config |
| Video decoders per client | 4 | SDK |
| Media frame | 512 KiB | relay |
| Control message | 64 KiB | control |
| Room token TTL | 300 s | control |
| Ring timeout | 45 s | control |
| Relay register window | 5 s | relay |
| Media budget | 900 frames / 10 s | relay |

Audio at 50 fps + video at 24 fps = 74 frames / 10 s · 10 = 740, under budget.
A 720p keyframe at 1.5 Mbps / 2 s GOP is ~375 KiB, under 512 KiB.

---

## 10. Testing

**JVM (`src/test`, no device):**

- Frame codec parity using `protocol/testdata/frames.json`, a shared vector file
  also read by a new vitest case, so a drift on either side fails both suites.
- JSON message encode/decode for every server and client message.
- Backoff, close-code policy, jitter buffer, sync offset, state machine with a
  fake transport and fake clock.
- `Participant` reconciliation for join/leave/rejoin/slot reuse.

**Instrumented (`src/androidTest`, emulator + real device):**

- Encoder produces an IDR on start and on `need_keyframe`; invalid size is dropped.
- Decoder renders after an SPS/PPS + IDR pair; reset recovers after a dropped keyframe.
- AEC actually attaches where the device supports it.
- Live end-to-end against a local backend: a TS fake peer joins the same room and
  echoes frames; assert audio and video arrive in both directions. Reuses
  `tools/e2e/fake-client.ts`.

**Backend:** new vitest cases for `call.token`, relay `keyframe`, and
`peerInfo`; extend `tools/smoke.ts` with a reconnect-after-token check.

---

## 11. Milestones

| Milestone | Deliverable | Done when |
|---|---|---|
| **M0** | Backend deltas (§3) + protocol vectors | vitest green, smoke covers `call.token` |
| **M1** | Library scaffold, build, framing, control transport, 1:1 audio | Two devices hear each other, mute works, reconnect survives a relay drop |
| **M2** | Video send/receive, camera, FGS, audio routing | 1:1 video under ~500 ms, screen-off audio keeps playing |
| **M3** | Rooms, 4-grid, keyframe recovery, sync, adaptation | 4-way video on Wi-Fi, tiles recover after a forced keyframe |
| **M4** | Research-Hub migration + docs | `StreamCallClient` deleted, `MeetingRoomViewModel` uses the SDK |

Estimated 2.5 weeks of focused work, matching approach C.

---

## 12. Integration prerequisites for the host

1. **Token endpoint.** Research-Hub has no backend. It must expose something
   like `POST /v1/rtc/session` (verifies the Firebase ID token, returns a
   15-minute session JWT) and `POST /v1/rtc/room` (returns a 5-minute room JWT
   with a fresh nonce). Both sign HS256 with the app secret, which never ships
   in the APK. Cloud Functions or the existing Render service both work.
2. **Local development without the endpoint:** `tools/mint-dev-token.ts` prints
   a session or room token from the local config; the app reads it from
   `local.properties` (`rtcSessionToken=…`). Debug builds only.
3. **Permissions:** `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` and, on
   API 34+, the FGS types are merged from the library manifest; runtime
   requests remain in the host.
4. **Cleartext:** debug builds hitting `ws://10.0.2.2:8080` need a
   `networkSecurityConfig` exception; release builds use `wss://` only.
5. **Meeting scheduling stays host-side.** The SDK only joins a `roomId` with a
   token the host minted.

---

## 13. Alternatives rejected

- **WebRTC** — user's explicit decision against it; also a 10 MB+ dependency
  and a different backend.
- **Transport-only SDK** — integrators write `MediaCodec` code; that is the
  part they get wrong.
- **Compose UI inside the SDK** — pins a Compose BOM and conflicts with the
  host's.
- **Opus in v1** — no NDK on the build machine, ~2 MB/ABI, and PCM16 is enough
  to validate the product. The `AudioEncoder`/`AudioDecoder` interfaces are in
  place so the swap is additive (§14).
- **Server-side mixing** — the relay stays a byte pipe; mixing costs money and
  latency.

---

## 14. Deferred

- Opus encode/decode (interface seams included in v1).
- Active-speaker switching and >4 concurrent video decoders.
- Push-driven incoming calls (`call.incoming` while the app is dead).
- Screen sharing, recording, E2EE, web client, simulcast.
- Proactive room-token refresh for calls longer than 5 minutes (lazy refresh on
  reconnect covers it).
- Per-participant volume, audio ducking, Bluetooth SCO tuning on legacy devices.
