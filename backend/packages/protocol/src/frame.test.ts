import { describe, expect, it } from 'vitest'
import {
  decodeClientFrame,
  decodeRelayFrame,
  encodeClientFrame,
  encodeRelayFrame,
  FLAG_KEYFRAME,
  HEADER_CLIENT,
  HEADER_RELAY,
  MAGIC,
  VERSION,
} from './frame.js'

describe('client frame', () => {
  it('round-trips type, flags, mediaTs and payload', () => {
    const buf = encodeClientFrame({
      type: 0x20,
      flags: FLAG_KEYFRAME,
      mediaTs: 123456,
      payload: Buffer.from([1, 2, 3]),
    })
    expect(buf.subarray(0, 5)).toEqual(Buffer.from([MAGIC, VERSION, 0x20, 1, 0x00]))
    expect(buf.readUInt32BE(4)).toBe(123456)
    expect(buf.length).toBe(HEADER_CLIENT + 3)
    const out = decodeClientFrame(buf)
    expect(out).toEqual({
      type: 0x20,
      flags: 1,
      mediaTs: 123456,
      payload: Buffer.from([1, 2, 3]),
    })
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
    const buf = encodeClientFrame({
      type: 0x10,
      flags: 0,
      mediaTs: 0xffffffff,
      payload: Buffer.alloc(0),
    })
    expect(buf.readUInt32BE(4)).toBe(0xffffffff)
  })
})

describe('relay frame', () => {
  it('adds senderSlot after flags and preserves mediaTs', () => {
    const buf = encodeRelayFrame({
      type: 0x10,
      flags: 0,
      senderSlot: 7,
      mediaTs: 42,
      payload: Buffer.from([9]),
    })
    expect(buf.length).toBe(HEADER_RELAY + 1)
    expect(buf[4]).toBe(7)
    expect(buf.readUInt32BE(5)).toBe(42)
    expect(decodeRelayFrame(buf)).toEqual({
      type: 0x10,
      flags: 0,
      senderSlot: 7,
      mediaTs: 42,
      payload: Buffer.from([9]),
    })
  })

  it('returns null for a truncated relay header', () => {
    expect(decodeRelayFrame(Buffer.alloc(8))).toBeNull()
  })
})
