# stream-rtc

A stateless RTC backend + Android SDK: 1:1 calls, group calls and meetings over
binary WebSocket (H.264 + PCM16). No WebRTC, no database — the host app owns
users, meetings and history.

```
Android A ──WSS control──► control-plane ──signalling──► Android B
Android A ══WSS media════► media-relay  ══frames═══════► Android B
```

## Layout

- `backend/` — Node/TypeScript control plane + media relay + shared protocol/core + dev tools.
- `android/`  — Android SDK (`io.streamrtc:rtc`) + README on how to use it.

## Quick start (backend)

```bash
cd backend
npm install
npm run dev:control   # http://localhost:8080
npm run dev:relay     # ws://localhost:8081
npm test
```

## Android SDK

See `android/README.md` for install, tokens, 1:1 & room quickstarts, permissions
and troubleshooting. Build/publish:

```bash
cd android
./gradlew :rtc:publishToMavenLocal
```

## Deploy

Render blueprint: `backend/render.yaml`.
EOF
echo done