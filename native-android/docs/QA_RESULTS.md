# QA results: competition integrity, shooter maps, Android stability (27 Nov 2026)

Everything below was run on a JVM (Linux, 4 cores, 15 GB). No phone or emulator was available, so nothing here
was measured on a device. Commands are run from `native-android/`; long runs are opt-in environment switches.

## 1. Competition ecosystem

### What runs

* `EcosystemHarnessTest.oneSeasonAllGames` (in the suite): one season of all seven games, fast simulation, seed
  `qa-regress`, with a save and reload inside a running international.
* `EcosystemHarnessTest.determinism` (in the suite): the same seed twice, 40 days of all seven games, identical state.
* `EcosystemHarnessTest.manySeasons` (opt-in): `UEM_HARNESS=1 UEM_H_SEASONS=25 UEM_H_SEED=qa-1 ./gradlew testDebugUnitTest --tests '*EcosystemHarnessTest*'`.
  Each season: `Invariants.world` monthly; `Invariants.season` with box scores at the season end; finished
  editions hashed and re-checked every later season; formats frozen once running; seats conserved; reloads
  at the start of a season, inside a running international, at the end of a season and after a reform is announced.

What `comp/Invariants.kt` checks, for every competition of the season (leagues, academy leagues, tier-2 leagues,
amateur / collegiate / Game Changers circuits, open qualifiers, promotion series, regional finals, play-ins,
internationals, world championships, the best of the rest, CACL, rivalry cups and all-star showdowns, nations):
series point to their edition and stage, both sides entered the stage, best-of respected and the winner has the
deciding score; standings equal the results; round robins meet exactly once (twice in double round robins);
group games stay in their group; Swiss pairs share their pre-match record (one cross-record pairing per
odd-sized record group), nobody plays after advancing or going out, unavoidable rematches are logged; bracket
nodes agree with their series, nobody loses twice in single elimination or three times in double elimination,
no node left unresolved; a finished edition has every series played, distinct placings and one trophy; the
ledger equals the field, every placing-based place matches the source's final placing, no berth twice;
last-chance qualifiers hold nobody with a direct place; the best of the rest holds nobody at the world
championship; academies stay out of senior internationals and their parent's promotion series; no team and no
athlete plays two series on a day (from the box scores); rosters, development rosters, leagues, organisations,
transfers and awards have no duplicates.

### Problems found and fixed

| Found by | Problem | Fix |
|---|---|---|
| probe (season 1) | Swiss draws repeated a pairing when a draw without one existed (The International, the Demacia Cup) | the draw searches for a rematch-free pairing before accepting a rematch (`Stages.noRematch`) |
| harness (season 1, seed `qa-regress`) | a lower league lost a seat: LCP promotion sent SHKP up from the PCS and the relegated clubs home to VCS and the LJL; nothing refilled the PCS (11 → 10, and it would keep shrinking) | lower leagues are brought back to their size from the amateur circuit of their own region, or trim their last clubs to it (`Circuits.rebalance`) |
| same | the LCP region's amateur champion was always promoted into the LJL (the first lower league listed) | promoted into its own region's lower league |
| the 20-season run of the previous phase | amateur clubs drawn into two championships on one day after a league was recognised | (fixed then; checked every season now) |

### Long runs

All seven games at once, fast simulation, from the November 2026 start. A run is restarted from the
beginning whenever a fix is made (never resumed with older results), so the first round found the problems
and the last round confirms the fixes.

**Round 1** (seeds `qa-1` 25 seasons, `qa-2` and `qa-3` 20 seasons, three JVMs in parallel; `qa-1` was stopped
at 2047 once its failures were understood):

| Found | Seed, season | Cause | Fix |
|---|---|---|---|
| the academy league of the LCP grew from 4 to 5 | `qa-1`, 2027 | by design (a promoted club's second team joins); the seat check also excused leagues that only gained clubs | academy leagues are checked by their own rule (every member's club plays in the league above); the seat check now counts arrivals |
| HoK CNC 11 → 9 clubs | `qa-3`, 2030 | a league expansion took two clubs from the lower league after the seats had been refilled | the leagues an expansion takes clubs from refill from their region's amateur circuit at once |
| retired players on club rosters (10 clubs in `qa-1`) | all seeds, from 2035 | a club promoted to fill a seat signed every player on its amateur roster, including those who had just retired | retired players leave the roster first; a candidate club needs a full line-up of active players |
| "LCP Amateur Championship never finished" | `qa-2` 2043–46, `qa-3` 2042–46 | the seat refills drained the LCP region's amateur circuit to two clubs; the championship put one in each group, a stage with no series never completed (a softlock repeated every season) | amateur circuits top up to their size every season end; a stage with nothing to play completes at once; a stuck event in an existing save moves on when it is loaded |
| "3 pairings across records, only 2 allowed" (Worlds 2043) | `qa-1` | the check was too strict: when record groups are odd, a team passes down through several groups | the check counts the carries (no engine change) |

What round 1 showed besides (seeds `qa-2` / `qa-3`, 20 seasons each):

| Measure | `qa-2` | `qa-3` |
|---|---|---|
| Events per season | 443–448 (amateur 145–150, tier-1 leagues 84–87, academy leagues 54–57, nations 49, tier-2 leagues 27, internationals 21, open qualifiers 21, rivalry / all-star 7–8, world championships 7, collegiate 6, best of the rest 4, CACL 4, LCQs 3–6, Game Changers 3, play-ins 1–5, promotion series 1–2) | same range |
| Series / games per season | about 14,000 / 31,000 | about 14,100 / 31,100 |
| Final world | 32,101 players, 280,997 series, 9,366 events, 87,167 transfers; save 69 MB | 32,100 players, 282,865 series, 9,380 events; save 69 MB |
| Save + reload (JVM) | 3.6 s at 20 MB (2029) … 6.6 s at 45 MB (2038) | 2.8 s … 6.8 s |
| Heap after each season | 295–751 MB (991 MB in the season with the 45 MB reload) | 297–786 MB (941 MB) |
| Time per season (three runs sharing four cores) | 161–238 s | 157–242 s |
| Upsets (the team lower on Elo at the season start wins) by Elo gap < 50 / 50–100 / 100–200 / 200+ | 45 % / 37 % / 28 % / 10 % | 45 % / 36 % / 28 % / 9 % |
| Different world champions in 20 seasons (per game) | 5 to 10 (most by one club: 10) | 7 to 10 (most: 12, MLBB) |
| World championship places by league | move with results and reforms: e.g. LoL LCK 5 → 3, CBLOL 2 → 5, LCP 2 → 1; VALORANT stays 4 per league (fixed by design) | LoL LPL 5 → 6, LEC 2 → 5, LCP 2 → 4 |
| Richest organisation | 14.5 M → 575 M | 13.0 M → 363 M (Team Liquid, four games) |
| Median organisation | 1.0 M → 1.5 M | 1.0 M → 1.5 M |
| Organisations below zero | none in any season | none |

**Flagged, not tuned in this phase** (balance; see known issues):

* **League dynasties.** Over 60 league splits one club won 53 (Wild Rift APNES, `qa-2`), 46 (LEC), 42 (HoK CNC,
  `qa-3`), 40 (Wild Rift APNPL). The dynasty clubs sit 300–550 Elo above their league's average with rosters of
  86–93 current ability (MPL ID's ONIC), so talent concentrates in one club and stays there; it is not money
  (ONIC 37 M, VH 19 M).
* **Money keeps growing at the top.** Successful multi-game organisations accumulate (Team Liquid 363 M after
  20 seasons, about 18 % a year) with nothing to spend it on; the median barely moves.

**Final round** (after all fixes; same seeds): see below.

## 2. Shooter maps

`ShooterMapsTest` (all 14 maps of the pool), `MapTest`, `ShooterBalanceTest`, `ReplayTest`; per-map figures from
`MapStatsProbeTest` (`UEM_MAPSTATS=16`: 16 seeded games per map, the same seeds before and after).

| Map | Attack wins before | after | Plants before | after | Kills / round before | after |
|---|---|---|---|---|---|---|
| valorant:bind | 51 % | 49 % | 62 % | 60 % | 7.11 | 7.04 |
| valorant:abyss | 59 % | 56 % | 45 % | 46 % | 5.54 | 5.55 |
| valorant:lotus | 54 % | 41 % | 41 % | 27 % | 7.85 | 7.69 |
| valorant:sunset | **9 %** | 52 % | 4 % | 49 % | 7.48 | 5.96 |
| valorant:summit | 63 % | 64 % | 52 % | 45 % | 7.33 | 7.15 |
| valorant:corrode | 58 % | 63 % | 33 % | 36 % | 7.19 | 7.15 |
| valorant:haven | 46 % | 43 % | 60 % | 55 % | 6.56 | 6.42 |
| cs2:mirage | 54 % | 46 % | 45 % | 41 % | 6.07 | 5.94 |
| cs2:inferno | 40 % | 46 % | 37 % | 42 % | 5.99 | 5.70 |
| cs2:dust2 | 57 % | 56 % | 43 % | 44 % | 6.34 | 6.31 |
| cs2:anubis | 62 % | 58 % | 70 % | 65 % | 6.17 | 5.96 |
| cs2:ancient | 62 % | 64 % | 42 % | 45 % | 6.42 | 6.20 |
| cs2:nuke | **11 %** | **13 %** | 10 % | 13 % | 6.81 | 6.60 |
| cs2:cache | 42 % | 37 % | 39 % | 35 % | 6.49 | 6.40 |

Changes behind the "after" column: sight on the exact wall raster, floors on Nuke, the Lotus wall, doors and
drop, legal re-routing and spawn exits, Sunset's B Main link, and a retune of the defenders' hold edge
(VALORANT 0.25 → 0.34, CS2 0.55 → 0.68) because the see-through walls had been giving defenders sight lines
the maps do not have: with the walls fixed, VALORANT attackers won 64 % in `ShooterBalanceTest` (its limit
is 60 %); after the retune 55 % (CS2 57 %). Lotus attacks are slower now (doors take time), hence fewer plants.

Map by map:

| Map | Special traversal | Changed | Evidence |
|---|---|---|---|
| Bind | two one-way teleporters | viewer no longer slides markers between teleporters; spawn exit fixed (a player walked through a wall leaving the attackers' spawn) | `fullGamesOnEveryMap` |
| Abyss, Summit, Corrode, Haven | none | no change needed | `graphs`, `sight`, `fullGamesOnEveryMap` |
| Lotus | one-way drop, rotating doors, breakable wall | the wall is closed until broken; doors are operated and block sight while closed; A Drop → A Site is a drop | `lotusMechanisms`, `graphs` |
| Sunset | none | B Lobby → B Main restored (B Main was a dead end) | per-map table; `maps_topology.json` source note |
| Mirage, Inferno, Dust II, Anubis, Ancient, Cache | none | no change needed | `graphs`, `sight`, `fullGamesOnEveryMap` |
| Nuke | levels, stairs, vents | floors block sight (A above B) | `sight` |

All 14 maps: every node reaches every site and both spawns; nothing stands in a wall; no walkable link crosses a
wall; one-way links cannot be used backwards; closed links are avoided and routes come back when they reopen;
every wall cell blocks a line through it; every position sample of full seeded games is on the map and outside
walls; every plant and defuse is inside its site; recording a game does not change its result or box score.

## 3. Saves and runtime

| Test | What it does | Result |
|---|---|---|
| `SaveRecoveryTest.damagedAndInterruptedSaves` | truncated save, flipped bytes, crash between the two renames (only the complete `.tmp` and the backup left), half-written `.tmp`, nothing readable | the backup / the complete `.tmp` / the save opens as appropriate, with an inbox note; an unreadable career gives a clear error and keeps its files |
| `SaveRecoveryTest.failedWriteKeepsThePreviousSave` | the temporary file cannot be written (as with a full disk) | the previous save loads unchanged; the next save works |
| `SaveRecoveryTest.integrityCheckOnLoad` | a fresh save loaded | no integrity problems reported |
| `IntegrityTest`, `SaveTest`, `MigrationTest`, `OldSaveFixtureTest` | reconciliation, export / import checksums, old saves | pass |
| `ReplayTest` | results applied once; a live series interrupted and resumed reaches the same result | pass |
| Harness reloads | save + load at four points of a long career, state digest compared | see the long runs |

Not measured: frame times, jank, start-up time and memory on a phone (no device here). The harness reports
JVM heap after each season and save sizes instead.

## 4. Known issues

| Priority | Issue | Reproduce | Why it is not fixed here |
|---|---|---|---|
| high | CS2 Nuke: attackers win about 13 % of rounds | `UEM_MAPSTATS=16 ./gradlew testDebugUnitTest --tests '*MapStatsProbeTest*'`; `UEM_MAPTRACE=cs2:nuke … '*MapTraceProbeTest*'` shows A-site defenders killing attackers waiting in Hut and Squeaky | In the real map Hut and Squeaky are rooms behind doors; the 2D layout leaves them open to A site, so attackers waiting to execute are shot. Letting attackers wait out of the site's sight fixed Nuke (71 %) but pushed most other maps to 64–84 %; a proper fix needs room geometry for Hut and Squeaky (or a retune of every map), not attempted without reliable geometry |
| high | League dynasties: one club wins up to 53 of 60 league splits over 20 seasons | `UEM_HARNESS=1 UEM_H_SEED=qa-2 …`; "league titles" line of `build/harness-qa-2.txt`; `InspectSaveTest` (`UEM_INSPECT=build/harness-qa-3`) lists dynasty clubs with Elo and roster ability | talent concentrates in the best club (transfers, player ambitions, development); a fix is a transfer / economy retune with its own before / after runs, out of this phase's time |
| medium | Money accumulates at the top (richest organisation 363–575 M after 20 seasons) with no spending sink | same runs, "finances" line | as above: an economy change (reinvestment, wage pressure) needs its own measured tuning |
| medium | One active map pool per game for every season; no historical pools by patch | `titles/Catalogue.kt` (`VAL_POOL`, `CS2_POOL`) | a pool rotation is a new feature (out of scope) |
| medium | No device profiling | — | no phone or emulator in this environment |
| low | The quick model (fast simulation, second-tier leagues) and the positional engine give different results for the same seed by design; quick-simulated series have no 2D replay | — | by design: whichever model played a series, its stored record is what every screen and replay shows |
