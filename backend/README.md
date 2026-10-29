# StreamRTC Backend

Stateless RTC backend for Android apps: 1:1 calls, group calls and meetings.
Media travels over binary WebSocket as pre-encoded H.264 + PCM16 frames that the
relay forwards without parsing. No database, no WebRTC, no user store.

## Layout

- `packages/protocol` — frame codec and signalling messages (shared)
- `packages/core` — config + JWT auth (shared)
- `packages/control-plane` — Fastify HTTP + WS signalling, presence, call state
- `packages/media-relay` — WS register, room slots, fan-out, backpressure
- `tools/` — mint-dev-token, smoke harness
- `docs/` — specs and plans

## Quick start

```bash
npm install
npm run dev:control   # http://localhost:8080
npm run dev:relay     # ws://localhost:8081
npm test              # vitest
npm run typecheck
```

## Tokens

Mint dev tokens:
```bash
npm run mint -- --app app --user u1 --kind session --ttl 3600 --config ./streamrtc.config.json
npm run mint -- --app app --user u1 --kind room --room r1 --role member --video true --ttl 300 --nonce n1 --config ./streamrtc.config.json
```

## Deploy

Render blueprint: `render.yaml`. Set `PUBLIC_RELAY_URL` on the control plane.
EOF
echo done