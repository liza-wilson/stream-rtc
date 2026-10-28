import { collectDefaultMetrics, Counter, Gauge, Histogram, Registry } from 'prom-client'

export const registry = new Registry()

export interface Metrics {
  roomsActive: Gauge<'service'>
  participantsActive: Gauge<'service'>
  framesIn: Counter<'service' | 'media'>
  framesOut: Counter<'service' | 'media'>
  bytesEgress: Counter<'service' | 'media'>
  subscribersDegraded: Counter<'service'>
  keyframeRequests: Counter<'service'>
  callSetup: Histogram<'outcome'>
  egressGbToday: Gauge<'service'>
}

let registered = false
let instance: Metrics | undefined

export function registerMetrics(reg: Registry = registry): Metrics {
  if (registered && instance) return instance
  registered = true

  collectDefaultMetrics({ register: reg, prefix: '' })

  const metrics: Metrics = {
    roomsActive: new Gauge({
      name: 'rtc_rooms_active',
      help: 'Rooms with at least one registered participant',
      labelNames: ['service'] as const,
      registers: [reg],
    }),
    participantsActive: new Gauge({
      name: 'rtc_participants_active',
      help: 'Registered media participants',
      labelNames: ['service'] as const,
      registers: [reg],
    }),
    framesIn: new Counter({
      name: 'rtc_frames_in_total',
      help: 'Media frames accepted from clients',
      labelNames: ['service', 'media'] as const,
      registers: [reg],
    }),
    framesOut: new Counter({
      name: 'rtc_frames_out_total',
      help: 'Media frames forwarded to clients',
      labelNames: ['service', 'media'] as const,
      registers: [reg],
    }),
    bytesEgress: new Counter({
      name: 'rtc_bytes_egress_total',
      help: 'Bytes written to client sockets',
      labelNames: ['service', 'media'] as const,
      registers: [reg],
    }),
    subscribersDegraded: new Counter({
      name: 'rtc_subscriber_degraded_total',
      help: 'Times a subscriber was dropped to audio-only',
      labelNames: ['service'] as const,
      registers: [reg],
    }),
    keyframeRequests: new Counter({
      name: 'rtc_keyframe_requests_total',
      help: 'Keyframe requests sent to publishers',
      labelNames: ['service'] as const,
      registers: [reg],
    }),
    callSetup: new Histogram({
      name: 'rtc_call_setup_seconds',
      help: 'Time from call.invite to both room tokens sent',
      labelNames: ['outcome'] as const,
      buckets: [0.1, 0.25, 0.5, 1, 2, 3, 5, 10],
      registers: [reg],
    }),
    egressGbToday: new Gauge({
      name: 'rtc_egress_gb_today',
      help: 'Egress in GB since UTC midnight (predicts the bill)',
      labelNames: ['service'] as const,
      registers: [reg],
    }),
  }

  instance = metrics
  return metrics
}

export function metrics(): Metrics {
  return registerMetrics()
}

export function metricsText(reg: Registry = registry): Promise<string> {
  registerMetrics(reg)
  return reg.metrics()
}
