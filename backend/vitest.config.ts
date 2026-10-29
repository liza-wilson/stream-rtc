import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['packages/**/src/**/*.test.ts', 'tools/**/*.test.ts'],
    testTimeout: 15_000,
    hookTimeout: 15_000,
  },
})
