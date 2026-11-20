# Live 2D simulation — specification and status

Master prompt §7, §7A, §7B (20 Nov 2026). MOBAs first (MLBB → Wild Rift → Honor of Kings → LoL → Dota 2),
shooters after.

## Architecture

```
locked fixture ──► series runner (src/sim/series.ts)
                     │  createSeries: locks MOBA fidelity (spatial | fast) before any result exists
                     ▼
               playNextGame ── draft ──► GameContext (rosters, characters, patch, map, seeds)
                     │
                     ├─ spatial: simulateMobaSpatial(g, record)   src/sim/moba/spatial.ts
                     │            reads MapDefinition              src/sim/moba/map.ts, maps.ts
                     │            writes GameResult (+ MobaReplay when recording)
                     └─ fast:    simulateMoba(g)                   src/sim/moba/moba.ts (aggregate)
                     ▼
            record.ts applySeries ── replays detached to the session cache (replayCache.ts)
                     ▼
            UI: MobaViewer (Canvas 2D) — read-only playback of the recorded timeline
```

* **Determinism.** Each game draws from `RNG.derive(seed, fixture, 'g', game)`. Commentary (`crng`) and
  recording never consume `rng`; `tests/spatial.test.ts` checks that a recorded game, an unrecorded game and a
  repeat are identical (winner, duration, every stat line).
* **No winner-first fabrication.** The winner is whoever destroys the opposing core in the simulation; kills,
  gold, structures and objectives are the state changes that led there.
* **Fidelity lock.** Simulating every background MOBA game spatially is too slow for full seasons on a phone
  (≈ 25–55 ms per game vs ≈ 2–3 ms; ~2 700 MOBA games per small-world season). `createSeries` therefore locks
  each fixture before play: **spatial** when it involves the user's or a followed team, or when it is opened in
  the live view / marked "full detail" before it is played; otherwise the **fast** aggregate engine. Viewing never
  switches the engine for a fixture, so live, text and instant results always agree. Reports say which engine
  produced a game.

## MapDefinition (src/sim/moba/map.ts)

Normalised 1000 × 1000 square, x right / y down, team 0 (Blue / Radiant) bottom-left. Authored as a `MapSpec`
(team 0 half + shared river; team 1 is the point reflection) and compiled once per title:

| Field | Content |
| --- | --- |
| `id`, `titleId`, `name`, `version`, `sources`, `notes` | identity, patch/version label, research references |
| `sideNames`, `orientation`, `laneNames` | Blue/Red or Radiant/Dire; title lane names (EXP/Gold, Baron/Dragon, Clash/Farm, Top/Bot) |
| `lanes` | polylines base → base for top, mid, bot (+ arc lengths, lookup tables in the engine) |
| `structures` | towers, inhibitors / barracks, base towers, core: position, HP, damage, range, destruction prerequisites (`requires`, `any:inhibitor`, `any:t3`), rebuild time |
| `camps` | jungle buffs/camps per side and river creatures: HP, gold, XP, spawn, respawn |
| `pits` | objective pits with the objectives that may appear there (Turtle/Lord, Baron/Herald/Grubs, Dragon/Elder, Overlord/Tyrant/Shadow/Tempest, Roshan/Tormentor, Wisdom shrines) |
| `river`, `areas` | river centreline; Dota high ground |
| `nav` | waypoint graph (lanes, river, jungle paths, base links), all-pairs distances and next hops |
| `walls`, `grid` | impassable terrain: every 10-unit cell farther than its corridor width from any walkable path, base, camp or pit. Walls are derived from the walkable geometry, so the drawn map and the movement rules cannot disagree |

Old saves: maps are versioned by `id` + `version`; replays reference `mapId`. Replays are session-only, so a patch
change never has to re-render an old replay.

### The five battlefields

| Title | Map | Distinct features modelled |
| --- | --- | --- |
| MLBB | Land of Dawn | edge-hugging EXP (top) and Gold (bottom) lanes; 3 turrets per lane + Base Crystal (vulnerable after a base turret falls); purple/orange buffs + 3 creep camps per side; Scavenger Crabs and Lithowanderers; Turtle pit on the EXP side, Lord pit on the Gold side; Turtle 2:00/2:00 until the Lord (8:00, 3:00), first Turtle in the EXP-side pit then random; Lord pushes a lane |
| Wild Rift | Summoner's Rift (Wild Rift) | compact map, faster rotations; Baron lane / Dragon lane; 3 turrets + inhibitor (rebuilds 4:00) per lane; Herald then Baron (11:00) in the top pit; dragons and Elder (19:00) in the bottom pit |
| Honor of Kings | Kings' Canyon | Clash / Mid / Farm lanes; 3 towers + Crystal; Overlord pit by the Clash lane, Tyrant pit by the Farm lane; Tyrant & Overlord 4:00, Shadow forms 10:00, Tempest Dragon 20:00 (Shadows leave); Overlord summons pushers in all three lanes |
| League of Legends | Summoner's Rift | Riot match-timeline coordinates (scaled); 4 structures per lane incl. inhibitor (rebuild 5:00) + 2 Nexus turrets; 6 camps per side + Scuttlers; Baron pit (Grubs 8:00, Herald 15:00, Baron 20:00), Dragon pit (drakes 5:00, soul at 4, Elder); Atakhan absent (2026) |
| Dota 2 | Dota map (Radiant vs Dire) | Radiant safe lane bottom / Dire safe lane top; T1–T3 per lane, barracks per lane, two T4 (vulnerable after any T3), Ancient; high ground; 7 neutral camps per side incl. an ancient camp; two Roshan pits alternating with day/night (5 min) and Tormentors on the opposite side; Wisdom shrines (7:00); buyback; denies |

## Spatial engine (src/sim/moba/spatial.ts)

Fixed step of **2 s** (all rates are per second × step). Per step:

1. Spawn minion waves on schedule (title first wave / interval / size; super minions after an inhibitor or
   barracks falls); camps, objectives (with pit choice and windows) and rebuilding structures.
2. **Vision**: an enemy is visible to a team inside sight of its heroes, wards or structures. Heroes react only
   to visible enemies; plans use last-seen information.
3. **Coach / shot-caller plans** every 4 s per team: defend a threatened structure (only at even odds), contest an
   objective (value × local power, conceded when the enemy is known to be stronger there or the team is behind),
   or group and siege the best lane after laning; one split-pusher for split-heavy drafts.
4. **Waves** advance along the lane geometry, fight the opposing wave at the contact point, siege the next
   structure in the destruction order, and are shot by towers.
5. **Heroes** (alternating order each step): respawn / buyback; recall; engagement decisions against visible
   enemies (avoid / trade in lane / commit) from visible local power, tower cover, HP, team style and gold
   deficit; chase limits; escape spells; abilities on cooldown with crowd control; healing by supports;
   farming waves (last hits by economy skill, denies in Dota); jungle routes, ganks from the fog; wards;
   objectives fought in the pit (contested last hits by jungle skill); sieging structures in range.
6. **Towers** draw aggro from heroes who attack allies in range, otherwise shoot minions.

Players act through their ratings (laning, jungle, skirmish, teamfight, macro, vision, economy, clutch) and the
draft (character class → damage/tank/heal weights, melee/ranged; composition early/late profile); attributes are
applied once through these ratings, not again as a blanket multiplier.

Stats are counted from the events: kills / deaths / assists (damage in the last 10 s), damage dealt and taken,
healing, net worth, creep score, denies, wards, objectives, structure last hits and siege damage, buybacks.

## Recorded timeline (`MobaReplay`, src/model/types.ts)

One frame per step: hero x, y, HP %, level, activity and target; structure HP; front of each side's wave per lane
and minion count; objective in each pit with HP; camps alive; team gold, kills, structures. Plus spatial events
(kills with assists, casts, escapes, recalls, respawns, buybacks, Aegis, objective spawns/takes, summons,
structures, core) and ward placements. ≈ 0.3–1 MB in memory per game; never saved.

## Viewer (src/ui/components/MobaViewer.tsx)

Canvas 2D (no new framework): cached static layer (terrain, river, lanes, bases, pits) + per-frame dynamic layer.
Interpolates positions/HP between frames; cannot change the simulation.

* Scoreboard (real teams, kills, gold, structures, objectives, clock), per-player table (character, level,
  live K/D/A from events, HP, activity), event feed.
* Controls: play / pause, 1×–16×, scrub, **Instant finish**, fit, zoom buttons, wheel and pinch zoom, drag pan,
  tap a player to follow, toggles for names, health, wards/vision, waypoint paths. Pauses when the app is
  backgrounded (Android).
* Live match screen hides each game's result and the series score until playback ends or is skipped; the match
  report offers "Watch 2D replay" for spatial games simulated in this session.

## Acceptance status (honest)

| Title | Map researched & implemented | Spatial sim | Live view | Notes / gaps |
| --- | --- | --- | --- | --- |
| MLBB | ✅ (community schematic + guides) | ✅ | ✅ | Lord evolution at 18:00 not modelled separately; emblem/spell detail abstracted |
| Wild Rift | ✅ (patch history; layout schematic) | ✅ | ✅ | dragon timing and absence of Nexus turrets unverified; elemental types abstracted |
| Honor of Kings | ✅ (guides; sides per CN convention) | ✅ | ✅ | camp names generic; Tempest buff modelled as execute |
| LoL | ✅ (Riot coordinate frame; 2026 objectives) | ✅ | ✅ | kills run ≈ 1.6–1.8× pro averages — tuning continues; drake elements abstracted |
| Dota 2 | ✅ (7.38–7.41 patch notes/guides; schematic) | ✅ | ✅ | Twin Gates, Lotus Pools, Watchers, outposts drawn as notes only; items/abilities abstracted |
| Valorant | ✅ 13 maps (official callouts, inferred connections) | ✅ | ✅ | round-level utility per agent class, not per individual ability |
| CS2 | ✅ 9 schematic maps (callout guides) | ✅ | ✅ | grenade line-ups abstracted; no per-weapon spray model |

Calibration (20 seeded games each, spatial engine; `npx vite-node scripts/calibrate-spatial.ts`):

| Title | Avg length | Kills / game | Structures | Objectives | Pro reference |
| --- | --- | --- | --- | --- | --- |
| MLBB | 13.9 min | 31 | 6.5 | 5.3 | ~15 min, 25–30 kills |
| Wild Rift | 13.5 min | 29 | 6.5 | 5.0 | ~17 min, ~30 kills |
| HoK | 15.7 min | 33 | 8.8 | 8.2 | ~17 min, ~30 kills |
| LoL | 26.7 min | 48 | 9.3 | 9.0 | ~31 min, ~26 kills |
| Dota 2 | 33.8 min | 66 | 10.2 | 8.7 | ~38 min, ~55 kills |

Performance: 25–55 ms per spatial game in Node on the build machine (2 s step); map compilation ≈ 60–100 ms per
title once per session.

## Tests

`tests/spatial.test.ts`: maps compile, every navigation node reachable and on walkable ground, camps/pits
walkable, mirrored structures, five distinct battlefields, title-specific structures/objectives; per title one
recorded game: core decides, kills = opposing deaths, replay K/D/A = box score, heroes never inside walls in any
frame, structures fall only after prerequisites, objectives taken at their pits; recording / repeat equality;
fidelity lock. `e2e/game.spec.ts` plays a live series through the viewer (skip).

## Shooters (Valorant, CS2)

**Maps** (`src/sim/shooter/map.ts`, `maps.ts`, `cs2Maps.ts`): attackers spawn at the bottom, defenders at the
top. A map is a graph of named callouts joined by walkable connections; walls are derived from those corridors on
a 10-unit grid and **line of sight uses the same grid plus active smokes**.

* Valorant: 13 competitive maps from valorant-api.com (official callout names and coordinates, minimap transform),
  rotated so attackers are at the bottom; connections are inferred from callout positions (relative-neighbourhood
  rule, every site linked toward the defender side) — schematic. Competitive pool as of the snapshot (Strafe,
  V26 Act 6, 14 Oct 2026): Summit, Abyss, Sunset, Lotus, Corrode, Haven, Bind. Haven and Lotus have three sites.
* CS2: 9 schematic maps authored from public callout guides with explicit connections (Dust II, Inferno,
  Mirage, Nuke, Ancient, Anubis, Cache, Train, Overpass). Active duty (csdb.gg, July 2026): Mirage, Inferno,
  Dust II, Anubis, Ancient, Nuke, Cache.

**Round engine** (`src/sim/shooter/spatialRounds.ts`, 0.5 s steps): buy phase from the economy (pistol, eco,
force, full; survivors keep weapons; AWP/Operator for the sniper role; Valorant utility from the agent class, CS2
grenades from the buy) → attack plan (default with map control, a lurker and an execute, rush, split; spike
carrier) and defender setup (one anchor per site, a mid player, the rest on the IGL's read) → movement along
the callout graph → **duels only with line of sight** (walls and smokes block it) weighing aim/skill, weapon and
range, holding versus peeking, site cover, crossfires and local numbers, flashes (blind), Valorant recon
(reveal), traps (reveal flankers), trades (a killer gives his position away), armour and wounds → defenders
rotate on what they see, hear or trap, give up a site when badly outnumbered and play for the retake → plant
(only on a site, not while contested; planters and defusers cannot shoot) → post-plant holds, retake, defuse
(attackers move in when they hear it), molly on the spike, saves when a defuse is impossible → round result,
KAST, clutches, multi-kills, economy, halftime, Valorant win-by-two overtime, CS2 MR12 + 6-round blocks.

Fidelity uses the same per-fixture lock as MOBAs (spatial for your/followed/opened fixtures; the existing round
engine for other background maps). Recording is passive.

Calibration (16 maps per title, `npx vite-node scripts/calibrate-shooter.ts`): Valorant ≈ 20 rounds per map,
7.4 kills per round, 0.48 plants per round, attack wins 52 %, first-kill team wins ≈ 68 %; CS2 ≈ 20 rounds,
7.1 kills, 0.58 plants, attack 49 %. A calibrated defender edge stands in for set-ups and cover the schematic
does not draw. ≈ 40–55 ms per map.

**Viewer** (`ShooterViewer`): map floor, sites, spawns and callouts; players with handle, agent, weapon, HP,
spike/bomb, blinded, planting/defusing; shot lines; smokes, mollies, flashes, recon, traps; round, clock or bomb
timer, alive counts, buys and credits; per-player K/D; kill feed with weapon and headshots; round history
(finished rounds only — no spoilers) with jump-to-round; play, pause, 1–8×, next round, instant finish, zoom,
follow player, callout labels. The live match screen hides each map's result until playback ends or is skipped.

Tests (`tests/shooter.test.ts`): every map connected with spawns and ≥ 2 sites; legal map scores; kills = deaths;
plants only on sites; defuses only after plants; dead players never move; live players never inside walls;
recording never changes a result.
