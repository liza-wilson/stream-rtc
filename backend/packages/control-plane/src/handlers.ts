import { mintRoomToken, verifySession, TokenError } from '@streamrtc/core'
import type { AppRegistry, SessionClaims } from '@streamrtc/core'
import { encodeMessage } from '@streamrtc/protocol'
import type { ClientMessage, ServerMessage } from '@streamrtc/protocol'
import { CallError, type Call, type CallRegistry } from './calls.js'
import type { Presence } from './presence.js'
import type { WsLike } from './types.js'

export interface ControlContext {
  apps: AppRegistry
  presence: Presence
  calls: CallRegistry
  now(): number
  id(): string
  publicRelayUrl: string
}

export interface Conn {
  ws: WsLike
  session?: SessionClaims
  close(code: number, reason: string): void
}

export const ROOM_TOKEN_TTL_SECONDS = 300

function send(ws: WsLike, message: ServerMessage): void {
  try {
    ws.send(encodeMessage(message))
  } catch {
    // dead sockets are cleaned up by their close handler
  }
}

function sendToUser(ctx: ControlContext, appId: string, userId: string, message: ServerMessage): void {
  for (const socket of ctx.presence.sockets(appId, userId)) send(socket, message)
}

function fail(conn: Conn, code: string, message?: string): void {
  send(conn.ws, { t: 'error', d: message === undefined ? { code } : { code, message } })
}

function sendRoomToken(
  ctx: ControlContext,
  appId: string,
  call: Call,
  userId: string,
  role: 'host' | 'member',
): void {
  const app = ctx.apps.get(appId)
  if (!app) return
  const token = mintRoomToken(
    app,
    {
      appId,
      userId,
      roomId: call.roomId,
      role,
      video: call.media?.video ?? true,
    },
    ROOM_TOKEN_TTL_SECONDS,
    ctx.id(),
  )
  sendToUser(ctx, appId, userId, {
    t: 'room.token',
    d: {
      token,
      relayUrl: ctx.publicRelayUrl,
      roomId: call.roomId,
      role,
      expiresIn: ROOM_TOKEN_TTL_SECONDS,
      peers: [],
    },
  })
}

export function handleMessage(ctx: ControlContext, conn: Conn, message: ClientMessage): void {
  if (message.t === 'hello') {
    if (conn.session) return
    let session: SessionClaims
    try {
      session = verifySession(message.d.token, ctx.apps, ctx.now())
    } catch (error) {
      const code = error instanceof TokenError ? error.code : 'invalid_token'
      send(conn.ws, { t: 'hello.err', d: { code } })
      conn.close(4401, 'invalid_token')
      return
    }
    conn.session = session
    ctx.presence.add(session.appId, session.userId, conn.ws)
    send(conn.ws, {
      t: 'hello.ok',
      d: {
        userId: session.userId,
        appId: session.appId,
        serverTime: ctx.now(),
      },
    })
    return
  }

  const session = conn.session
  if (!session) {
    conn.close(4401, 'not_authenticated')
    return
  }

  switch (message.t) {
    case 'call.invite': {
      const { toUserId, roomId, media } = message.d
      if (toUserId === session.userId) {
        fail(conn, 'self_call')
        return
      }
      if (!ctx.presence.online(session.appId, toUserId)) {
        send(conn.ws, { t: 'callee_offline', d: { toUserId } })
        return
      }
      let call
      try {
        call = ctx.calls.create({
          appId: session.appId,
          roomId,
          callerId: session.userId,
          calleeId: toUserId,
          media,
        })
      } catch (error) {
        if (error instanceof CallError) {
          fail(conn, error.code, error.message)
          return
        }
        throw error
      }
      sendToUser(ctx, session.appId, toUserId, {
        t: 'call.incoming',
        d: {
          callId: call.id,
          fromUserId: session.userId,
          ...(session.displayName === undefined ? {} : { displayName: session.displayName }),
          roomId: call.roomId,
          media,
        },
      })
      return
    }

    case 'call.accept': {
      const call = ctx.calls.get(message.d.callId)
      if (
        !call ||
        call.appId !== session.appId ||
        (call.callerId !== session.userId && call.calleeId !== session.userId)
      ) {
        fail(conn, 'not_found')
        return
      }
      try {
        ctx.calls.accept(call.id, session.userId)
      } catch (error) {
        if (error instanceof CallError) {
          fail(conn, error.code, error.message)
          return
        }
        throw error
      }

      sendToUser(ctx, session.appId, call.callerId, {
        t: 'call.accepted',
        d: { callId: call.id, byUserId: session.userId, roomId: call.roomId },
      })

      const app = ctx.apps.get(session.appId)
      if (!app) {
        fail(conn, 'unknown_app')
        return
      }
      sendRoomToken(ctx, session.appId, call, call.callerId, 'host')
      sendRoomToken(ctx, session.appId, call, call.calleeId, 'member')
      return
    }

    case 'call.reject': {
      const call = ctx.calls.get(message.d.callId)
      if (!call || call.appId !== session.appId) {
        fail(conn, 'not_found')
        return
      }
      try {
        ctx.calls.reject(call.id, session.userId, message.d.reason)
      } catch (error) {
        if (error instanceof CallError) {
          fail(conn, error.code, error.message)
          return
        }
        throw error
      }
      const other = call.callerId === session.userId ? call.calleeId : call.callerId
      sendToUser(ctx, session.appId, other, {
        t: 'call.rejected',
        d: { callId: call.id, reason: message.d.reason ?? 'rejected' },
      })
      ctx.calls.remove(call.id)
      return
    }

    case 'call.end': {
      const call = ctx.calls.get(message.d.callId)
      if (!call || call.appId !== session.appId) {
        fail(conn, 'not_found')
        return
      }
      try {
        ctx.calls.end(call.id, session.userId, message.d.reason)
      } catch (error) {
        if (error instanceof CallError) {
          fail(conn, error.code, error.message)
          return
        }
        throw error
      }
      const other = call.callerId === session.userId ? call.calleeId : call.callerId
      sendToUser(ctx, session.appId, other, {
        t: 'call.ended',
        d: { callId: call.id, reason: message.d.reason ?? 'ended' },
      })
      ctx.calls.remove(call.id)
      return
    }

    case 'call.token': {
      const call = ctx.calls.get(message.d.callId)
      if (
        !call ||
        call.appId !== session.appId ||
        call.state !== 'active' ||
        (call.callerId !== session.userId && call.calleeId !== session.userId)
      ) {
        fail(conn, 'not_found')
        return
      }
      const role = call.callerId === session.userId ? 'host' : 'member'
      sendRoomToken(ctx, session.appId, call, session.userId, role)
      return
    }

    case 'room.join':
      fail(conn, 'use_room_token')
      return

    case 'room.leave':
      send(conn.ws, { t: 'error', d: { code: 'use_room_token' } })
      return

    case 'room.state':
      fail(conn, 'unsupported')
      return

    case 'ping':
      send(conn.ws, { t: 'pong', d: { ts: message.d.ts, serverTime: ctx.now() } })
      return
  }
}

export function endTimedOutCalls(ctx: ControlContext): void {
  for (const call of ctx.calls.silentSince(ctx.now())) {
    ctx.calls.end(call.id, call.callerId, 'timeout')
    if (call.appId) {
      for (const userId of [call.callerId, call.calleeId]) {
        sendToUser(ctx, call.appId, userId, {
          t: 'call.ended',
          d: { callId: call.id, reason: 'timeout' },
        })
      }
    }
    ctx.calls.remove(call.id)
  }
  for (const call of ctx.calls.staleActive(ctx.now())) {
    ctx.calls.remove(call.id)
  }
}
