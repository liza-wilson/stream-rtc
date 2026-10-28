# stream-rtc

A small, **stateless** RTC backend for Android apps: 1:1 calls, group calls and
meetings. Media travels over **binary WebSocket** as pre-encoded H.264 + Opus
(or PCM16) frames that the relay forwards without parsing. There is no database,
no WebRTC, and no user store — the host app owns users, meetings and history.

```
Android A ──WSS control──► control-plane ──signalling──► Android B
Android A ══WSS media════► media-relay  ══frames═══════► Android B
```

- `packages/protocol` — frame codec and signalling messages (shared)
- `packages/core` — config + JWT auth (shared)
- `packages/control-plane` — Fastify HTTP + WS signalling, presence, call state
- `packages/media-relay` — WS register, room slots, fan-out, backpressure

## Quick start

```bash
npm install

# 1. create a config file with one app secret (>= 32 chars)
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"

cat > streamrtc.config.json <<'JSON'
{ "apps": { "com.example.calls": { "secret": "<paste-secret>" } } }
JSON

# 2. run the two services
STREAMRTC_CONFIG_FILE=./streamrtc.config.json PUBLIC_RELAY_URL=ws://127.0.0.1:8081 npm run dev:control
STREAMRTC_CONFIG_FILE=./streamrtc.config.json PORT=8081 npm run dev:relay
```

Health and metrics: `GET /healthz`, `GET /readyz`, `GET /metrics` (control plane
only; the relay exposes `/healthz` and `/readyz`).

## Configuration

`STREAMRTC_CONFIG_FILE` points at JSON:

```json
{
  "apps": {
    "com.example.calls": {
      "secret": "32+ character HS256 secret",
      "secretNext": "optional, used during rotation",
      "maxRoomParticipants": 16,
      "maxVideoParticipants": 8
    }
  }
}
```

Env vars:

| Var | Service | Meaning |
|---|---|---|
| `PORT` | both | Listen port (Render sets it) |
| `STREAMRTC_CONFIG_FILE` | both | Path to the JSON above |
| `PUBLIC_RELAY_URL` | control | WSS URL handed to clients in `room.token` |

The relay reads the same config, so both services must see the same apps and
secrets.

## Tokens

The host app's backend signs both token types with the app secret (HS256, `v: 1`).
`userId` is always taken from the verified token, never from a message body.

**Session token** (15 min, used for `hello`):

```js
const jwt = require('jsonwebtoken')
const token = jwt.sign(
  { v: 1, appId: 'com.example.calls', userId: 'u_42', displayName: 'Aman', exp: Math.floor(Date.now()/1000) + 900 },
  '<secret>',
  { algorithm: 'HS256' },
)
```

**Room token** (5 min, single use, used to register with the relay):

```js
const token = jwt.sign(
  {
    v: 1,
    appId: 'com.example.calls',
    userId: 'u_42',
    roomId: 'r_9f3c',
    role: 'member',            // or "host"
    nonce: crypto.randomUUID(), // burned by the relay; replay closes 4401
    exp: Math.floor(Date.now()/1000) + 300,
  },
  '<secret>',
  { algorithm: 'HS256' },
)
```

For a scheduled meeting the host backend stores the meeting and the `roomId`,
then signs a room token when a participant joins. This backend never schedules
or remembers anything.

## Protocol

Control plane (`/ws`), JSON text frames `{ "t": "...", "d": {...} }`:

| Client → | Server → |
|---|---|
| `hello {token, deviceId, platform, codecs}` | `hello.ok` / `hello.err`, else close `4401` |
| `call.invite {toUserId, roomId, media}` | `call.incoming` to callee, or `callee_offline` (host app sends FCM) |
| `call.accept {callId}` | `call.accepted` to caller + `room.token` to both |
| `call.reject {callId, reason}` | `call.rejected` to caller |
| `call.end {callId, reason}` | `call.ended` to the other party |
| `room.join {roomId}` | `error {code: "use_room_token"}` |
| `ping {ts}` | `pong {ts, serverTime}` |

Ringing calls time out after 45 s (`call.ended {reason: "timeout"}`).

Media relay:

1. Connect, then within 5 s send `{"t":"register","token":"<room token>"}`.
2. Receive `{"t":"registered","slot":0,"peers":[],"limits":{...}}`.
3. Send binary frames; every other member of the room receives them with the
   sender's slot inserted.

Binary layout (big endian): `MAGIC 0x53 | VERSION 1 | type | flags | ...`

- client frame: `header 8 B` = magic, version, type, flags, u32 mediaTs
- relay frame: `header 9 B` = same + u8 senderSlot before `mediaTs`

Types: `0x10` Opus, `0x11` PCM16, `0x20` H.264 (Annex-B).
Flags: `1` keyframe, `2` EOS, `4` screen.

Buffer policy per subscriber: above 512 KB of queued output, video is dropped
(audio keeps flowing, and a keyframe is requested when the subscriber recovers);
above 2 MB the socket is closed with `1013`.

## Deploying to Render

`render.yaml` declares two services, `streamrtc-control` and `streamrtc-relay`,
both `plan: starter`, with `/healthz` checks and no database.

1. Push the repo, then **New → Blueprint** in Render and point it at the repo.
2. Create the secret file `streamrtc.config.json` on **both** services
   (Environment → Secret Files). Render mounts it at `/etc/secrets/`, which is
   what `STREAMRTC_CONFIG_FILE` already points to.
3. Set `PUBLIC_RELAY_URL` on the control service to
   `wss://<relay-host>` (no trailing slash, no path).
4. Deploy; the relay answers `/healthz`, the control plane answers `/readyz`
   once the config parses.

The free plan **cannot** be used: it sleeps after 15 minutes of inactivity, which
breaks call setup. Starter ($7/mo per service) is the floor.

**Bandwidth is the real cost.** Hobby includes 5 GB/month, Pro 25 GB, then
$0.15/GB:

| Scenario | Per hour | 5 GB covers |
|---|---|---|
| 1:1, 360p + Opus | ~240 MB | ~20 h |
| 4-way, 360p + Opus | ~2.9 GB | ~1.7 h |
| 4-way, PCM16 audio | ~4.0 GB | ~1.2 h |

Opus is an 8× bandwidth decision, not a nice-to-have. If egress grows, move only
the relay to a cheap VPS with generous traffic and change `PUBLIC_RELAY_URL` —
the relay has no Render-specific code.

## Tests

```bash
npm test                              # all unit + e2e tests
npx vitest run tools/e2e             # control plane + relay end to end
```

## What this backend does not do

- No users, contacts, meetings, history or push tokens — the host app owns them.
- No recording, transcoding, mixing or E2EE (E2EE is feasible later because the
  relay never parses payloads).
- No Android SDK — see the spec, `docs/superpowers/specs/2026-10-29-streamrtc-design.md`.
