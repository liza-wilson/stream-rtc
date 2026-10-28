import type { RelayMember, WsLike } from './types.js'

export type RoomErrorCode = 'room_full' | 'video_slots_full' | 'duplicate_user' | 'not_found'

export class RoomError extends Error {
  readonly code: RoomErrorCode

  constructor(code: RoomErrorCode, message: string = code) {
    super(message)
    this.name = 'RoomError'
    this.code = code
  }
}

export interface RelayRegistryDeps {
  now(): number
  maxParticipants?: number
  maxVideo?: number
  resumeGraceMs?: number
}

interface Room {
  key: string
  members: Map<number, RelayMember>
  reserved: Map<string, { slot: number; until: number }>
}

export interface JoinLimits {
  maxParticipants?: number
  maxVideo?: number
}

export interface JoinResult {
  member: RelayMember
  evicted?: RelayMember
}

const DEFAULTS = {
  maxParticipants: 16,
  maxVideo: 8,
  resumeGraceMs: 30_000,
}

export class RelayRegistry {
  private readonly rooms = new Map<string, Room>()
  private readonly deps: RelayRegistryDeps
  private readonly maxParticipants: number
  private readonly maxVideo: number
  private readonly resumeGraceMs: number

  constructor(deps: RelayRegistryDeps) {
    this.deps = deps
    this.maxParticipants = deps.maxParticipants ?? DEFAULTS.maxParticipants
    this.maxVideo = deps.maxVideo ?? DEFAULTS.maxVideo
    this.resumeGraceMs = deps.resumeGraceMs ?? DEFAULTS.resumeGraceMs
  }

  private key(appId: string, roomId: string): string {
    return `${appId}\u0000${roomId}`
  }

  join(
    appId: string,
    roomId: string,
    userId: string,
    ws: WsLike,
    opts: { hasVideo: boolean } & JoinLimits,
  ): JoinResult {
    const now = this.deps.now()
    const key = this.key(appId, roomId)
    const room = this.rooms.get(key) ?? { key, members: new Map(), reserved: new Map() }
    this.rooms.set(key, room)

    const maxParticipants = opts.maxParticipants ?? this.maxParticipants
    const maxVideo = opts.maxVideo ?? this.maxVideo

    let evicted: RelayMember | undefined
    for (const member of room.members.values()) {
      if (member.userId === userId) {
        evicted = member
        room.members.delete(member.slot)
        member.ws.close(1008, 'duplicate_session')
        break
      }
    }

    const reservation = room.reserved.get(userId)
    let slot: number | undefined
    if (reservation && reservation.until > now) {
      slot = reservation.slot
      room.reserved.delete(userId)
    } else if (reservation) {
      room.reserved.delete(userId)
    }

    if (slot === undefined) slot = this.lowestFreeSlot(room, maxParticipants)
    if (slot === undefined) {
      this.dropIfEmpty(room)
      throw new RoomError('room_full', `room_full: ${roomId}`)
    }

    if (opts.hasVideo) {
      const videoCount = [...room.members.values()].filter((m) => m.hasVideo).length
      if (videoCount >= maxVideo) {
        this.dropIfEmpty(room)
        throw new RoomError('video_slots_full', `video_slots_full: ${roomId}`)
      }
    }

    const member: RelayMember = {
      appId,
      roomId,
      userId,
      slot,
      ws,
      joinedAt: now,
      degraded: false,
      lastFrameAt: now,
      hasVideo: opts.hasVideo,
    }
    room.members.set(slot, member)
    this.pruneReservations(room, now)
    return evicted ? { member, evicted } : { member }
  }

  leave(ws: WsLike): RelayMember | undefined {
    const member = this.memberOf(ws)
    if (!member) return undefined
    const room = this.rooms.get(this.key(member.appId, member.roomId))
    if (!room) return member
    room.members.delete(member.slot)
    room.reserved.set(member.userId, {
      slot: member.slot,
      until: this.deps.now() + this.resumeGraceMs,
    })
    this.pruneReservations(room, this.deps.now())
    this.dropIfEmpty(room)
    return member
  }

  peers(appId: string, roomId: string, exceptUserId: string): RelayMember[] {
    const room = this.rooms.get(this.key(appId, roomId))
    if (!room) return []
    return [...room.members.values()].filter((m) => m.userId !== exceptUserId)
  }

  members(appId: string, roomId: string): RelayMember[] {
    const room = this.rooms.get(this.key(appId, roomId))
    return room ? [...room.members.values()] : []
  }

  memberOf(ws: WsLike): RelayMember | undefined {
    for (const room of this.rooms.values()) {
      for (const member of room.members.values()) {
        if (member.ws === ws) return member
      }
    }
    return undefined
  }

  stats(): { rooms: number; participants: number } {
    let participants = 0
    for (const room of this.rooms.values()) participants += room.members.size
    return { rooms: this.rooms.size, participants }
  }

  /** Drops expired reservations and rooms that no longer hold anyone. */
  prune(nowMs: number): void {
    for (const room of this.rooms.values()) {
      this.pruneReservations(room, nowMs)
      this.dropIfEmpty(room)
    }
  }

  private dropIfEmpty(room: Room): void {
    if (room.members.size === 0 && room.reserved.size === 0) this.rooms.delete(room.key)
  }

  private lowestFreeSlot(room: Room, maxParticipants: number): number | undefined {
    for (let slot = 0; slot < maxParticipants; slot++) {
      if (!room.members.has(slot) && !this.slotReserved(room, slot)) return slot
    }
    return undefined
  }

  private slotReserved(room: Room, slot: number): boolean {
    const now = this.deps.now()
    for (const reservation of room.reserved.values()) {
      if (reservation.slot === slot && reservation.until > now) return true
    }
    return false
  }

  private pruneReservations(room: Room, now: number): void {
    for (const [userId, reservation] of room.reserved) {
      if (reservation.until <= now) room.reserved.delete(userId)
    }
  }
}
