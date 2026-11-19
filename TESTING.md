# Testing

## Suites (`npm test`, Vitest, node environment)

| File | Covers |
| --- | --- |
| `tests/model.test.ts` | 86 attributes & group sizes; input formula; derived summaries; player generation for all 30 titles (valid ranges, proficiencies, CA calibration, required input); unique handles; E weights; input penalty; catalogue inputs, families, formats (spec tables), lineups, engine configs, unique objective sets; similarity guideline bands; cross-title switch keeps attributes and transfers familiarity by similarity |
| `tests/competition.test.ts` | Round-robin pairings & daily conflicts; double RR scheduling; bracket seeding; single elim with byes; double elim (loss counts, no bracket reset); Swiss (no rematches, 8 qualifiers/8 eliminated); BR points aggregation & unique placements; slot allocation |
| `tests/engines.test.ts` | Every two-team title: series completes with correct win counts, finite stats & ratings, kills = opposing deaths, tactical round/KAST consistency, legal drafts, fearless (global/team) enforcement, commentary present; every BR title: full-lobby placements and kill totals; AI drafts legal for all character titles; illegal draft actions rejected; lineup validation; competitive balance (stronger team wins >60%, underdogs sometimes win) for 7 engines |
| `tests/systems.test.ts` | Rookie draft order & signings; luxury tax; Nations Cup squads (nationality, club contracts untouched); contract validation (salary, length, min age, hard cap), salary ordering by ability; ledger & once-per-month finance; facility upgrades; free-agent signing within roster limits & release; AI refills short rosters & respects limits; transfer window rules; training develops CA and input proficiency; patches shift meta and reduce adaptation |
| `tests/time.test.ts` | Idempotent days/months (no duplicate fixtures, money, awards); one long advance == many 1-day advances; stop-before-user-match and resume; save → reload → advance determinism; full-detail viewing does not change results |
| `tests/persistence.test.ts` | IndexedDB round-trip (fake-indexeddb); backup recovery from a corrupted main save; delete; JSON export/import; v1→v2 migration then continued simulation; rejects malformed/future/corrupt saves |
| `tests/season.test.ts` | **All 30 titles complete a valid season** (champions incl. Worlds per title, all fixtures played, POY per title, world invariants); 3-season multi-title world invariants (no double rosters, roster limits, finite values, league membership, retirements, transfers, development, pruning, patches) |
| `tests/longterm.test.ts` | 10 seasons (default) / 30 seasons (`LONG_SIM=1`): bounded population, finite finances, bounded save growth, multiple world champions per title, legends & retirees |

End-to-end (`npm run e2e`, Playwright, Chromium): new career → organisation pick → players directory,
profile tabs, world popularity map, global search → advance to match day → play a full live series (or BR
event) → match report with commentary → advance a week → save → reload → load save restores the date;
asserts no page errors. Mobile viewport: no horizontal overflow.

## Latest results

* Vitest: **85 passed / 85** (≈45s total; season test ≈18s for all 30 titles).
* Playwright: **2 passed / 2**.
* Long-term: 10 seasons × 3 titles in ~13s; save 3.4MB → 5.5MB.

## Simulation validation tools

* `scripts/calibrate.ts` — per-engine win rate by strength-gap bucket and average game length.
* `scripts/economy.ts` — organisation cash-flow distribution and debt count after a season.
* `scripts/dynasty.ts` — world champions and top-team strength across 8 seasons.
* `scripts/bench.ts` — generation and season timings (`TITLES`, `SIZE` env vars).

## Known issues / limitations

* Full 30-title worlds: Tiny ≈12s, Small ≈26s, Standard ≈50s per season (Web Worker + progress bar; see
  PROGRESS.md for the optimisation plan).
* Tactical shooters are somewhat more deterministic than MOBAs for large strength gaps (many rounds compound).
* Top-team strength drifts upward slowly in long saves (equilibrium higher than the generated start level);
  turnover still occurs via aging, poaching and patches.
* Calibration sample sizes are small (6 games per pairing); use the scripts for deeper runs when tuning.
