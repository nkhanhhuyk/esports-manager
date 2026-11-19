/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Relative base so the built bundle can be served from file:// (Capacitor/Android) or any sub-path.
export default defineConfig({
  base: './',
  plugins: [react()],
  worker: { format: 'es' },
  build: { target: 'es2022', sourcemap: false, chunkSizeWarningLimit: 2000 },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    testTimeout: 240000,
    hookTimeout: 240000,
    pool: 'forks',
  },
});
