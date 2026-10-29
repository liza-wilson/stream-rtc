import { describe, expect, it } from 'vitest'
import { metricsText, registerMetrics } from './metrics.js'

describe('metrics', () => {
  it('exposes the RTC gauges and counters', async () => {
    registerMetrics()
    registerMetrics()
    const text = await metricsText()
    expect(text).toContain('rtc_rooms_active')
    expect(text).toContain('rtc_participants_active')
    expect(text).toContain('rtc_frames_in_total')
    expect(text).toContain('rtc_frames_out_total')
    expect(text).toContain('rtc_bytes_egress_total')
    expect(text).toContain('rtc_subscriber_degraded_total')
    expect(text).toContain('rtc_keyframe_requests_total')
    expect(text).toContain('rtc_call_setup_seconds')
    expect(text).toContain('rtc_egress_gb_today')
  })
})
