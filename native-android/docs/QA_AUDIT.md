# QA audit: competition integrity, shooter maps, Android stability (27 Nov 2026)

Scope: the three priorities of the stability brief only. Seven titles: MLBB, Wild Rift, HoK, LoL, Dota 2,
VALORANT, CS2. The audit used the source repository at commit `58147ce`, not the APK. Results and before/after
figures are in [QA_RESULTS.md](QA_RESULTS.md).

## Baseline (before any change in this phase)

| What | Result | How |
|---|---|---|
| Build + unit suite | APK built; 117 tests, 0 failures, 4 opt-in long runs skipped | `./gradlew assembleDebug testDebugUnitTest` |
| One season, all seven games (fast simulation, JVM) | 75 s, 443 events, 13,929 series | `QaProbeTest` (`UEM_PROBE=1`) |
| 20 seasons, four MOBAs | passed; 64 s / season; 43 MB save | `LongEcosystemTest` (`UEM_ECO=1`) |
| Shooter rounds by map (16 seeded games each) | Sunset: attackers win 9 %, Nuke 11 % (other maps 40–63 %) | `MapStatsProbeTest` (`UEM_MAPSTATS=16`) |
| Device profiling | **not done**: no device or emulator in this environment | — |

## Where things live

| Area | Source |
|---|---|
| Editions, stages, fixtures, progress, entrants, placements | `comp/Competitions.kt` (`Season`, `Stages`, `Progress`) |
| Circuit points, berth allocation, play-in seeds, LCQ | `Progress.circuitPoints`, `Progress.entrants`, `Progress.leagueOrder`, `comp/Formats.kt` |
| Regional coefficients, seed redistribution, reforms | `comp/Formats.kt` (`performance`, `evolve`, `applyPending`, `preview`) |
| League expansion / merger / split / recognition / associate | `comp/Leagues.kt` |
| Academy leagues, call-ups | `mgmt/Academies.kt` |
| Best of the rest, regional finals, CACL, rivalry cups | `Season.invitational`, `Season.regionalFinals`, `comp/LivingWorld.kt` |
| Major patches | `mgmt/Patches.kt` → `Formats.evolve` |
| Qualification ledger | `model.QualEntry`, `Competition.entries` |
| Day advance (parallel series, one series a day per player) | `game/Advance.kt` |
| Shooter map graphs and walls (14 maps) | `assets/maps.json`, `assets/maps_topology.json`, `ui/MapGeo.kt` |
| Shooter rounds: movement, pathing, sight, utility, plant / defuse | `sim/ShooterRound.kt`, `sim/TacticalEngine.kt` |
| Replays and the 2D viewer | `sim/Replay.kt`, `ui/ShooterView2.kt` |
| Saves, box-score pages, export / import | `persist/SaveStore.kt`, `game/BoxStore.kt` |
| Lifecycle, threading, autosave | `MainActivity.kt` (`onStop`), `ui/GameVm.kt` |

## Matrix

Status: **implemented** (works end to end, with a test), **partial**, **missing**, **broken** (a defect
found). "Now" is the state after this phase.

### 1. Competition ecosystem

| Item | Before | Evidence | Now |
|---|---|---|---|
| League RR / DRR, groups, Swiss, single / double elimination | implemented | `Stages.start`, `SeasonTest`, `FormatsTest` | implemented; checked every season by `Invariants` |
| Swiss pairing by pre-match record, no rematches | **partial**: a greedy draw forced a rematch when a valid draw existed (seen at The International and the Demacia Cup) | probe warnings | a draw without rematches is searched first; a rematch stays only when none exists, and is logged |
| Play-Ins as a separate competition | implemented | `defKey …:playin`, `ArchiveNavTest` | implemented |
| LCQ: no team with a direct berth | implemented, unchecked | `lcq` rule | checked every season (`Invariants.entryRules`) |
| Best of the rest: nobody at the world championship | implemented, unchecked | `rest` rule | checked every season |
| Qualification ledger traceable (source edition + placing) | implemented, unchecked | `QualEntry` | checked: each placing-based entry matches the source's final placing; one berth per entry; ledger = field |
| Academies never in the parent's league or promotion | implemented | `AcademiesTest` | also checked every season |
| Seats conserved at season boundaries unless a reform applies | not checked | — | checked by the harness |
| Editions frozen once running; history never rewritten | not checked | — | checked by the harness (format signature while running, results hash after) |
| Athletes: one series a day | implemented in `Advance.playFixtures` | `busy` set | checked from the stored box scores every season |
| Rosters, organisations, transfers, awards (no duplicates) | not checked | — | `Invariants.world` (also run on every load) |
| Whole-world long run, all seven games, fixed seeds, reloads | **partial**: four MOBAs only, one reload | `LongEcosystemTest` | `EcosystemHarnessTest` (seven games, seeds, four reload checkpoints, balance figures) |
| Map pools by patch / year | **missing**: one active pool per title for every season (`Catalogue.VAL_POOL`, `CS2_POOL`); no historical pools | `titles/Catalogue.kt` | unchanged (documented limitation; a rotation system is a new feature) |

### 2. Shooter maps (VALORANT, CS2)

| Item | Before | Now |
|---|---|---|
| 14 map graphs: nodes, links, sites, spawns, walls | implemented | every node reaches every site and both spawns (directed); nothing in a wall; walkable links never through a wall (`ShooterMapsTest.graphs`) |
| Special traversal only where real | implemented for Bind (teleporters), Lotus (drop, doors, breakable wall), Nuke (levels, stairs, vents) | asserted per map; every other map has walk links only |
| Line of sight through walls | **broken**: walls are a 100 × 100 raster, sight used an 80-cell grid, so 10-unit walls could fall between samples (see-through walls) | exact 100-cell grid, every cell on the line visited (`ShooterGrid.clear`); a test sends a line through every wall cell |
| Floors (Nuke) | **broken**: levels stored but ignored; upper A and lower B could shoot each other through the floor | players a floor apart see each other only at the ends of the same stairs / ladder / drop (Heaven over A) |
| Lotus breakable wall | **broken**: walked through while intact | closed until broken (3 s at it), open for the rest of the round |
| Lotus rotating doors | **broken**: plain links | operated (2.5 s), then open 10 s for both sides; a closed door blocks sight |
| Lotus A Drop → A Site | **broken**: a "walk" link through the drawn ledge | a one-way drop |
| Re-routing part-way along a link | **broken**: the player cut straight across to the new route | finishes the link or turns back along it (`midLinkRerouteStaysOnTheGraph`) |
| Leaving the spawn | **broken** (Bind): players spread around the spawn walked straight to the next node, through a wall | back to the spawn point, then along the graph |
| No route | **broken**: a straight line to the target | stays put |
| Closed links (utility wall, breakable) | missing | routes avoid them, and recover when they reopen (`closedLinksAndRecovery`) |
| Plants / defuses at legal places | implemented, unchecked | every plant and defuse within its site's radius on all 14 maps (`fullGamesOnEveryMap`) |
| Replay viewer between samples | **broken** (Bind): markers slid from a teleporter to its exit through walls | jumps when the straight line is not open |
| Recording does not change results | implemented (`ReplayTest`) | also per map |
| Sunset balance | **broken**: B Main a dead end, attackers won 9 % | B Lobby → B Main restored (topology, documented as a layout correction): 53 % |
| Nuke balance | **broken**: attackers win 11–16 % | **unresolved**, see QA_RESULTS known issues |

### 3. Android persistence and runtime

| Item | Before | Now |
|---|---|---|
| Atomic save | implemented (temporary file + rename), **partial**: not flushed to the device before the rename; the previous save copied in full each time (tens of MB) | flushed (`fd.sync`) then renamed; the previous save is moved to `.bak` (atomic, no copy); metadata written the same way |
| Recovery | **partial**: fell back to `.bak` only | the save, then a completely written `.tmp` (crash between the renames), then `.bak`; a note in the inbox and a message say which; nothing is deleted |
| Full disk / failed write | **partial**: an exception, no explanation | the previous save is untouched and the message says so (`failedWriteKeepsThePreviousSave`) |
| A career whose latest file is missing | **broken**: not listed (only `.uem` files were) | listed and opened from the backup |
| Integrity check on load | gzip CRC only | plus `Invariants.world` (reported, nothing repaired) |
| Save while the next simulation starts | **broken**: an autosave on the IO thread could still be writing while a new Advance changed the world | simulations wait for a save in progress |
| Double advance | implemented (`busy` guard) | unchanged |
| Simulation / saving off the main thread | implemented (`Dispatchers.Default` / `IO`) | unchanged |
| Process death / background | implemented (`onStop` save, `resumeIfInterrupted`) | unchanged |
| Results applied once, live series resumes identically | implemented (`ReplayTest`) | unchanged |
| Device profiling (startup, jank, memory) | **not done** (no device) | **not done**; JVM timings and heap per season from the harness instead |
