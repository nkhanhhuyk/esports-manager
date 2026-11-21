# Upgrade audit: deep 2D simulation, analysis, custom tournaments (21 Nov 2026)

Baseline before this upgrade: `./gradlew assembleDebug testDebugUnitTest` passes (13 suites,
23 tests, soak gated by `UEM_SOAK=1`). No roster/database updater work is part of this upgrade.

| Feature | Class / file | Confirmed working | Issue | Planned fix |
|---|---|---|---|---|
| MOBA simulation | `sim/MobaEngine.kt` | Deterministic per seed; kills, objectives, structures, buyback/Aegis; tuned kill totals; recording never consumes RNG | Minute-tick *event* engine: no positions, no abilities, no cooldowns, no CC; fights are dice rolls; the core falls after an abstract count | Replace the internals with a positional, ability-aware simulation on the real map (same entry point and outputs) |
| Character kits | `assets/charinfo.json`, `titles/Catalogue.kt` | 724 characters; skill text for LoL (173), Dota 2 (127), Valorant (30) | MLBB (133), Wild Rift (143), HoK (118) have no skills; no structured mechanics for any title | New `assets/abilities.json` (typed mechanics per ability, sourced per title) + `sim/Abilities.kt` |
| MOBA map | `assets/maps.json`, `ui/MapGeo.kt` | Lanes, towers, camps, pits, wall rectangles for 5 maps | Geometry lives in the UI layer (Compose types); no walkable grid, no pathfinding, no line of sight | `sim/MobaMap.kt`: walkability grid from the wall data, distance-field pathfinding, grid line of sight (labelled approximate layout) |
| Replays | `sim/Replay.kt`, `model.Replay` | One authoritative stream; watch = quick sim; v1 events + gold/CS/level snapshots | No positions, HP, casts or structure HP: the viewer invents movement | Replay v2 (appended fields): sampled positions/HP/status, casts, structure damage, wards, waves; v1 stays viewable |
| MOBA viewer | `ui/LiveMap.kt` `MobaViewer` | Map, tokens with portraits, follow, scrub, jump to kill/fight/objective | Movement and health bars are drawn guesses; speeds 15–120× only; no cooldowns, abilities or structure HP | Project v2 replay state; 1×/2×/4×/8× + skip, previous/next event, overlays, cast effects |
| Shooter simulation | `sim/TacticalEngine.kt` | Economy, buys, duels, trades, plants/defuses, OT; replay of round events | Round outcome rolled abstractly; no positions, line of sight, utility or weapons | After MOBAs: positional rounds on the topology graphs with utility/weapons |
| Shooter maps | `assets/maps.json` shooter, `maps_topology.json` | 14 graphs; typed links (teleporters, drops, vents, levels); `MapTest` | Graph nodes, not geometry; no visibility data | Visibility between nodes derived from graph adjacency (labelled approximation) |
| Match report | `ui/MatchScreen.kt` | Draft, box score, series totals | No timeline, heatmaps or explanations | Analysis from recorded v2 data with jump-to-replay |
| Competitions | `comp/Competitions.kt`, `Stages` | RR/DRR/groups/Swiss/SE/DE generators used by the calendar | No user-created events | Custom competition editor reusing these generators |
| Saves | `persist/SaveStore.kt`, `BoxStore` | Atomic pages, export/import, idempotent results | — | Append-only schema changes; v1 replays kept |
