# Audit against the APK review (21 Nov 2026)

Baseline before changes: 8 JVM test suites, all passing (`./gradlew testDebugUnitTest`).
After: 13 suites (adds ReplayTest, IntegrityTest, MapTest, DataReportTest; soak test with `UEM_SOAK=1`).

| File / class | Was implemented | Found incomplete or failing | Change made |
| --- | --- | --- | --- |
| `sim/SeriesSim.kt` `SeriesSim.run` | Whole series for background matches | Used `autoVeto()`, a different map-veto algorithm from the live `VetoSession` → watching a shooter series could change its maps | Background series now take the same veto / draft path as live series; `autoVeto` removed; optional recording and resume |
| `sim/MobaEngine.kt` | Minute-tick MOBA engine, kills, fights, objectives, structures | Dead players could still take part in plays; assists not attributable to kills; no levels; events only for the watched game | Deaths with exact respawn times (no play while dead, buyback restores), assist bit masks, experience → levels, per-tick gold / CS / level snapshots; recording draws no random numbers |
| `sim/TacticalEngine.kt` | Round engine (buys, duels, trades, plants, defuses, OT) | Kill location and assister not recorded; no economy snapshots | Kill location (approach / site / post-plant), assister id, per-round credits and loadout |
| `sim/Replay.kt` (new) `Recorder`, `ReplayCodec` | — | No stored replays | Compact replay (`GameRecord.replay`), codec, snapshot lookup |
| `ui/LiveMap.kt` `MobaViewer` / `TacticalViewer` | 2D viewers | Levels, gold, health were viewer estimates; assists apportioned; respawn timer guessed | Viewers read only the replay (K/D/A, respawns, gold, CS, levels, credits); jump to kill / fight / objective / structure / highlight; timeline markers; minion waves (drawn); team filter; follow camera |
| `ui/LiveVm.kt` `LiveMatch` | Live series for your team | No spectating; nothing survived an app restart mid-series | Spectator mode (both coaches draft step by step); progress saved to `World.live` after every game; resume; `simRest` records replays |
| `game/Advance.kt` | Day loop, apply, rollover | `apply` could count a result twice; unfinished live series lost; no replay policy; population drained over ~20 seasons | Apply-once guard; resume from `World.live`; replay policy (important / mine / all / off); replay pruning after ~2 seasons; youth intake keeps each game's active pool near its start size |
| `persist/SaveStore.kt` | Atomic world save + `.bak`, weekly box pages | Pages written in place; no export / import | Atomic page writes; export (zip + manifest + SHA-256) and import into a new slot with checks and renaming |
| `gen/WorldGen.kt` | Real snapshot + fictional leagues | Facts from a different person with the same handle applied (Peyz, Ruler, Caps, Knight); no provenance; HoK fictional and WR 2021 not labelled | Name-matching guard; `Team.dataNote` and player provenance (verified / estimated / generated / historical) shown in the UI; docs/DATA_PROVENANCE.md |
| `ui/MapGeo.kt` `Maps.Shooter` | Untyped two-way edges | No one-way links, levels, teleporters | Typed directed links (walk, stairs, ladder, door, vent, breakable, drop, teleport) and levels from `assets/maps_topology.json` (Bind teleporters, Lotus drop / doors / wall, Nuke levels / vents); directed pathfinding; drawn in the viewer |
| `ui/Watch.kt` (new) | — | No way to watch other matches or replays | Match centre, replay screen, spectator home, resume card |

## Not done / limits

* Movement between recorded events is drawn by the viewer (lanes, routes, rotations); the engines
  are event engines, not positional simulations. Wards / fog, cooldowns and per-ability casts are
  not simulated.
* Honor of Kings current rosters and current Wild Rift rosters: no source could be used (see
  docs/DATA_PROVENANCE.md).
* Nothing here was run on a phone or emulator (no hardware acceleration in this environment):
  background / force-stop behaviour is covered by the saved `World.live` state and JVM tests, not
  by device runs.
* Release build: the APK is a debug build (debuggable). A signed release build needs a signing key
  kept by you; installing it over the debug build requires uninstalling (export your save first).
