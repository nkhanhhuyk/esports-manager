/**
 * Save codec: JSON compressed with gzip (CompressionStream, built into Android System WebView and
 * modern browsers) and stored as one binary value. One binary write/read is far faster and lighter
 * on phones than storing the world as a deep object graph, and the stored size drops ~6–8×.
 * Falls back to plain JSON text where CompressionStream is missing. Values written by older builds
 * (plain objects) are still read.
 */
export interface Packed {
  fmt: 'gz1' | 'json1';
  data: ArrayBuffer | string;
  /** Uncompressed JSON length (diagnostics). */
  n: number;
}

export function isPacked(v: unknown): v is Packed {
  return !!v && typeof v === 'object' && ((v as Packed).fmt === 'gz1' || (v as Packed).fmt === 'json1') && 'data' in (v as object);
}

const hasStreams = () => typeof CompressionStream !== 'undefined' && typeof DecompressionStream !== 'undefined' && typeof Response !== 'undefined' && typeof Blob !== 'undefined';

export async function pack(value: unknown): Promise<Packed> {
  const json = JSON.stringify(value);
  if (!hasStreams()) return { fmt: 'json1', data: json, n: json.length };
  const stream = new Blob([json]).stream().pipeThrough(new CompressionStream('gzip'));
  const data = await new Response(stream).arrayBuffer();
  return { fmt: 'gz1', data, n: json.length };
}

export async function unpack<T>(v: unknown): Promise<T> {
  if (!isPacked(v)) return v as T;
  if (v.fmt === 'json1') return JSON.parse(v.data as string) as T;
  const buf = v.data instanceof ArrayBuffer ? v.data : new Uint8Array(v.data as unknown as ArrayLike<number>).buffer;
  const stream = new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'));
  return JSON.parse(await new Response(stream).text()) as T;
}
