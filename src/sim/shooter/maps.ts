/**
 * Real tactical-shooter maps. Valorant: callout positions from valorant-api.com (snapshot
 * src/data/real/valorantMaps.json), connections inferred from positions. CS2: see cs2Maps.ts.
 */
import VAL from '../../data/real/valorantMaps.json';
import { compileShooterMap, type ShooterMap, type ShooterMapSpec } from './map';
import { CS2_MAPS } from './cs2Maps';

const V = VAL as unknown as { as_of: string; source: string; maps: Array<{ id: string; name: string; sites: string; callouts: ShooterMapSpec['nodes'] }> };

export const VALORANT_MAP_SPECS: ShooterMapSpec[] = V.maps.map((m) => ({
  id: `valorant:${m.name.toLowerCase()}`,
  titleId: 'valorant',
  name: m.name,
  version: `valorant-api.com ${V.as_of}`,
  sources: [V.source],
  notes: [`${m.sites}. Callout positions are official game data (minimap space); connections between callouts are inferred from their positions (schematic).`],
  nodes: m.callouts,
}));

/** Competitive pool as of the snapshot (Strafe, V26 Act 6, 14 Oct 2026). */
export const VALORANT_POOL = ['summit', 'abyss', 'sunset', 'lotus', 'corrode', 'haven', 'bind'].map((x) => `valorant:${x}`);

const cache = new Map<string, ShooterMap>();
export function shooterMap(mapId: string): ShooterMap | null {
  let m = cache.get(mapId);
  if (m) return m;
  const spec = VALORANT_MAP_SPECS.find((s) => s.id === mapId) ?? CS2_MAPS.find((s) => s.id === mapId);
  if (!spec) return null;
  m = compileShooterMap(spec);
  cache.set(mapId, m);
  return m;
}
