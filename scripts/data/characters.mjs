/**
 * Build the offline, versioned catalog of REAL playable characters for the five MOBAs and
 * Valorant (CS2 has no hero/agent abilities). Development-time only; the game reads the output
 * JSON and never fetches at runtime.
 *
 *   node scripts/data/characters.mjs            → src/data/real/characters.json
 *
 * Sources (see DATA_SOURCES.md): Riot Data Dragon (LoL champions, official), Meraki Analytics
 * (LoL positions, community), wildrift.leagueoflegends.com (Wild Rift champions, roles and
 * difficulty, official), pvp.qq.com herolist.json (Honor of Kings heroes and classes, official
 * Tencent CN) + hokstats.gg (global English names, matched by official hero image id),
 * mlbbhub.com (MLBB heroes: classes, lanes, difficulty, kit ratings; community),
 * OpenDota constants (Dota 2 heroes, roles, attributes), valorant-api.com (Valorant agents and
 * abilities; community mirror of game data).
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { get, getJSON, today } from './fetch.mjs';

const slug = (s) => s.toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const titleCase = (s) => s.toLowerCase().replace(/(^|[\s'.-])([a-z])/g, (m, a, b) => a + b.toUpperCase());
const out = { snapshot_id: `real-${today()}`, as_of: today(), titles: {} };
const meta = (title, patch, sources) => ({ title_id: title, patch_id: patch, collected_at: new Date().toISOString(), sources });
const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const politeGet = (u) => {
  const r = get(u);
  return r;
};

// ------------------------------------------------------------------------------------------
// League of Legends (PC)
// ------------------------------------------------------------------------------------------
const ddv = getJSON('https://ddragon.leagueoflegends.com/api/versions.json')[0];
const dd = getJSON(`https://ddragon.leagueoflegends.com/cdn/${ddv}/data/en_US/champion.json`).data;
const meraki = getJSON('https://cdn.merakianalytics.com/riot/lol/resources/latest/en-US/champions.json');
const merakiByName = new Map(Object.values(meraki).map((m) => [m.name.toLowerCase(), m]));
const POS = { TOP: 'top', JUNGLE: 'jungle', MIDDLE: 'mid', BOTTOM: 'bot', SUPPORT: 'support' };
const LOL_CLASS = { Fighter: 'Fighter', Tank: 'Tank', Mage: 'Mage', Assassin: 'Assassin', Marksman: 'Marksman', Support: 'Enchanter' };
out.titles.lol = {
  ...meta('S223', `LoL ${ddv}`, [`https://ddragon.leagueoflegends.com/cdn/${ddv}/data/en_US/champion.json`, 'https://cdn.merakianalytics.com/riot/lol/resources/latest/en-US/champions.json']),
  characters: Object.values(dd).map((c) => {
    const m = merakiByName.get(c.name.toLowerCase());
    return {
      character_id: `lol-${slug(c.id)}`,
      name: c.name,
      type: 'champion',
      status: 'active',
      classes: c.tags.map((t) => LOL_CLASS[t]).filter(Boolean),
      lanes: (m?.positions ?? []).map((p) => POS[p]).filter(Boolean),
      lanes_source: m ? 'Meraki Analytics positions' : 'none',
      kit: { offense: Math.max(c.info.attack, c.info.magic), durability: c.info.defense, difficulty: c.info.difficulty },
      title_line: c.title,
    };
  }),
};

// ------------------------------------------------------------------------------------------
// Wild Rift (official champion list and per-champion roles/difficulty)
// ------------------------------------------------------------------------------------------
const wrList = get('https://wildrift.leagueoflegends.com/en-us/champions/');
const wrLinks = [...new Set([...wrList.matchAll(/href="\/en-us\/champions\/([a-z0-9-]+)\/"/g)].map((x) => x[1]))];
const WR_CLASS = { fighter: 'Fighter', tank: 'Tank', mage: 'Mage', assassin: 'Assassin', marksman: 'Marksman', support: 'Enchanter' };
const WR_LANE = { top: 'baron', jungle: 'jungle', mid: 'mid', bot: 'dragon', support: 'support' };
const wrChars = [];
for (const s of wrLinks) {
  const html = politeGet(`https://wildrift.leagueoflegends.com/en-us/champions/${s}/`);
  const nd = html.match(/<script id="__NEXT_DATA__"[^>]*>(.*?)<\/script>/s);
  if (!nd) continue;
  const str = nd[1];
  const i = str.indexOf('"type":"characterMasthead"');
  const seg = str.slice(Math.max(0, i - 3000), i);
  const title = [...seg.matchAll(/"title":"([^"]+)","subtitle":"([^"]*)"/g)].pop();
  const roles = [...(seg.match(/"roles":\[(.*?)\]/)?.[1] ?? '').matchAll(/"id":"([a-z]+)"/g)].map((x) => x[1]);
  const diff = Number(seg.match(/"difficulty":\{[^}]*"value":(\d)/)?.[1] ?? 2);
  if (!title) continue;
  const name = titleCase(title[1]).replace(/\bIv\b/, 'IV').replace(/^Dr\. Mundo$/i, 'Dr. Mundo');
  const pc = merakiByName.get(name.toLowerCase()) ?? merakiByName.get(title[1].toLowerCase());
  wrChars.push({
    character_id: `wildrift-${s}`,
    name,
    type: 'champion',
    status: 'active',
    classes: roles.map((r) => WR_CLASS[r]).filter(Boolean),
    lanes: (pc?.positions ?? []).map((p) => WR_LANE[POS[p]]).filter(Boolean),
    lanes_source: pc ? 'estimate: PC League positions mapped to Wild Rift lanes (WR site lists roles, not lanes)' : 'none',
    kit: { difficulty: diff * 3 },
    title_line: title[2],
  });
  pause(300);
}
out.titles.wildrift = { ...meta('S221', `Wild Rift (official site ${today()})`, ['https://wildrift.leagueoflegends.com/en-us/champions/']), characters: wrChars };

// ------------------------------------------------------------------------------------------
// Honor of Kings (official CN list for classes, global English names from hokstats.gg)
// ------------------------------------------------------------------------------------------
const hokCN = getJSON('https://pvp.qq.com/web201605/js/herolist.json');
const hokHome = get('https://hokstats.gg/');
const hokGlobal = new Map();
for (const m of hokHome.matchAll(/href="\/heroes\/([a-z0-9-]+)\/"[^>]*title="([^"]+)"><img src="([^"]+)"/g)) {
  const ename = m[3].match(/heroimg\/(\d+)\//)?.[1];
  if (!hokGlobal.has(m[1])) hokGlobal.set(m[1], { slug: m[1], name: m[2].replace(/&#x27;|&#39;/g, "'"), ename: ename ? Number(ename) : null });
}
const HOK_TYPE = { 1: 'Warrior', 2: 'Mage', 3: 'Tank', 4: 'Assassin', 5: 'Marksman', 6: 'Support' };
const HOK_LANE = { Warrior: ['clash', 'jungle'], Mage: ['mid'], Tank: ['roam', 'clash'], Assassin: ['jungle'], Marksman: ['farm'], Support: ['roam'] };
const cnByEname = new Map(hokCN.map((h) => [h.ename, h]));
const HOK_CAMP_LANE = { 'clash lane': 'clash', clash: 'clash', jungling: 'jungle', jungle: 'jungle', 'mid lane': 'mid', mid: 'mid', 'farm lane': 'farm', farm: 'farm', roaming: 'roam', roam: 'roam' };
const HOK_CAMP_CLASS = { fighter: 'Warrior', warrior: 'Warrior', mage: 'Mage', tank: 'Tank', assassin: 'Assassin', marksman: 'Marksman', support: 'Support' };
const hokChars = [];
for (const g of hokGlobal.values()) {
  const cn = g.ename ? cnByEname.get(g.ename) : undefined;
  const page = politeGet(`https://hokstats.gg/heroes/${g.slug}/`);
  // Structured description: "Arthur (Fighter/Tank) — Clash Lane / Jungle role in Honor of Kings."
  const camp = page.match(/"description":"[^"]*\(([A-Za-z/ ]+)\) — ([A-Za-z /&]+?) role in Honor of Kings/);
  let classes = camp ? camp[1].split('/').map((x) => HOK_CAMP_CLASS[x.trim().toLowerCase()]).filter(Boolean) : [];
  let lanes = camp ? camp[2].split('/').map((x) => HOK_CAMP_LANE[x.trim().toLowerCase()]).filter(Boolean) : [];
  const fromCN = cn ? [HOK_TYPE[cn.hero_type], HOK_TYPE[cn.hero_type2]].filter(Boolean) : [];
  if (!classes.length) classes = fromCN;
  const lanesSource = lanes.length ? 'hokstats.gg hero profile' : 'estimate: derived from classes';
  if (!lanes.length) lanes = [...new Set(classes.flatMap((c) => HOK_LANE[c] ?? []))];
  hokChars.push({
    character_id: `hok-${g.slug}`,
    name: g.name,
    type: 'hero',
    status: 'active',
    classes: [...new Set(classes)],
    lanes: [...new Set(lanes)],
    lanes_source: lanesSource,
    cn_name: cn?.cname,
    official_id: g.ename,
    classes_source: camp ? 'hokstats.gg hero profile' : cn ? 'official pvp.qq.com hero_type' : 'unknown',
  });
  pause(400);
}
out.titles.hok = { ...meta('S222', `Honor of Kings global (${today()})`, ['https://pvp.qq.com/web201605/js/herolist.json', 'https://hokstats.gg/']), characters: hokChars };

// ------------------------------------------------------------------------------------------
// Mobile Legends: Bang Bang (mlbbhub.com hero pages)
// ------------------------------------------------------------------------------------------
const mlList = get('https://mlbbhub.com/heroes');
const mlSlugs = [...new Set([...mlList.matchAll(/"url":"https:\/\/mlbbhub\.com\/heroes\/([a-z0-9-]+)"/g)].map((x) => x[1]))];
const ML_LANE = { 'Gold': 'gold', 'Gold Lane': 'gold', EXP: 'exp', 'EXP Lane': 'exp', Mid: 'mid', 'Mid Lane': 'mid', Jungle: 'jungle', Roam: 'roam' };
const mlChars = [];
for (const s of mlSlugs) {
  const h = politeGet(`https://mlbbhub.com/heroes/${s}`);
  const i = h.indexOf(`\\"slug\\":\\"${s}\\",\\"name\\":`);
  if (i < 0) continue;
  const seg = h.slice(i, i + 1500).replace(/\\"/g, '"');
  const name = seg.match(/"name":"([^"]+)"/)?.[1];
  const arr = (k) => [...(seg.match(new RegExp(`"${k}":\\[(.*?)\\]`))?.[1] ?? '').matchAll(/"([^"]+)"/g)].map((x) => x[1]);
  const num = (k) => Number(seg.match(new RegExp(`"${k}":(\\d+)`))?.[1] ?? NaN);
  mlChars.push({
    character_id: `mlbb-${s}`,
    name,
    type: 'hero',
    status: 'active',
    classes: arr('role'),
    lanes: arr('lane').map((l) => ML_LANE[l]).filter(Boolean),
    lanes_source: 'mlbbhub.com',
    specialty: arr('specialty'),
    kit: { durability: num('durability') / 10, offense: num('offense') / 10, control: num('controlEffect') / 10, difficulty: num('difficulty') },
  });
  pause(400);
}
out.titles.mlbb = { ...meta('S220', `MLBB (${today()})`, ['https://mlbbhub.com/heroes']), characters: mlChars };

// ------------------------------------------------------------------------------------------
// Dota 2 (OpenDota constants)
// ------------------------------------------------------------------------------------------
const od = getJSON('https://api.opendota.com/api/constants/heroes');
const dotaClass = (h) => {
  const r = new Set(h.roles);
  const out = [];
  if (r.has('Carry') && (h.primary_attr === 'agi' || h.primary_attr === 'all')) out.push('Agility Carry');
  if ((r.has('Carry') || r.has('Durable')) && h.primary_attr === 'str') out.push('Strength Core');
  if (r.has('Nuker') && (h.primary_attr === 'int' || h.primary_attr === 'all')) out.push('Intelligence Nuker');
  if (r.has('Initiator')) out.push('Initiator');
  if (r.has('Support')) out.push('Support');
  if (r.has('Pusher')) out.push('Pusher');
  return out.length ? out : ['Initiator'];
};
const dotaLanes = (h) => {
  const r = new Set(h.roles);
  const l = new Set();
  if (r.has('Carry')) l.add('pos1');
  if (r.has('Nuker') || (r.has('Carry') && r.has('Escape'))) l.add('pos2');
  if (r.has('Initiator') || r.has('Durable')) l.add('pos3');
  if (r.has('Support') && (r.has('Disabler') || r.has('Initiator') || r.has('Nuker'))) l.add('pos4');
  if (r.has('Support')) l.add('pos5');
  return [...l];
};
out.titles.dota2 = {
  ...meta('S224', `Dota 2 (OpenDota constants ${today()})`, ['https://api.opendota.com/api/constants/heroes']),
  characters: Object.values(od).map((h) => ({
    character_id: `dota2-${slug(h.localized_name)}`,
    name: h.localized_name,
    type: 'hero',
    status: 'active',
    classes: dotaClass(h),
    lanes: dotaLanes(h),
    lanes_source: 'estimate: derived from OpenDota roles',
    dota_roles: h.roles,
    primary_attr: h.primary_attr,
    attack_type: h.attack_type,
  })),
};

// ------------------------------------------------------------------------------------------
// Valorant (valorant-api.com)
// ------------------------------------------------------------------------------------------
const va = getJSON('https://valorant-api.com/v1/agents?isPlayableCharacter=true').data;
out.titles.valorant = {
  ...meta('S232', `Valorant (valorant-api.com ${today()})`, ['https://valorant-api.com/v1/agents?isPlayableCharacter=true']),
  characters: va.map((a) => ({
    character_id: `valorant-${slug(a.displayName)}`,
    name: a.displayName,
    type: 'agent',
    status: 'active',
    classes: [a.role?.displayName].filter(Boolean),
    lanes: [],
    abilities: (a.abilities ?? []).map((x) => ({ slot: x.slot, name: x.displayName })),
    official_uuid: a.uuid,
  })),
};

// Lanes missing from the sources (newest champions): estimate from classes, labelled.
const CLASS_LANE = {
  lol: { Fighter: ['top'], Tank: ['top', 'support'], Mage: ['mid'], Assassin: ['mid', 'jungle'], Marksman: ['bot'], Enchanter: ['support'] },
  wildrift: { Fighter: ['baron'], Tank: ['baron', 'support'], Mage: ['mid'], Assassin: ['mid', 'jungle'], Marksman: ['dragon'], Enchanter: ['support'] },
};
for (const [k, map] of Object.entries(CLASS_LANE))
  for (const c of out.titles[k].characters)
    if (!c.lanes.length) {
      c.lanes = [...new Set(c.classes.flatMap((x) => map[x] ?? []))];
      c.lanes_source = 'estimate: derived from classes (not in the positions source yet)';
    }
for (const t of Object.values(out.titles)) for (const c of t.characters) c.name = c.name.replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
for (const [k, v] of Object.entries(out.titles)) {
  v.characters.sort((a, b) => a.name.localeCompare(b.name));
  console.log(k, v.characters.length, 'chars;', v.characters.filter((c) => !c.classes.length).length, 'without classes;', v.characters.filter((c) => !c.lanes.length).length, 'without lanes');
}
mkdirSync('src/data/real', { recursive: true });
writeFileSync('src/data/real/characters.json', JSON.stringify(out));
