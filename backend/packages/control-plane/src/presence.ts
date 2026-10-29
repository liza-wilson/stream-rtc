import type { WsLike } from './types.js'

export class Presence {
  private readonly byUser = new Map<string, Set<WsLike>>()

  private key(appId: string, userId: string): string {
    return `${appId}:${userId}`
  }

  add(appId: string, userId: string, ws: WsLike): void {
    const key = this.key(appId, userId)
    const set = this.byUser.get(key)
    if (set) {
      set.add(ws)
      return
    }
    this.byUser.set(key, new Set([ws]))
  }

  remove(appId: string, userId: string, ws: WsLike): void {
    const key = this.key(appId, userId)
    const set = this.byUser.get(key)
    if (!set) return
    set.delete(ws)
    if (set.size === 0) this.byUser.delete(key)
  }

  sockets(appId: string, userId: string): WsLike[] {
    const set = this.byUser.get(this.key(appId, userId))
    return set ? [...set] : []
  }

  online(appId: string, userId: string): boolean {
    const set = this.byUser.get(this.key(appId, userId))
    return set !== undefined && set.size > 0
  }

  count(): number {
    let total = 0
    for (const set of this.byUser.values()) total += set.size
    return total
  }
}
