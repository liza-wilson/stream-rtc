export interface WsLike {
  send(data: string | Buffer): void
  close(code?: number, reason?: string): void
  readonly bufferedAmount: number
}
