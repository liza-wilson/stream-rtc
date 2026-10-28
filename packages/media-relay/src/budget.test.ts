import { describe, expect, it } from 'vitest'
import { Budget, CONTROL_BUDGET, MEDIA_BUDGET } from './budget.js'

describe('Budget', () => {
  it('allows up to the media limit then refuses', () => {
    const b = new Budget()
    for (let i = 0; i < MEDIA_BUDGET; i++) expect(b.tryConsume('media', 1000)).toBe(true)
    expect(b.tryConsume('media', 1000)).toBe(false)
  })

  it('keeps media and control budgets separate', () => {
    const b = new Budget()
    for (let i = 0; i < CONTROL_BUDGET; i++) b.tryConsume('control', 1000)
    expect(b.tryConsume('control', 1000)).toBe(false)
    expect(b.tryConsume('media', 1000)).toBe(true)
  })

  it('resets after the window elapses', () => {
    const b = new Budget(2, 2, 10_000)
    b.tryConsume('media', 0)
    b.tryConsume('media', 0)
    expect(b.tryConsume('media', 5_000)).toBe(false)
    expect(b.tryConsume('media', 10_001)).toBe(true)
  })
})
