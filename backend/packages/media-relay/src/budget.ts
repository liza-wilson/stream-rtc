export const MEDIA_BUDGET = 900
export const CONTROL_BUDGET = 60
export const WINDOW_MS = 10_000

export type BudgetKind = 'media' | 'control'

export class Budget {
  private readonly mediaLimit: number
  private readonly controlLimit: number
  private readonly windowMs: number
  private windowStart: number | null = null
  private media = 0
  private control = 0

  constructor(mediaLimit: number = MEDIA_BUDGET, controlLimit: number = CONTROL_BUDGET, windowMs: number = WINDOW_MS) {
    this.mediaLimit = mediaLimit
    this.controlLimit = controlLimit
    this.windowMs = windowMs
  }

  private roll(nowMs: number): void {
    if (this.windowStart === null || nowMs - this.windowStart >= this.windowMs) {
      this.windowStart = nowMs
      this.media = 0
      this.control = 0
    }
  }

  tryConsume(kind: BudgetKind, nowMs: number): boolean {
    this.roll(nowMs)
    if (kind === 'media') {
      if (this.media >= this.mediaLimit) return false
      this.media += 1
      return true
    }
    if (this.control >= this.controlLimit) return false
    this.control += 1
    return true
  }

  counts(nowMs: number): { media: number; control: number } {
    this.roll(nowMs)
    return { media: this.media, control: this.control }
  }
}
