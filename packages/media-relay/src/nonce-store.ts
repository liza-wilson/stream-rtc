export class NonceStore {
  private readonly until = new Map<string, number>()
  private readonly ttlMs: number

  constructor(ttlMs: number) {
    this.ttlMs = ttlMs
  }

  burn(nonce: string, nowMs: number): boolean {
    const expiresAt = this.until.get(nonce)
    if (expiresAt !== undefined && expiresAt > nowMs) return false
    this.until.set(nonce, nowMs + this.ttlMs)
    return true
  }

  has(nonce: string): boolean {
    return this.until.has(nonce)
  }

  prune(nowMs: number): void {
    for (const [nonce, expiresAt] of this.until) {
      if (expiresAt <= nowMs) this.until.delete(nonce)
    }
  }
}
