import { describe, expect, it } from 'vitest'
import { startControl } from '@streamrtc/control-plane/server'
import { startRelay } from '@streamrtc/media-relay/server'
import { encodeClientFrame, decodeRelayFrame } from '@streamrtc/protocol'
import { FakeClient } from './fake-client.js'

describe('1:1 end to end', () => {
  it('connects two clients and delivers video both ways', async () => {
    const apps = {
      get: (id: string) => ({
        appId: id,
        secret: 'a'.repeat(32),
        maxRoomParticipants: 16,
        maxVideoParticipants: 8,
      }),
      appIds: () => ['app'],
    }
    const relay = await startRelay({
      port: 0,
      deps: {
        verify: (t) => JSON.parse(Buffer.from(t.split('.')[1]!, 'base64url').toString()),
        now: () => Date.now(),
      },
    })
    const control = await startControl({
      port: 0,
      deps: {
        apps,
        now: () => Date.now(),
        id: () => crypto.randomUUID(),
        publicRelayUrl: relay.address,
      },
    })
    try {
      const a = new FakeClient(apps, control.address)
      const b = new FakeClient(apps, control.address)
      await a.hello('alice')
      await b.hello('bob')
      const call = await a.invite('bob', 'r1')
      await b.accept(call.callId)
      await a.awaitRoomToken()
      await b.awaitRoomToken()
      await a.joinRelay()
      await b.joinRelay()

      a.publish(
        encodeClientFrame({ type: 0x20, flags: 1, mediaTs: 11, payload: Buffer.from([7, 7]) }),
      )
      const got = await b.nextMedia()
      expect(decodeRelayFrame(got)).toMatchObject({ type: 0x20, mediaTs: 11 })

      b.publish(
        encodeClientFrame({ type: 0x10, flags: 0, mediaTs: 12, payload: Buffer.from([1]) }),
      )
      expect((await a.nextMedia()).length).toBeGreaterThan(8)
    } finally {
      await control.close()
      await relay.close()
    }
  })
})
