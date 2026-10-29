import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { parse } from 'yaml'

describe('render.yaml', () => {
  const cfg = parse(readFileSync('../render.yaml', 'utf8'))

  it('declares exactly the combined control+relay service', () => {
    expect(cfg.services.map((s: { name: string }) => s.name).sort()).toEqual([
      'streamrtc',
    ])
  })

  it('declares no database or key-value service', () => {
    expect(cfg.databases ?? []).toEqual([])
    expect(
      cfg.services.some((s: { type?: string }) => /redis|keyvalue/i.test(s.type ?? '')),
    ).toBe(false)
  })

  it('gives both services a health check and the shared config file', () => {
    for (const service of cfg.services) {
      expect(service.healthCheckPath).toBe('/healthz')
      expect(
        service.envVars.some((e: { key: string }) => e.key === 'STREAMRTC_CONFIG_FILE'),
      ).toBe(true)
    }
  })
})
