/** Development aid: render compiled shooter maps (walls, graph, callouts, sites) to SVG. */
import { mkdirSync, writeFileSync } from 'node:fs';
import { shooterMap, VALORANT_MAP_SPECS } from '../src/sim/shooter/maps';
import { CS2_MAPS } from '../src/sim/shooter/cs2Maps';
const out = process.argv[2] ?? 'shooter-renders';
mkdirSync(out, { recursive: true });
for (const s of [...VALORANT_MAP_SPECS, ...CS2_MAPS]) {
  const m = shooterMap(s.id)!;
  const p: string[] = [`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000" width="700" height="700"><rect width="1000" height="1000" fill="#c9c2b0"/>`];
  for (const [x, y, w, h] of m.walls) p.push(`<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="#2b2f36"/>`);
  for (const [a, b] of m.edges) p.push(`<line x1="${m.nodes[a].x}" y1="${m.nodes[a].y}" x2="${m.nodes[b].x}" y2="${m.nodes[b].y}" stroke="#8a6" stroke-width="3"/>`);
  for (const st of m.sites) p.push(`<circle cx="${st.x}" cy="${st.y}" r="${st.r}" fill="none" stroke="#e63" stroke-width="5"/><text x="${st.x}" y="${st.y + 12}" font-size="40" text-anchor="middle" fill="#e63">${st.id}</text>`);
  for (const n of m.nodes) p.push(`<circle cx="${n.x}" cy="${n.y}" r="6" fill="${n.kind === 'atk-spawn' ? '#d33' : n.kind === 'def-spawn' ? '#33d' : '#222'}"/><text x="${n.x + 8}" y="${n.y - 6}" font-size="17" fill="#111">${n.sup === 'Mid' || n.kind !== 'area' ? '' : n.sup + ' '}${n.name}</text>`);
  p.push(`<text x="10" y="990" font-size="26" fill="#111">${m.name} (${m.titleId}) · ${m.nodes.length} callouts · ${m.edges.length} links${m.inferredEdges ? ' (inferred)' : ''}</text></svg>`);
  writeFileSync(`${out}/${s.id.replace(':', '-')}.svg`, p.join(''));
}
console.log('done');
