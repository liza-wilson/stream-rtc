import { describe, expect, it } from 'vitest'
import { startRelay } from '@streamrtc/media-relay/server'
import { RelayRegistry } from '@streamrtc/media-relay'
import { decodeRelayFrame, encodeClientFrame } from '@streamrtc/protocol'
import { FakeClient } from './fake-client.js'

describe('group call', () => {
  it('fans one publisher out to three subscribers with distinct slots', async () => {
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
    try {
      const clients = ['a', 'b', 'c', 'd'].map((n) => new FakeClient(apps, 'http://unused'))
      await Promise.all(clients.map((c, i) => c.joinRoomDirect(relay.address, 'r1', `u${i}`)))

      clients[0]!.publish(
        encodeClientFrame({ type: 0x20, flags: 1, mediaTs: 1, payload: Buffer.from([1]) }),
      )
      const got = await Promise.all(clients.slice(1).map((c) => c.nextMedia()))
      expect(got.map((g) => decodeRelayFrame(g)!.senderSlot)).toEqual([0, 0, 0])
      expect(clients[0]!.mediaCount()).toBe(0)
    } finally {
      await relay.close()
    }
  })

  it('rejects a fourth video publisher when the video limit is two', async () => {
    const apps = {
      get: (id: string) => ({
        appId: id,
        secret: 'a'.repeat(32),
        maxRoomParticipants: 16,
        maxVideoParticipants: 2,
      }),
      appIds: () => ['app'],
    }
    const relay = await startRelay({
      port: 0,
      deps: {
        verify: (t) => JSON.parse(Buffer.from(t.split('.')[1]!, 'base64url').toString()),
        now: () => Date.now(),
        registry: new RelayRegistry({ now: () => Date.now(), maxVideo: 2 }),
      },
    })
    try {
      const first = new FakeClient(apps, 'http://unused')
      const second = new FakeClient(apps, 'http://unused')
      const third = new FakeClient(apps, 'http://unused')
      await first.joinRoomDirect(relay.address, 'r2', 'u0')
      await second.joinRoomDirect(relay.address, 'r2', 'u1')
      await expect(third.joinRoomDirect(relay.address, 'r2', 'u2')).rejects.toThrow(/closed/)
    } finally {
      await relay.close()
    }
  })
})
