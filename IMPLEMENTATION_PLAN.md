# Implementation plan

Priorities (master prompt, 19 Nov 2026): keep the working app and data → seven-title restriction →
playable coherent core → installable Android APK → deepen management mechanics → presentation and
balancing.

## History (before the seven-title edition) ✅
Foundation (React/TS/Vite, router, Zustand, IndexedDB, world generator, calendar), shared management
mechanics (86 attributes, proficiencies, effectiveness, staff, contracts, finance, scouting, transfers,
training, AI market), competitions (RR, groups, Swiss, brackets, splits, MSI, Worlds, Nations Cup),
history/awards/GOAT/HoF, careers & board, negotiations, drafts & picks, saves and the Web Worker were built
for a 30-title scope and are kept.

## M0 — Audit, preserve and narrow ✅
* Catalogue reduced to S220 MLBB, S221 Wild Rift, S222 HoK, S223 LoL, S224 Dota 2, S232 Valorant, S234 CS2
  (stable `sourceId`s); excluded titles removed from UI, seeds, AI, competitions, tests.
* Battle-royale, hero, arena, Brawl-sets and hybrid engines and BR points stages deleted; exactly two engine
  families remain.
* Draft defaults per spec (no silent series restrictions; LoL Fearless optional preset), MOBA side choice
  (loser chooses), Dota buyback.
* Old saves: v5 migration + conversion with an exportable untouched backup; unconvertible saves refused
  with an explanation.

## M1 — Playable seven-title core ✅
Legal 5v5 lineups, the MOBA and shooter adapters with title-specific rules/stats, results, standings,
background AI, rollover; save/load with **five manual slots** + autosave; tests for registry, formats,
overtime arithmetic, side choice, buyback, conversion and slots; e2e flows (career, live match, slot save &
reload, transfer negotiation, mobile layout).

## M2 — Android APK alongside web ✅ (device test pending)
Capacitor 8 project (`android/`), scripts (`build:web`, `android:sync`, `android:debug-apk`,
`android:install`), system Back, safe areas, background autosave; debug APK built and verified at
`android/app/build/outputs/apk/debug/app-debug.apk`. Remaining: install/run on a physical device or emulator
(not possible in the build environment — no hardware virtualisation), release signing when needed.

## M3 — Deep management systems 🔶
Done: scouting with uncertainty, 86-attribute profiles, training incl. input/role/character adaptation,
transfers (negotiations, pre-contracts, sell-ons), loans, trades & picks, RFA, staff, board & job security,
finances & facilities, news, awards, national teams.
Next: organisation-level treasury with department allocations that sum to cash, staff capacity/philosophy,
explicit roster registration separate from contracts, dual-title rosters with one shared calendar, fan
segments, training-session capacity/cancellation.

## M4 — Long careers and polish 🔶
Done: deterministic seeded simulation, idempotent days, save-size compaction, 10-season soak test.
Next: per-title calibration reports, phone/foldable performance profiling on a real device, difficulty
presets, release-signing automation (documented in ANDROID_BUILD.md).

## Changing a title's rules
Titles are fixed to the seven above. Their presets live in `src/data/titles/catalogue.ts` (roles, draft
steps, formats, engine config, stats, salary/prize scale, popularity row). After changing a preset run
`npx vite-node scripts/calibrate.ts` and the test suite; competition-level variants belong in
`world.rulePresets` / the Database Editor as named presets, never as silent defaults.
