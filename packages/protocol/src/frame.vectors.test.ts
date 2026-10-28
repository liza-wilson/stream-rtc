import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { decodeClientFrame, decodeRelayFrame, encodeClientFrame, encodeRelayFrame } from './frame.js'

const vectors = JSON.parse(
  readFileSync(new URL('../testdata/frames.json', import.meta.url), 'utf8'),
) as Array<{
  name: string; type: number; flags: number; mediaTs: number; senderSlot: number
  payloadHex: string; clientHex: string; relayHex: string
}>

describe('frame vectors', () => {
  for (const v of vectors) {
    it(`${v.name} matches the committed bytes`, () => {
      const payload = Buffer.from(v.payloadHex, 'hex')
      const client = encodeClientFrame({ type: v.type as never, flags: v.flags, mediaTs: v.mediaTs, payload })
      expect(client.toString('hex')).toBe(v.clientHex)
      expect(decodeClientFrame(client)).toMatchObject({ type: v.type, flags: v.flags, mediaTs: v.mediaTs })
      const relay = encodeRelayFrame({ type: v.type as never, flags: v.flags, mediaTs: v.mediaTs, senderSlot: v.senderSlot, payload })
      expect(relay.toString('hex')).toBe(v.relayHex)
      expect(decodeRelayFrame(relay)).toMatchObject({ senderSlot: v.senderSlot, type: v.type })
    })
  }
})
