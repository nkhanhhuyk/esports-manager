# Progress

Status of the specification, honestly assessed. "Playable" means usable end-to-end in the UI and covered
by simulation tests.

## Milestone status (master prompt of 19 Nov 2026)

| Milestone | Status | Evidence |
| --- | --- | --- |
| M0 seven-title restriction | ✅ | `tests/model.test.ts` registry gates; no other title in catalogue/UI/seeds/tests; old saves converted with backup (`tests/persistence.test.ts`) |
| M1 playable core | ✅ | all seven complete a season (`tests/season.test.ts`); engine integrity (`tests/engines.test.ts`); Playwright career + slot save/reload |
| M2 Android APK | ✅ built, ⏳ device test | `android/app/build/outputs/apk/debug/app-debug.apk` (debug-signed, verified with aapt/apksigner); no emulator available in the build environment |
| M3 deep management | 🔶 | see Partially implemented |
| M4 long careers & polish | 🔶 | 10-season soak; device profiling pending |

## Complete & playable

* **Seven-title catalogue** — S220 MLBB, S221 Wild Rift, S222 Honor of Kings, S223 League of Legends,
  S224 Dota 2 (MOBA) and S232 Valorant, S234 Counter-Strike 2 (tactical shooters) with the specified inputs
  (3 touchscreen, 4 M&K, 0 controller), 5v5 lineups, title-specific roles, draft rules, map veto, series
  formats, objectives, structures, economies, stats and popularity.
* **All seven titles run full seasons** (regional splits → playoffs → MSI → Worlds → rollover) under their
  own rules — verified in `tests/season.test.ts`.
* **Two engine families** — Engine A (5v5 MOBA, five adapters; Dota 2 buyback; core destruction wins;
  coin-toss then loser-chooses side; fresh draft every game; optional LoL Fearless preset) and Engine B
  (round-based tactical shooter; Valorant agents/abilities/Spike/credits and win-by-two overtime; CS2
  no abilities, money economy, MR12 and 6-round overtime blocks).
* **Saves**: five manual save slots + one autosave per career; Save button (also on phones) / Ctrl+S;
  background autosave; v1→v5 migrations; seven-title conversion of old saves with exportable original.
* **Android APK** (Capacitor 8): offline, system Back, safe areas, autosave on background, save export via
  the Android share sheet — ANDROID_BUILD.md.
* **Competition registration** separate from contracts: locked registration lists (≤ 8), standing plan,
  2 changes per competition in transfer windows, emergency registrations, AI registration of signings,
  reminders and Tactics warnings (`tests/registration.test.ts`).
* **86-attribute model + 3 input proficiencies** with the specified formulas; derived summaries; title / role
  / character / map / weapon proficiencies; CA/PA; personalities; trajectories.
* **World generation**: 20 regions, 95 countries, culture-specific names & handles, multi-title organisations,
  leagues (single & macro regions, challenger tiers), rosters, staff, finances, sponsors, patches.
* **Time**: day/week/month/next event/end of season/1-5-10 seasons; pauses before user match days;
  idempotent day processing; Web Worker for long advances with progress bar.
* **Live matches**: map veto plan, interactive drafts (optional; the coach drafts by default), game-by-game
  play with box scores and commentary; results recorded identically to instant sims.
* **Competitions**: RR/DRR, groups, Swiss, single elim with byes, double elim (finals start 0-0), page
  playoff; qualification by placements and circuit points; prize money; promotion/relegation.
* **Market**: free agents, extensions, releases, loans, trades (policy-gated), academy promotions,
  cross-title signings with title switching, shortlist, transfer history, player search over every
  contracted player (contract end, release clause, listed/asking price, pre-contract status); AI clubs
  recruit by needs/strategy/budget, renew and poach.
* **Transfer negotiations** (football-manager style): bid a fee (+ optional sell-on clause) → the club
  accepts, counters or rejects and loses patience after 3 failed bids (refuses approaches for 30 days);
  release clauses cannot be refused → personal terms with the player (salary, seasons, signing bonus,
  release clause; honest counter-demands, 4 offers, 14-day deadline) → completion pays fee & bonus and
  attaches the sell-on clause, which pays the former club a share of the next fee. Negotiations tab tracks
  every deal with its log.
* **Pre-contracts**: players in the final six months of a contract (no team option) can agree a free move
  for next season with you or AI clubs; restricted free agents sign offer sheets the current club may match
  (inbox Match / Let go; AI matches valued players at sensible salaries).
* **Selling**: asking prices on listed players (AI bids anchor on them), "Offer to clubs" produces bids from
  clubs that would improve, and incoming bids can be accepted, rejected or countered (accepted up to the
  bidder's hidden ceiling, one final offer back, then they walk away).
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
* **National teams**: biennial Nations Cup (odd seasons, December off-season) for the six most popular
  non-BR titles; squads are the best players of each nation in the title (club contracts untouched);
  trophies and legacy points for players; fatigue carries into club play.
* **League policies in play**: rookie drafts (franchised leagues by default; reverse-standings order, two
  rounds, rookie deals with team option), soft-cap luxury tax charged monthly, hard caps, import limits,
  minimum ages, roster limits, trade/loan/fee permissions.
* **Tradable draft picks**: every draft league holds two rounds of picks for this and next season's drafts;
  picks can be traded with players and cash in the trade builder (picks-only deals allowed), are valued by
  projected slot (team-strength rank), round and distance, and the owning club makes the selection. Team
  pages list held picks (own / acquired).
* **Restricted free agency**: in restricted-FA leagues (franchised shooter leagues by default) clubs can
  retain expiring players aged ≤ 24 with a one-season qualifying offer at +10%; AI tenders starters and
  high-upside players, user teams choose via a toggle on the Team page.
* **Saves**: IndexedDB slots (two alternating copies per save = built-in backup), autosave from the simulation
  worker (no UI freeze), unsaved-changes marker + close-tab warning, plain-language storage errors, persistent
  storage request, crash-recovery prompt, export/import JSON, zod validation,
  v1→v2→v3→v4 migrations.
* **UI**: sidebar shell, breadcrumbs, back/forward, global search, sortable/filterable/paginated tables with
  column chooser, profiles for every entity, player comparison, title-adaptive match screens, charts,
  light/dark themes, responsive mobile layout, database editor.

## Partially implemented

* **Dual-title participation**: `Player.dualTitleId` exists and cross-title histories are tracked, but a player
  cannot yet appear on two active rosters simultaneously.
* **Draft picks / transfers**: AI clubs do not yet trade picks among themselves; no transfer instalments,
  add-ons or loan-to-buy options; AI-to-AI deals are resolved instantly rather than negotiated.
* **Career roles & job security**: choose Head Coach & GM, Head Coach, General Manager, Sporting Director,
  Owner or Commissioner. Each role has its own permissions (tactics/live matches, transfers, contracts, staff,
  finance, facilities, expansion); AI staff run whatever your role doesn't (e.g. the AI GM recruits for a Head
  Coach). The board sets per-division season objectives, reviews them at rollover, tracks confidence and can
  dismiss you (not owners/commissioners) — then you pick a new job from interested organisations. A
  **national-team coach** job can be held alongside any club role. **Mid-season**: board reactions after
  every split / trophy / money worry, ultimatums and mid-season dismissal (the advance pauses), a manager
  contract renewed only at confidence ≥ 40, and job approaches from bigger organisations while employed.
  Not yet: negotiating your own contract terms or salary.
* **Scouting assignments**: scouts auto-scout your titles + focus list; no region assignment UI.
* **Affiliate/partner organisations & joint ownership**: `partners` field exists; no gameplay yet.
* **Media/social reactions**: news feed covers major events; no fan-sentiment model.

## Missing / next steps (priority order)

1. **Install and test the APK on a real Android device or emulator** (airplane mode, restart, rotation,
   Back, backgrounding) — not possible in the build environment (no `/dev/kvm`).
2. Organisation treasury with department allocations that sum to cash; staff capacity and philosophy
   alignment.
3. Dual-title rosters with one shared calendar and divided practice time.
4. Training sessions as scarce capacity (staff, rooms, cancellations without phantom growth).
5. Fan segments, rivalries from repeated encounters, richer news.
6. Performance for Standard/Large worlds (seven titles: measure and tune on a phone).
7. Release signing automation (procedure documented in ANDROID_BUILD.md).
