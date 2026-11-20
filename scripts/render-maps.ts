/** Development aid: render every compiled MOBA map (walls, lanes, nav graph, structures) to SVG. */
import { writeFileSync, mkdirSync } from 'node:fs';
import { mobaMap, MOBA_MAP_TITLES } from '../src/sim/moba/maps';
const out = process.argv[2] ?? 'map-renders';
mkdirSync(out, { recursive: true });
for (const id of MOBA_MAP_TITLES) {
  const t0 = Date.now();
  const m = mobaMap(id);
  const ms = Date.now() - t0;
  const parts: string[] = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000" width="800" height="800"><rect width="1000" height="1000" fill="#2c3b2a"/>`];
  for (const [x, y, w, h] of m.walls) parts.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="#17221a"/>`);
  for (const a of m.areas) parts.push(`<polygon points="${a.poly.map((p) => `${p.x},${p.y}`).join(' ')}" fill="${a.team ? '#6b2a2a' : '#2a4b6b'}" opacity="0.25"/>`);
  parts.push(`<polyline points="${m.river.map((p) => `${p.x},${p.y}`).join(' ')}" fill="none" stroke="#3b6f9c" stroke-width="40" opacity="0.5"/>`);
  for (const [a, list] of m.nav.adj.entries()) for (const [b] of list) if (a < b) parts.push(`<line x1="${m.nav.nodes[a].x}" y1="${m.nav.nodes[a].y}" x2="${m.nav.nodes[b].x}" y2="${m.nav.nodes[b].y}" stroke="#d8d29a" stroke-width="2" opacity="0.6"/>`);
  for (const l of ['top', 'mid', 'bot'] as const) parts.push(`<polyline points="${m.lanes[l].map((p) => `${p.x},${p.y}`).join(' ')}" fill="none" stroke="#c9b37a" stroke-width="5"/>`);
  for (const s of m.structures) parts.push(`<circle cx="${s.pos.x}" cy="${s.pos.y}" r="${s.kind === 'core' ? 22 : 11}" fill="${s.team ? '#e05555' : '#4a9be8'}" stroke="#fff"/><text x="${s.pos.x + 12}" y="${s.pos.y - 8}" font-size="14" fill="#fff">${s.id}</text>`);
  for (const c of m.camps) parts.push(`<rect x="${c.pos.x - 7}" y="${c.pos.y - 7}" width="14" height="14" fill="${c.kind === 'buff' ? '#c77dff' : c.kind === 'river' ? '#4ee' : '#8c8'}"/>`);
  for (const p of m.pits) parts.push(`<circle cx="${p.pos.x}" cy="${p.pos.y}" r="${p.radius}" fill="none" stroke="#ffd166" stroke-width="4"/><text x="${p.pos.x - 30}" y="${p.pos.y + p.radius + 16}" font-size="15" fill="#ffd166">${p.name}</text>`);
  // unreachable nodes
  const N = m.nav.nodes.length;
  let unreachable = 0;
  for (let j = 0; j < N; j++) if (!Number.isFinite(m.nav.dist[j])) unreachable++;
  parts.push(`<text x="10" y="990" font-size="18" fill="#fff">${m.name} · ${N} nodes · ${m.walls.length} wall rects · unreachable ${unreachable} · ${ms} ms</text></svg>`);
  writeFileSync(`${out}/${id}.svg`, parts.join(''));
  console.log(id, N, 'nodes', m.walls.length, 'walls', 'unreachable', unreachable, ms, 'ms');
}
