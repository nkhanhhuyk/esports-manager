/**
 * Picture links for real characters, weapons, teams, organisations, players, leagues and events
 * (src/data/real/images.json, built by scripts/data/images.mjs). Pictures are loaded from the owners'
 * own servers when the device is online; nothing is bundled. Without a link, offline, or when a
 * picture fails to load, the game shows its own original badge. Online pictures can be switched off
 * in Settings (stored on this device only).
 */
import IMAGES from '../data/real/images.json';
import { orgKey } from '../gen/realWorld';
import type { Competition, League, Organisation, Player, Team, World } from '../model/types';

interface ImageSnapshot {
  characters: Record<string, string>;
  weapons: Record<string, string>;
  teams: Record<string, string>;
  players: Record<string, string>;
  leagues: Record<string, string>;
  events: Record<string, string>;
}
const IMG = IMAGES as unknown as ImageSnapshot;
const norm = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]/g, '');

const KEY = 'uem-online-images';
export function onlinePictures(): boolean {
  try {
    return localStorage.getItem(KEY) !== '0';
  } catch {
    return true;
  }
}
export function setOnlinePictures(on: boolean): void {
  try {
    localStorage.setItem(KEY, on ? '1' : '0');
  } catch {
    /* storage unavailable: keep the default */
  }
}

/** Organisation logos: any of the organisation's real teams, by organisation name key. */
let orgIndex: Map<string, string> | null = null;
function orgLogos(): Map<string, string> {
  if (orgIndex) return orgIndex;
  orgIndex = new Map();
  // Prefer LoL, then Valorant, Dota 2, MLBB logos for organisations with several teams.
  const order = ['lol', 'valorant', 'dota2', 'mlbb'];
  const entries = Object.entries(IMG.teams).sort((a, b) => order.indexOf(a[0].split('|')[0]) - order.indexOf(b[0].split('|')[0]));
  for (const [k, url] of entries) {
    const name = k.slice(k.indexOf('|') + 1);
    const ok = orgKey(name);
    if (!orgIndex.has(ok)) orgIndex.set(ok, url);
    if (!orgIndex.has(name)) orgIndex.set(name, url);
  }
  return orgIndex;
}

export function charPic(charId: string | undefined): string | undefined {
  return charId ? IMG.characters[charId] : undefined;
}

export function weaponPic(titleId: string, name: string | undefined): string | undefined {
  return name ? IMG.weapons[`${titleId}|${norm(name)}`] : undefined;
}

export function teamPic(world: World, team: Team | undefined): string | undefined {
  if (!team) return undefined;
  if (team.real) {
    const own = IMG.teams[`${team.titleId}|${norm(team.name)}`];
    if (own) return own;
  }
  return orgPic(world.orgs[team.orgId]);
}

export function orgPic(org: Organisation | undefined): string | undefined {
  if (!org?.real) return undefined;
  const m = orgLogos();
  return m.get(orgKey(org.name)) ?? m.get(norm(org.name));
}

export function playerPic(p: Player | undefined): string | undefined {
  if (!p?.real) return undefined;
  return IMG.players[`${p.titleId}|${norm(p.handle)}`];
}

export function leaguePic(league: League | undefined): string | undefined {
  if (!league?.real) return undefined;
  return IMG.leagues[`${league.titleId}|${norm(league.name)}`] ?? IMG.leagues[`${league.titleId}|${norm(league.short)}`];
}

export function compPic(world: World, comp: Pick<Competition, 'leagueId' | 'tier' | 'titleId' | 'realName' | 'rules' | 'name'> | undefined): string | undefined {
  if (!comp) return undefined;
  if (comp.leagueId) return leaguePic(world.leagues[comp.leagueId]);
  if (!comp.realName) return undefined;
  const ev = comp.tier === 'worlds' ? 'worlds' : comp.rules?.fearless === 'global' || /first stand/i.test(comp.name) ? 'fs' : comp.tier === 'international' ? 'msi' : null;
  return ev ? IMG.events[`${comp.titleId}|${ev}`] : undefined;
}

/** URLs that failed to load this session (not retried). */
export const failedPics = new Set<string>();
