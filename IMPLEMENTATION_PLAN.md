# Implementation plan

Priorities (spec order): simulation speed & correctness → save stability → gameplay depth → long-term
world → statistical accuracy → clean UI → expandability → visuals.

## Phase 1 — Working foundation ✅
React/TS/Vite shell, hash router, Zustand store, IndexedDB saves, world generator (countries, regions,
leagues, organisations, teams, players, staff), shared calendar, time advancement, save/load.
*Dependency for everything else.*

## Phase 2 — Shared management mechanics ✅
86 attributes, input proficiencies, title/role/character/map/weapon proficiencies, effectiveness model,
staff, contracts, finance, scouting, transfers (FA, bids, trades, loans, promotions), training &
progression, AI market.

## Phase 3 — Competitive vertical slices ✅
Working engines for LoL/MLBB (MOBA), Valorant/CS2 (tactical), Overwatch/Rivals (hero), PUBG/Apex (BR),
Brawl Stars (sets), Deadlock (hybrid), plus drafts, vetoes, stats, commentary, live play.

## Phase 4 — All 30 rulesets ✅
Every title configured on the engine families with distinct roles, drafts, objectives, economies, modes,
maps, formats and stats; all 30 complete seasons in tests.

## Phase 5 — Advanced world simulation ✅ (core) / 🔶 (extensions)
Done: multi-league structures, promotion/relegation, MSI/Worlds qualification, popularity drift, patch
evolution & eras, cross-title careers, staff movement & ex-player staff, academies, org expansion,
closures, acquisitions, rebrands, history, awards, GOAT, Hall of Fame.
Also done: national teams, rookie drafts with tradable picks, restricted free agency, career-role permissions.
Next: dual-title rosters, affiliate exchanges, AI pick trades.

## Phase 6 — Long-term stability & Android 🔶
Done: deterministic seeded simulation, idempotent days, pruning/archiving for long saves (10-season soak in
CI, 30-season optional), save validation & migration, backup recovery, responsive layout, relative build.
Next: performance work for Standard/Large worlds with all 30 titles (target < 10s/season), Capacitor
project scaffold, on-device memory profiling.

## Adding a new title
1. Add a `TitleDef` to `src/data/titles/catalogue.ts` (roles via `MOBA_ROLE`/`SHOOTER_ROLE` presets, lineup,
   draft steps, formats, engine config, stats, salary/prize scale) and a popularity row.
2. Pick an existing engine family (or add one under `src/sim/` and a case in `series.ts`).
3. Run `npx vite-node scripts/calibrate.ts` with `TITLES=<id>` and adjust constants if needed.
4. The season planner, AI, finance, UI and tests pick it up automatically.
