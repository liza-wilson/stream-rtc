import { describe, expect, it } from 'vitest'
import { encodeMessage, parseClientMessage, parseServerMessage } from './messages.js'

describe('client messages', () => {
  it('parses hello with codecs', () => {
    const m = parseClientMessage(
      JSON.stringify({
        t: 'hello',
        d: {
          token: 'abc',
          deviceId: 'd1',
          platform: 'android',
          codecs: { audio: ['opus', 'pcm16'], video: ['h264'] },
        },
      }),
    )
    expect(m?.t).toBe('hello')
  })

  it('parses call.invite and rejects a missing toUserId', () => {
    expect(
      parseClientMessage(
        JSON.stringify({
          t: 'call.invite',
          d: { roomId: 'r1', media: { audio: true, video: true } },
        }),
      ),
    ).toBeNull()
  })

  it('returns null for unknown types and non-JSON', () => {
    expect(parseClientMessage('{"t":"nope","d":{}}')).toBeNull()
    expect(parseClientMessage('not json')).toBeNull()
  })

  it('rejects payloads over 64 KB', () => {
    const big = JSON.stringify({ t: 'ping', d: { ts: 1, pad: 'x'.repeat(70_000) } })
    expect(parseClientMessage(big)).toBeNull()
  })

  it('parses call.token and relay keyframe requests', () => {
    expect(parseClientMessage(JSON.stringify({ t: 'call.token', d: { callId: 'c1' } }))?.t).toBe('call.token')
    expect(parseClientMessage(JSON.stringify({ t: 'keyframe', d: { slot: 3 } }))?.t).toBe('keyframe')
  })

  it('rejects malformed call.token and keyframe payloads', () => {
    expect(parseClientMessage(JSON.stringify({ t: 'call.token', d: {} }))).toBeNull()
    expect(parseClientMessage(JSON.stringify({ t: 'keyframe', d: { slot: -1 } }))).toBeNull()
    expect(parseClientMessage(JSON.stringify({ t: 'keyframe', d: { slot: 16 } }))).toBeNull()
  })
})

describe('server messages', () => {
  it('round-trips an encoded message', () => {
    const raw = encodeMessage({ t: 'pong', d: { ts: 1, serverTime: 2 } })
    expect(JSON.parse(raw)).toEqual({ t: 'pong', d: { ts: 1, serverTime: 2 } })
    expect(parseServerMessage(raw)?.t).toBe('pong')
  })
})
