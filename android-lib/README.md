# StreamRTC Android SDK (`io.streamrtc:rtc`)

Drop-in Android library that connects to a StreamRTC backend (control plane +
media relay) and runs the full media pipeline (camera, H.264 encode/decode,
render, AEC/NS) over the Home-SPY WebSocket relay. No WebRTC, no database —
the host app owns users, meetings and history.

## Requirements

- `minSdk 24`, `compileSdk 36`
- JDK 17+ (Gradle toolchain)
- Android Gradle Plugin 9.1.0 (built-in Kotlin)
- A running StreamRTC backend (control plane + media relay)

## Install (manual)

### Option A — publish to your local Maven repo (recommended)

```bash
cd android-lib
JAVA_HOME=/path/to/jdk-17 ./gradlew :rtc:publishToMavenLocal
```

Then in your app `settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); mavenLocal() }
}
```
and app module `build.gradle.kts`:
```kotlin
dependencies { implementation("io.streamrtc:rtc:0.1.0") }
```

### Option B — copy the module into your project

1. Copy the whole `android-lib/` folder into your project (rename if you like).
2. `settings.gradle.kts`: `include(":rtc")` with `project(":rtc").projectDir = file("android-lib/rtc")`.
3. App module: `implementation(project(":rtc"))`.
4. Copy version-catalog entries from `android-lib/gradle/libs.versions.toml`.

## Tokens

Backend (or dev tool) mints two HS256 JWTs.

Session token claims: `{ v, appId, userId, displayName?, iat, exp }`
Room token claims: `{ v, appId, userId, roomId, role, nonce, video?, iat, exp }`

- `nonce` must be fresh per join (relay burns it; replay → 4401).
- Room tokens require `roomTokenProvider` in `StreamRtcConfig`.

Dev minting:
```bash
npm run mint -- --app app --user u1 --kind session --ttl 3600 --config ./streamrtc.config.json
npm run mint -- --app app --user u1 --kind room --room r1 --role member --video true --ttl 300 --nonce n1 --config ./streamrtc.config.json
```

## Quickstart — 1:1 call

```kotlin
val rtc = StreamRtc.get(context, StreamRtcConfig(
    controlUrl = "https://your-control-plane",
    tokenProvider = { yourBackend.mintSessionToken() },
    displayName = "Alice",
))
rtc.start()

val call = rtc.calls.dial("bob", CallMedia.audioVideo)
call.end()
rtc.stop()
```

Incoming calls:
```kotlin
rtc.calls.incoming.collect { incoming -> incoming.accept() /* or reject() */ }
```

## Rooms / 4-tile grid

```kotlin
val room = rtc.rooms.join("room-1", JoinOptions(video = true))
room.participants.collect { list -> }
val remote = room.remoteVideo(slot)  // RemoteVideo? (null for unknown slot)
room.leave()
```

## Permissions & foreground service

Manifest:
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

The SDK ships its own FGS (`io.streamrtc.service.CallService`, type camera|microphone) and starts/stops it automatically.

## Lifecycle

- `StreamRtc.get(context, config)` is process-wide.
- `start()` opens control socket; `stop()` closes everything.
- On process death the OS restarts the FGS only if the call was active.

## Video presets

`VideoConfig.Low` (320×180 @15fps, 300kbps), `VideoConfig.Standard` (640×360 @24fps, 700kbps, default), `VideoConfig.High` (1280×720 @30fps, 1.5Mbps). Pass via `StreamRtcConfig(video = ...)`.

## Troubleshooting (close codes)

| Code | Meaning | SDK action |
|---|---|---|
| 4401 | bad/expired/replayed token | refresh + reconnect |
| 4400/4403/4404/4405 | invalid/forbidden/not-found/auth | terminal |
| 4409 | room full / video slots full | terminal (`room_unavailable`) |
| 1008 | duplicate session | terminal (`duplicate_session`) |
| 1013 | slow consumer | step encoder down |
| 1006/1011/1012 | transient | reconnect with backoff |

- `CalleeOffline` — peer not registered.
- Video slot exhaustion — max 4 decoders per device (5th tile stays Idle).
- Audio: PCM16 16 kHz mono, 20 ms (~640 B).

## Limits

| Limit | Value |
|---|---|
| Max participants per room | 16 |
| Max video publishers per room | 8 |
| Max decoders per device | 4 |
| Audio | PCM16 16 kHz mono, 20 ms |

Part of StreamRTC.
EOF
echo done