import type { CapacitorConfig } from '@capacitor/cli';

/**
 * Android packaging of the existing web build (Capacitor). The app ships the production `dist/`
 * bundle inside the APK and never loads a dev server or remote URL at runtime.
 * appId is a fictional reverse-domain identifier chosen for this project (see ANDROID_BUILD.md).
 */
const config: CapacitorConfig = {
  appId: 'dev.uem.esportsmanager',
  appName: 'Esports Manager',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
  },
  plugins: {
    SystemBars: {
      // Edge-to-edge WebView with --safe-area-inset-* CSS variables for the status/navigation bars.
      insetsHandling: 'css',
      initialViewportFitValueHint: 'cover',
      style: 'DARK',
    },
  },
};

export default config;
