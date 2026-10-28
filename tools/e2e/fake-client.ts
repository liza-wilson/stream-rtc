import { randomUUID } from 'node:crypto'
import WebSocket from 'ws'
import jwt from 'jsonwebtoken'

type Message = { t: string; d: Record<string, unknown> }

interface AppsLike {
  get(appId: string): { appId: string; secret: string } | undefined
  appIds(): string[]
}

const APP_ID = 'app'

export class FakeClient {
  private static readonly byUser = new Map<string, FakeClient>()

  private control?: WebSocket
  private relay?: WebSocket
  private readonly apps: AppsLike
  private readonly controlUrl: string

  private readonly queue: Message[] = []
  private readonly waiters: ((m: Message) => void)[] = []
  private readonly media: Buffer[] = []
  private readonly mediaWaiters: ((b: Buffer) => void)[] = []

  private room?: { token: string; relayUrl: string; roomId: string }
  private closed?: Error

  constructor(apps: AppsLike, controlUrl: string) {
    this.apps = apps
    this.controlUrl = controlUrl
  }

  private secret(): string {
    const app = this.apps.get(APP_ID)
    if (!app) throw new Error('test app missing')
    return app.secret
  }

  private sessionToken(userId: string): string {
    return jwt.sign({ v: 1, appId: APP_ID, userId, displayName: userId, exp: 9e9 }, this.secret(), {
      algorithm: 'HS256',
    })
  }

  private roomToken(roomId: string, userId: string): string {
    return jwt.sign(
      {
        v: 1,
        appId: APP_ID,
        userId,
        roomId,
        role: 'member',
        nonce: randomUUID(),
        exp: Math.floor(Date.now() / 1000) + 300,
      },
      this.secret(),
      { algorithm: 'HS256' },
    )
  }

  private static open(url: string): Promise<WebSocket> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url)
      ws.once('open', () => resolve(ws))
      ws.once('error', reject)
    })
  }

  private attach(ws: WebSocket): void {
    ws.on('close', (code, reason) => {
      this.closed = new Error(`socket closed ${code} ${reason.toString()}`)
      for (const waiter of this.waiters.splice(0)) {
        waiter({ t: '__closed__', d: {} })
      }
    })
    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer)
        const waiter = this.mediaWaiters.shift()
        if (waiter) waiter(buf)
        else this.media.push(buf)
        return
      }
      const message = JSON.parse(data.toString()) as Message
      const waiter = this.waiters.shift()
      if (waiter) waiter(message)
      else this.queue.push(message)
    })
  }

  private send(message: Message): void {
    if (!this.control) throw new Error('control socket not connected')
    this.control.send(JSON.stringify(message))
  }

  private wait(type: string): Promise<Message> {
    if (this.closed) return Promise.reject(this.closed)
    const index = this.queue.findIndex((m) => m.t === type)
    if (index >= 0) return Promise.resolve(this.queue.splice(index, 1)[0]!)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for ${type}`)), 5_000)
      const check = (m: Message): void => {
        if (m.t === '__closed__') {
          clearTimeout(timer)
          reject(this.closed ?? new Error('socket closed'))
          return
        }
        if (m.t === type) {
          clearTimeout(timer)
          resolve(m)
          return
        }
        this.queue.push(m)
        this.waiters.push(check)
      }
      this.waiters.push(check)
    })
  }

  async hello(userId: string): Promise<void> {
    this.control = await FakeClient.open(this.controlUrl)
    this.attach(this.control)
    FakeClient.byUser.set(userId, this)
    this.send({
      t: 'hello',
      d: {
        token: this.sessionToken(userId),
        deviceId: 'fake',
        platform: 'android',
        codecs: { audio: ['opus', 'pcm16'], video: ['h264'] },
      },
    })
    const reply = await this.wait('hello.ok')
    if (reply.t !== 'hello.ok') throw new Error(`hello failed: ${JSON.stringify(reply)}`)
  }

  async invite(toUserId: string, roomId: string): Promise<Record<string, unknown>> {
    this.send({ t: 'call.invite', d: { toUserId, roomId, media: { audio: true, video: true } } })
    const callee = FakeClient.byUser.get(toUserId)
    const incoming = await (callee ?? this).wait('call.incoming')
    return incoming.d
  }

  async accept(callId: unknown): Promise<void> {
    this.send({ t: 'call.accept', d: { callId } })
  }

  async awaitRoomToken(): Promise<Record<string, unknown>> {
    const message = await this.wait('room.token')
    this.room = {
      token: message.d['token'] as string,
      relayUrl: message.d['relayUrl'] as string,
      roomId: message.d['roomId'] as string,
    }
    return message.d
  }

  async joinRelay(): Promise<void> {
    if (!this.room) throw new Error('no room token yet')
    this.relay = await FakeClient.open(this.room.relayUrl)
    this.attach(this.relay)
    this.relay.send(JSON.stringify({ t: 'register', token: this.room.token }))
    const registered = await this.wait('registered')
    if (registered.t !== 'registered') throw new Error(`register failed: ${JSON.stringify(registered)}`)
  }

  async joinRoomDirect(relayUrl: string, roomId: string, userId: string): Promise<void> {
    FakeClient.byUser.set(userId, this)
    this.relay = await FakeClient.open(relayUrl)
    this.attach(this.relay)
    this.relay.send(JSON.stringify({ t: 'register', token: this.roomToken(roomId, userId) }))
    const registered = await this.wait('registered')
    if (registered.t !== 'registered') throw new Error(`register failed: ${JSON.stringify(registered)}`)
  }

  publish(frame: Buffer): void {
    if (!this.relay) throw new Error('relay socket not connected')
    this.relay.send(frame)
  }

  nextMedia(): Promise<Buffer> {
    const head = this.media.shift()
    if (head) return Promise.resolve(head)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timed out waiting for media')), 5_000)
      this.mediaWaiters.push((buf) => {
        clearTimeout(timer)
        resolve(buf)
      })
    })
  }

  mediaCount(): number {
    return this.media.length
  }

  close(): void {
    this.control?.close()
    this.relay?.close()
  }
}
