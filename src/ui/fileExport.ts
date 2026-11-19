/**
 * Hand a file to the user. Browsers: a normal download. Android (Capacitor): WebViews ignore
 * <a download>, so the file is written to the app cache and offered through the system share sheet
 * (Save to Files / Drive / send). No storage permission is needed.
 */
import { Capacitor } from '@capacitor/core';
import { Directory, Encoding, Filesystem } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';

export async function saveFile(blob: Blob, name: string): Promise<{ ok: boolean; message: string }> {
  if (Capacitor.isNativePlatform()) {
    try {
      const res = await Filesystem.writeFile({ path: `exports/${name}`, data: await blob.text(), directory: Directory.Cache, encoding: Encoding.UTF8, recursive: true });
      await Share.share({ title: name, files: [res.uri], dialogTitle: 'Save or send the save file' });
      return { ok: true, message: 'Choose where to keep the save file (e.g. Files or Drive).' };
    } catch (e) {
      const msg = (e as Error)?.message ?? String(e);
      if (/cancel/i.test(msg)) return { ok: true, message: 'Export cancelled.' };
      return { ok: false, message: `Export failed: ${msg}` };
    }
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return { ok: true, message: `Exported ${name}.` };
}
