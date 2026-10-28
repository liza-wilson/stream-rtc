import { z } from 'zod'

export const MAX_MESSAGE_BYTES = 64 * 1024

const helloD = z.strictObject({
  token: z.string().min(1),
  deviceId: z.string().min(1),
  platform: z.string().min(1),
  appVersion: z.string().optional(),
  codecs: z.strictObject({
    audio: z.array(z.string()),
    video: z.array(z.string()),
  }),
})

const inviteD = z.strictObject({
  toUserId: z.string().min(1),
  roomId: z.string().min(1),
  media: z.strictObject({ audio: z.boolean(), video: z.boolean() }),
})

const callRefD = z.strictObject({
  callId: z.string().min(1),
  reason: z.string().optional(),
})

const roomRefD = z.strictObject({ roomId: z.string().min(1) })

const pingD = z.strictObject({ ts: z.number() })

const clientSchema = z.discriminatedUnion('t', [
  z.strictObject({ t: z.literal('hello'), d: helloD }),
  z.strictObject({ t: z.literal('call.invite'), d: inviteD }),
  z.strictObject({ t: z.literal('call.accept'), d: callRefD }),
  z.strictObject({ t: z.literal('call.reject'), d: callRefD }),
  z.strictObject({ t: z.literal('call.end'), d: callRefD }),
  z.strictObject({ t: z.literal('room.join'), d: roomRefD }),
  z.strictObject({ t: z.literal('room.leave'), d: roomRefD }),
  z.strictObject({ t: z.literal('room.state'), d: roomRefD }),
  z.strictObject({ t: z.literal('ping'), d: pingD }),
])

export type HelloD = z.infer<typeof helloD>
export type InviteD = z.infer<typeof inviteD>
export type CallRefD = z.infer<typeof callRefD>
export type RoomRefD = z.infer<typeof roomRefD>

export type ClientMessage =
  | { t: 'hello'; d: HelloD }
  | { t: 'call.invite'; d: InviteD }
  | { t: 'call.accept'; d: CallRefD }
  | { t: 'call.reject'; d: CallRefD }
  | { t: 'call.end'; d: CallRefD }
  | { t: 'room.join'; d: RoomRefD }
  | { t: 'room.leave'; d: RoomRefD }
  | { t: 'room.state'; d: RoomRefD }
  | { t: 'ping'; d: { ts: number } }

export type ServerMessageType =
  | 'hello.ok'
  | 'hello.err'
  | 'call.incoming'
  | 'call.ringing'
  | 'call.accepted'
  | 'call.rejected'
  | 'call.ended'
  | 'callee_offline'
  | 'room.token'
  | 'room.participant_joined'
  | 'room.participant_left'
  | 'room.participant_updated'
  | 'room.state'
  | 'error'
  | 'pong'

export interface ServerMessage {
  t: ServerMessageType
  d: Record<string, unknown>
}

const serverSchema = z.object({
  t: z.string().min(1),
  d: z.record(z.string(), z.unknown()),
})

export function parseClientMessage(raw: string): ClientMessage | null {
  if (Buffer.byteLength(raw, 'utf8') > MAX_MESSAGE_BYTES) return null
  let json: unknown
  try {
    json = JSON.parse(raw)
  } catch {
    return null
  }
  const parsed = clientSchema.safeParse(json)
  return parsed.success ? (parsed.data as ClientMessage) : null
}

export function parseServerMessage(raw: string): ServerMessage | null {
  if (Buffer.byteLength(raw, 'utf8') > MAX_MESSAGE_BYTES) return null
  let json: unknown
  try {
    json = JSON.parse(raw)
  } catch {
    return null
  }
  const parsed = serverSchema.safeParse(json)
  return parsed.success ? (parsed.data as ServerMessage) : null
}

export function encodeMessage(message: ServerMessage): string {
  return JSON.stringify(message)
}
