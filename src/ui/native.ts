/**
 * Android (Capacitor) integration. No-ops in the browser build.
 *  - System Back navigates back through the in-app history and exits only from Home/Welcome.
 *  - When Android backgrounds the app (it may be killed there), unsaved progress is autosaved.
 */
import { Capacitor } from '@capacitor/core';
import { App } from '@capacitor/app';
import { useGame } from './store';

function atRoot(): boolean {
  const h = window.location.hash.replace(/^#\/?/, '');
  return h === '' || h === 'home' || !useGame.getState().world;
}

export function initNative(): void {
  if (!Capacitor.isNativePlatform()) return;
  document.documentElement.classList.add('native');
  void App.addListener('backButton', () => {
    const sidebar = document.querySelector('.sidebar.open');
    if (sidebar) {
      // Close the open navigation drawer first.
      (document.querySelector('.hamburger') as HTMLButtonElement | null)?.click();
      return;
    }
    if (atRoot()) void App.exitApp();
    else window.history.back();
  });
  void App.addListener('pause', () => {
    const s = useGame.getState();
    if (s.world && s.unsaved && !s.busy) void s.save(true);
  });
}
