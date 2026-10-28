export type CallState = 'ringing' | 'active' | 'rejected' | 'ended'

export type CallErrorCode = 'not_found' | 'invalid_transition' | 'forbidden'

export class CallError extends Error {
  readonly code: CallErrorCode

  constructor(code: CallErrorCode, message: string = code) {
    super(message)
    this.name = 'CallError'
    this.code = code
  }
}

export interface Call {
  id: string
  appId: string
  roomId: string
  callerId: string
  calleeId: string
  media?: { audio: boolean; video: boolean }
  state: CallState
  createdAt: number
  answeredAt?: number
  endReason?: string
}

export interface CallRegistryDeps {
  now(): number
  id(): string
}

const RING_TIMEOUT_MS = 45_000
const ACTIVE_TTL_MS = 4 * 60 * 60_000

const ALLOWED: Record<CallState, CallState[]> = {
  ringing: ['active', 'rejected', 'ended'],
  active: ['ended'],
  rejected: [],
  ended: [],
}

export class CallRegistry {
  private readonly calls = new Map<string, Call>()
  private readonly deps: CallRegistryDeps

  constructor(deps: CallRegistryDeps) {
    this.deps = deps
  }

  create(input: {
    appId?: string
    roomId: string
    callerId: string
    calleeId: string
    media?: { audio: boolean; video: boolean }
  }): Call {
    if (input.callerId === input.calleeId) {
      throw new CallError('forbidden', 'forbidden: cannot call yourself')
    }
    const call: Call = {
      id: this.deps.id(),
      appId: input.appId ?? '',
      roomId: input.roomId,
      callerId: input.callerId,
      calleeId: input.calleeId,
      ...(input.media === undefined ? {} : { media: input.media }),
      state: 'ringing',
      createdAt: this.deps.now(),
    }
    this.calls.set(call.id, call)
    return call
  }

  get(callId: string): Call | undefined {
    return this.calls.get(callId)
  }

  accept(callId: string, userId: string): Call {
    const call = this.require(callId)
    if (userId !== call.calleeId) {
      throw new CallError('forbidden', 'forbidden: only the callee can accept')
    }
    this.transition(call, 'active')
    call.answeredAt = this.deps.now()
    return call
  }

  reject(callId: string, userId: string, reason?: string): Call {
    const call = this.require(callId)
    this.requireParticipant(call, userId)
    this.transition(call, 'rejected')
    if (reason !== undefined) call.endReason = reason
    return call
  }

  end(callId: string, userId: string, reason?: string): Call {
    const call = this.require(callId)
    this.requireParticipant(call, userId)
    this.transition(call, 'ended')
    if (reason !== undefined) call.endReason = reason
    return call
  }

  silentSince(nowMs: number): Call[] {
    const out: Call[] = []
    for (const call of this.calls.values()) {
      if (call.state === 'ringing' && call.createdAt + RING_TIMEOUT_MS <= nowMs) out.push(call)
    }
    return out
  }

  staleActive(nowMs: number, ttlMs: number = ACTIVE_TTL_MS): Call[] {
    const out: Call[] = []
    for (const call of this.calls.values()) {
      if (call.state === 'active' && call.createdAt + ttlMs <= nowMs) out.push(call)
    }
    return out
  }

  remove(callId: string): void {
    this.calls.delete(callId)
  }

  size(): number {
    return this.calls.size
  }

  private require(callId: string): Call {
    const call = this.calls.get(callId)
    if (!call) throw new CallError('not_found', `not_found: ${callId}`)
    return call
  }

  private requireParticipant(call: Call, userId: string): void {
    if (userId !== call.callerId && userId !== call.calleeId) {
      throw new CallError('forbidden', 'forbidden: not a participant')
    }
  }

  private transition(call: Call, next: CallState): void {
    if (!ALLOWED[call.state].includes(next)) {
      throw new CallError('invalid_transition', `invalid_transition: ${call.state} -> ${next}`)
    }
    call.state = next
  }
}
