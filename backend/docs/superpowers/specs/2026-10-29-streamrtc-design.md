# StreamRTC — Stateless Real-Time Calling Bridge

**Status:** Approved direction — no database, host app owns all state
**Date:** 2026-10-29
**Supersedes:** earlier draft of same date (DB-backed platform)

---

## 1. Intent

A **stateless bridge** for real-time audio/video calls that any Android app can
use. It does three things and nothing else:

1. **Verifies tokens** the host app already signed.
2. **Signals** calls and rooms over WebSocket (presence, invite, accept, join).
3. **Relays** encoded media frames between room members.

**No database. No user table. No scheduling table. No call history.**
The integrating app manages users, meetings, contacts, and history — it already
has somewhere to put them. We hold only what a live call needs, in memory, and
throw it away when the call ends.

**Why this way:** the team already ships a working media path in Home-SPY
(MediaCodec H.264 + AudioRecord, framed over WebSocket, forwarded by a Node
relay, rendered via WebCodecs). Reusing it is proven and fast. Dropping the DB
makes the backend genuinely independent of any one app — it becomes
infrastructure, not a product with its own users.

---

## 2. Goals

| # | Goal |
|---|---|
| G1 | 1:1 audio + video call over WAN, ~150–350 ms glass-to-glass on good networks |
| G2 | Group calls: up to 8 video, 16 audio-only per room |
| G3 | Host app schedules meetings however it likes and passes a `roomId` — we join and call |
| G4 | Zero persistent state on our side; restart loses nothing that matters |
| G5 | One deployment serves multiple apps (`appId` in every token) |
| G6 | Android SDK where a call is ~10 lines |
| G7 | Deploy on Render; move media host later without a rewrite |

## 3. Non-goals

- No DB, no migrations, no user accounts, no meeting storage, no call history.
- No transcoding, simulcast, server-side audio mixing, recording.
- No screen share, SIP/PSTN, broadcast mode, web/iOS SDK in v1.
- No E2EE in v1 (frame format allows it later — §14.4).
- No "WhatsApp-identical latency". Intercom-grade: see §16.

## 4. Anti-goals

- **A1** Rebuilding WebRTC badly instead of shipping calls.
- **A2** Backend that only one app can use.
- **A3** Silent freezes — every failure must surface exactly one user-visible string.
- **A4** Unbounded memory growth when a client stops reading.
- **A5** Media egress that quietly makes hosting 10× the estimate.
- **A6** An SDK that makes the integrator think about binary framing or codecs.
- **A7** Re-introducing a database "for convenience" — state belongs to the host app.

## 5. Approaches considered

**A. WebSocket media relay (Home-SPY pattern) — CHOSEN.**
Proven here; works on every network over TCP 443 (no NAT traversal, no TURN);
plaintext frames allow future recording/E2EE; deploys anywhere, including
Render.
Cost: TCP head-of-line blocking, one bitrate for all receivers, and the client
must supply AEC + jitter buffer + congestion handling. Group calls cost
`N × (N−1)` bandwidth.

**B. WebRTC + SFU (LiveKit).** Better latency and 40 % less egress, ready
Android SDK — but cannot self-host on Render (no UDP ingress) and the human
partner chose A.

**C. Hybrid.** Door left open at the relay boundary (§12.3); not built.

---

## 6. Architecture

```
┌──────────────┐   WSS JSON: token, presence, invite, accept, join
│ Android SDK  │◄──────────────────────────────────────► CONTROL PLANE
│              │                                         (stateless, Node)
│              │   WSS binary: media frames              no DB, no disk
│              │◄──────────────────────────────────────► MEDIA RELAY
└──────────────┘                                         (in-memory rooms)
                    ▲
                    │ host app signs tokens with a shared secret,
                    │ stores users + meetings itself
```

| Service | Responsibility | State held |
|---|---|---|
| **control-plane** | Verify session tokens, route invites by presence, mint room tokens, system messages | `userId → sockets` in memory |
| **media-relay** | Burn room token, assign slot, fan out frames, backpressure | `roomId → slots` in memory |

Two services because media is gigabytes and control is kilobytes: the expensive
part can move host without touching the other, and a relay restart never breaks
call setup. Home-SPY already separates `RELAY_URL` from `CMD_WS_URL`.

Shared `packages/protocol` so framing and message shapes cannot drift.

---

## 7. Stateless auth

### 7.1 Shared secret, two token kinds

The host app's backend holds one secret per app. No token endpoint is required
(though one is offered for apps that do not want to implement HMAC).

| Token | Claims | TTL | Used for |
|---|---|---|---|
| **Session** | `v, appId, userId, displayName, exp` | 15 min, refreshed | Authenticate the control socket |
| **Room** | `v, appId, userId, roomId, role, exp, nonce` | 5 min, single use | Register on the media relay |

Both are `HS256` JWTs signed with the app secret. The server verifies
`appId → secret` from configuration (env/file, no DB).

```jsonc
// session
{ "v": 1, "appId": "com.acme.calls", "userId": "u_42",
  "displayName": "Aman", "exp": 1793000000 }

// room
{ "v": 1, "appId": "com.acme.calls", "userId": "u_42",
  "roomId": "r_9f3c", "role": "member", "nonce": "8f2a...", "exp": 1793000000 }
```

### 7.2 Tenant config without a DB

```jsonc
// streamrtc.config.json (mounted as a Render secret file)
{ "apps": {
    "com.acme.calls": { "secret": "…32 bytes base64…",
                        "maxRoomParticipants": 16,
                        "maxVideoParticipants": 8 }
} }
```

Rotate by adding `secretNext` with a 24 h overlap; the server accepts both.

### 7.3 Why no DB is safe

- Nobody can forge a token without the app secret.
- A room token is single-use: the relay burns the `nonce` in memory (TTL 10 min).
- A restart drops live calls — acceptable, because every live call is already a
  transient thing. The host app decides what to record.
- No query needs a table: everything is "who is connected right now", which is
  exactly what a socket registry is.

---

## 8. Media protocol v1

### 8.1 Codecs

| Media | Primary | Fallback |
|---|---|---|
| Audio | Opus 16 kHz mono, 24–32 kbps, 20 ms | PCM16 16 kHz mono (256 kbps) |
| Video | H.264 Baseline `avc1.42E01E`, Annex-B, CBR | — |

Opus is used when the device exposes `c2.android.opus.encoder` (API 29+); PCM16
otherwise. The audio type is carried **per frame**, so one device publishing
PCM16 never forces others to change.

### 8.2 Binary framing

**Client → Relay (8-byte header):**

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | magic `0x53` |
| 1 | 1 | version `0x01` |
| 2 | 1 | type: `0x10` opus, `0x11` pcm16, `0x20` h264 |
| 3 | 1 | flags: bit0 keyframe, bit1 EOS, bit2 screen |
| 4 | 4 | mediaTs — sender monotonic ms, u32BE |
| 8 | n | payload (one Opus packet, PCM16 LE, or one Annex-B access unit) |

**Relay → Client (9-byte header):** bytes 0–3 identical, then `senderSlot:u8`,
`mediaTs:u32BE`, payload.

The relay never parses payloads; it rewrites the header (one allocation per
recipient) and writes.

### 8.3 Clocks

`mediaTs` is the sender's monotonic clock. It is used only for A/V sync within
one sender and for jitter buffering. No cross-sender clock sync is needed: each
incoming audio stream has its own jitter buffer, and the SDK mixes decoded PCM
into one `AudioTrack`.

### 8.4 Jitter buffer (client, mandatory)

Target 60 ms, adaptive 40–200 ms. Grow if >2 % of packets are late over 5 s;
shrink after 10 s under 0.5 %. Drop video frames later than `target + 150 ms` and
never stall. For audio, only drop when backlog exceeds 300 ms, and drop whole
frames from the oldest end.

### 8.5 Keyframes

The relay asks a publisher for a keyframe when a subscriber joins, recovers from
degradation, or reports a decode error. Publisher calls
`MediaCodec.setParameters(KEY_REQUEST_SYNC_FRAME)`. Subscribers discard delta
frames until the first keyframe (Home-SPY already does this; it is load-bearing).

### 8.6 Backpressure (relay, mandatory)

Per subscriber: `bufferedAmount` > 512 KB for 500 ms → stop video, send
`degraded`, keep audio. > 2 MB → close `1013`. On recovery → request one
keyframe, resume.
Per publisher: 900 media frames / 10 s and 60 control messages / 10 s, then close
`1008` (Home-SPY's proven budgets).

**Known limitation, documented:** one bitrate per publisher means a weak
receiver cannot be served a smaller stream; it drops frames or forces keyframes
for everyone. §18 adds a two-layer mode later.

---

## 9. Signaling (control plane, WSS JSON)

Envelope: `{ "t": "<type>", "id": "optional", "d": { … } }`

### 9.1 Client → server

| `t` | Payload |
|---|---|
| `hello` | `{ token, deviceId, platform, appVersion, codecs: { audio:[], video:[] } }` |
| `call.invite` | `{ toUserId, roomId, media: { audio, video } }` |
| `call.accept` / `call.reject` | `{ callId, reason? }` |
| `call.end` | `{ callId }` |
| `room.join` | `{ roomId }` — room token already held by the client |
| `room.leave` | `{ roomId }` |
| `room.state` | `{ roomId }` |
| `ping` | `{ ts }` |

### 9.2 Server → client

| `t` | Payload |
|---|---|
| `hello.ok` | `{ userId, serverTime, limits }` |
| `hello.err` | `{ code, message }` |
| `call.incoming` | `{ callId, from: { userId, displayName }, roomId, media }` |
| `call.ringing` / `call.accepted` / `call.rejected` / `call.ended` | `{ callId, … }` |
| `room.token` | `{ relayUrl, roomId, token, expiresAt, slot, peers: [] }` |
| `room.participant_joined` / `room.participant_left` | `{ roomId, participant }` |
| `room.participant_updated` | `{ roomId, userId, { muted, videoEnabled } }` |
| `room.state` | `{ roomId, participants: [] }` |
| `callee_offline` | `{ callId, toUserId }` — host app pushes FCM itself |
| `pong` | `{ ts, serverTime }` |

### 9.3 1:1 flow (no DB)

```
Caller                    Control plane                  Callee
  │ hello(session token)        │                           │
  │────────────────────────────►│  presence[userId] = ws    │
  │ call.invite{toUserId,roomId}│                           │
  │────────────────────────────►│                           │
  │                             │ call.incoming ───────────►│
  │                             │◄────────── call.accept    │
  │◄── call.accepted            │                           │
  │                             │  mint 2 room tokens       │
  │◄── room.token ──────────────┤──────── room.token ──────►│
  │                             │                           │
  │══════ WSS media relay: frames both ways ════════════════│
```

If the callee has no socket, the control plane answers `callee_offline` and the
**host app** sends the push notification — it owns the FCM tokens.

`roomId` is chosen by the caller's app (UUID). Room tokens are minted by the
control plane after `accept`, so the callee cannot be joined by a third party.
Host-signed room tokens are also accepted, for rooms the host created itself.

### 9.4 Group calls and meetings

There is no meeting code in this backend.

- The host app stores the meeting (its DB/Firebase) and decides the `roomId`.
- At join time the host backend signs a **room token** for that `roomId` and
  returns it to its client; the client sends `room.join`.
- The control plane mints room tokens itself for rooms it creates through
  `call.invite` / `room.create`.

This is why scheduling needs no schema here: a meeting is just a `roomId` that
the host app remembers and hands out tokens for.

---

## 10. Relay lifecycle

```
connect(WSS /media)
  → {"t":"register","token":"<room token>"}        (within 5 s)
  → verify JWT, room role, TTL, burn nonce
  → {"t":"registered","slot":2,"peers":[0,1],"limits":{...}}
  → binary frames both directions
  → close: peers get {"t":"peer_left","slot":2}
```

Slots 0–15, reused after a 30 s grace period so a reconnect does not renegotiate.
The relay holds no credentials and writes nothing to disk.

---

## 11. Failure handling

| Failure | Behaviour |
|---|---|
| Control socket drops | Reconnect with exponential backoff (1→30 s, jitter). Media is a different socket, so the call survives. |
| Media socket drops | Re-register with a resume token within 30 s; slot is retained. Otherwise full rejoin. |
| Relay restart / deploy | Sockets close `1012`; SDK reconnects; call state rebuilds from a fresh `room.join`. |
| Publisher silent > 3 s | Peers get `publisher_stalled`; UI shows "reconnecting". |
| Decoder error | Tear down decoder, request keyframe, resume on next IDR. |
| Slow subscriber | Audio-only, then close `1013` (§8.6). |
| Clock jump / device sleep | `mediaTs` regression > 500 ms → resync A/V baseline. |
| Duplicate join | Second socket for same `(room, user)` evicts the first with `1008`. |
| Token expired mid-call | Media token TTL only gates *joining*; reconnects mint a new one. |

One failure, one user-visible string. No silent freezes (A3).

---

## 12. Capacity and deployment

### 12.1 Capacity (single instance, v1)

- ~300–500 Mbps relayed per vCPU (copy-bound)
- ~2,000 media sockets per GB RAM
- 8-way video room ≈ 18 Mbps down per participant

Target on 1 CPU / 2 GB: **~500 concurrent participants at 360p**. Bandwidth
price, not CPU, is the real ceiling.

Scaling out (v2): pin rooms to a relay instance, control plane returns that
instance's `relayUrl`, least-loaded assignment with room affinity. The control
plane is stateless and scales horizontally.

### 12.2 Render

| Service | Plan | Note |
|---|---|---|
| `streamrtc-control` | $7 → $25 | Must not spin down; **free tier unusable** (sleeps at 15 min) |
| `streamrtc-relay` | $7 → $25 | Same image runs on any VPS unchanged |

**No Postgres, no Redis.** Total: **$14/month**.

Render includes **5 GB/month bandwidth (Hobby), 25 GB (Pro)**, then $0.15/GB:

| Scenario | Egress/hour | 5 GB covers | 25 GB covers | Beyond |
|---|---|---|---|---|
| 1:1, 360p + Opus | ~240 MB | ~20 h | ~104 h | $0.036 / call-hour |
| 4-way, 360p + Opus | ~2.9 GB | ~1.7 h | ~8.6 h | $0.43 / group-hour |
| 4-way, PCM16 audio | ~4.0 GB | ~1.2 h | ~6.2 h | $0.60 / group-hour |

Render is fine for pilots and for control traffic; it is an expensive place for
production media. Opus is therefore not optional — it is an 8× bandwidth
decision.

### 12.3 Exit ramp

The relay image has no Render-specific code. Moving it to a €5–8 VPS with 20 TB
traffic changes `RELAY_URL` and nothing else: a 360p 1:1 hour then costs
~€0.0004 instead of $0.036. This is why §6 splits the services.

---

## 13. Observability

Metrics (`/metrics`): `rtc_rooms_active`, `rtc_participants_active`,
`rtc_frames_in_total`, `rtc_frames_out_total`, `rtc_bytes_egress_total`,
`rtc_subscriber_degraded_total`, `rtc_keyframe_requests_total`,
`rtc_call_setup_seconds` (histogram), `rtc_egress_gb_today` (the number that
predicts the bill).

Logs: structured JSON, one line per register/close and per call transition.
Never log payloads or tokens.

Alerts: egress > 80 % of allowance; socket error rate > 1 %; call setup p95 > 3 s.

Health: `/healthz` (process), `/readyz` (config loaded, relay reachable).

---

## 14. Security

1. **WSS only.** No plaintext media path exists.
2. **Short-lived HS256 tokens**, room tokens single-use with a burned nonce.
3. **Never trust client-supplied identity** — `userId` comes from the verified
   token, never from the message body.
4. **E2EE (phase 3, genuinely feasible here):** the relay never parses payloads,
   so a room key can be distributed over the control socket and payloads
   encrypted AES-GCM-128 (`[iv|tag|ct]`, ~28 B overhead). An SFU that transcodes
   could not do this.
5. **Abuse:** per-IP and per-user rate limits on `hello` and `register`; max rooms
   per IP; oversized-frame rejection before buffer allocation.
6. **Secrets** live in Render secret files, never in the repo.

---

## 15. Android SDK

```kotlin
val rtc = StreamRTC.initialize(
    context,
    StreamRtcConfig(
        baseUrl = "https://streamrtc.example.com",
        tokenProvider = { hostBackend.sessionToken() },   // suspend, SDK caches
    )
)

// 1:1
val call = rtc.calls.start(CallTarget.User("user_42"), media = Media.Both)
call.state.collect { /* Ringing, Active, Ended */ }
call.setMuted(true); call.setCameraEnabled(false); call.end()

// group / meeting (host app supplies the roomId it stored)
val room = rtc.rooms.join(roomId = "r_9f3c", displayName = "Aman")
room.participants.collect { tiles -> grid.render(tiles) }
room.audio.setSpeakerphone(true)

// meeting scheduling = host app's job; we only join rooms
```

| Layer | Contents |
|---|---|
| `protocol` | Frame codec, message models, version negotiation |
| `transport` | OkHttp WebSockets, reconnect/backoff, token refresh, resume |
| `media` | CameraX → MediaCodec H.264; AudioRecord → Opus/PCM16 |
| `rtc` | Jitter buffer, A/V sync, per-sender decoders, mixing, AEC |
| `api` | Public façade — the only thing integration code touches |

Mandatory inside the SDK so integrators cannot get it wrong: echo cancellation
(`AcousticEchoCanceler` + `VOICE_COMMUNICATION`, documented half-duplex
fallback), adaptive jitter buffer, keyframe recovery, lifecycle-safe
camera/encoder teardown (Home-SPY's ANR-immune service pattern).

`minSdk 26`; Opus on API 29+, PCM16 fallback below.

---

## 16. Latency budget

| Stage | Typical |
|---|---|
| Capture + encode | 30–50 ms |
| Network (RTT/2, same region) | 20–60 ms |
| Relay copy + forward | 1–5 ms |
| Jitter buffer | 40–200 ms |
| Decode + render | 30–60 ms |
| **Glass-to-glass, good network** | **~150–350 ms** |
| **Under packet loss** | 400 ms – 2 s (TCP stall) |

Usable for conversation, clearly behind WebRTC products. Not suitable for music,
remote gaming, or turn-taking-heavy talk on lossy mobile networks.

---

## 17. Project structure

```
stream-rtc/
├── docs/superpowers/specs/           # this document
├── packages/
│   ├── protocol/                     # frame + message codecs, shared
│   ├── control-plane/                # Fastify HTTP + WS signalling
│   └── media-relay/                  # ws server, room registry, backpressure
├── deploy/
│   ├── render.yaml                   # 2 services, no DB
│   └── docker/{control,relay}.Dockerfile
├── sdk-android/                      # Gradle module (M4)
└── tools/                            # load test, protocol fuzzer, e2e harness
```

TypeScript, `pnpm` workspaces, `zod` validation, `vitest`.

---

## 18. Roadmap

| Milestone | Deliverable | Done when |
|---|---|---|
| **M1** (2 wk) | protocol, config, auth, control plane, relay, 1:1 media | Two Android devices: 1:1 video+audio over WAN, <500 ms, 30 min without drift |
| **M2** (2 wk) | group calls, keyframes, backpressure, reconnect/resume | 4-way on mixed networks; killing a socket recovers in <3 s |
| **M3** (1 wk) | host-app integration contract + examples (meetings live host-side) | A sample host backend issues room tokens for a scheduled meeting; join works |
| **M4** (2 wk) | Android SDK v0.1 + demo app | Demo integrates in <100 LOC |
| **M5** (1.5 wk) | load test, metrics, limits, security review | 500 participants on one relay; egress dashboard; alert fires in staging |
| **M6** (later) | two-layer video, recording, E2EE, web SDK | — |

M1 is the go/no-go: if 1:1 quality is unacceptable on real mobile networks, §5
needs revisiting before M2.

---

## 19. Calls made here (my judgement, cheap to change)

1. **Opus primary, PCM16 fallback** — 8× bandwidth saving, old devices still work.
2. **Separate media-relay service** — enables the Render exit ramp.
3. **Room as the only primitive** — 1:1 is a 2-member room.
4. **Client-side audio mixing** — relay stays dumb and cheap.
5. **8 video / 16 audio participants** — set by mobile decoders and bandwidth.
6. **Host signs tokens; we never store users.**
7. **Single-use room tokens with in-memory nonce burn.**
8. **No recording in v1** — server muxing is real work; format allows it later.
9. **`minSdk 26`.**
10. **`roomId` is a UUID chosen by the host app**, not by us.

## 20. Open questions

1. **Token signing:** will the host backend sign tokens itself (no call to us), or
   do you want a `POST /v1/token` endpoint for convenience?
2. **Group size:** 8 video / 16 audio enough for launch?
3. **`minSdk`:** which value do the target apps actually ship with?
4. **Multiple apps at launch**, or one app with multi-tenancy as a property?
5. **Background/offline calls:** host pushes FCM itself (recommended), or do you
   want the bridge to hold push tokens in memory for the session?
