# Audit: competition-evolution, academy and regional/UX addenda (26 Nov 2026)

Status per requirement against the code as it stands. **Done** = implemented and covered by a test;
**partial** = the core exists, something listed is missing; **missing** = not built yet. "Phase" is the
implementation order (1 = navigation and archives, 2 = connected competition views, 3 = evolution,
4 = living world).

## Academy / LCQ / Best of the Rest / coefficients / play-ins / reforms addendum

| Requirement | Status | Where |
|---|---|---|
| Academy leagues for MOBA top leagues (fixtures, standings, stats, playoffs) | done | `mgmt/Academies.ensureLeagues`, `comp/Competitions.kt` (tier-2 splits), `AcademiesTest` |
| Academy players: one person, call-ups and promotions, development from academy games | done | `mgmt/Academies.kt` (`callUp`, `sendBack`, `keep`), `sim/Subs.kt`, `SubsTest` |
| Academy awards (MVP, rookie, academy coach) | missing | phase 2 |
| Academy team never in its parent's league or promotion | done | `Competitions.moveTeam`, `promo` rule, `AcademiesTest` |
| Last-chance qualifier: 2 direct + next 6 on points, two groups of three, semis, final | done | `Season.regionalFinals`, `lcq` rule, `FormatsTest` |
| LCQ one-berth / two-berth modes | done | `Progress.leagueOrder` (places beyond the direct ones) |
| Best of the Rest (DCGI-style), 2–4 per league, disjoint from Worlds | done | `Season.invitational`, `rest` rule, `FormatsTest.formatsPlayThrough` |
| Region coefficients over recent internationals; seed moves of one at a time | done | `Formats.performance`, `Formats.change("seeds")` |
| Separate qualifying places from stage placement (main vs play-in seeds) | done | `Formats.mainSeeds`, `planEvent` |
| Play-ins: 2, 4, 8, 16 teams, knockout; optional, added/removed/reshaped | done | `Formats.playInStages`, `FormatsTest` |
| Play-in of 6 teams (two groups of three) | partial | stage builder handles 6 via groups; not a named preset |
| Reforms only at major patches, cooldown, no mid-season change | done | `Formats.evolve` (≥2 seasons, 50%), applies from next season |
| Reform reasons, advance notice vs next season, statuses | partial | reasons done; advance notice and statuses: phase 3 |
| Social reactions with one canonical ID per change | partial | posts keyed by change (season, day, index); explicit IDs: phase 3 |
| Pro/rel ↔ franchise | done | `Formats.change("prorel")`, `Formats.promotionLowers` |

## Regional evolution, archives and UX addendum (latest: Play-Ins separate)

| § | Requirement | Status | Where / phase |
|---|---|---|---|
| 1 | Region expansion, merger, split, recognition, contraction | missing | phase 3 |
| 2 | Continental Academy Champions League | missing | phase 2 |
| 3 | Promotion/relegation playoffs (survival series, cross-seeded) | partial | a promotion round robin exists (`Competitions.kt`); playoff formats: phase 2 |
| 4 | Qualification Race dashboard | partial | Road screen per team (`EcosystemUi.RoadScreen`); per-competition race: phase 2 |
| 5 | Hosting bids and venues | missing | phase 4 |
| 6 | Reform impact preview (read-only) | missing | phase 3 |
| 7 | Evolving tournament prestige | missing | `Competition.prestige` is fixed; phase 4 |
| 8 | Regional rivalry cups | missing | phase 4 |
| 9 | Multi-title organisation rankings | partial | EWC Club Championship, world rankings (`RankingsScreen`); phase 4 |
| 10 | Reform timing (advance notice / next season) and archive timeline | partial | log + Road screen card; phase 3 |
| 11A | Patch notes and metas by year, patch dropdown, stored meta snapshot per patch | **done (phase 1)** | `PatchesScreen`, `PatchState.metaAt/patchAt/usage`, `PatchRec.roleMetas/usage` |
| 11B | Tier lists with Year and Patch dropdowns | **done (phase 1)** | `TierScreen`, `TierList.View` |
| 12 | Solo queue Year + Week dropdowns, stored weekly snapshots | **done (phase 1)** | `SoloQueue.snapshot`, `World.soloWeeks`, `SoloQueueScreen` |
| 13 | League stage by matchweek, groups by group + matchday, playoffs by round | **done (phase 1)** | `ui/FixtureFilters.kt`, `CompScreen.stageMatches` |
| 13A | Swiss by pre-match record (0-0 … 2-2), cross-record pairings apart | **done (phase 1)** | `FixtureFilters.swiss`, `ArchiveNavTest` |
| 13B | Play-Ins as their own stage, never inside Swiss/groups/playoffs | **done (phase 1)** | `CompScreen` (an event's play-in competition shown as its "Play-Ins" stage) |
| 14 | App-wide selector audit | partial | see below |

### Notes on phase 1

* **Historical metas are reproduced, not overwritten.** A past patch's balance is recomputed from the
  stored patch records (`PatchState.metaAt` replays the first *k* patches exactly as the engines did). What
  was picked, banned and won on a patch is archived into that patch when the next one is released; role
  metas are stored with each patch. Saves from before this version have no per-patch picks or role metas
  for their old patches, and the screens say so.
* **Solo queue weeks.** Week 1 is the season's first seven days. A season has 52 Mondays, or 53 in some
  years; the 53rd is shown as Week 53 rather than merged, so no week's ladder is lost. Each week keeps each
  game's 60 best and every server's top 10; seasons before the last keep their top 30 (save size).
* **Swiss records** are rebuilt from the earlier rounds' results (a round is drawn only after the previous
  one is complete), so a finished match never changes group.

### §14 selector audit

| Screen | Before | Decision |
|---|---|---|
| Competition Matches tab | one list per stage | filters per stage type (phase 1) |
| Patch notes | every patch on one page | Year + Patch (phase 1) |
| Tier lists | current patch only | Year + Patch (phase 1) |
| Solo queue | live ladder only | Year + Week (phase 1) |
| Competitions directory | Season / All tabs + edition select | unchanged: the season tabs and edition dropdown already do the job |
| Team match history, transfers, player career | lists by date | phase 2: season filter |
| Social feed | game and team filters | unchanged: the feed covers the last two weeks only |
