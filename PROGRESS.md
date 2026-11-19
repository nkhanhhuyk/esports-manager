# Progress

Status of the specification, honestly assessed. "Playable" means usable end-to-end in the UI and covered
by simulation tests.

## Complete & playable

* **30-title catalogue** with correct inputs, families, engines, roles/lineups, draft rules, map veto modes,
  series formats (spec tables), maps/modes, objectives, structures, economies, stats, popularity.
* **All 30 titles run full seasons** (regional splits → playoffs → MSI → Worlds → rollover) under their own
  rules — verified in `tests/season.test.ts`.
* **Seven engine families** (lane MOBA with 4 economies, Brawl Stars sets, tactical, hero, arena, battle
  royale, Deadlock hybrid) with title-specific configs, stats, ratings, MVPs and commentary.
* **86-attribute model + 3 input proficiencies** with the specified formulas; derived summaries; title / role
  / character / map / weapon proficiencies; CA/PA; personalities; trajectories.
* **World generation**: 20 regions, 95 countries, culture-specific names & handles, multi-title organisations,
  leagues (single & macro regions, challenger tiers), rosters, staff, finances, sponsors, patches.
* **Time**: day/week/month/next event/end of season/1-5-10 seasons; pauses before user match days;
  idempotent day processing; Web Worker for long advances with progress bar.
* **Live matches**: map veto plan, interactive drafts (sequential & simultaneous, fearless), game-by-game
  play with box scores and commentary; BR events watched as full lobbies; results recorded identically to
  instant sims.
* **Competitions**: RR/DRR, groups, Swiss, single elim with byes, double elim, page playoff, BR points with
  match point; qualification by placements and circuit points; prize money; promotion/relegation.
* **Market**: free agents, bids/counters/buyouts, extensions, releases, loans, trades (policy-gated),
  academy promotions, cross-title signings with title switching, AI offers for user players (inbox),
  shortlist, transfer history; AI clubs recruit by needs/strategy/budget, renew and poach.
* **Finance**: monthly accounts per org with division budgets, ledger, charts, facilities upgrades,
  sponsorship renewal, distress → injections/acquisitions.
* **Training & progression**: team/individual focus, intensity, scrims, opponent prep, diminishing returns,
  fatigue & health setbacks, aging & decline, potential revisions, resurgences, input adaptation training.
* **Patches/meta**: per-title version history, balance & major patches, styles, map pool rotation, objective
  values, eras; meta adaptation for teams.
* **Awards/history**: tournament/finals/worlds MVPs, season awards incl. role & stat leaders, coach/team/org
  of the year, champions history, dynasties, GOAT (per title & cross-title), Hall of Fame, retired archive.
* **Organisation lifecycle**: buy league slots (enter new titles), academies, division closure, rebrand,
  AI rebrands/relocations/closures/acquisitions with history preserved.
* **Staff**: generation, hiring/release, development, retirement, ex-players into staff.
* **Saves**: IndexedDB slots, autosave + backup, crash-recovery prompt, export/import JSON, zod validation,
  v1→v2 migration.
* **UI**: sidebar shell, breadcrumbs, back/forward, global search, sortable/filterable/paginated tables with
  column chooser, profiles for every entity, player comparison, title-adaptive match screens, charts,
  light/dark themes, responsive mobile layout, database editor.

## Partially implemented

* **Career roles**: data model supports coach, GM, director, owner, national coach, commissioner; the game
  plays as combined Coach/GM with delegation toggles (lineup, draft, routine training/contract reminders).
  Role-specific permission sets are not enforced yet.
* **Dual-title participation**: `Player.dualTitleId` exists and cross-title histories are tracked, but a player
  cannot yet appear on two active rosters simultaneously.
* **Drafts (rookie drafts)**: league policy flag exists (`draft`), not yet run at rollover.
* **Restricted free agency / luxury tax**: policy fields exist; soft-cap tax is not charged yet.
* **National teams / Nations Cup**: not implemented (data model leaves room via career role).
* **Scouting assignments**: scouts auto-scout your titles + focus list; no region assignment UI.
* **Affiliate/partner organisations & joint ownership**: `partners` field exists; no gameplay yet.
* **Media/social reactions**: news feed covers major events; no fan-sentiment model.

## Missing / next steps (priority order)

1. **Performance for Standard/Large worlds with all 30 titles** — currently ~15s/season for Tiny (446 teams).
   Next: cache per-game team contexts, pre-filter draft candidates, lighter AI market scans, optional
   lower-detail engine path for very low-tier leagues.
2. Role-based permissions for career modes (owner/director/national coach) and board objectives/job security.
3. Rookie drafts, restricted FA, luxury tax collection, trade blocks UI.
4. National team competitions (biennial Nations Cup per title).
5. Dual-title rosters with schedule-conflict fatigue.
6. Richer AI strategic shifts (rebuild vs contend cycles), affiliate partnerships and player exchanges.
7. More granular commentary (per-round tactical narration), heatmap-style analytics.
8. Capacitor Android project scaffold and on-device performance tuning.
