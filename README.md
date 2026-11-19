# Ultimate Esports Manager

A deep, spreadsheet-style esports management simulation covering **30 competitive titles** across two
families — **13 MOBAs** and **17 shooters** — in a persistent, fictional global esports universe that can
be simulated for as many seasons as you like.

Manage a multi-title organisation as combined Coach / General Manager: recruit fictional players with an
86-attribute model, set lineups and playstyles, draft characters and veto maps, train and develop
players, balance the books, and watch rivals rise and fall across decades.

Everything runs **offline in the browser** (no server, no account, no paid API). Saves live in IndexedDB.

---

## Play it

**Portable single file (no install):** run `npm run build:portable` and open `release/index.html` by
double-clicking it (Chrome, Edge, Firefox or Safari). Everything — game, simulation worker and styles — is
inlined into one ~1 MB HTML file that works offline from disk; saves persist in the browser's storage for
that file (use *Settings → Export save file* to move a career between browsers or machines). The simulation and
autosaves run in a background worker so the page stays responsive (`npm run check:portable` verifies this).

**From source:**

```bash
npm install
npm run dev          # http://localhost:5173
```

**Host it:** `npm run build` produces a static `dist/` folder (relative paths) that any static host
(GitHub Pages, Netlify, an S3 bucket, a USB stick served by `npx serve`) can serve as-is.

Other scripts:

| Command | Purpose |
| --- | --- |
| `npm run build` | Type-check + production build into `dist/` (relative paths, Capacitor-ready) |
| `npm run build:portable` | One self-contained `release/index.html` (worker inlined) that runs from disk |
| `npm run check:portable` | Build it and smoke-test it from `file://` (worker loads, no freezes, autosave survives reload) |
| `npm run preview` | Serve the production build |
| `npm test` | Vitest suite (106 tests: model, engines, competitions, time, saves, transfers, multi-season, long-term) |
| `npm run test:long` | 30-season, 5-title soak test (`LONG_SIM=1`) |
| `npm run e2e` | Playwright end-to-end tests (builds + previews automatically) |
| `npx vite-node scripts/calibrate.ts` | Balance report: win rate vs strength gap per engine |
| `npx vite-node scripts/economy.ts` | Economy report: org cash flow distribution over a season |
| `npx vite-node scripts/dynasty.ts` | Competitive turnover over 8 seasons |
| `npx vite-node scripts/bench.ts` | Simulation speed benchmark (`TITLES=… SIZE=…`) |

## How to play

1. **New career** — choose save name, seed, career role (Head Coach & GM, Head Coach, GM, Sporting
   Director, Owner, Commissioner), world size (Tiny → Large) and which of the 30 titles exist.
   The world is generated (countries, leagues, organisations, teams, players, staff, finances) and the
   game starts **on today's date**, in the off-season before the new season.
2. **Pick an organisation** — filter by title; each has divisions, reputation, finances and a board strategy.
3. **Advance time** with **Continue ▸** (next match day / event) or the ▾ menu (1 day, 1 week, 1 month,
   to end of season, simulate 1/5/10 seasons). The game pauses before your match days.
4. **Match day** — *Play live* to run the map veto, draft every game (bans / picks with meta and comfort
   hints, or let the coach finish), then play game by game with box scores and commentary. Or *Simulate*
   to delegate to your coaching staff. Battle royale events are watched live as full-lobby games.
5. **Manage** — Tactics/Draft (lineup, positions, playstyle, aggression, draft priority, character pools,
   ban priorities, map preferences, delegation), Training (team & individual focus, intensity, scrims,
   opponent prep), Market (free agents, player search, transfer negotiations — fee then personal terms —, pre-contracts, asking prices, counter-offers, loans, trades, shortlist), Scouting,
   Finances (division budgets, ledger, charts), Facilities, Organisation (buy league slots to expand into
   new titles, found academies, close divisions, rebrand). Optionally take a **national-team job** (Teams
   page) and coach a country in the biennial Nations Cup.
6. **Explore** — every player, team, organisation, competition and staff member has a profile; global
   search, breadcrumbs, browser back/forward, sortable/filterable tables with column choosers, player
   comparison, world popularity map, patch history, history/champions, GOAT rankings and Hall of Fame.
7. **Customise** — the Database Editor edits players (all 86 attributes + input proficiencies), creates
   players, edits organisations, league policies (age, roster, import limits, salary caps, trades, loans,
   transfer fees, franchising, full-detail simulation), series formats per title, and regional popularity.
8. **Saves** — multiple slots, autosave after every advance with a rolling backup, crash recovery prompt,
   JSON export/import, versioned schema with migrations.

Controls: everything is mouse/touch driven. `Enter` in the search box opens the first result.

## Architecture

```
src/
  core/          seeded RNG (sfc32), calendar day numbers, math helpers
  model/         World/Player/Team/... types, 86-attribute table, id allocation
  data/          countries & regions, culture name pools, the 30-title catalogue (rulesets),
                 phase/role weighting presets, fictional character & map generators
  gen/           world, player, staff, organisation and name generators
  sim/
    common/      effectiveness model (E formula, input adaptation, phase ratings), team context,
                 draft/composition logic, engine helpers
    moba/        lane MOBA engine (gold / shared-XP / score / souls economies), Brawl Stars engine
    shooter/     tactical, hero, arena (CoD) and battle royale engines
    series.ts    vetoes, per-game drafts, fearless rules, sets, BR events (incremental for live play)
    record.ts    applies results: stats lines, Elo, form/morale/fatigue, proficiency gains, news
  competition/   stage formats (RR, groups, Swiss, single/double elim, page playoff, BR points),
                 season planner (splits, playoffs, MSI, Worlds), qualification & lifecycle
  systems/       contracts, finance, transfers & AI market, lineup, training & progression, patches,
                 awards/legacy/HoF, scouting, staff, organisation actions, news & inbox
  game/          time advancement (idempotent day processing) and season rollover
  persistence/   IndexedDB saves, backups, session recovery, zod validation, migrations
  worker/        Web Worker for world generation and multi-day/season simulation
  ui/            React app: shell, router, store (Zustand), components, pages
tests/           Vitest suites          e2e/  Playwright specs          scripts/  tuning tools
```

Key properties:

* **Deterministic** — every random draw comes from an RNG derived from the world seed plus a stable key
  (fixture id, game index, day, entity). The same save + seed always produces the same future; watching
  a match in full detail never changes its result.
* **Idempotent time** — each calendar day is processed exactly once (`world.processed.lastDay`), monthly
  finance once per month, each season rollover once; repeated or interrupted commands cannot duplicate
  fixtures, awards, transfers or money.
* **Title-agnostic core, title-specific rules** — universal systems (players, contracts, AI, finance) are
  separate from rulesets in `data/titles/catalogue.ts` and the engine families in `sim/`. Adding a title is
  a catalogue entry (roles, draft, formats, maps, engine config, popularity) on an existing engine family.
* **Scales to long saves** — unobserved matches keep results + season stats only; old competitions,
  fixtures and low-legacy retirees are pruned/archived at rollover so save size stays bounded.

## Android packaging (Capacitor)

The build uses relative asset paths and no server APIs, so it can be wrapped directly:

```bash
npm i -D @capacitor/cli && npm i @capacitor/core @capacitor/android
npx cap init "Ultimate Esports Manager" com.example.uem --web-dir dist
npm run build && npx cap add android && npx cap sync android && npx cap open android
```

IndexedDB persists inside the Android WebView; the layout is responsive (collapsible sidebar, scrollable
dense tables, single-column cards below 860px).

## Documentation

* [GAME_DESIGN.md](GAME_DESIGN.md) — consolidated mechanics and requirements
* [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) — phases, dependencies, priorities
* [PROGRESS.md](PROGRESS.md) — what is complete, partial and missing
* [DECISIONS.md](DECISIONS.md) — assumptions, formulas and balancing constants
* [TESTING.md](TESTING.md) — test coverage, validation results, known issues

All organisations, players, staff, characters, maps and competitions are fictional and generated.
Title names are used only to identify rulesets; mechanics are editable simplified presets, not official rules.
