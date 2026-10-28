export interface WsLike {
  send(data: string | Buffer): void
  close(code?: number, reason?: string): void
  readonly bufferedAmount: number
}

export interface RelayMember {
  roomId: string
  userId: string
  slot: number
  ws: WsLike
  joinedAt: number
  degraded: boolean
  lastFrameAt: number
  hasVideo: boolean
}
