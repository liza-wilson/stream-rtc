export const MAGIC = 0x53
export const VERSION = 1

export const HEADER_CLIENT = 8
export const HEADER_RELAY = 9

export const FLAG_KEYFRAME = 1
export const FLAG_EOS = 2
export const FLAG_SCREEN = 4

export const TYPE_OPUS = 0x10
export const TYPE_PCM16 = 0x11
export const TYPE_H264 = 0x20

export type MediaType = typeof TYPE_OPUS | typeof TYPE_PCM16 | typeof TYPE_H264

const MEDIA_TYPES: ReadonlySet<number> = new Set([TYPE_OPUS, TYPE_PCM16, TYPE_H264])

const MAX_SLOT = 15

export interface ClientFrame {
  type: MediaType
  flags: number
  mediaTs: number
  payload: Buffer
}

export interface RelayFrame extends ClientFrame {
  senderSlot: number
}

export function encodeClientFrame(frame: ClientFrame): Buffer {
  const buf = Buffer.allocUnsafe(HEADER_CLIENT + frame.payload.length)
  buf[0] = MAGIC
  buf[1] = VERSION
  buf[2] = frame.type
  buf[3] = frame.flags & 0xff
  buf.writeUInt32BE(frame.mediaTs >>> 0, 4)
  frame.payload.copy(buf, HEADER_CLIENT)
  return buf
}

export function decodeClientFrame(buf: Buffer): ClientFrame | null {
  if (buf.length < HEADER_CLIENT) return null
  if (buf[0] !== MAGIC || buf[1] !== VERSION) return null
  const type = buf[2]
  if (type === undefined || !MEDIA_TYPES.has(type)) return null
  return {
    type: type as MediaType,
    flags: buf[3] ?? 0,
    mediaTs: buf.readUInt32BE(4),
    payload: buf.subarray(HEADER_CLIENT),
  }
}

export function encodeRelayFrame(frame: RelayFrame): Buffer {
  const buf = Buffer.allocUnsafe(HEADER_RELAY + frame.payload.length)
  buf[0] = MAGIC
  buf[1] = VERSION
  buf[2] = frame.type
  buf[3] = frame.flags & 0xff
  buf[4] = frame.senderSlot
  buf.writeUInt32BE(frame.mediaTs >>> 0, 5)
  frame.payload.copy(buf, HEADER_RELAY)
  return buf
}

export function decodeRelayFrame(buf: Buffer): RelayFrame | null {
  if (buf.length < HEADER_RELAY) return null
  if (buf[0] !== MAGIC || buf[1] !== VERSION) return null
  const type = buf[2]
  if (type === undefined || !MEDIA_TYPES.has(type)) return null
  const senderSlot = buf[4]
  if (senderSlot === undefined || senderSlot > MAX_SLOT) return null
  return {
    type: type as MediaType,
    flags: buf[3] ?? 0,
    senderSlot,
    mediaTs: buf.readUInt32BE(5),
    payload: buf.subarray(HEADER_RELAY),
  }
}
