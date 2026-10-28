import { decodeClientFrame, encodeRelayFrame, TYPE_H264 } from '@streamrtc/protocol'
import type { RelayRegistry } from './registry.js'
import type { RelayMember } from './types.js'

export interface FanoutDeps {
  now(): number
  degradeAfterMs?: number
  degradeBytes?: number
  closeBytes?: number
}

export const DEGRADE_BYTES = 512 * 1024
export const CLOSE_BYTES = 2 * 1024 * 1024

export function requestKeyframe(peers: RelayMember[]): void {
  const message = JSON.stringify({ t: 'need_keyframe', d: {} })
  for (const peer of peers) {
    try {
      if (peer.hasVideo) peer.ws.send(message)
    } catch {
      // a dead socket is handled by its close handler
    }
  }
}

export function handleClientFrame(
  registry: RelayRegistry,
  from: RelayMember,
  raw: Buffer,
  deps: FanoutDeps,
): void {
  const frame = decodeClientFrame(raw)
  if (!frame) return

  const degradeBytes = deps.degradeBytes ?? DEGRADE_BYTES
  const closeBytes = deps.closeBytes ?? CLOSE_BYTES
  const isVideo = frame.type === TYPE_H264

  from.lastFrameAt = deps.now()

  const recovered: RelayMember[] = []
  for (const peer of registry.peers(from.appId, from.roomId, from.userId)) {
    const buffered = peer.ws.bufferedAmount

    if (buffered > closeBytes) {
      peer.ws.close(1013, 'slow_consumer')
      continue
    }

    if (isVideo) {
      if (peer.degraded || buffered > degradeBytes) {
        peer.degraded = true
        continue
      }
    } else if (peer.degraded && buffered <= degradeBytes) {
      peer.degraded = false
      recovered.push(peer)
    }

    peer.ws.send(
      encodeRelayFrame({
        type: frame.type,
        flags: frame.flags,
        senderSlot: from.slot,
        mediaTs: frame.mediaTs,
        payload: frame.payload,
      }),
    )
  }

  if (recovered.length > 0) requestKeyframe(recovered)
}
