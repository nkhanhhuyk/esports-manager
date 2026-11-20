/**
 * The five MOBA battlefields. Each spec is original schematic geometry (no game art is used),
 * authored from the public references listed in `sources` (see DATA_SOURCES.md → Maps). Team 0
 * (Blue / Radiant) sits bottom-left; team 1's half is the point reflection of team 0's.
 */
import { compileMap, type MapDefinition, type MapSpec, type Pt } from './map';

const P = (x: number, y: number): Pt => ({ x, y });

// ---------------------------------------------------------------------------------------------
// Mobile Legends: Bang Bang — Land of Dawn
// ---------------------------------------------------------------------------------------------
const MLBB: MapSpec = {
  id: 'mlbb-land-of-dawn',
  titleId: 'mlbb',
  name: 'Land of Dawn',
  version: '2026.11 (Season 39 standard map)',
  sideNames: ['Blue', 'Red'],
  orientation: 'Blue base bottom-left, Red top-right; EXP lane top, Gold lane bottom (Blue view); river top-left → bottom-right.',
  sources: [
    'https://mlbbhub.com/map (community schematic: lanes, 3 turrets per lane, buffs/creeps/crabs, Turtle & Lord pits, timers)',
    'https://www.oneesports.gg/mobile-legends/3-pro-tips-secure-turtle-mlbb/ (first Turtle spawns on the EXP-lane side)',
    'https://www.vcgamers.com/news/en/?p=101995 (Gold lane is the side away from the first Turtle)',
  ],
  notes: [
    'Turtle 2:00, respawn 2:00 until the Lord appears; first Turtle in the EXP-side pit, later spawns pick a pit at random.',
    'Lord 8:00, respawn 3:00 (2:30 after 18:00); evolves at 18:00; a slain Lord pushes a lane.',
    'Lithowanderer river creatures 0:45, respawn 2:00; buffs 0:30, respawn 1:30.',
    'Base Crystal becomes vulnerable once any lane’s base turret falls (game rule as simulated).',
  ],
  moveSpeed: 27,
  laneNames: { top: 'EXP Lane', mid: 'Mid Lane', bot: 'Gold Lane' },
  lanes: {
    top: [P(120, 790), P(120, 600), P(120, 400), P(120, 200), P(130, 130), P(200, 120), P(400, 120), P(600, 120), P(800, 120)],
    mid: [P(185, 815), P(300, 700), P(400, 600), P(500, 500), P(600, 400), P(700, 300), P(815, 185)],
  },
  bases: P(120, 880),
  fountain: P(55, 945),
  structures: [
    { id: 'top-t1', kind: 'tower', lane: 'top', tier: 1, name: 'EXP Outer Turret', pos: P(120, 280), hp: 6000, dps: 230, range: 55 },
    { id: 'top-t2', kind: 'tower', lane: 'top', tier: 2, name: 'EXP Inner Turret', pos: P(120, 450), hp: 6500, dps: 260, range: 55, requires: ['top-t1'] },
    { id: 'top-t3', kind: 'tower', lane: 'top', tier: 3, name: 'EXP Base Turret', pos: P(120, 650), hp: 7000, dps: 300, range: 55, requires: ['top-t2'] },
    { id: 'mid-t1', kind: 'tower', lane: 'mid', tier: 1, name: 'Mid Outer Turret', pos: P(430, 570), hp: 6000, dps: 230, range: 55 },
    { id: 'mid-t2', kind: 'tower', lane: 'mid', tier: 2, name: 'Mid Inner Turret', pos: P(350, 650), hp: 6500, dps: 260, range: 55, requires: ['mid-t1'] },
    { id: 'mid-t3', kind: 'tower', lane: 'mid', tier: 3, name: 'Mid Base Turret', pos: P(250, 750), hp: 7000, dps: 300, range: 55, requires: ['mid-t2'] },
    { id: 'bot-t1', kind: 'tower', lane: 'bot', tier: 1, name: 'Gold Outer Turret', pos: P(720, 880), hp: 6000, dps: 230, range: 55 },
    { id: 'bot-t2', kind: 'tower', lane: 'bot', tier: 2, name: 'Gold Inner Turret', pos: P(550, 880), hp: 6500, dps: 260, range: 55, requires: ['bot-t1'] },
    { id: 'bot-t3', kind: 'tower', lane: 'bot', tier: 3, name: 'Gold Base Turret', pos: P(350, 880), hp: 7000, dps: 300, range: 55, requires: ['bot-t2'] },
    { id: 'core', kind: 'core', lane: null, tier: 9, name: 'Base Crystal', pos: P(120, 880), hp: 9000, dps: 320, range: 60, requires: [] },
  ],
  camps: [
    { id: 'purple', name: 'Purple Buff', kind: 'buff', pos: P(250, 600), hp: 2400, gold: 90, xp: 180, spawn: 30, respawn: 90 },
    { id: 'orange', name: 'Orange Buff', kind: 'buff', pos: P(320, 800), hp: 2400, gold: 90, xp: 180, spawn: 30, respawn: 90 },
    { id: 'creep-a', name: 'Jungle Creep', kind: 'camp', pos: P(420, 750), hp: 1500, gold: 60, xp: 120, spawn: 30, respawn: 90 },
    { id: 'creep-b', name: 'Jungle Creep', kind: 'camp', pos: P(180, 480), hp: 1500, gold: 60, xp: 120, spawn: 30, respawn: 90 },
    { id: 'creep-c', name: 'Jungle Creep', kind: 'camp', pos: P(200, 680), hp: 1500, gold: 60, xp: 120, spawn: 30, respawn: 90 },
  ],
  riverCamps: [
    { id: 'crab', name: 'Scavenger Crab', kind: 'river', pos: P(185, 185), hp: 1800, gold: 70, xp: 90, spawn: 120, respawn: 120 },
    { id: 'litho', name: 'Lithowanderer', kind: 'river', pos: P(445, 445), hp: 1600, gold: 50, xp: 80, spawn: 45, respawn: 120 },
  ],
  pits: [
    { id: 'turtle-pit', name: 'Turtle Pit (EXP side)', pos: P(285, 285), radius: 24, objectives: ['turtle', 'lord'] },
    { id: 'lord-pit', name: 'Lord Pit (Gold side)', pos: P(715, 715), radius: 24, objectives: ['lord', 'turtle'] },
  ],
  river: [P(60, 60), P(285, 285), P(500, 500), P(715, 715), P(940, 940)],
  paths: [
    // Gold-side (bottom) jungle
    [P(205, 855), P(320, 800), P(420, 750), P(560, 650), P(640, 640)],
    [P(320, 800), P(330, 880)],
    [P(420, 750), P(470, 880)],
    [P(420, 750), P(450, 560)],
    // EXP-side (top) jungle
    [P(120, 760), P(200, 680), P(250, 600), P(180, 480), P(120, 420)],
    [P(250, 600), P(360, 640)],
    [P(250, 600), P(330, 470), P(300, 400), P(285, 285)],
    [P(180, 480), P(240, 380), P(300, 400)],
    [P(240, 380), P(185, 185)],
  ],
  widths: { lane: 34, path: 18, river: 38, base: 95 },
};

// ---------------------------------------------------------------------------------------------
// League of Legends: Wild Rift — Summoner's Rift (Wild Rift)
// ---------------------------------------------------------------------------------------------
const WR: MapSpec = {
  id: 'wr-summoners-rift',
  titleId: 'wildrift',
  name: "Summoner's Rift (Wild Rift)",
  version: 'Patch 6.3 (2026)',
  sideNames: ['Blue', 'Red'],
  orientation: 'Blue base bottom-left, Red top-right; Baron lane top, Dragon lane bottom; river top-left → bottom-right.',
  sources: [
    'https://www.riftpatchnotes.com/unit/baron-nashor (Wild Rift: Baron spawn 11:00 since 6.2, respawn 3:30)',
    'https://www.riftpatchnotes.com/unit/rift-herald (Wild Rift: Herald in the Baron pit, targets turrets only since 5.0)',
    'https://www.riftpatchnotes.com/unit/elder-dragon (Wild Rift: Elder Dragon 19:00 since 6.2)',
    'https://wildrift.leagueoflegends.com/en-gb/news/game-updates/wild-rift-patch-notes-52 (official patch notes)',
  ],
  notes: [
    'Compact mobile map: shorter lanes and faster rotations than PC; three turrets per lane plus an inhibitor (rebuilds after 4:00).',
    'Elemental dragons from 4:00 (game preset, not verified for 6.3); Rift Herald in the Baron pit before Baron.',
    'Nexus without separate Nexus turrets in this model (simplification, not verified).',
  ],
  moveSpeed: 31,
  laneNames: { top: 'Baron Lane', mid: 'Mid Lane', bot: 'Dragon Lane' },
  lanes: {
    top: [P(130, 780), P(110, 620), P(105, 420), P(110, 230), P(150, 150), P(230, 110), P(420, 105), P(620, 110), P(780, 130)],
    mid: [P(190, 810), P(290, 710), P(395, 605), P(500, 500), P(605, 395), P(710, 290), P(810, 190)],
  },
  bases: P(130, 870),
  fountain: P(60, 940),
  structures: [
    { id: 'top-t1', kind: 'tower', lane: 'top', tier: 1, name: 'Baron Outer Turret', pos: P(108, 300), hp: 5000, dps: 200, range: 50 },
    { id: 'top-t2', kind: 'tower', lane: 'top', tier: 2, name: 'Baron Inner Turret', pos: P(110, 520), hp: 5200, dps: 220, range: 50, requires: ['top-t1'] },
    { id: 'top-t3', kind: 'tower', lane: 'top', tier: 3, name: 'Baron Inhibitor Turret', pos: P(118, 690), hp: 5500, dps: 250, range: 50, requires: ['top-t2'] },
    { id: 'top-inh', kind: 'inhibitor', lane: 'top', tier: 4, name: 'Baron Inhibitor', pos: P(125, 750), hp: 4000, dps: 0, range: 0, requires: ['top-t3'], respawn: 240 },
    { id: 'mid-t1', kind: 'tower', lane: 'mid', tier: 1, name: 'Mid Outer Turret', pos: P(410, 590), hp: 5000, dps: 200, range: 50 },
    { id: 'mid-t2', kind: 'tower', lane: 'mid', tier: 2, name: 'Mid Inner Turret', pos: P(330, 670), hp: 5200, dps: 220, range: 50, requires: ['mid-t1'] },
    { id: 'mid-t3', kind: 'tower', lane: 'mid', tier: 3, name: 'Mid Inhibitor Turret', pos: P(250, 750), hp: 5500, dps: 250, range: 50, requires: ['mid-t2'] },
    { id: 'mid-inh', kind: 'inhibitor', lane: 'mid', tier: 4, name: 'Mid Inhibitor', pos: P(212, 788), hp: 4000, dps: 0, range: 0, requires: ['mid-t3'], respawn: 240 },
    { id: 'bot-t1', kind: 'tower', lane: 'bot', tier: 1, name: 'Dragon Outer Turret', pos: P(700, 892), hp: 5000, dps: 200, range: 50 },
    { id: 'bot-t2', kind: 'tower', lane: 'bot', tier: 2, name: 'Dragon Inner Turret', pos: P(480, 890), hp: 5200, dps: 220, range: 50, requires: ['bot-t1'] },
    { id: 'bot-t3', kind: 'tower', lane: 'bot', tier: 3, name: 'Dragon Inhibitor Turret', pos: P(310, 882), hp: 5500, dps: 250, range: 50, requires: ['bot-t2'] },
    { id: 'bot-inh', kind: 'inhibitor', lane: 'bot', tier: 4, name: 'Dragon Inhibitor', pos: P(250, 875), hp: 4000, dps: 0, range: 0, requires: ['bot-t3'], respawn: 240 },
    { id: 'core', kind: 'core', lane: null, tier: 9, name: 'Nexus', pos: P(130, 870), hp: 7000, dps: 0, range: 0, requires: [] },
  ],
  camps: [
    { id: 'blue', name: 'Blue Sentinel', kind: 'buff', pos: P(265, 480), hp: 2600, gold: 90, xp: 200, spawn: 60, respawn: 150 },
    { id: 'gromp', name: 'Gromp', kind: 'camp', pos: P(165, 440), hp: 1800, gold: 70, xp: 140, spawn: 60, respawn: 120 },
    { id: 'wolves', name: 'Murk Wolves', kind: 'camp', pos: P(260, 575), hp: 1600, gold: 70, xp: 130, spawn: 60, respawn: 120 },
    { id: 'raptors', name: 'Raptors', kind: 'camp', pos: P(470, 640), hp: 1500, gold: 65, xp: 120, spawn: 60, respawn: 120 },
    { id: 'red', name: 'Red Brambleback', kind: 'buff', pos: P(530, 730), hp: 2600, gold: 90, xp: 200, spawn: 60, respawn: 150 },
  ],
  riverCamps: [{ id: 'scuttle', name: 'Rift Scuttler', kind: 'river', pos: P(300, 360), hp: 1700, gold: 80, xp: 100, spawn: 150, respawn: 150 }],
  pits: [
    { id: 'baron-pit', name: 'Baron Pit', pos: P(335, 305), radius: 26, objectives: ['herald', 'baron'] },
    { id: 'dragon-pit', name: 'Dragon Pit', pos: P(665, 695), radius: 26, objectives: ['drake', 'elder'] },
  ],
  river: [P(95, 95), P(320, 320), P(500, 500), P(680, 680), P(905, 905)],
  paths: [
    [P(110, 640), P(190, 600), P(260, 575), P(310, 630), P(345, 655)],
    [P(165, 440), P(265, 480), P(260, 575)],
    [P(165, 440), P(106, 430)],
    [P(265, 480), P(300, 400), P(300, 360), P(330, 330)],
    [P(265, 480), P(360, 520), P(420, 580)],
    [P(420, 580), P(470, 640), P(530, 730), P(580, 800), P(580, 893)],
    [P(470, 640), P(570, 570)],
    [P(530, 730), P(620, 740), P(665, 695)],
    [P(230, 845), P(380, 790), P(530, 730)],
    [P(185, 805), P(220, 700), P(260, 575)],
    [P(300, 360), P(335, 305)],
  ],
  widths: { lane: 32, path: 17, river: 36, base: 90 },
};

// ---------------------------------------------------------------------------------------------
// Honor of Kings — Kings' Canyon (5v5 map)
// ---------------------------------------------------------------------------------------------
const HOK: MapSpec = {
  id: 'hok-kings-canyon',
  titleId: 'hok',
  name: "Kings' Canyon",
  version: '2026 global standard map',
  sideNames: ['Blue', 'Red'],
  orientation: 'Blue base bottom-left, Red top-right; Clash lane top, Farm lane bottom (Blue view); Overlord pit by the Clash lane, Tyrant pit by the Farm lane.',
  sources: [
    'https://sportskeeda.com/esports/honor-kings-hero-s-gorge-map-guide-overview-monsters-and-more (map overview, monsters)',
    'https://www.bluestacks.com/blog/game-guides/honor-of-kings/hok-map-guide-en.html (lanes, three towers per lane, Tyrant/Overlord/Tempest Dragon)',
    'https://bittopup.com/ms/article/Honor-of-Kings-Dragon-Timers-2025-Complete-Guide-Tips (Tyrant/Overlord 4:00, Shadow forms 10:00, Tempest Dragon 20:00)',
  ],
  notes: [
    'Tyrant and Overlord from 4:00 (respawn 4:00); Shadow Tyrant / Shadow Overlord from 10:00 (respawn 3:30); Tempest Dragon from 20:00 in either pit (respawn 3:00).',
    'Sides follow the Chinese-server convention (主宰 by the Clash lane, 暴君 by the Farm lane, Blue view); some English guides describe the mirrored view.',
    'Crystal becomes vulnerable once any high-ground tower falls (game rule as simulated).',
  ],
  moveSpeed: 26,
  laneNames: { top: 'Clash Lane', mid: 'Mid Lane', bot: 'Farm Lane' },
  lanes: {
    top: [P(150, 780), P(140, 620), P(135, 420), P(145, 250), P(185, 185), P(250, 145), P(420, 135), P(620, 140), P(780, 150)],
    mid: [P(205, 795), P(310, 690), P(405, 595), P(500, 500), P(595, 405), P(690, 310), P(795, 205)],
  },
  bases: P(140, 860),
  fountain: P(70, 930),
  structures: [
    { id: 'top-t1', kind: 'tower', lane: 'top', tier: 1, name: 'Clash Outer Tower', pos: P(140, 330), hp: 6500, dps: 240, range: 55 },
    { id: 'top-t2', kind: 'tower', lane: 'top', tier: 2, name: 'Clash Inner Tower', pos: P(140, 520), hp: 7000, dps: 270, range: 55, requires: ['top-t1'] },
    { id: 'top-t3', kind: 'tower', lane: 'top', tier: 3, name: 'Clash High-ground Tower', pos: P(148, 700), hp: 7500, dps: 310, range: 55, requires: ['top-t2'] },
    { id: 'mid-t1', kind: 'tower', lane: 'mid', tier: 1, name: 'Mid Outer Tower', pos: P(420, 580), hp: 6500, dps: 240, range: 55 },
    { id: 'mid-t2', kind: 'tower', lane: 'mid', tier: 2, name: 'Mid Inner Tower', pos: P(340, 660), hp: 7000, dps: 270, range: 55, requires: ['mid-t1'] },
    { id: 'mid-t3', kind: 'tower', lane: 'mid', tier: 3, name: 'Mid High-ground Tower', pos: P(262, 738), hp: 7500, dps: 310, range: 55, requires: ['mid-t2'] },
    { id: 'bot-t1', kind: 'tower', lane: 'bot', tier: 1, name: 'Farm Outer Tower', pos: P(670, 860), hp: 6500, dps: 240, range: 55 },
    { id: 'bot-t2', kind: 'tower', lane: 'bot', tier: 2, name: 'Farm Inner Tower', pos: P(480, 865), hp: 7000, dps: 270, range: 55, requires: ['bot-t1'] },
    { id: 'bot-t3', kind: 'tower', lane: 'bot', tier: 3, name: 'Farm High-ground Tower', pos: P(300, 855), hp: 7500, dps: 310, range: 55, requires: ['bot-t2'] },
    { id: 'core', kind: 'core', lane: null, tier: 9, name: 'Crystal', pos: P(140, 860), hp: 9500, dps: 330, range: 60, requires: [] },
  ],
  camps: [
    { id: 'blue', name: 'Blue Buff', kind: 'buff', pos: P(255, 560), hp: 2500, gold: 90, xp: 180, spawn: 30, respawn: 90 },
    { id: 'red', name: 'Red Buff', kind: 'buff', pos: P(445, 735), hp: 2500, gold: 90, xp: 180, spawn: 30, respawn: 90 },
    { id: 'camp-a', name: 'Jungle Camp', kind: 'camp', pos: P(210, 455), hp: 1500, gold: 60, xp: 110, spawn: 30, respawn: 90 },
    { id: 'camp-b', name: 'Jungle Camp', kind: 'camp', pos: P(230, 650), hp: 1500, gold: 60, xp: 110, spawn: 30, respawn: 90 },
    { id: 'camp-c', name: 'Jungle Camp', kind: 'camp', pos: P(545, 760), hp: 1500, gold: 60, xp: 110, spawn: 30, respawn: 90 },
    { id: 'camp-d', name: 'Jungle Camp', kind: 'camp', pos: P(360, 790), hp: 1500, gold: 60, xp: 110, spawn: 30, respawn: 90 },
  ],
  riverCamps: [{ id: 'sprite', name: 'River Sprite', kind: 'river', pos: P(420, 420), hp: 1400, gold: 55, xp: 80, spawn: 60, respawn: 120 }],
  pits: [
    { id: 'overlord-pit', name: 'Overlord Pit (Clash side)', pos: P(300, 300), radius: 26, objectives: ['overlord', 'shadow', 'tempest'] },
    { id: 'tyrant-pit', name: 'Tyrant Pit (Farm side)', pos: P(700, 700), radius: 26, objectives: ['tyrant', 'shadowtyrant', 'tempest'] },
  ],
  river: [P(70, 70), P(300, 300), P(500, 500), P(700, 700), P(930, 930)],
  paths: [
    [P(138, 610), P(230, 650), P(255, 560), P(210, 455), P(136, 430)],
    [P(255, 560), P(355, 600), P(405, 595)],
    [P(210, 455), P(270, 380), P(300, 300)],
    [P(255, 560), P(330, 470), P(420, 420)],
    [P(230, 650), P(200, 760)],
    [P(405, 595), P(445, 735), P(545, 760), P(620, 690), P(700, 700)],
    [P(445, 735), P(360, 790), P(250, 840)],
    [P(545, 760), P(570, 862)],
    [P(445, 735), P(520, 640), P(580, 580)],
  ],
  widths: { lane: 33, path: 18, river: 38, base: 92 },
};

// ---------------------------------------------------------------------------------------------
// League of Legends — Summoner's Rift (PC)
// ---------------------------------------------------------------------------------------------
/** Riot match-timeline coordinates (0..14870 × 0..14980, origin bottom-left) → map units. */
const S = (x: number, y: number): Pt => ({ x: Math.round(x / 14.87), y: Math.round(1000 - y / 14.98) });
const LOL: MapSpec = {
  id: 'lol-summoners-rift',
  titleId: 'lol',
  name: "Summoner's Rift",
  version: 'Patch 26.23 (2026 season)',
  sideNames: ['Blue', 'Red'],
  orientation: 'Blue base bottom-left, Red top-right; Baron pit top river, Dragon pit bottom river.',
  sources: [
    'https://www.leagueoflegends.com/news/dev/dev-2026-season-one-gameplay-preview/ (official: 2026 objectives — Atakhan removed, Baron at 20:00)',
    'https://www.sheepesports.com/en/all/articles/league-of-legends-2026-s1-gameplay-changes-atakhan-removed-new-plates-on-t2-and-t3-nashor-back (2026 S1 changes)',
    'https://docs.bayesesports.com/docs-data-portal/misc/tower_coords (tower coordinates in Riot timeline units; Blue top outer 981,10441 cross-checked)',
    'https://riot-api-libraries.readthedocs.io/en/latest/roleid.html (Riot match-timeline coordinate system)',
  ],
  notes: [
    'Structure and camp positions use the Riot match-timeline coordinate system scaled to 1000 units; values other than the cross-checked turret are approximate.',
    'Elemental drakes 5:00 (respawn 5:00, soul at four); Void Grubs from 8:00; Rift Herald 15:00; Baron 20:00 (respawn 6:00); Elder after a soul.',
    'Atakhan is not part of the 2026 Rift. Inhibitors rebuild after 5:00.',
  ],
  moveSpeed: 22,
  laneNames: { top: 'Top Lane', mid: 'Mid Lane', bot: 'Bot Lane' },
  lanes: {
    top: [S(1300, 3300), S(1000, 4800), S(1050, 7000), S(1000, 10441), S(1250, 12900), S(2300, 13800), S(4318, 13900), S(7943, 13600), S(11000, 13400)],
    mid: [S(2600, 2700), S(3651, 3696), S(5048, 4812), S(6300, 6300), S(7435, 7435), S(8600, 8600), S(9800, 10000), S(11134, 11207), S(12300, 12300)],
  },
  bases: S(1551, 1658),
  fountain: S(420, 420),
  structures: [
    { id: 'top-t1', kind: 'tower', lane: 'top', tier: 1, name: 'Top Outer Turret', pos: S(981, 10441), hp: 5000, dps: 210, range: 52 },
    { id: 'top-t2', kind: 'tower', lane: 'top', tier: 2, name: 'Top Inner Turret', pos: S(1512, 6699), hp: 5000, dps: 230, range: 52, requires: ['top-t1'] },
    { id: 'top-t3', kind: 'tower', lane: 'top', tier: 3, name: 'Top Inhibitor Turret', pos: S(1169, 4287), hp: 5500, dps: 260, range: 52, requires: ['top-t2'] },
    { id: 'top-inh', kind: 'inhibitor', lane: 'top', tier: 4, name: 'Top Inhibitor', pos: S(1171, 3571), hp: 4000, dps: 0, range: 0, requires: ['top-t3'], respawn: 300 },
    { id: 'mid-t1', kind: 'tower', lane: 'mid', tier: 1, name: 'Mid Outer Turret', pos: S(5846, 6396), hp: 5000, dps: 210, range: 52 },
    { id: 'mid-t2', kind: 'tower', lane: 'mid', tier: 2, name: 'Mid Inner Turret', pos: S(5048, 4812), hp: 5000, dps: 230, range: 52, requires: ['mid-t1'] },
    { id: 'mid-t3', kind: 'tower', lane: 'mid', tier: 3, name: 'Mid Inhibitor Turret', pos: S(3651, 3696), hp: 5500, dps: 260, range: 52, requires: ['mid-t2'] },
    { id: 'mid-inh', kind: 'inhibitor', lane: 'mid', tier: 4, name: 'Mid Inhibitor', pos: S(3203, 3208), hp: 4000, dps: 0, range: 0, requires: ['mid-t3'], respawn: 300 },
    { id: 'bot-t1', kind: 'tower', lane: 'bot', tier: 1, name: 'Bot Outer Turret', pos: S(10504, 1029), hp: 5000, dps: 210, range: 52 },
    { id: 'bot-t2', kind: 'tower', lane: 'bot', tier: 2, name: 'Bot Inner Turret', pos: S(6919, 1483), hp: 5000, dps: 230, range: 52, requires: ['bot-t1'] },
    { id: 'bot-t3', kind: 'tower', lane: 'bot', tier: 3, name: 'Bot Inhibitor Turret', pos: S(4281, 1253), hp: 5500, dps: 260, range: 52, requires: ['bot-t2'] },
    { id: 'bot-inh', kind: 'inhibitor', lane: 'bot', tier: 4, name: 'Bot Inhibitor', pos: S(3452, 1236), hp: 4000, dps: 0, range: 0, requires: ['bot-t3'], respawn: 300 },
    { id: 'nexus-a', kind: 'base-tower', lane: null, tier: 5, name: 'Nexus Turret', pos: S(1748, 2270), hp: 2700, dps: 260, range: 52, requires: ['any:inhibitor'] },
    { id: 'nexus-b', kind: 'base-tower', lane: null, tier: 5, name: 'Nexus Turret', pos: S(2177, 1807), hp: 2700, dps: 260, range: 52, requires: ['any:inhibitor'] },
    { id: 'core', kind: 'core', lane: null, tier: 9, name: 'Nexus', pos: S(1551, 1658), hp: 5500, dps: 0, range: 0, requires: ['nexus-a', 'nexus-b'] },
  ],
  camps: [
    { id: 'blue', name: 'Blue Sentinel', kind: 'buff', pos: S(3821, 7901), hp: 2300, gold: 90, xp: 200, spawn: 90, respawn: 300 },
    { id: 'gromp', name: 'Gromp', kind: 'camp', pos: S(2288, 8448), hp: 2050, gold: 80, xp: 150, spawn: 90, respawn: 135 },
    { id: 'wolves', name: 'Murk Wolves', kind: 'camp', pos: S(3783, 6495), hp: 1600, gold: 85, xp: 140, spawn: 90, respawn: 135 },
    { id: 'raptors', name: 'Raptors', kind: 'camp', pos: S(6954, 5458), hp: 1200, gold: 70, xp: 120, spawn: 90, respawn: 135 },
    { id: 'red', name: 'Red Brambleback', kind: 'buff', pos: S(7765, 4065), hp: 2300, gold: 90, xp: 200, spawn: 90, respawn: 300 },
    { id: 'krugs', name: 'Krugs', kind: 'camp', pos: S(8394, 2650), hp: 1400, gold: 105, xp: 160, spawn: 90, respawn: 135 },
  ],
  riverCamps: [{ id: 'scuttle', name: 'Rift Scuttler', kind: 'river', pos: S(4400, 9600), hp: 1500, gold: 75, xp: 110, spawn: 210, respawn: 150 }],
  pits: [
    { id: 'baron-pit', name: 'Baron Pit', pos: S(4950, 10400), radius: 24, objectives: ['grubs', 'herald', 'baron'] },
    { id: 'dragon-pit', name: 'Dragon Pit', pos: S(9866, 4414), radius: 24, objectives: ['drake', 'elder'] },
  ],
  river: [S(1500, 13500), S(3500, 11300), S(4950, 10100), S(7435, 7545), S(9866, 4800), S(11400, 3600), S(13400, 1500)],
  paths: [
    [S(1050, 7000), S(2600, 7300), S(3783, 6495), S(4700, 5700), S(5048, 4812)],
    [S(2288, 8448), S(3821, 7901), S(3783, 6495)],
    [S(2288, 8448), S(1000, 8600)],
    [S(3821, 7901), S(4500, 8800), S(4400, 9600), S(4950, 10100)],
    [S(3821, 7901), S(5600, 7500), S(6300, 6300)],
    [S(6300, 6300), S(6954, 5458), S(7765, 4065), S(8394, 2650), S(8700, 1200)],
    [S(6954, 5458), S(8100, 6700)],
    [S(7765, 4065), S(9000, 4300), S(9866, 4800)],
    [S(2700, 1900), S(5300, 2900), S(7765, 4065)],
    [S(2600, 2700), S(3300, 5200), S(3783, 6495)],
    [S(4950, 10100), S(4950, 10400)],
  ],
  widths: { lane: 30, path: 16, river: 34, base: 88 },
};

// ---------------------------------------------------------------------------------------------
// Dota 2 — the Dota map (Radiant vs Dire), patch 7.41
// ---------------------------------------------------------------------------------------------
const DOTA: MapSpec = {
  id: 'dota2-map',
  titleId: 'dota2',
  name: 'Dota 2 map (Radiant vs Dire)',
  version: 'Patch 7.41e',
  sideNames: ['Radiant', 'Dire'],
  orientation: 'Radiant base bottom-left, Dire top-right; Radiant safe lane = bottom, Dire safe lane = top; river top-left → bottom-right with two Roshan pits.',
  sources: [
    'https://www.gosugamers.net/dota2/news/74352-dota-2-introduces-more-water-and-new-roshan-pit-locations-in-patch-7-38 (two Roshan pits, day/night)',
    'https://bo3.gg/dota2/news/patch-7-39-has-been-released-in-dota-2 (7.39: Roshan pits moved; first Tormentor 20:00)',
    'https://hawk.live/posts/valve-released-patch-741-for-dota-2 (7.41: Tormentor on low ground near the Lotus Pools; Twin Gates moved)',
    'https://www.playnews.gg/en/guides/dota-2-the-map-objectives-guide-for-patch-7-41e-roshan-the-gravekeeper-shrines-and-portals (7.41e objectives: day/night side swap every 5 min from 15:00)',
  ],
  notes: [
    'Three towers per lane (T1–T3) on approach, melee and ranged Barracks behind each T3 (high ground), two T4 towers and the Ancient.',
    'Roshan alternates between the top-left and bottom-right river pits with the day/night cycle; the Tormentor appears on the opposite side (first 20:00).',
    'Wisdom Runes every 7:00 at each side’s shrine. Twin Gates, Lotus Pools, Watchers and outposts are drawn as landmarks but not simulated.',
    'Positions are an original schematic of the topology, not measured coordinates.',
  ],
  moveSpeed: 19,
  laneNames: { top: 'Top Lane', mid: 'Mid Lane', bot: 'Bot Lane' },
  lanes: {
    top: [P(110, 740), P(95, 560), P(90, 380), P(100, 210), P(150, 120), P(260, 90), P(420, 85), P(600, 82), P(740, 95)],
    mid: [P(205, 795), P(300, 700), P(400, 600), P(500, 500), P(600, 400), P(700, 300), P(795, 205)],
  },
  bases: P(120, 875),
  fountain: P(45, 955),
  structures: [
    { id: 'top-t1', kind: 'tower', lane: 'top', tier: 1, name: 'Top Tier 1 Tower', pos: P(88, 395), hp: 1800 * 3, dps: 190, range: 55 },
    { id: 'top-t2', kind: 'tower', lane: 'top', tier: 2, name: 'Top Tier 2 Tower', pos: P(94, 560), hp: 2500 * 3, dps: 220, range: 55, requires: ['top-t1'] },
    { id: 'top-t3', kind: 'tower', lane: 'top', tier: 3, name: 'Top Tier 3 Tower', pos: P(105, 700), hp: 2500 * 3, dps: 240, range: 55, requires: ['top-t2'] },
    { id: 'top-rax', kind: 'barracks', lane: 'top', tier: 4, name: 'Top Barracks', pos: P(112, 752), hp: 2200 * 3, dps: 0, range: 0, requires: ['top-t3'] },
    { id: 'mid-t1', kind: 'tower', lane: 'mid', tier: 1, name: 'Mid Tier 1 Tower', pos: P(415, 585), hp: 1800 * 3, dps: 190, range: 55 },
    { id: 'mid-t2', kind: 'tower', lane: 'mid', tier: 2, name: 'Mid Tier 2 Tower', pos: P(330, 670), hp: 2500 * 3, dps: 220, range: 55, requires: ['mid-t1'] },
    { id: 'mid-t3', kind: 'tower', lane: 'mid', tier: 3, name: 'Mid Tier 3 Tower', pos: P(245, 755), hp: 2500 * 3, dps: 240, range: 55, requires: ['mid-t2'] },
    { id: 'mid-rax', kind: 'barracks', lane: 'mid', tier: 4, name: 'Mid Barracks', pos: P(212, 788), hp: 2200 * 3, dps: 0, range: 0, requires: ['mid-t3'] },
    { id: 'bot-t1', kind: 'tower', lane: 'bot', tier: 1, name: 'Bot Tier 1 Tower', pos: P(600, 918), hp: 1800 * 3, dps: 190, range: 55 },
    { id: 'bot-t2', kind: 'tower', lane: 'bot', tier: 2, name: 'Bot Tier 2 Tower', pos: P(430, 912), hp: 2500 * 3, dps: 220, range: 55, requires: ['bot-t1'] },
    { id: 'bot-t3', kind: 'tower', lane: 'bot', tier: 3, name: 'Bot Tier 3 Tower', pos: P(290, 895), hp: 2500 * 3, dps: 240, range: 55, requires: ['bot-t2'] },
    { id: 'bot-rax', kind: 'barracks', lane: 'bot', tier: 4, name: 'Bot Barracks', pos: P(240, 888), hp: 2200 * 3, dps: 0, range: 0, requires: ['bot-t3'] },
    { id: 't4-a', kind: 'base-tower', lane: null, tier: 5, name: 'Tier 4 Tower', pos: P(150, 835), hp: 2600 * 3, dps: 250, range: 55, requires: ['any:t3'] },
    { id: 't4-b', kind: 'base-tower', lane: null, tier: 5, name: 'Tier 4 Tower', pos: P(165, 850), hp: 2600 * 3, dps: 250, range: 55, requires: ['any:t3'] },
    { id: 'core', kind: 'core', lane: null, tier: 9, name: 'Ancient', pos: P(120, 875), hp: 4500 * 3, dps: 0, range: 0, requires: ['t4-a', 't4-b'] },
  ],
  camps: [
    { id: 'large-a', name: 'Large Camp', kind: 'camp', pos: P(465, 700), hp: 1800, gold: 95, xp: 150, spawn: 60, respawn: 60 },
    { id: 'medium-a', name: 'Medium Camp', kind: 'camp', pos: P(560, 760), hp: 1300, gold: 70, xp: 110, spawn: 60, respawn: 60 },
    { id: 'small-a', name: 'Small Camp', kind: 'camp', pos: P(660, 820), hp: 900, gold: 50, xp: 80, spawn: 60, respawn: 60 },
    { id: 'large-b', name: 'Large Camp', kind: 'camp', pos: P(390, 800), hp: 1800, gold: 95, xp: 150, spawn: 60, respawn: 60 },
    { id: 'ancient', name: 'Ancient Camp', kind: 'buff', pos: P(330, 560), hp: 3200, gold: 160, xp: 260, spawn: 60, respawn: 60 },
    { id: 'medium-b', name: 'Medium Camp', kind: 'camp', pos: P(215, 520), hp: 1300, gold: 70, xp: 110, spawn: 60, respawn: 60 },
    { id: 'large-c', name: 'Large Camp', kind: 'camp', pos: P(200, 420), hp: 1800, gold: 95, xp: 150, spawn: 60, respawn: 60 },
  ],
  riverCamps: [],
  pits: [
    { id: 'rosh-top', name: 'Roshan Pit (top)', pos: P(300, 255), radius: 22, objectives: ['roshan', 'tormentor'] },
    { id: 'rosh-bot', name: 'Roshan Pit (bottom)', pos: P(700, 745), radius: 22, objectives: ['roshan', 'tormentor'] },
    { id: 'tormentor-top', name: 'Tormentor (top-left, low ground)', pos: P(185, 250), radius: 18, objectives: ['tormentor'] },
    { id: 'tormentor-bot', name: 'Tormentor (bottom-right, low ground)', pos: P(815, 750), radius: 18, objectives: ['tormentor'] },
    { id: 'wisdom-r', name: 'Radiant Wisdom Shrine', pos: P(150, 470), radius: 14, objectives: ['wisdom'] },
    { id: 'wisdom-d', name: 'Dire Wisdom Shrine', pos: P(850, 530), radius: 14, objectives: ['wisdom'] },
  ],
  river: [P(120, 120), P(300, 290), P(500, 500), P(700, 710), P(880, 880)],
  paths: [
    [P(95, 560), P(215, 520), P(330, 560), P(400, 600)],
    [P(200, 420), P(215, 520)],
    [P(200, 420), P(150, 470), P(92, 470)],
    [P(330, 560), P(360, 420), P(300, 290)],
    [P(200, 420), P(240, 330), P(300, 290)],
    [P(240, 330), P(185, 250), P(110, 240)],
    [P(400, 600), P(465, 700), P(560, 760), P(660, 820), P(700, 915)],
    [P(465, 700), P(590, 620)],
    [P(560, 760), P(700, 745), P(700, 710)],
    [P(250, 860), P(390, 800), P(465, 700)],
    [P(390, 800), P(430, 912)],
    [P(300, 290), P(300, 255)],
  ],
  sharedPaths: [[P(700, 710), P(815, 750), P(890, 760)]],
  widths: { lane: 30, path: 16, river: 34, base: 100 },
  areas: [{ kind: 'highground', poly: [P(20, 660), P(170, 660), P(300, 720), P(345, 830), P(340, 980), P(20, 980)] }],
};

const SPECS: Record<string, MapSpec> = { mlbb: MLBB, wildrift: WR, hok: HOK, lol: LOL, dota2: DOTA };
const compiled = new Map<string, MapDefinition>();

/** The battlefield of a MOBA title (compiled lazily, cached). */
export function mobaMap(titleId: string): MapDefinition {
  let m = compiled.get(titleId);
  if (!m) {
    const spec = SPECS[titleId];
    if (!spec) throw new Error(`No MOBA map for ${titleId}`);
    m = compileMap(spec);
    compiled.set(titleId, m);
  }
  return m;
}

export const MOBA_MAP_TITLES = Object.keys(SPECS);
export function mapSpec(titleId: string): MapSpec | undefined {
  return SPECS[titleId];
}
