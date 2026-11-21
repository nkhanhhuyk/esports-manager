# Upgrade report: deep 2D simulation, analysis, custom tournaments (21 Nov 2026)

Scope: the seven titles only (MLBB, Wild Rift, Honor of Kings, LoL, Dota 2, Valorant, CS2). No roster /
database updater was built and no real team or player data was changed. Findings table before the
work: `docs/UPGRADE_AUDIT.md`.

## What changed and what now works

| Area | Files / classes | Behaviour now |
|---|---|---|
| Ability model | `assets/abilities.json`, `sim/Abilities.kt`, `scripts/abilities_fetch.py`, `scripts/abilities_build.py` | 723 of 724 characters have a sourced kit (HoK Mulan uses a class template). Every ability: slot, type, targeting (self / around self / targeted / line / area / cone / ally / global), range in map units, cooldown by rank, cost, typed effects (damage with type, damage over time, stun, knock-up, knock-back, suppression, charm, taunt, fear, root, silence, slow, blind, heal, shield, dash, stealth, immunity, attack/move speed, damage reduction, summon, reveal, execute, terrain, global, revive), an AI usage tag and a visual-effect id. Kits are kept within a power band per class (outliers from text parsing do not decide games). Valorant agents: ability kinds, charges, cost, ultimate points. |
| MOBA engine | `sim/MobaEngine.kt` (rewritten, same entry point), `sim/MobaMap.kt` | Positional simulation on each title's map in 2-second steps: heroes walk the walkability grid (distance fields around terrain); minion waves spawn, walk, clash and siege; towers, inhibitors / barracks, base towers and the core have hit points and shoot (the Nexus and Ancient do not); jungle camps with respawns; the title's objectives (Turtle/Lord, Drakes/Herald/Grubs/Baron/Elder, Tyrant/Overlord/Shadow/Tempest, Wisdom runes/Roshan/Tormentor) spawn, are fought over, smite/retribution steals, and give their kind of reward (team gold, stacks and soul, push buffs and super minions, Aegis, execute, XP, stat buffs). Heroes cast their own kit under cooldowns and mana; crowd control stops actions or movement; shields, heals, dashes, stealth (broken by attacking) and immunity act on the fight; skillshots can miss (aim vs dodging) and are blocked by walls; vision from heroes, towers, wards and minions (fog of war). Dota 2: buyback, Aegis, BKB, courier shopping. Teams plan from their macro rating (laning, ganks, objectives, group pushes, defending, base sieges with escalation in stalemates). The game ends only when a core is destroyed. Each mechanic reads one phase rating (laning, mechanics/skirmish, teamfight, macro, jungle, vision, clutch, objective, survival, movement, economy), so ratings are not double-counted. |
| MOBA replays | `sim/Replay.kt`, `model.Replay` (fields appended) | Replay v2: every hero's position, health and state every 2 s; minion wave fronts; casts (caster, target, slot, effects, target point); structure health steps; wards and sweeps; recalls; items; objective spawns; teamfights (start, place, kills, duration); kills with assists and respawn times; executions (tower / monster deaths, not team kills). |
| MOBA viewer | `ui/MobaView2.kt`, `ui/LiveMap.kt` | Plays the recorded state: real positions, health bars, levels, dead / respawn timers, CC and recall markers, stealth; ability effects drawn by type (projectiles, areas, beams, dashes, heals/shields, CC rings); minion waves; structure health; objectives alive/taken; wards; movement paths. 1×–32× speed, pause, skip, scrub, previous/next event, next kill / teamfight / objective / structure, follow a player, auto camera on the action, zoom/pan/reset, spectator or either team's (approximate) vision. Old v1 replays keep the previous event view. |
| Shooter rounds | `sim/ShooterRound.kt`, `sim/TacticalEngine.kt` | Rounds are played on the map graph: legal paths (teleporters, one-way drops, vents, stairs, levels), line of sight through the map walls, smokes and walls block sight, flashes/concussions blind, recon and traps reveal, slows, mollies/HE burn, heals. Weapons: fire rate, body/head damage, range, armour penetration (CS2), shields (Valorant). Buys become weapons, armour, kits (Valorant charges / signatures / ultimates) or CS2 grenades and defuse kits. Attack plans (execute, split, default, lurk), defender holds with angle advantage, rotations on information, plants, post-plant positions, retakes, defuses (kit in CS2) in real time. Economy, halves and overtime unchanged. |
| Shooter replays / viewer | `ui/ShooterView2.kt` | Per-second positions, health, blindness, plant/defuse progress, utility areas, kill lines with weapons, bomb; round jumps, kill / utility / plant jumps, speeds, follow. |
| Analysis | `ui/Analysis.kt`, `ui/MatchScreen.kt` | MOBA: gold-lead chart (tap to watch), key moments, laning by lane at the end of laning, teamfights, players' damage / healing / crowd control / casts / wards, heatmaps (positions, deaths, wards, abilities) per team or player, "observed" facts vs "analyst's reading". Shooters: every round (buys, winner, how, opening kill), opening duels per player, trades, attack/defence results, site conversions, observed vs opinion. Every row opens the replay at that moment. Games recorded before positions were stored say so instead of inventing data. |
| Custom competitions | `comp/CustomComps.kt`, `ui/CompEditor.kt`, `model.CustomComp`, `CompTier.CUSTOM` | Create a competition for any of the seven games: league, double league, groups + single or double elimination, single elimination (byes), double elimination, Swiss + single elimination (8 or 16 teams); invited teams, whole leagues or the top N of a finished competition; best-of per stage with title checks (MOBAs 1/3/5/7, shooters 1/3/5); dates; prize pool and entry fees; Fearless rule for MOBAs; LAN/online; one-off or every season. Live validation (team count, format fit, legal match lengths, schedule fits the season, duplicates) with a preview (stages, byes, matches, days, prize pool). Publishing creates an edition played and paid like any competition; recurring events get a new edition each season; cancelling stops future editions; finished editions are never rewritten. Reached from Events (+ Create) and More. |

## Tests and results (JVM, `./gradlew testDebugUnitTest`)

All suites pass (21 classes). New or extended:

* `MobaBalanceTest` — 5 titles × 40 seeded games (strongest vs weakest) + 20 vs a near peer. Checks:
  length within 75–130 % of the title's average, kills in range, the strongest roster wins most
  games, near peers both win games, every game ends with a core falling, recorded kills equal the
  score, no ability recast before its shortest cooldown, no dead hero casts, the same seed gives the
  same game with or without recording.
* `ShooterBalanceTest` — Valorant and CS2 × 40 seeded games over all pool maps: kills per round,
  attack win rate, plant rate, defuses, utility per round, strongest roster wins, recorded kills =
  box score, one round-end event per round.
* `CustomCompTest` — 6/8/16-team league, groups+SE, SE with byes, DE, Swiss+SE, groups+DE played to
  a champion with prizes paid; invalid drafts (1 team, Swiss of 10, Bo7 in a shooter, groups too
  small) create nothing; duplicates count once; cancellation; recurring edition next season with the
  old result kept; drafts and editions survive save/load.
* `IntegrityTest.twentySeasonSoak` (UEM_SOAK=1) — now also checks a recurring custom event is played
  exactly once per season and fixture ids stay unique.
* Existing `ReplayTest` (recording/watching never changes results, replays add up to box scores,
  interrupted live series resume identically, results applied once), `IntegrityTest`, `SaveTest`,
  `SeasonTest`, `ScreenshotTest` (renders the new viewers and analysis) pass.

Last measured balance (seeded, 4-core desktop JVM, includes recording):

```
mlbb     length 15.3 min  kills 27.5  structures 7.8  objectives 3.2  casts 480   strongest wins 90%  vs peer 62%
wildrift length 19.9 min  kills 35.6  structures 11.0 objectives 6.4  casts 848   strongest wins 95%  vs peer 75%
hok      length 18.1 min  kills 30.9  structures 8.6  objectives 6.2  casts 480   strongest wins 95%  vs peer 81%
lol      length 34.8 min  kills 40.6  structures 14.5 objectives 8.6  casts 1252  strongest wins 70%  vs peer 50%
dota2    length 42.4 min  kills 50.3  structures 13.2 objectives 11.1 casts 418   strongest wins 65%  vs peer 62%
valorant kills/round 7.0  attack wins 52%  plants 45% of rounds  defuses 15% of plants  strongest wins 90%
cs2      kills/round 6.5  attack wins 52%  plants 46% of rounds  defuses 11% of plants  strongest wins 92%
```

Performance (desktop JVM, 4 cores): a MOBA game ≈ 6–25 ms, a shooter map ≈ 13–23 ms; 120 days of
the full seven-title calendar (≈ 4,900 games) in 6.8 s. Phones are slower per core (not measured
here: no device or emulator in this environment).

20-season soak (`UEM_SOAK=1`, all seven titles, user career, a recurring custom event): passed. Each
season ≈ 44–59 s on 4 desktop cores (the previous abstract engine needed ≈ 8 s), heap after each
season 109–227 MB, save 3.3 → 19.3 MB, box-score / replay pages 66 → 238 MB (replays older than
about two seasons are pruned; box scores are kept). The custom event was played exactly once every
season; fixture ids stayed unique; every league team could field a lineup in every season.

Expect a simulated season on a phone to take a few minutes (it is several times slower per core than
a desktop); long "simulate 10 seasons" runs save after every season and can be stopped. Settings →
Replays → "Important" (default) or "Off" keeps the page storage down.

## Approximations (and where numbers come from)

* Ability effects are classified from official / community descriptions; damage magnitude is a
  multiplier on the engine's per-title standard (from the source where it lists damage: Dota 2,
  MLBB). Durations, Wild Rift cooldowns (PC × 0.8), HoK numbers and Valorant credit costs / charges /
  ultimate points are approximations and marked `approx` per ability in `abilities.json`.
* Weapons: public values (Valorant from valorant-api; CS2 approximate 2025 values); Valve and Riot
  change them in patches.
* MOBA geometry: a 50 × 50 walkability grid from the map's wall rectangles (approximate layout, not
  the games' navigation meshes); vision radius per title; tower, objective and minion numbers are
  engine values tuned so game length, kills and structures match each title's typical pro games.
* Shooter geometry: the existing node graphs plus a line-of-sight grid from the wall rectangles
  (approximate layout); movement speed and timings (round 100 s / 115 s, spike 45 s / bomb 40 s,
  plant 4 s / 3.2 s, defuse 7 s / 10 s, 5 s with a kit) follow the games' rules.

## Saves and migration

All schema changes are appended fields (`Replay.per/step/posN/pos/posT`, `Competition.finalBo/
customId`, `World.customComps`, `CompTier.CUSTOM` at the end), so existing saves load. Replays made
before this upgrade (v1) still play in the previous event view; their analysis shows "unavailable"
rather than invented heatmaps.

## Known limitations

* Not run on a phone or emulator here (no hardware acceleration in this environment): frame rate,
  battery and ANR behaviour on devices are unmeasured. The viewer's toggles (abilities, minions,
  paths, names) serve as a reduced-effects mode.
* Kills in LoL (~40 per game) are above the pro average (~25–30); Dota 2 games are a few minutes
  longer than average. Occasional outlier games (a hero dying very often) remain and are logged by
  the tuning tests (`MobaTraceTest`, `MobaDiagTest`, `MobaCharBalanceTest`, env-gated).
* Character win rates spread roughly 35–65 % (10th–90th percentile) in random drafts; the draft AI
  does not yet know which kits are strong in the engine.
* Release build: the APK is a debug build. To make a signed release: create a keystore outside the
  repository (`keytool -genkeypair -v -keystore ~/keys/uem.jks -alias uem -keyalg RSA -keysize 2048
  -validity 10000`), add a `signingConfigs.release` reading `UEM_STORE_FILE`, `UEM_STORE_PASSWORD`,
  `UEM_KEY_ALIAS`, `UEM_KEY_PASSWORD` from the environment, and run `./gradlew assembleRelease`.
  Never commit the keystore or passwords. A release build cannot be installed over the debug build
  (different signature): export the save first.

APK: `native-android/app/build/outputs/apk/debug/app-debug.apk` (copied to
`release/esports-manager-native-debug.apk`).
