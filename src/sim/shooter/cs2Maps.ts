/**
 * Counter-Strike 2 maps — original schematic layouts (T spawn at the bottom, CT spawn at the top) authored
 * from public callout guides: route topology and callout names are what matter for the simulation;
 * positions are approximate. No game files or radar images are used. Active duty (July 2026 rotation,
 * csdb.gg): Mirage, Inferno, Dust II, Anubis, Ancient, Nuke, Cache; Train and Overpass are kept for
 * future rotations.
 */
import type { ShooterMapSpec } from './map';

const SOURCES = [
  'https://csdb.gg/map-callouts/ (CS2 map hub; July 2026 active duty)',
  'https://www.playnews.gg/en/guides/counter-strike-2-the-complete-callout-guide-for-every-active-duty-2026-map-mirage-inferno-ancient-nuke-dust-2-anubis-and-train',
  'https://tradeit.gg/blog/cs2-maps-callouts/',
];

type N = [string, string, number, number]; // sup, name, x, y
function spec(id: string, name: string, nodes: N[], edges: Array<[string, string]>, note: string): ShooterMapSpec {
  return {
    id: `cs2:${id}`,
    titleId: 'cs2',
    name,
    version: 'CS2 2026 (schematic)',
    sources: SOURCES,
    notes: [note, 'Schematic: callout names and route connections follow public callout guides; positions are approximate.'],
    nodes: nodes.map(([sup, nm, x, y]) => ({ sup, name: nm, x, y })),
    edges,
  };
}
const T = 'T Side/Spawn';
const CT = 'CT Side/Spawn';

export const CS2_MAPS: ShooterMapSpec[] = [
  spec(
    'dust2',
    'Dust II',
    [
      ['T Side', 'Spawn', 560, 930], ['A', 'Outside Long', 760, 810], ['A', 'Long Doors', 845, 690], ['A', 'Long', 880, 430], ['A', 'Pit', 930, 270],
      ['A', 'Site', 790, 180], ['A', 'Short', 640, 330], ['Mid', 'Top Mid', 560, 760], ['Mid', 'Mid', 545, 560], ['Mid', 'Mid Doors', 520, 380],
      ['B', 'Lower Tunnels', 380, 600], ['B', 'Upper Tunnels', 230, 690], ['B', 'Outside Tunnels', 360, 830], ['B', 'Site', 200, 220], ['B', 'Doors', 340, 260],
      ['CT Side', 'Spawn', 620, 110],
    ],
    [
      [T, 'A/Outside Long'], [T, 'Mid/Top Mid'], [T, 'B/Outside Tunnels'], ['A/Outside Long', 'A/Long Doors'], ['A/Long Doors', 'A/Long'], ['A/Long', 'A/Pit'], ['A/Long', 'A/Site'],
      ['A/Pit', 'A/Site'], ['A/Site', 'A/Short'], ['A/Short', 'Mid/Mid'], ['A/Site', CT], ['Mid/Top Mid', 'Mid/Mid'], ['Mid/Mid', 'Mid/Mid Doors'], ['Mid/Mid', 'B/Lower Tunnels'],
      ['Mid/Mid Doors', CT], ['Mid/Mid Doors', 'B/Doors'], ['B/Outside Tunnels', 'B/Upper Tunnels'], ['B/Upper Tunnels', 'B/Lower Tunnels'], ['B/Upper Tunnels', 'B/Site'],
      ['B/Site', 'B/Doors'], ['B/Doors', CT],
    ],
    'Long A and Short (Catwalk) to A; Upper Tunnels to B; Mid splits the map.',
  ),
  spec(
    'inferno',
    'Inferno',
    [
      ['T Side', 'Spawn', 300, 930], ['Mid', 'Second Mid', 430, 780], ['B', 'Banana', 220, 560], ['B', 'Car', 250, 380], ['B', 'Site', 290, 170], ['B', 'Construction', 420, 230],
      ['Mid', 'Mid', 520, 600], ['A', 'Apartments', 660, 730], ['A', 'Balcony', 770, 560], ['A', 'Pit', 890, 420], ['A', 'Site', 780, 330], ['A', 'Short', 690, 460],
      ['A', 'Library', 680, 250], ['Mid', 'Arch', 560, 300], ['CT Side', 'Spawn', 520, 110],
    ],
    [
      [T, 'Mid/Second Mid'], [T, 'B/Banana'], ['Mid/Second Mid', 'Mid/Mid'], ['Mid/Second Mid', 'A/Apartments'], ['Mid/Mid', 'A/Short'], ['A/Short', 'A/Site'], ['A/Apartments', 'A/Balcony'],
      ['A/Balcony', 'A/Site'], ['A/Balcony', 'A/Pit'], ['A/Pit', 'A/Site'], ['A/Site', 'A/Library'], ['A/Library', 'Mid/Arch'], ['Mid/Arch', CT], ['B/Banana', 'B/Car'], ['B/Car', 'B/Site'],
      ['B/Site', 'B/Construction'], ['B/Construction', CT], ['Mid/Arch', 'B/Construction'],
    ],
    'Banana into B; Apartments, Short and Mid into A; Arch links CT to both sites.',
  ),
  spec(
    'mirage',
    'Mirage',
    [
      ['T Side', 'Spawn', 520, 930], ['A', 'Ramp', 720, 720], ['A', 'Palace', 880, 590], ['A', 'Tetris', 700, 520], ['A', 'Site', 780, 340], ['A', 'Jungle', 620, 330],
      ['Mid', 'Top Mid', 520, 760], ['Mid', 'Mid', 480, 560], ['Mid', 'Connector', 560, 440], ['Mid', 'Window', 500, 320], ['Mid', 'Underpass', 360, 610], ['B', 'Short', 330, 430],
      ['B', 'Apartments', 190, 720], ['B', 'Kitchen', 160, 470], ['B', 'Site', 200, 300], ['B', 'Market', 330, 210], ['CT Side', 'Spawn', 560, 130],
    ],
    [
      [T, 'A/Ramp'], [T, 'A/Palace'], [T, 'Mid/Top Mid'], [T, 'B/Apartments'], ['A/Ramp', 'A/Tetris'], ['A/Ramp', 'A/Site'], ['A/Palace', 'A/Site'], ['A/Tetris', 'A/Site'],
      ['Mid/Top Mid', 'Mid/Mid'], ['Mid/Mid', 'Mid/Connector'], ['Mid/Connector', 'A/Jungle'], ['A/Jungle', 'A/Site'], ['A/Jungle', CT], ['A/Site', CT], ['Mid/Mid', 'Mid/Window'],
      ['Mid/Window', 'B/Market'], ['Mid/Mid', 'Mid/Underpass'], ['Mid/Mid', 'B/Short'], ['B/Short', 'B/Site'], ['B/Apartments', 'B/Kitchen'], ['B/Kitchen', 'B/Site'],
      ['B/Site', 'B/Market'], ['B/Market', CT], ['Mid/Underpass', 'B/Apartments'],
    ],
    'Ramp, Palace and Connector into A; Apartments and Short into B; Window controls Mid.',
  ),
  spec(
    'nuke',
    'Nuke',
    [
      ['T Side', 'Spawn', 500, 940], ['A', 'Lobby', 430, 720], ['A', 'Squeaky', 360, 560], ['A', 'Hut', 520, 560], ['A', 'Site', 470, 390], ['A', 'Heaven', 600, 300],
      ['A', 'Vents', 330, 360], ['B', 'Ramp', 250, 540], ['B', 'Site', 230, 260], ['Outside', 'Outside', 780, 640], ['Outside', 'Secret', 820, 360], ['CT Side', 'Spawn', 620, 120],
    ],
    [
      [T, 'A/Lobby'], [T, 'Outside/Outside'], ['A/Lobby', 'A/Squeaky'], ['A/Lobby', 'A/Hut'], ['A/Lobby', 'B/Ramp'], ['A/Squeaky', 'A/Site'], ['A/Hut', 'A/Site'], ['A/Site', 'A/Heaven'],
      ['A/Heaven', CT], ['A/Site', 'A/Vents'], ['A/Vents', 'B/Site'], ['B/Ramp', 'B/Site'], ['Outside/Outside', 'Outside/Secret'], ['Outside/Secret', 'B/Site'], ['Outside/Outside', CT],
      ['B/Site', CT],
    ],
    'Stacked sites drawn side by side: Upper (A) over Lower (B), joined by Vents; Outside and Secret flank.',
  ),
  spec(
    'ancient',
    'Ancient',
    [
      ['T Side', 'Spawn', 500, 930], ['A', 'Main', 250, 700], ['A', 'Site', 240, 320], ['Mid', 'Donut', 380, 450], ['Mid', 'Top Mid', 500, 760], ['Mid', 'Mid', 520, 580],
      ['B', 'Cave', 650, 560], ['B', 'Ramp', 800, 690], ['B', 'Site', 760, 320], ['Mid', 'CT Mid', 520, 330], ['CT Side', 'Spawn', 500, 120],
    ],
    [
      [T, 'A/Main'], [T, 'Mid/Top Mid'], [T, 'B/Ramp'], ['A/Main', 'A/Site'], ['Mid/Top Mid', 'Mid/Mid'], ['Mid/Mid', 'Mid/Donut'], ['Mid/Donut', 'A/Site'], ['Mid/Mid', 'B/Cave'],
      ['B/Cave', 'B/Site'], ['B/Ramp', 'B/Site'], ['B/Cave', 'B/Ramp'], ['Mid/Mid', 'Mid/CT Mid'], ['Mid/CT Mid', CT], ['A/Site', CT], ['B/Site', CT],
    ],
    'A Main and Donut into A; Ramp and Cave into B; Mid connects both.',
  ),
  spec(
    'anubis',
    'Anubis',
    [
      ['T Side', 'Spawn', 500, 930], ['A', 'Main', 780, 700], ['A', 'Site', 760, 320], ['Mid', 'Mid', 520, 720], ['Mid', 'Canal', 520, 560], ['Mid', 'Bridge', 520, 400],
      ['A', 'Connector', 640, 300], ['B', 'Main', 240, 680], ['B', 'Site', 250, 320], ['CT Side', 'Spawn', 500, 120],
    ],
    [
      [T, 'A/Main'], [T, 'Mid/Mid'], [T, 'B/Main'], ['A/Main', 'A/Site'], ['Mid/Mid', 'Mid/Canal'], ['Mid/Canal', 'Mid/Bridge'], ['Mid/Bridge', 'A/Connector'], ['A/Connector', 'A/Site'],
      ['Mid/Canal', 'B/Site'], ['B/Main', 'B/Site'], ['A/Site', CT], ['B/Site', CT], ['Mid/Bridge', CT],
    ],
    'Canal and Bridge control the middle; long mains into each site.',
  ),
  spec(
    'cache',
    'Cache',
    [
      ['T Side', 'Spawn', 500, 930], ['A', 'Main', 780, 640], ['A', 'Site', 760, 300], ['A', 'Highway', 650, 420], ['Mid', 'Mid', 500, 600], ['Mid', 'Garage', 360, 520],
      ['Mid', 'Vents', 450, 380], ['B', 'Main', 250, 650], ['B', 'Site', 230, 320], ['B', 'Checkers', 360, 270], ['CT Side', 'Spawn', 500, 120],
    ],
    [
      [T, 'A/Main'], [T, 'Mid/Mid'], [T, 'B/Main'], ['A/Main', 'A/Site'], ['Mid/Mid', 'A/Highway'], ['A/Highway', 'A/Site'], ['Mid/Mid', 'Mid/Garage'], ['Mid/Garage', 'B/Site'],
      ['Mid/Mid', 'Mid/Vents'], ['Mid/Vents', 'B/Checkers'], ['B/Main', 'B/Site'], ['B/Site', 'B/Checkers'], ['B/Checkers', CT], ['A/Site', CT], ['A/Highway', CT],
    ],
    'Long A Main and Highway into A; B Main and Garage into B; Checkers links CT.',
  ),
  spec(
    'train',
    'Train',
    [
      ['T Side', 'Spawn', 500, 930], ['A', 'Ivy', 760, 640], ['A', 'Main', 520, 620], ['A', 'Site', 560, 330], ['Mid', 'Pop Dog', 360, 560], ['Mid', 'Connector', 420, 400],
      ['B', 'Halls', 240, 650], ['B', 'Site', 240, 300], ['B', 'Ladder Room', 360, 220], ['CT Side', 'Spawn', 600, 120],
    ],
    [
      [T, 'A/Ivy'], [T, 'A/Main'], [T, 'B/Halls'], ['A/Ivy', 'A/Site'], ['A/Main', 'A/Site'], ['A/Main', 'Mid/Pop Dog'], ['Mid/Pop Dog', 'Mid/Connector'], ['Mid/Connector', 'A/Site'],
      ['Mid/Connector', 'B/Ladder Room'], ['B/Halls', 'B/Site'], ['B/Site', 'B/Ladder Room'], ['B/Ladder Room', CT], ['A/Site', CT],
    ],
    'Ivy and Main into the trains of A; Halls into B; Pop Dog and Connector link them.',
  ),
  spec(
    'overpass',
    'Overpass',
    [
      ['T Side', 'Spawn', 500, 930], ['Mid', 'Fountain', 420, 760], ['A', 'Long', 220, 600], ['A', 'Bathrooms', 300, 420], ['A', 'Site', 250, 290], ['Mid', 'Connector', 480, 420],
      ['B', 'Water', 620, 560], ['B', 'Monster', 800, 650], ['B', 'Site', 760, 330], ['B', 'Heaven', 640, 270], ['CT Side', 'Spawn', 500, 120],
    ],
    [
      [T, 'Mid/Fountain'], [T, 'B/Monster'], ['Mid/Fountain', 'A/Long'], ['A/Long', 'A/Site'], ['A/Long', 'A/Bathrooms'], ['A/Bathrooms', 'A/Site'], ['Mid/Fountain', 'B/Water'],
      ['B/Water', 'Mid/Connector'], ['Mid/Connector', 'A/Site'], ['Mid/Connector', 'B/Site'], ['B/Water', 'B/Site'], ['B/Monster', 'B/Site'], ['B/Site', 'B/Heaven'], ['B/Heaven', CT], ['A/Site', CT],
    ],
    'Long A and Bathrooms into A; Monster and Water (Short) into B; Connector mid.',
  ),
];

export const CS2_POOL = ['mirage', 'inferno', 'dust2', 'anubis', 'ancient', 'nuke', 'cache'].map((x) => `cs2:${x}`);
