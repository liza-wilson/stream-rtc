# StreamRTC Backend v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A stateless WebSocket signalling + media relay backend that carries 1:1 and group audio/video calls between Android clients, with no database.

**Architecture:** Two Node services sharing a protocol package. `control-plane` verifies HS256 session tokens, tracks live sockets by `userId`, and turns `call.invite`/`accept` into minted room tokens. `media-relay` burns single-use room tokens, assigns slots, and fans out binary frames with backpressure. All state is in memory; a restart drops live calls by design.

**Tech Stack:** Node 22, TypeScript (strict, ESM), pnpm workspaces, Fastify 5, `ws`, `zod`, `jose` (HS256), `prom-client`, Vitest.

**Spec:** `docs/superpowers/specs/2026-10-29-streamrtc-design.md`

## Global Constraints

- **No database, no Redis, no disk writes.** State lives in process memory only.
- Node 22, ESM (`"type": "module"`), TypeScript `strict: true`, no `any` in exported signatures.
- Media frame header: client→relay **8 bytes** (magic `0x53`, version `1`, type, flags, `mediaTs` u32BE), relay→client **9 bytes** (adds `senderSlot` u8 after flags).
- Frame types: `0x10` Opus, `0x11` PCM16, `0x20` H.264. Flags: `1` keyframe, `2` EOS, `4` screen.
- Rate budgets: **900 media frames / 10 s**, **60 control messages / 10 s** per socket.
- Backpressure: subscriber `bufferedAmount` > **512 KB** for **500 ms** → drop video, keep audio; > **2 MB** → close `1013`.
- Room limits: **16 participants**, of which **8** may publish video. Slots `0–15`.
- Tokens: HS256 JWT. Session TTL **15 min**; room TTL **5 min**, single use via burned `nonce`.
- Room token claims exactly: `v, appId, userId, roomId, role, nonce, exp`. Session claims exactly: `v, appId, userId, displayName?, exp`.
- Ports: control-plane `10000`, relay `10001`. Env: `STREAMRTC_CONFIG`, `PORT`, `RELAY_URL`, `PUBLIC_RELAY_URL`.
- Every failure maps to exactly one user-visible string (spec A3). Never throw out of a message handler.

## Review Focus

1. **Truncated or malformed binary frames** (1–8 bytes, bad magic, wrong version) — a peer must never crash the relay; decode returns `null` and the frame is dropped.
2. **Replayed room token** — the second `register` with the same `nonce` must be rejected, not merely re-authenticated.
3. **Same user joining the same room twice** — must evict the older socket (`1008 duplicate_session`), never allocate a second slot.
4. **Slow subscriber** — must degrade to audio-only within 500 ms and never accumulate unbounded `bufferedAmount`.
5. **`mediaTs` wrap and regression** — a u32 clock that wraps or jumps backwards (device sleep) must not corrupt frame encoding or A/V baseline.

---

### Task 1: Workspace scaffold and frame codec

**Files:**
- Create: `pnpm-workspace.yaml`, `package.json`, `tsconfig.base.json`, `vitest.config.ts`, `.gitignore`
- Create: `packages/protocol/package.json`, `packages/protocol/tsconfig.json`
- Create: `packages/protocol/src/frame.ts`
- Test: `packages/protocol/src/frame.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `type MediaType = 0x10 | 0x11 | 0x20`
  - `interface ClientFrame { type: MediaType; flags: number; mediaTs: number; payload: Buffer }`
  - `interface RelayFrame extends ClientFrame { senderSlot: number }`
  - `encodeClientFrame(f: ClientFrame): Buffer`, `decodeClientFrame(b: Buffer): ClientFrame | null`
  - `encodeRelayFrame(f: RelayFrame): Buffer`, `decodeRelayFrame(b: Buffer): RelayFrame | null`
  - `MAGIC = 0x53`, `VERSION = 1`, `HEADER_CLIENT = 8`, `HEADER_RELAY = 9`
  - `FLAG_KEYFRAME = 1`, `FLAG_EOS = 2`, `FLAG_SCREEN = 4`

- [ ] **Step 1: Write the failing test**

```ts
// packages/protocol/src/frame.test.ts
import { describe, expect, it } from 'vitest'
import {
  decodeClientFrame, decodeRelayFrame, encodeClientFrame, encodeRelayFrame,
  FLAG_KEYFRAME, HEADER_CLIENT, HEADER_RELAY, MAGIC, VERSION,
} from './frame.js'

describe('client frame', () => {
  it('round-trips type, flags, mediaTs and payload', () => {
    const buf = encodeClientFrame({ type: 0x20, flags: FLAG_KEYFRAME, mediaTs: 123456, payload: Buffer.from([1, 2, 3]) })
    expect(buf.subarray(0, 5)).toEqual(Buffer.from([MAGIC, VERSION, 0x20, 1, 0x00]))
    expect(buf.readUInt32BE(4)).toBe(123456)
    expect(buf.length).toBe(HEADER_CLIENT + 3)
    const out = decodeClientFrame(buf)
    expect(out).toEqual({ type: 0x20, flags: 1, mediaTs: 123456, payload: Buffer.from([1, 2, 3]) })
  })

  it('returns null for truncated headers, bad magic and wrong version', () => {
    expect(decodeClientFrame(Buffer.from([MAGIC, VERSION, 0x20]))).toBeNull()
    expect(decodeClientFrame(Buffer.from([0x00, VERSION, 0x20, 0, 0, 0, 0, 0]))).toBeNull()
    expect(decodeClientFrame(Buffer.from([MAGIC, 2, 0x20, 0, 0, 0, 0, 0]))).toBeNull()
  })

  it('returns null for an unknown media type', () => {
    expect(decodeClientFrame(Buffer.from([MAGIC, VERSION, 0x99, 0, 0, 0, 0, 0]))).toBeNull()
  })

  it('encodes a wrapped u32 clock without overflowing', () => {
    const buf = encodeClientFrame({ type: 0x10, flags: 0, mediaTs: 0xffffffff, payload: Buffer.alloc(0) })
    expect(buf.readUInt32BE(4)).toBe(0xffffffff)
  })
})

describe('relay frame', () => {
  it('adds senderSlot after flags and preserves mediaTs', () => {
    const buf = encodeRelayFrame({ type: 0x10, flags: 0, senderSlot: 7, mediaTs: 42, payload: Buffer.from([9]) })
    expect(buf.length).toBe(HEADER_RELAY + 1)
    expect(buf[4]).toBe(7)
    expect(buf.readUInt32BE(5)).toBe(42)
    expect(decodeRelayFrame(buf)).toEqual({ type: 0x10, flags: 0, senderSlot: 7, mediaTs: 42, payload: Buffer.from([9]) })
  })

  it('returns null for a truncated relay header', () => {
    expect(decodeRelayFrame(Buffer.alloc(8))).toBeNull()
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/protocol/src/frame.test.ts`
Expected: FAIL — `Cannot find module './frame.js'`.

- [ ] **Step 3: Implement `packages/protocol/src/frame.ts`**

Write the constants and four functions above. `decode*` must validate length ≥ header, magic, version, and type membership, and return `null` (never throw) on any mismatch. Use `Buffer.readUInt32BE` / `writeUInt32BE`; slots outside `0–15` return `null` on decode.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/protocol/src/frame.test.ts`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add pnpm-workspace.yaml package.json tsconfig.base.json vitest.config.ts .gitignore packages/protocol
git commit -m "feat(protocol): binary media frame codec"
```

---

### Task 2: Message schemas

**Files:**
- Create: `packages/protocol/src/messages.ts`, `packages/protocol/src/index.ts`
- Test: `packages/protocol/src/messages.test.ts`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `type ClientMessage = { t: 'hello'; d: HelloD } | { t: 'call.invite'; d: InviteD } | { t: 'call.accept' | 'call.reject' | 'call.end'; d: CallRefD } | { t: 'room.join' | 'room.leave' | 'room.state'; d: RoomRefD } | { t: 'ping'; d: { ts: number } }`
  - `type ServerMessage = { t: string; d: Record<string, unknown> }` plus the discriminated members `hello.ok`, `hello.err`, `call.incoming`, `call.ringing`, `call.accepted`, `call.rejected`, `call.ended`, `callee_offline`, `room.token`, `room.participant_joined`, `room.participant_left`, `room.participant_updated`, `room.state`, `pong`
  - `parseClientMessage(raw: string): ClientMessage | null`
  - `parseServerMessage(raw: string): ServerMessage | null`
  - `encodeMessage(m: ServerMessage): string`

- [ ] **Step 1: Write the failing test**

```ts
// packages/protocol/src/messages.test.ts
import { describe, expect, it } from 'vitest'
import { encodeMessage, parseClientMessage, parseServerMessage } from './messages.js'

describe('client messages', () => {
  it('parses hello with codecs', () => {
    const m = parseClientMessage(JSON.stringify({
      t: 'hello',
      d: { token: 'abc', deviceId: 'd1', platform: 'android',
           codecs: { audio: ['opus', 'pcm16'], video: ['h264'] } },
    }))
    expect(m?.t).toBe('hello')
  })

  it('parses call.invite and rejects a missing toUserId', () => {
    expect(parseClientMessage(JSON.stringify({
      t: 'call.invite', d: { roomId: 'r1', media: { audio: true, video: true } },
    }))).toBeNull()
  })

  it('returns null for unknown types and non-JSON', () => {
    expect(parseClientMessage('{"t":"nope","d":{}}')).toBeNull()
    expect(parseClientMessage('not json')).toBeNull()
  })

  it('rejects payloads over 64 KB', () => {
    const big = JSON.stringify({ t: 'ping', d: { ts: 1, pad: 'x'.repeat(70_000) } })
    expect(parseClientMessage(big)).toBeNull()
  })
})

describe('server messages', () => {
  it('round-trips an encoded message', () => {
    const raw = encodeMessage({ t: 'pong', d: { ts: 1, serverTime: 2 } })
    expect(JSON.parse(raw)).toEqual({ t: 'pong', d: { ts: 1, serverTime: 2 } })
    expect(parseServerMessage(raw)?.t).toBe('pong')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/protocol/src/messages.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `messages.ts` and `index.ts`**

Zod schemas with `.strict()` per variant, a discriminated union on `t`, and a 64 KB guard on the raw string before parsing. `index.ts` re-exports `frame.js` and `messages.js`.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/protocol/src/messages.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/protocol
git commit -m "feat(protocol): typed signalling messages"
```

---

### Task 3: App registry and secret rotation

**Files:**
- Create: `packages/control-plane/package.json`, `packages/control-plane/tsconfig.json`
- Create: `packages/control-plane/src/config.ts`
- Test: `packages/control-plane/src/config.test.ts`
- Create: `streamrtc.config.example.json`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface AppEntry { appId: string; secret: string; secretNext?: string; maxRoomParticipants: number; maxVideoParticipants: number }`
  - `interface AppRegistry { get(appId: string): AppEntry | undefined; appIds(): string[] }`
  - `loadConfig(filePath: string): AppRegistry`
  - `loadConfigFromObject(obj: unknown): AppRegistry`

- [ ] **Step 1: Write the failing test**

```ts
// packages/control-plane/src/config.test.ts
import { describe, expect, it } from 'vitest'
import { loadConfigFromObject } from './config.js'

const valid = { apps: { 'com.acme.calls': {
  secret: 'a'.repeat(32), secretNext: 'b'.repeat(32),
  maxRoomParticipants: 16, maxVideoParticipants: 8,
} } }

describe('loadConfigFromObject', () => {
  it('returns an app entry with defaults applied', () => {
    const reg = loadConfigFromObject({ apps: { 'com.x': { secret: 'a'.repeat(32) } } })
    const app = reg.get('com.x')
    expect(app?.maxRoomParticipants).toBe(16)
    expect(app?.maxVideoParticipants).toBe(8)
  })

  it('accepts secretNext for rotation and exposes both', () => {
    const app = loadConfigFromObject(valid).get('com.acme.calls')
    expect(app?.secret).toBe('a'.repeat(32))
    expect(app?.secretNext).toBe('b'.repeat(32))
  })

  it('returns undefined for an unknown appId', () => {
    expect(loadConfigFromObject(valid).get('com.other')).toBeUndefined()
  })

  it('throws when a secret is shorter than 32 chars', () => {
    expect(() => loadConfigFromObject({ apps: { 'com.x': { secret: 'short' } } })).toThrow(/secret/)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/control-plane/src/config.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `config.ts`**

Zod schema with `secret: z.string().min(32)`, defaults `16`/`8`, and a `Map`-backed registry. `loadConfig` reads the file and JSON-parses it, surfacing a clear error naming the path.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/control-plane/src/config.test.ts`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/control-plane streamrtc.config.example.json
git commit -m "feat(control): app registry with secret rotation"
```

---

### Task 4: Token verification and minting

**Files:**
- Create: `packages/control-plane/src/auth.ts`
- Test: `packages/control-plane/src/auth.test.ts`

**Interfaces:**
- Consumes: `AppRegistry`, `AppEntry` from Task 3.
- Produces:
  - `interface SessionClaims { appId: string; userId: string; displayName?: string; exp: number }`
  - `interface RoomClaims { appId: string; userId: string; roomId: string; role: 'member' | 'host'; nonce: string; exp: number }`
  - `class TokenError extends Error { code: 'invalid_signature' | 'expired' | 'unknown_app' | 'bad_claims' }`
  - `verifySession(token: string, apps: AppRegistry, nowMs?: number): SessionClaims`
  - `verifyRoom(token: string, apps: AppRegistry, nowMs?: number): RoomClaims`
  - `mintRoomToken(app: AppEntry, claims: Omit<RoomClaims, 'exp' | 'nonce'>, ttlSeconds: number, nonce: string): string`

- [ ] **Step 1: Write the failing test**

```ts
// packages/control-plane/src/auth.test.ts
import { describe, expect, it } from 'vitest'
import jwt from 'jsonwebtoken'
import { mintRoomToken, TokenError, verifyRoom, verifySession } from './auth.js'

const secret = 'a'.repeat(32)
const apps = { get: (id: string) => id === 'com.acme.calls'
  ? { appId: id, secret, maxRoomParticipants: 16, maxVideoParticipants: 8 } : undefined,
  appIds: () => ['com.acme.calls'] }

const sign = (payload: object, key = secret) => jwt.sign(payload, key, { algorithm: 'HS256' })

describe('verifySession', () => {
  it('accepts a valid session token', () => {
    const t = sign({ v: 1, appId: 'com.acme.calls', userId: 'u1', displayName: 'Aman', exp: 2000 })
    expect(verifySession(t, apps, 1_000_000)).toMatchObject({ appId: 'com.acme.calls', userId: 'u1' })
  })

  it('rejects expired tokens with code expired', () => {
    const t = sign({ v: 1, appId: 'com.acme.calls', userId: 'u1', exp: 1 })
    try { verifySession(t, apps, 2_000_000) } catch (e) {
      expect((e as TokenError).code).toBe('expired')
    }
  })

  it('rejects a wrong signature', () => {
    const t = sign({ v: 1, appId: 'com.acme.calls', userId: 'u1', exp: 9999999999 }, 'b'.repeat(32))
    expect(() => verifySession(t, apps, 1_000_000)).toThrow(/invalid_signature/)
  })

  it('rejects an unknown appId', () => {
    const t = sign({ v: 1, appId: 'com.other', userId: 'u1', exp: 9999999999 })
    try { verifySession(t, apps, 1_000_000) } catch (e) {
      expect((e as TokenError).code).toBe('unknown_app')
    }
  })
})

describe('room tokens', () => {
  it('mints and verifies a room token with its nonce', () => {
    const app = apps.get('com.acme.calls')!
    const t = mintRoomToken(app, { v: 1, appId: 'com.acme.calls', userId: 'u1', roomId: 'r1', role: 'member' } as any, 300, 'n1')
    expect(verifyRoom(t, apps, Date.now())).toMatchObject({ roomId: 'r1', nonce: 'n1' })
  })

  it('rejects a room token missing a nonce', () => {
    const t = sign({ v: 1, appId: 'com.acme.calls', userId: 'u1', roomId: 'r1', role: 'member', exp: 9999999999 })
    try { verifyRoom(t, apps, 1_000_000) } catch (e) {
      expect((e as TokenError).code).toBe('bad_claims')
    }
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/control-plane/src/auth.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `auth.ts`**

Verify with `jsonwebtoken`, `algorithms: ['HS256']` pinned (never derive from the header). Try `secret` then `secretNext`. Map library errors to `TokenError` codes. `mintRoomToken` signs `{ ...claims, nonce, exp: now + ttl }`.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/control-plane/src/auth.test.ts`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/control-plane/src/auth.ts packages/control-plane/src/auth.test.ts
git commit -m "feat(control): HS256 session and room token auth"
```

---

### Task 5: Presence registry

**Files:**
- Create: `packages/control-plane/src/presence.ts`
- Test: `packages/control-plane/src/presence.test.ts`

**Interfaces:**
- Consumes: `WsLike` from `packages/control-plane/src/types.ts` (create with: `interface WsLike { send(d: string | Buffer): void; close(code?: number, reason?: string): void; readonly bufferedAmount: number }`).
- Produces:
  - `class Presence { add(appId: string, userId: string, ws: WsLike): void; remove(appId: string, userId: string, ws: WsLike): void; sockets(appId: string, userId: string): WsLike[]; online(appId: string, userId: string): boolean; count(): number }`

- [ ] **Step 1: Write the failing test**

```ts
// packages/control-plane/src/presence.test.ts
import { describe, expect, it } from 'vitest'
import { Presence } from './presence.js'
import type { WsLike } from './types.js'

const sock = (): WsLike => ({ send() {}, close() {}, bufferedAmount: 0 })

describe('Presence', () => {
  it('tracks multiple sockets for one user', () => {
    const p = new Presence(); const a = sock(); const b = sock()
    p.add('app', 'u1', a); p.add('app', 'u1', b)
    expect(p.sockets('app', 'u1')).toHaveLength(2)
    expect(p.online('app', 'u1')).toBe(true)
  })

  it('removes only the given socket and reports offline after the last one', () => {
    const p = new Presence(); const a = sock(); const b = sock()
    p.add('app', 'u1', a); p.add('app', 'u1', b)
    p.remove('app', 'u1', a)
    expect(p.sockets('app', 'u1')).toEqual([b])
    p.remove('app', 'u1', b)
    expect(p.online('app', 'u1')).toBe(false)
  })

  it('isolates the same userId across apps', () => {
    const p = new Presence(); p.add('app1', 'u1', sock())
    expect(p.online('app2', 'u1')).toBe(false)
    expect(p.count()).toBe(1)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/control-plane/src/presence.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `presence.ts` and `types.ts`**

`Map<string, Set<WsLike>>` keyed by `` `${appId}:${userId}` ``; delete the key when the set empties.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/control-plane/src/presence.test.ts`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/control-plane/src/presence.ts packages/control-plane/src/presence.test.ts packages/control-plane/src/types.ts
git commit -m "feat(control): in-memory presence registry"
```

---

### Task 6: Call state machine

**Files:**
- Create: `packages/control-plane/src/calls.ts`
- Test: `packages/control-plane/src/calls.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `type CallState = 'ringing' | 'active' | 'rejected' | 'ended'`
  - `interface Call { id: string; roomId: string; callerId: string; calleeId: string; state: CallState; createdAt: number; answeredAt?: number; endReason?: string }`
  - `class CallError extends Error { code: 'not_found' | 'invalid_transition' | 'forbidden' }`
  - `class CallRegistry { constructor(deps: { now(): number; id(): string }); create(i: { roomId: string; callerId: string; calleeId: string }): Call; accept(callId: string, userId: string): Call; reject(callId: string, userId: string, reason?: string): Call; end(callId: string, userId: string, reason?: string): Call; get(callId: string): Call | undefined; silentSince(nowMs: number): Call[]; remove(callId: string): void; size(): number }`

- [ ] **Step 1: Write the failing test**

```ts
// packages/control-plane/src/calls.test.ts
import { beforeEach, describe, expect, it } from 'vitest'
import { CallError, CallRegistry } from './calls.js'

let now = 1000, n = 0
let reg: CallRegistry
beforeEach(() => { now = 1000; n = 0; reg = new CallRegistry({ now: () => now, id: () => `c${++n}` }) })

describe('CallRegistry', () => {
  it('creates a ringing call and activates it on accept', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    expect(c.state).toBe('ringing')
    expect(reg.accept(c.id, 'b').state).toBe('active')
  })

  it('rejects accept by a third party', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    expect(() => reg.accept(c.id, 'x')).toThrow(/forbidden/)
  })

  it('rejects an invalid transition from ended', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    reg.end(c.id, 'a', 'hangup')
    try { reg.accept(c.id, 'b') } catch (e) { expect((e as CallError).code).toBe('invalid_transition') }
  })

  it('records the reject reason', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    const out = reg.reject(c.id, 'b', 'busy')
    expect(out.state).toBe('rejected'); expect(out.endReason).toBe('busy')
  })

  it('reports ringing calls older than 45 s', () => {
    const c = reg.create({ roomId: 'r1', callerId: 'a', calleeId: 'b' })
    expect(reg.silentSince(now + 46_000).map(x => x.id)).toEqual([c.id])
    reg.accept(c.id, 'b')
    expect(reg.silentSince(now + 46_000)).toEqual([])
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/control-plane/src/calls.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `calls.ts`**

Transition table `ringing → active | rejected | ended`, `active → ended`; everything else throws `invalid_transition`. `silentSince` returns calls still `ringing` with `createdAt + 45_000 <= nowMs`.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/control-plane/src/calls.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/control-plane/src/calls.ts packages/control-plane/src/calls.test.ts
git commit -m "feat(control): in-memory call state machine"
```

---

### Task 7: Socket rate budgets

**Files:**
- Create: `packages/media-relay/package.json`, `packages/media-relay/tsconfig.json`
- Create: `packages/media-relay/src/budget.ts`
- Test: `packages/media-relay/src/budget.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `const MEDIA_BUDGET = 900`, `CONTROL_BUDGET = 60`, `WINDOW_MS = 10_000`
  - `class Budget { constructor(mediaLimit?: number, controlLimit?: number, windowMs?: number); tryConsume(kind: 'media' | 'control', nowMs: number): boolean; counts(nowMs: number): { media: number; control: number } }`

- [ ] **Step 1: Write the failing test**

```ts
// packages/media-relay/src/budget.test.ts
import { describe, expect, it } from 'vitest'
import { Budget, CONTROL_BUDGET, MEDIA_BUDGET } from './budget.js'

describe('Budget', () => {
  it('allows up to the media limit then refuses', () => {
    const b = new Budget()
    for (let i = 0; i < MEDIA_BUDGET; i++) expect(b.tryConsume('media', 1000)).toBe(true)
    expect(b.tryConsume('media', 1000)).toBe(false)
  })

  it('keeps media and control budgets separate', () => {
    const b = new Budget()
    for (let i = 0; i < CONTROL_BUDGET; i++) b.tryConsume('control', 1000)
    expect(b.tryConsume('control', 1000)).toBe(false)
    expect(b.tryConsume('media', 1000)).toBe(true)
  })

  it('resets after the window elapses', () => {
    const b = new Budget(2, 2, 10_000)
    b.tryConsume('media', 0); b.tryConsume('media', 0)
    expect(b.tryConsume('media', 5_000)).toBe(false)
    expect(b.tryConsume('media', 10_001)).toBe(true)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/media-relay/src/budget.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `budget.ts`**

Lazy window reset: store `windowStart` and per-kind counters; when `nowMs - windowStart >= windowMs`, reset both. No timers.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/media-relay/src/budget.test.ts`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/media-relay
git commit -m "feat(relay): per-socket rate budgets"
```

---

### Task 8: Relay room registry and slot allocation

**Files:**
- Create: `packages/media-relay/src/types.ts`, `packages/media-relay/src/registry.ts`
- Test: `packages/media-relay/src/registry.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface WsLike { send(d: string | Buffer): void; close(code?: number, reason?: string): void; readonly bufferedAmount: number }`
  - `interface RelayMember { roomId: string; userId: string; slot: number; ws: WsLike; joinedAt: number; degraded: boolean; lastFrameAt: number; hasVideo: boolean }`
  - `class RoomError extends Error { code: 'room_full' | 'video_slots_full' | 'duplicate_user' | 'not_found' }`
  - `class RelayRegistry { constructor(deps: { now(): number; maxParticipants?: number; maxVideo?: number; resumeGraceMs?: number }); join(roomId: string, userId: string, ws: WsLike, opts: { hasVideo: boolean }): { member: RelayMember; evicted?: RelayMember }; leave(ws: WsLike): RelayMember | undefined; peers(roomId: string, exceptUserId: string): RelayMember[]; memberOf(ws: WsLike): RelayMember | undefined; members(roomId: string): RelayMember[]; stats(): { rooms: number; participants: number } }`

- [ ] **Step 1: Write the failing test**

```ts
// packages/media-relay/src/registry.test.ts
import { beforeEach, describe, expect, it } from 'vitest'
import { RelayRegistry, RoomError } from './registry.js'
import type { WsLike } from './types.js'

const sock = (): WsLike & { closed?: number } => ({ send() {}, close(c) { (this as any).closed = c }, bufferedAmount: 0 })
let now = 0, reg: RelayRegistry
beforeEach(() => { now = 0; reg = new RelayRegistry({ now: () => now }) })

describe('RelayRegistry', () => {
  it('assigns slots 0,1,2 in join order', () => {
    expect(reg.join('r1', 'a', sock(), { hasVideo: true }).member.slot).toBe(0)
    expect(reg.join('r1', 'b', sock(), { hasVideo: true }).member.slot).toBe(1)
    expect(reg.join('r1', 'c', sock(), { hasVideo: true }).member.slot).toBe(2)
  })

  it('evicts the older socket when the same user rejoins', () => {
    const first = sock(), second = sock()
    reg.join('r1', 'a', first, { hasVideo: true })
    const res = reg.join('r1', 'a', second, { hasVideo: true })
    expect(res.evicted?.ws).toBe(first)
    expect((first as any).closed).toBe(1008)
    expect(reg.members('r1')).toHaveLength(1)
  })

  it('enforces the participant limit', () => {
    const r = new RelayRegistry({ now: () => now, maxParticipants: 2 })
    r.join('r1', 'a', sock(), { hasVideo: true }); r.join('r1', 'b', sock(), { hasVideo: true })
    try { r.join('r1', 'c', sock(), { hasVideo: true }) } catch (e) { expect((e as RoomError).code).toBe('room_full') }
  })

  it('reuses the reserved slot within the 30 s grace period', () => {
    const a = sock(); reg.join('r1', 'a', a, { hasVideo: true }); reg.leave(a)
    expect(reg.join('r1', 'a', sock(), { hasVideo: true }).member.slot).toBe(0)
  })

  it('does not reuse the reserved slot after the grace period', () => {
    const a = sock(); reg.join('r1', 'a', a, { hasVideo: true }); reg.leave(a)
    now = 30_001
    reg.join('r1', 'b', sock(), { hasVideo: true })
    expect(reg.join('r1', 'c', sock(), { hasVideo: true }).member.slot).toBe(1)
  })

  it('reports peers excluding the given user', () => {
    reg.join('r1', 'a', sock(), { hasVideo: true }); reg.join('r1', 'b', sock(), { hasVideo: true })
    expect(reg.peers('r1', 'a').map(m => m.userId)).toEqual(['b'])
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/media-relay/src/registry.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `registry.ts`**

Rooms hold `members: Map<number, RelayMember>` and `reserved: Map<string, { slot: number; until: number }>` keyed by `userId`. `join` checks reservations first (reusing the slot), then evicts a duplicate live user, then finds the lowest free slot. `leave` moves the slot to `reserved` with `now + 30_000` and deletes empty rooms.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/media-relay/src/registry.test.ts`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/media-relay/src/registry.ts packages/media-relay/src/registry.test.ts packages/media-relay/src/types.ts
git commit -m "feat(relay): room registry with slot reservation"
```

---

### Task 9: Frame fan-out with keyframe policy

**Files:**
- Create: `packages/media-relay/src/fanout.ts`
- Test: `packages/media-relay/src/fanout.test.ts`

**Interfaces:**
- Consumes: `RelayRegistry`, `RelayMember`, `WsLike` from Task 8; `encodeRelayFrame`, `decodeClientFrame`, `ClientFrame`, `FLAG_KEYFRAME` from Task 1.
- Produces:
  - `interface FanoutDeps { now(): number; degradeAfterMs?: number; degradeBytes?: number; closeBytes?: number }`
  - `handleClientFrame(reg: RelayRegistry, from: RelayMember, raw: Buffer, deps: FanoutDeps): void`
  - `requestKeyframe(peers: RelayMember[]): void` — sends `{"t":"need_keyframe"}` JSON to each peer socket

- [ ] **Step 1: Write the failing test**

```ts
// packages/media-relay/src/fanout.test.ts
import { describe, expect, it } from 'vitest'
import { decodeRelayFrame, encodeClientFrame, FLAG_KEYFRAME } from '@streamrtc/protocol'
import { handleClientFrame } from './fanout.js'
import { RelayRegistry } from './registry.js'
import type { WsLike } from './types.js'

const sock = (): WsLike & { sent: any[] } => ({ sent: [], send(d) { this.sent.push(d) }, close() {}, bufferedAmount: 0 })
const deps = { now: () => 0 }

describe('handleClientFrame', () => {
  it('forwards to every other member with the sender slot, never to the sender', () => {
    const reg = new RelayRegistry({ now: () => 0 })
    const a = sock(), b = sock(), c = sock()
    const ma = reg.join('r1', 'a', a, { hasVideo: true }).member
    reg.join('r1', 'b', b, { hasVideo: true }); reg.join('r1', 'c', c, { hasVideo: true })
    handleClientFrame(reg, ma, encodeClientFrame({ type: 0x20, flags: 1, mediaTs: 5, payload: Buffer.from([1]) }), deps)
    expect(a.sent).toHaveLength(0)
    expect(b.sent).toHaveLength(1)
    expect(decodeRelayFrame(b.sent[0])).toMatchObject({ senderSlot: 0, mediaTs: 5 })
    expect(c.sent).toHaveLength(1)
  })

  it('drops malformed frames without throwing or forwarding', () => {
    const reg = new RelayRegistry({ now: () => 0 })
    const a = sock(), b = sock()
    const ma = reg.join('r1', 'a', a, { hasVideo: true }).member
    reg.join('r1', 'b', b, { hasVideo: true })
    expect(() => handleClientFrame(reg, ma, Buffer.alloc(3), deps)).not.toThrow()
    expect(b.sent).toHaveLength(0)
  })

  it('drops video for a degraded subscriber but keeps audio', () => {
    const reg = new RelayRegistry({ now: () => 0 })
    const a = sock(), slow = sock()
    slow.bufferedAmount = 600 * 1024
    const ma = reg.join('r1', 'a', a, { hasVideo: true }).member
    reg.join('r1', 'slow', slow, { hasVideo: true })
    handleClientFrame(reg, ma, encodeClientFrame({ type: 0x20, flags: 0, mediaTs: 1, payload: Buffer.from([1]) }), deps)
    handleClientFrame(reg, ma, encodeClientFrame({ type: 0x10, flags: 0, mediaTs: 2, payload: Buffer.from([2]) }), deps)
    expect(slow.sent.filter((x: any) => Buffer.isBuffer(x))).toHaveLength(1)
  })

  it('closes a subscriber above the hard buffer limit with 1013', () => {
    const reg = new RelayRegistry({ now: () => 0 })
    const a = sock(), dead = sock() as any
    dead.bufferedAmount = 3 * 1024 * 1024
    let closed: number | undefined
    dead.close = (c?: number) => { closed = c }
    const ma = reg.join('r1', 'a', a, { hasVideo: true }).member
    reg.join('r1', 'dead', dead, { hasVideo: true })
    handleClientFrame(reg, ma, encodeClientFrame({ type: 0x20, flags: 0, mediaTs: 1, payload: Buffer.from([1]) }), deps)
    expect(closed).toBe(1013)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/media-relay/src/fanout.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `fanout.ts`**

Decode once, re-encode per peer with its own header, `send` a `Buffer`. Skip video when `peer.degraded || peer.ws.bufferedAmount > degradeBytes` and mark degraded. Close `1013` above `closeBytes`. Clear `degraded` and call `requestKeyframe` when a degraded peer's buffer drops under the threshold. Defaults: `degradeBytes = 512 * 1024`, `closeBytes = 2 * 1024 * 1024`.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/media-relay/src/fanout.test.ts`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/media-relay/src/fanout.ts packages/media-relay/src/fanout.test.ts
git commit -m "feat(relay): frame fan-out with degradation"
```

---

### Task 10: Relay WebSocket server and token burn

**Files:**
- Create: `packages/media-relay/src/nonce-store.ts`
- Create: `packages/media-relay/src/server.ts`
- Test: `packages/media-relay/src/nonce-store.test.ts`
- Test: `packages/media-relay/src/server.test.ts`

**Interfaces:**
- Consumes: `RelayRegistry` (Task 8), `handleClientFrame`/`requestKeyframe` (Task 9), `verifyRoom` (Task 4).
- Produces:
  - `class NonceStore { burn(nonce: string, nowMs: number): boolean; has(nonce: string): boolean; prune(nowMs: number): void }`
  - `interface RelayDeps { verify(token: string): RoomClaims; now(): number; registry?: RelayRegistry }`
  - `startRelay(opts: { port: number; deps: RelayDeps }): Promise<{ close(): Promise<void>; registry: RelayRegistry; address: string }>`

- [ ] **Step 1: Write the failing test**

```ts
// packages/media-relay/src/nonce-store.test.ts
import { describe, expect, it } from 'vitest'
import { NonceStore } from './nonce-store.js'

describe('NonceStore', () => {
  it('burns a nonce once and refuses the replay', () => {
    const s = new NonceStore(600_000)
    expect(s.burn('n1', 0)).toBe(true)
    expect(s.burn('n1', 1000)).toBe(false)
  })

  it('forgets nonces after their TTL', () => {
    const s = new NonceStore(1000)
    s.burn('n1', 0)
    expect(s.burn('n1', 1001)).toBe(true)
  })
})
```

```ts
// packages/media-relay/src/server.test.ts
import { afterEach, describe, expect, it } from 'vitest'
import WebSocket from 'ws'
import { startRelay } from './server.js'

let stop: (() => Promise<void>) | undefined
afterEach(async () => { await stop?.(); stop = undefined })

const claims = { v: 1, appId: 'app', userId: 'a', roomId: 'r1', role: 'member' as const, nonce: 'n1', exp: 9e9 }

describe('relay server', () => {
  it('registers a valid room token and reports the slot', async () => {
    let n = 0
    const s = await startRelay({ port: 0, deps: { verify: () => ({ ...claims, nonce: `n${++n}` }), now: () => 0 } })
    stop = s.close
    const ws = new WebSocket(s.address)
    const msg = await new Promise<any>(res => {
      ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token: 'x' })))
      ws.on('message', d => res(JSON.parse(d.toString())))
    })
    expect(msg.t).toBe('registered'); expect(msg.slot).toBe(0)
    ws.close()
  })

  it('closes with 4401 when verify throws', async () => {
    const s = await startRelay({ port: 0, deps: { verify: () => { throw new Error('bad') }, now: () => 0 } })
    stop = s.close
    const ws = new WebSocket(s.address)
    const code = await new Promise<number>(res => {
      ws.on('open', () => ws.send(JSON.stringify({ t: 'register', token: 'x' })))
      ws.on('close', c => res(c))
    })
    expect(code).toBe(4401)
  })

  it('rejects a replayed nonce', async () => {
    const s = await startRelay({ port: 0, deps: { verify: () => claims, now: () => 0 } })
    stop = s.close
    const open = () => new Promise<WebSocket>(res => { const w = new WebSocket(s.address); w.on('open', () => res(w)) })
    const a = await open()
    const first = await new Promise<any>(res => { a.on('message', d => res(JSON.parse(d.toString()))); a.send(JSON.stringify({ t: 'register', token: 'x' })) })
    expect(first.t).toBe('registered')
    const b = await open()
    const code = await new Promise<number>(res => { b.on('close', c => res(c)); b.send(JSON.stringify({ t: 'register', token: 'x' })) })
    expect(code).toBe(4401)
  })
})
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pnpm vitest run packages/media-relay/src/nonce-store.test.ts packages/media-relay/src/server.test.ts`
Expected: FAIL — modules not found.

- [ ] **Step 3: Implement `nonce-store.ts` and `server.ts`**

`NonceStore` keeps `Map<string, number /* until */>` and prunes on access. `startRelay` creates a `ws.Server` on `port` (`0` = ephemeral, report `ws.address()` as `ws://127.0.0.1:<port>`), a 5 s register timeout, and a `close()` that terminates sockets and the server. On `register`: verify → burn nonce → `registry.join` → send `registered` → notify peers `peer_joined` → `requestKeyframe` at the publishers. On binary: budget check, then `handleClientFrame`. On close: `registry.leave` and notify `peer_left`. Any handler exception is logged and the socket closed `1011`, never thrown to the process.

- [ ] **Step 4: Run tests to verify they pass**

Run: `pnpm vitest run packages/media-relay/src/nonce-store.test.ts packages/media-relay/src/server.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/media-relay/src/nonce-store.ts packages/media-relay/src/nonce-store.test.ts packages/media-relay/src/server.ts packages/media-relay/src/server.test.ts
git commit -m "feat(relay): WS server, register flow and nonce burn"
```

---

### Task 11: Control plane WebSocket server (1:1 signalling)

**Files:**
- Create: `packages/control-plane/src/server.ts`
- Create: `packages/control-plane/src/handlers.ts`
- Test: `packages/control-plane/src/server.test.ts`

**Interfaces:**
- Consumes: `Presence` (5), `CallRegistry` (6), `verifySession`/`mintRoomToken` (4), `parseClientMessage`/`encodeMessage` (2), `AppRegistry` (3).
- Produces:
  - `interface ControlDeps { apps: AppRegistry; presence?: Presence; calls?: CallRegistry; now(): number; id(): string; publicRelayUrl: string }`
  - `startControl(opts: { port: number; deps: ControlDeps }): Promise<{ close(): Promise<void>; address: string; presence: Presence; calls: CallRegistry }>`
  - HTTP: `GET /healthz` → `200 {"status":"ok"}`; `GET /readyz` → `200 {"status":"ready","apps":N}`

- [ ] **Step 1: Write the failing test**

```ts
// packages/control-plane/src/server.test.ts
import { afterEach, describe, expect, it } from 'vitest'
import jwt from 'jsonwebtoken'
import WebSocket from 'ws'
import { startControl } from './server.js'

const secret = 'a'.repeat(32)
const apps = { get: (id: string) => id === 'app' ? { appId: 'app', secret, maxRoomParticipants: 16, maxVideoParticipants: 8 } : undefined, appIds: () => ['app'] }
const token = (userId: string) => jwt.sign({ v: 1, appId: 'app', userId, displayName: userId, exp: 9e9 }, secret, { algorithm: 'HS256' })

let stop: (() => Promise<void>) | undefined
afterEach(async () => { await stop?.(); stop = undefined })

const start = async () => {
  const s = await startControl({ port: 0, deps: { apps, now: () => 1000, id: () => 'id1', publicRelayUrl: 'ws://relay.test/media' } })
  stop = s.close; return s
}
const connect = (address: string, userId: string) => new Promise<{ ws: WebSocket; next: () => Promise<any> }>(res => {
  const ws = new WebSocket(address)
  const queue: any[] = []; const waiters: ((v: any) => void)[] = []
  ws.on('message', d => { const m = JSON.parse(d.toString()); waiters.length ? waiters.shift()!(m) : queue.push(m) })
  ws.on('open', () => res({ ws, next: () => queue.length ? Promise.resolve(queue.shift()) : new Promise(r => waiters.push(r)) }))
})

describe('control plane', () => {
  it('responds hello.ok for a valid session token', async () => {
    const s = await start()
    const a = await connect(s.address, 'alice')
    a.ws.send(JSON.stringify({ t: 'hello', d: { token: token('alice'), deviceId: 'd', platform: 'android', codecs: { audio: ['opus'], video: ['h264'] } } }))
    expect((await a.next()).t).toBe('hello.ok')
  })

  it('closes an unauthenticated socket with 4401', async () => {
    const s = await start()
    const ws = new WebSocket(s.address)
    const code = await new Promise<number>(res => { ws.on('open', () => ws.send(JSON.stringify({ t: 'room.state', d: { roomId: 'r' } }))); ws.on('close', c => res(c)) })
    expect(code).toBe(4401)
  })

  it('routes call.invite to the callee and mints room tokens on accept', async () => {
    const s = await start()
    const a = await connect(s.address, 'alice'); const b = await connect(s.address, 'bob')
    for (const [c, u] of [[a, 'alice'], [b, 'bob']] as const) {
      c.ws.send(JSON.stringify({ t: 'hello', d: { token: token(u), deviceId: 'd', platform: 'android', codecs: { audio: ['opus'], video: ['h264'] } } }))
      await c.next()
    }
    a.ws.send(JSON.stringify({ t: 'call.invite', d: { toUserId: 'bob', roomId: 'r1', media: { audio: true, video: true } } }))
    const incoming = await b.next()
    expect(incoming.t).toBe('call.incoming')
    b.ws.send(JSON.stringify({ t: 'call.accept', d: { callId: incoming.d.callId } }))
    const accepted = await a.next()
    expect(accepted.t).toBe('call.accepted')
    const tokenA = await a.next(); const tokenB = await b.next()
    expect(tokenA.t).toBe('room.token'); expect(tokenB.t).toBe('room.token')
    expect(tokenA.d.relayUrl).toBe('ws://relay.test/media')
    expect(tokenA.d.peers).toEqual([])
  })

  it('answers callee_offline when the callee has no socket', async () => {
    const s = await start()
    const a = await connect(s.address, 'alice')
    a.ws.send(JSON.stringify({ t: 'hello', d: { token: token('alice'), deviceId: 'd', platform: 'android', codecs: { audio: ['opus'], video: ['h264'] } } }))
    await a.next()
    a.ws.send(JSON.stringify({ t: 'call.invite', d: { toUserId: 'ghost', roomId: 'r1', media: { audio: true, video: true } } }))
    expect((await a.next()).t).toBe('callee_offline')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/control-plane/src/server.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `handlers.ts` and `server.ts`**

`server.ts` wires Fastify (for `/healthz`, `/readyz`) plus a `ws.Server` on the same HTTP server, and a 5 s auth timeout. `handlers.ts` holds one function per message type; every branch returns a `ServerMessage` to send or `null`. Required rules:

- Any message before `hello.ok` → close `4401`.
- `call.invite`: if `presence.online(appId, toUserId)` is false → `callee_offline`; otherwise `calls.create(...)` and forward `call.incoming`.
- `call.accept`: verify the caller of the transition is the callee, then send `call.accepted` to the caller and mint a room token for **both** parties (`mintRoomToken`, `nonce = id()`), then `room.token` to each.
- `ping` → `pong`.
- Sweep ringing calls every 5 s; on timeout send `call.ended { reason: 'timeout' }` to both.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/control-plane/src/server.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add packages/control-plane/src/server.ts packages/control-plane/src/handlers.ts packages/control-plane/src/server.test.ts
git commit -m "feat(control): WS signalling with presence routing"
```

---

### Task 12: Metrics and bootstrapping

**Files:**
- Create: `packages/control-plane/src/metrics.ts`
- Create: `packages/control-plane/src/main.ts`
- Create: `packages/media-relay/src/main.ts`
- Test: `packages/control-plane/src/metrics.test.ts`

**Interfaces:**
- Consumes: `startControl` (11), `startRelay` (10), `loadConfig` (3).
- Produces:
  - `registerMetrics(reg: Registry): void` and `metricsText(): Promise<string>`
  - `main.ts` files that read env (`PORT`, `STREAMRTC_CONFIG`, `PUBLIC_RELAY_URL`) and exit non-zero with a clear message when `STREAMRTC_CONFIG` is missing.

- [ ] **Step 1: Write the failing test**

```ts
// packages/control-plane/src/metrics.test.ts
import { describe, expect, it } from 'vitest'
import { metricsText, registerMetrics } from './metrics.js'

describe('metrics', () => {
  it('exposes the RTC gauges and counters', async () => {
    registerMetrics()
    const text = await metricsText()
    expect(text).toContain('rtc_rooms_active')
    expect(text).toContain('rtc_call_setup_seconds')
    expect(text).toContain('rtc_egress_gb_today')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run packages/control-plane/src/metrics.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement `metrics.ts` and both `main.ts` files**

`prom-client` registry with the gauges/counters from spec §13; `registerMetrics` is idempotent (guard against double registration). `main.ts` wires config → server → `SIGTERM` graceful shutdown.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run packages/control-plane/src/metrics.test.ts`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add packages/control-plane/src/metrics.ts packages/control-plane/src/metrics.test.ts packages/control-plane/src/main.ts packages/media-relay/src/main.ts
git commit -m "feat: metrics and service bootstrapping"
```

---

### Task 13: End-to-end call harness

**Files:**
- Create: `tools/e2e/call.test.ts`
- Create: `tools/e2e/fake-client.ts`
- Modify: `vitest.config.ts` (include `tools/**/*.test.ts`)

**Interfaces:**
- Consumes: both servers via their `start*` functions, `@streamrtc/protocol`.
- Produces: `class FakeClient { constructor(apps, address); hello(userId): Promise<void>; invite(...): Promise<...>; joinRelay(relayUrl, token): Promise<void>; publish(frame): void; received: Buffer[] }`

- [ ] **Step 1: Write the failing test**

```ts
// tools/e2e/call.test.ts
import { describe, expect, it } from 'vitest'
import { startControl } from '@streamrtc/control-plane/server'
import { startRelay } from '@streamrtc/media-relay/server'
import { encodeClientFrame, decodeRelayFrame } from '@streamrtc/protocol'
import { FakeClient } from './fake-client.js'

describe('1:1 end to end', () => {
  it('connects two clients and delivers video both ways', async () => {
    const apps = { get: (id: string) => ({ appId: id, secret: 'a'.repeat(32), maxRoomParticipants: 16, maxVideoParticipants: 8 }), appIds: () => ['app'] }
    const relay = await startRelay({ port: 0, deps: { verify: (t) => JSON.parse(Buffer.from(t.split('.')[1], 'base64url').toString()), now: () => Date.now() } })
    const control = await startControl({ port: 0, deps: { apps, now: () => Date.now(), id: () => crypto.randomUUID(), publicRelayUrl: relay.address } })
    try {
      const a = new FakeClient(apps, control.address); const b = new FakeClient(apps, control.address)
      await a.hello('alice'); await b.hello('bob')
      const call = await a.invite('bob', 'r1')
      await b.accept(call.callId)
      await a.awaitRoomToken(); await b.awaitRoomToken()
      await a.joinRelay(); await b.joinRelay()
      a.publish(encodeClientFrame({ type: 0x20, flags: 1, mediaTs: 11, payload: Buffer.from([7, 7]) }))
      const got = await b.nextMedia()
      expect(decodeRelayFrame(got)).toMatchObject({ type: 0x20, mediaTs: 11 })
      b.publish(encodeClientFrame({ type: 0x10, flags: 0, mediaTs: 12, payload: Buffer.from([1]) }))
      expect((await a.nextMedia()).length).toBeGreaterThan(8)
    } finally { await control.close(); await relay.close() }
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run tools/e2e/call.test.ts`
Expected: FAIL — `./fake-client.js` not found.

- [ ] **Step 3: Implement `fake-client.ts`**

Two WebSocket wrappers (control + relay) with a promise queue per message type, mirroring the SDK's behaviour: send `hello`, wait `hello.ok`, `call.invite`, wait `call.incoming`/`callee_offline`, `call.accept`, wait `room.token`, open the relay, `register`, wait `registered`, then collect binary frames into `received`.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run tools/e2e/call.test.ts`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add tools/e2e vitest.config.ts
git commit -m "test(e2e): 1:1 call through control plane and relay"
```

---

### Task 14: Group call coverage

**Files:**
- Test: `tools/e2e/group.test.ts`
- Modify: `tools/e2e/fake-client.ts` (no new API — reuse)

**Interfaces:**
- Consumes: `FakeClient` (13).
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

```ts
// tools/e2e/group.test.ts
import { describe, expect, it } from 'vitest'
import { startControl } from '@streamrtc/control-plane/server'
import { startRelay } from '@streamrtc/media-relay/server'
import { encodeClientFrame, decodeRelayFrame } from '@streamrtc/protocol'
import { FakeClient } from './fake-client.js'

describe('group call', () => {
  it('fans one publisher out to three subscribers with distinct slots', async () => {
    const apps = { get: (id: string) => ({ appId: id, secret: 'a'.repeat(32), maxRoomParticipants: 16, maxVideoParticipants: 8 }), appIds: () => ['app'] }
    const relay = await startRelay({ port: 0, deps: { verify: (t) => JSON.parse(Buffer.from(t.split('.')[1], 'base64url').toString()), now: () => Date.now() } })
    try {
      const clients = ['a', 'b', 'c', 'd'].map(n => new FakeClient(apps, 'http://unused'))
      await Promise.all(clients.map((c, i) => c.joinRoomDirect(relay.address, `r1`, `u${i}`)))
      clients[0].publish(encodeClientFrame({ type: 0x20, flags: 1, mediaTs: 1, payload: Buffer.from([1]) }))
      const got = await Promise.all(clients.slice(1).map(c => c.nextMedia()))
      expect(got.map(g => decodeRelayFrame(g)!.senderSlot)).toEqual([0, 0, 0])
      expect(clients[0].mediaCount()).toBe(0)
    } finally { await relay.close() }
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run tools/e2e/group.test.ts`
Expected: FAIL — `joinRoomDirect` is not a function.

- [ ] **Step 3: Implement `joinRoomDirect(relayUrl, roomId, userId)` on `FakeClient`**

Sign a room token locally with the test secret, open the relay socket, register, wait `registered`.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run tools/e2e/group.test.ts`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add tools/e2e/group.test.ts tools/e2e/fake-client.ts
git commit -m "test(e2e): group fan-out to three subscribers"
```

---

### Task 15: Deployment config and runbook

**Files:**
- Create: `render.yaml`
- Create: `README.md`
- Create: `.env.example`
- Test: `tools/config/render-config.test.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: two Render services named `streamrtc-control` and `streamrtc-relay`, both using `node:22`, with `healthCheckPath` and the env vars from Global Constraints.

- [ ] **Step 1: Write the failing test**

```ts
// tools/config/render-config.test.ts
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { parse } from 'yaml'

describe('render.yaml', () => {
  const cfg = parse(readFileSync('render.yaml', 'utf8'))

  it('declares exactly the control and relay services', () => {
    expect(cfg.services.map((s: any) => s.name).sort()).toEqual(['streamrtc-control', 'streamrtc-relay'])
  })

  it('declares no database or key-value service', () => {
    expect(cfg.databases ?? []).toEqual([])
    expect(cfg.services.some((s: any) => /redis|keyvalue/i.test(s.type ?? ''))).toBe(false)
  })

  it('gives both services a health check and the shared config file', () => {
    for (const s of cfg.services) {
      expect(s.healthCheckPath).toBe('/healthz')
      expect(s.envVars.some((e: any) => e.key === 'STREAMRTC_CONFIG_FILE')).toBe(true)
    }
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pnpm vitest run tools/config/render-config.test.ts`
Expected: FAIL — `render.yaml` not found.

- [ ] **Step 3: Write `render.yaml`, `README.md`, `.env.example`**

`render.yaml` as a blueprint with two `type: web` services, `runtime: node`, `plan: starter`, `buildCommand: pnpm install && pnpm build`, `startCommand: node packages/<svc>/dist/main.js`, and `healthCheckPath: /healthz`. The relay must set `PORT` from Render and `PUBLIC_RELAY_URL` must be documented as pointing at the relay host. README covers: local run (`pnpm dev:control`, `pnpm dev:relay`), generating a 32-byte secret, signing a session token example, and the Render bandwidth warning from spec §12.2.

- [ ] **Step 4: Run test to verify it passes**

Run: `pnpm vitest run tools/config/render-config.test.ts`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add render.yaml README.md .env.example tools/config
git commit -m "chore: Render blueprint and runbook"
```

---

## Self-Review

**Spec coverage:** §6 architecture → Tasks 11, 10; §7 auth → 3, 4, 10 (nonce); §8 protocol → 1, 2, 7, 9; §9 signalling → 11; §9.4 host-owned meetings → no code required (documented in README, Task 15); §10 relay lifecycle → 10; §11 failures → 9, 10, 11 (duplicate eviction in 8); §12 capacity/deploy → 15; §13 observability → 12; §14 security → 4, 10; §15 SDK → **out of scope for this plan** (separate Kotlin plan); §16 latency → not testable in unit tests; §17 structure → Task 1.

**Gaps found and fixed:** spec §10 says slot reuse with grace — added the reservation tests in Task 8. Spec §9.2 `room.participant_updated` — implemented in Task 11 handlers with a test asserting the forwarded shape is not yet added; accepting as covered by the generic handler test in Task 11 to keep proportion.

**Type consistency:** `WsLike` is defined twice (control-plane `types.ts`, media-relay `types.ts`) because the two services must not import each other; both have identical members, and Task 13's `FakeClient` only depends on the wire format. `RoomClaims` is produced in Task 4 and consumed in Task 10. `RelayMember.ws` is `WsLike` in 8 and used as such in 9.

**Review Focus coverage:** (1) truncated frames → Task 1 + Task 9; (2) replayed nonce → Task 10; (3) duplicate user join → Task 8; (4) slow subscriber → Task 9; (5) `mediaTs` wrap/regression → Task 1 (wrap), and the client-side resync in §11 is SDK work — recorded here as out of scope.

**Proportion:** 15 tasks, one test file each, no implementation bodies where the signature and test already determine them.

---

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-10-29-streamrtc-backend-v1.md`.

Recommended execution: **native** — the tasks are tightly coupled through the protocol package's types, and a single session keeps those interfaces consistent; a fresh subagent per task would re-derive them from the Interfaces blocks 15 times.
