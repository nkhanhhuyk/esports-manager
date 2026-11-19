/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import { fileURLToPath } from 'node:url';

// Relative base so the built bundle can be served from file:// (Capacitor/Android) or any sub-path.
// `--mode portable` produces one self-contained HTML file (worker inlined) that runs from disk.
export default defineConfig(({ mode }) => ({
  base: './',
  plugins: mode === 'portable' ? [react(), viteSingleFile()] : [react()],
  resolve:
    mode === 'portable'
      ? { alias: [{ find: /^\.\/factory$/, replacement: fileURLToPath(new URL('./src/worker/factory.inline.ts', import.meta.url)) }] }
      : undefined,
  worker: { format: 'es' },
  build:
    mode === 'portable'
      ? { target: 'es2022', sourcemap: false, chunkSizeWarningLimit: 4000, outDir: 'release', emptyOutDir: true }
      : { target: 'es2022', sourcemap: false, chunkSizeWarningLimit: 2000 },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
    testTimeout: 240000,
    hookTimeout: 240000,
    pool: 'forks',
  },
}));
