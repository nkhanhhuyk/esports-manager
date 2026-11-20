# Testing

## Suites (`npm test`, Vitest, node environment)

| File | Covers |
| --- | --- |
| `tests/model.test.ts` | 86 attributes & group sizes (62+10+14); input formula incl. the spec's M=95 examples (61.75/75.05/88.35/95) and the E example (73.75); derived summaries; player generation for all seven titles; unique handles; E weights; input penalty; **registry gates**: exactly S220/S221/S222/S223/S224/S232/S234, 5 MOBA + 2 shooters, 3 touch + 4 M&K + 0 controller, all 5v5, two engines; formats BO3/BO5/BO7 etc.; no default Fearless (LoL preset only); CS2 no agents/abilities, Valorant agents/Spike; MR12 + overtime configs; Dota-only buyback; title-specific lineups, objectives and Wild Rift pacing ≠ LoL; similarity bands; cross-title switch keeps attributes |
| `tests/competition.test.ts` | Round-robin pairings & daily conflicts; double RR scheduling; bracket seeding; single elim with byes; double elim (loss counts, no bracket reset); Swiss (no rematches, 8 qualifiers/8 eliminated; backtracking pairing avoids greedy rematches; odd-group float); slot allocation |
| `tests/engines.test.ts` | Every title: series stops at the required wins, finite stats & ratings, kills = opposing deaths, MOBA games end on core destruction only, tactical round/KAST consistency, legal drafts, commentary present; **Valorant win-by-two and CS2 6-round overtime-block score arithmetic** over 60 maps each; **MLBB loser-chooses side** (first pick = previous loser); **LoL champions may repeat by default, Fearless only with the preset**; **Dota 2 buybacks** (stats = events = team totals; none in LoL); AI drafts legal; illegal draft actions rejected; lineup validation; competitive balance (stronger team wins >60%) for LoL, MLBB, Dota 2, Valorant, CS2 |
| `tests/systems.test.ts` | Rookie draft order & signings; draft-pick trades (rejected when under-valued, ownership moves, new owner selects, picks pruned/seeded at rollover); restricted-FA qualifying offers (retained, contract runs through next season, user opt-out); luxury tax; Nations Cup squads (nationality, club contracts untouched); national coach job (qualification, named squad, pause before national matches); contract validation (salary, length, min age, hard cap), salary ordering by ability; ledger & once-per-month finance; facility upgrades; free-agent signing within roster limits & release; AI refills short rosters & respects limits; transfer window rules; training develops CA and input proficiency; patches shift meta and reduce adaptation |
| `tests/career.test.ts` | Role permissions; AI general manager recruits for a head-coach user; board objectives, dismissal, job offers & taking a new job; season review at rollover sets new objectives; no board for commissioners; mid-season split reactions (praise/warning), ultimatum then mid-season dismissal (teams handed to AI staff), advance pauses on dismissal, job approaches (accept moves the manager with a new contract & objectives, decline +2, one open at a time), manager contract renewal vs non-renewal, monthly finance warnings |
| `tests/time.test.ts` | Idempotent days/months (no duplicate fixtures, money, awards); one long advance == many 1-day advances; stop-before-user-match and resume; save → reload → advance determinism; full-detail viewing does not change results |
| `tests/persistence.test.ts` | IndexedDB round-trip (fake-indexeddb); backup recovery from a corrupted main save; delete; JSON export/import; v1→v2 migration then continued simulation; v2→v3 migration seeds unique draft picks; rejects malformed/future/corrupt saves; **five manual save slots** (independent copies, active slot, slot 6 rejected, delete); **30-title-edition conversion** (original kept and exportable, removed title's leagues/teams/competitions gone, every person kept with the same ID in an allowed title as a free agent, inbox notice, world keeps simulating, archived labels resolve) and refusal for saves with only removed titles; alternating save slots (no backup rewrite) and serialised overlapping saves; corrupt newest slot recovers the previous one; old single-slot saves load and upgrade; storage-error messages; old detailed records of other clubs slimmed (user matches kept) and proficiencies rounded |
| `tests/season.test.ts` | **All seven titles complete a valid season and nothing else exists in the world** (champions incl. Worlds per title, all fixtures played, POY per title, world invariants); 3-season multi-title world invariants (no double rosters, roster limits, finite values, league membership, retirements, transfers, development, pruning, patches) |
| `tests/transfers.test.ts` | Negotiation flow (lowball rejected → counter → accept → terms counter → signed; fee, bonus, clause and records), club patience & 30-day block, release clause forces acceptance, withdrawal, sell-on payout on the next sale, pre-contract completes at rollover (and blocks extensions/bids), refused for longer contracts, offer sheet matched by the current club, counter-offers vs bidder ceiling, asking price & offer-to-clubs, deterministic outcomes, v3→v4 migration |
| `tests/registration.test.ts` | Registration lock (5–8 rostered players per entrant, unique across teams, inbox notice); standing plan honoured; changes refused outside windows, limited to 2 in windows, duplicates refused, emergency registrations allowed and flagged; unregistered user signing notice; AI auto-registers signings in a window; season simulation: every player who played a competition was registered for it |
| `tests/treasury.test.ts` | Cash, authority and commitments kept apart (fundable formula); raising a budget limited to the unallocated reserve, never below payroll, budget changes move no cash; moving budget nets to zero; audit log; user signing over budget refused; forecast drops salaries after contracts end and sponsors after deals end; the board sets budgets at rollover when the role lacks finance authority |
| `tests/fans.test.ts` | A single ordinary league defeat starts no rivalry; repeated finals build an announced rivalry (H2H, finals, moments) that fades without contact while a minor one is forgotten; a star transfer between rivals adds heat; rivalry raises match pressure but leaves every attribute unchanged; segments react to what they care about (final win → winning fans, academy debut → community, glamour signing → winning up / local down, favourite leaving → local down, moves between rivals sting both clubs); followers vs paying supporters and mood-driven merchandise; player fans arrive and leave with stars; a simulated season keeps rivalries consistent with recorded fixtures and fan totals equal to followers, with audiences recorded; a v5 save gains segments with the same followers and quietly seeded rivalries from its finals |
| `tests/training.test.ts` | Coach timetables respect match days (light mornings), a rest day, the scrim target and staff capacity; a week of rest develops nobody (attributes and title proficiency unchanged) while a full week does; one practice room shared by several divisions cancels the others' blocks; match-day afternoons are matches and heavy morning sessions are cancelled with a reason; no coaching staff → coached sessions run unsupervised; scrims pair real partners of the same title and another organisation, both sides record mirrored results and gain intel; the outside-league policy never scrims a league rival |
| `tests/modes.test.ts` | Spectator mode: no organisation is user-controlled, the former club keeps a full roster through a season, nothing waits for a user decision, followed teams' matches are full detail with match reports and training timetables; stopping the day before a followed match and taking charge again; delegated signings let the GM fill a short roster (not otherwise); delegated registration ignores the manager's plan; roles without a permission always delegate |
| `tests/longterm.test.ts` | 10 seasons (default) / 30 seasons (`LONG_SIM=1`): bounded population, finite finances, bounded save growth, multiple world champions per title, legends & retirees |

End-to-end (`npm run e2e`, Playwright, Chromium): new career → organisation pick → players directory,
profile tabs, world popularity map, global search → advance to match day → play a full live series →
match report with commentary → advance a week → Save opens Save / Load → save to slot 1 → Save again writes
slot 1 → Export downloads `slot1_*.uem.json` → reload → load slot 1 restores the date;
asserts no page errors. Transfer flow: new career → into the window → player search → bid → accept the
club's counter → offer personal terms → signing completed and listed under Negotiations. Mobile viewport: no horizontal overflow. Spectator flow: new world → "Follow every team" → Following page with followed teams, manager-only pages hidden → advance a month → spectator dashboard → a name inside a news item opens its page.

Portable build (`npm run check:portable`): builds `release/index.html`, opens it from disk in Chromium,
advances a month and fails if the worker did not load, the page froze for more than 1.5 s, the autosave did
not survive a reload, or any warning/error was logged.

## Latest results

* Vitest: **131 passed / 131** (20 Nov 2026: + training sessions & scrims, spectator mode, delegation).
* Playwright: **4 passed / 4** (incl. spectator mode and linked names); `npm run check:portable` passes (worker loads from file://, no freezes,
  autosave survives reload); Pixel 7 emulation: no horizontal overflow, slot save works by tap.
* Android: `npm run android:debug-apk` → `android/app/build/outputs/apk/debug/app-debug.apk` built and
  verified with `aapt dump badging` (package `dev.uem.esportsmanager`, min 24 / target 36, INTERNET only),
  bundled `assets/public` with relative paths and no localhost URL, debug signature (`apksigner verify`).
  Not run on a device/emulator: the build machine has no hardware virtualisation (see ANDROID_BUILD.md).
* Long-term: 10 seasons × 3 titles in ~13s; save 3.4MB → 5.5MB.

## Simulation validation tools

* `scripts/calibrate.ts` — per-engine win rate by strength-gap bucket and average game length.
* `scripts/economy.ts` — organisation cash-flow distribution and debt count after a season.
* `scripts/dynasty.ts` — world champions and top-team strength across 8 seasons.
* `scripts/bench.ts` — generation and season timings (`TITLES`, `SIZE` env vars).

## Known issues / limitations

* Season speed was last benchmarked with 30 titles (Small ≈24 s); seven-title worlds are proportionally
  faster but have not been re-benchmarked on a phone.
* Tactical shooters are somewhat more deterministic than MOBAs for large strength gaps (many rounds compound).
* Top-team strength drifts upward slowly in long saves (equilibrium higher than the generated start level);
  turnover still occurs via aging, poaching and patches.
* Calibration sample sizes are small (6 games per pairing); use the scripts for deeper runs when tuning.
