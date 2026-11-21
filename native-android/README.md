# Esports Manager — native Android app

A native rewrite of the game (Kotlin, Jetpack Compose), replacing the web-based APK step by step.
It installs next to the previous app (application id `dev.uem.em`; the web-based app is
`dev.uem.esportsmanager`), so both can be used while the native one catches up.

## Why native

* **Simulation on every CPU core.** Each day's series run in parallel from a frozen view of the
  world and are applied afterwards in fixture order, so results do not depend on which thread ran
  what. The web version simulated one series at a time on the WebView's single JavaScript thread.
* **Compact, fast saves.** The world is saved as binary (protobuf) + gzip on a background thread,
  written to a temporary file and renamed into place, with the previous save kept as a backup.
  Per-game box scores go to weekly page files and load when a match report opens.
* **No web layer.** No WebView, no IndexedDB, no copying the world between threads.

Measured on the 4-core build machine (JVM unit tests, same code as the app):

| | Native | Web-based app (same machine) |
| --- | --- | --- |
| New world (seven games, real data) | 0.25–0.45 s | about 2 s |
| One full season incl. rollover | about 3 s | about 18–24 s |
| Save after a season | 0.6 s, 2.2 MB | 3–5 s, 19+ MB |
| Load | 0.5 s | 1–2 s |

Phones are slower per core but have 6–8 cores, which the native app uses.

## Features (native)

* **World**: real leagues, teams and players (real birthdays, nationalities, positions), fictional
  leagues where no real data exists, dormant real teams and their former players as free agents,
  prospects; pictures for players, teams, leagues and characters.
* **Season**: three splits per league, First Stand (hard Fearless in MOBAs), MSI (double
  elimination), Worlds by circuit points; Nations Cup with national teams every other December.
* **Matches**: fast MOBA and shooter engines on all CPU cores; drafts with bans and Fearless,
  side / pick-order choice, map veto, MVPs; **play your matches live** (your draft or veto, every
  game on a 2D map with kills, fights, objectives, towers, or round by round for shooters).
* **Management**: lineups and tactics, training focus and intensity, staff (hire / release),
  contracts and renewals, transfer market (windows, free agents, bids then personal terms,
  transfer list, AI clubs buying and selling, AI bids for your players), finances (sponsors,
  media, merchandise, salaries, facilities, prize money), board objectives and confidence,
  dismissal and job offers, scouting (potential is a range until scouted), injuries and breaks,
  morale and chemistry, monthly development.
* **Game life**: minor / medium / major patches with written notes that change the meta and, after
  Worlds, the game itself; season awards (MVP, Rookie, All-Pro, Worlds MVP); youth intake; season
  preview and review; news and inbox.
* **Screens**: home, events (standings, groups, brackets, matches, player stats with KDA and stat
  groups), squad, market, team, player, match report, inbox, finances, board, history & awards,
  patch notes, schedule, stats hub, teams, players, settings (difficulty, autosave, pictures),
  database editor (players and teams), follow teams.

* **Deals and team life**: loans for young players, player-for-player trades with cash,
  pre-contracts from September, roster limits, delegated bids and renewals, rivalries and fans
  that follow results, weekly scrims (sharpness and chemistry against fatigue).

* **Supporters and registration**: fans by segment (core, casual, local, international) that
  drive merchandise; rosters registered when a competition starts, two amendments per competition.
* **Live 2D view on the real maps**: lanes, river, terrain, towers, pits and camps of each MOBA,
  and the real Valorant / CS2 layouts (callouts, sites, spawns). Every player is a circle in the
  team colour with the champion / hero / agent portrait, name, health bar and level; dead players
  wait greyed at the fountain with a respawn timer; pinch to zoom, drag to pan, tap a player on the
  live scoreboard (level, K/D/A, gold, CS, what they are doing) to follow them.
* **Detailed screens** (as in the web version): player page in nine tabs (overview, all 86
  attributes, proficiencies with cross-title projection, career, statistics, contract, development
  chart, transfer history, honours and legacy); team page (sortable roster, schedule, statistics,
  staff, history, organisation, registration); match report (preview, draft, full box score, team
  comparison, series totals); competitions (series directory, standings with form, brackets,
  matches by day, player stats, prizes); stats hub (leaderboards, records, head-to-head, season
  preview and review); squad (lineup, tactics with character pools and ban priorities, training,
  staff, contracts); market (free agents, transfer list, shortlist, search, offers, history);
  finances (breakdowns, balance chart, 12-month forecast, ledger); history (champions, GOAT
  rankings, Hall of Fame, dynasties, retired); world (games, national scenes, rankings,
  organisations); player comparison; schedule calendar; inbox filters; board objectives.

* **Your job and your staff**: start as Head Coach & GM, Head Coach, General Manager, Sporting
  Director, Owner (no board) or Commissioner (sandbox); the staff run whatever your job does not
  cover. Delegate any area you do control (lineups, training plan, signings, offers for your
  players, renewals, staff hiring, scouting list, facility upgrades) from Settings.
* **Real or fictional world**: a new career starts from the real snapshot or a fully generated
  world (leagues, organisations, teams, players, coaches, crests and faces all generated).
* **Academies**: every LCK and MPL ID / PH team has an academy in LCK Challengers League / MDL
  (rosters generated, labelled), top teams of other leagues with a Challengers division field one,
  AI organisations found more over time, and you can found one (cost depends on the region).
  Academies sign the best of the region's youth intake every year, develop players faster, and AI
  clubs promote from them before signing anyone; move players up or down from their page.
* **Simulate 2, 5 or 10 seasons** from the Continue menu: the staff run everything meanwhile and
  the game is saved after every season (stop at any time).
* **Tier lists and character / weapon sheets**: S–D tiers per role (MOBAs), per role / map /
  weapon type (Valorant), weapons and loadouts per role (CS2); tap any champion, hero, agent or
  weapon icon for base stats and skills. Patches can release new fictional characters and weapons
  with generated art; Fearless rules (none / soft / hard / insane) per game and event, changed by
  major patches.

* **Positional 2D simulation (Nov 2026 upgrade)**: MOBA games are simulated on the real maps with
  every hero's own abilities (sourced kits for 723 of 724 characters), minion waves, structures with
  health, jungle camps, the title's objectives, vision and wards; the game ends when a core falls.
  Valorant and CS2 rounds are played on the map graphs with line of sight, weapons, utility, plants
  and defuses. Replays store positions, casts and utility; the viewer plays them at 1×–32× with
  event jumps, follow, auto camera and team vision views. Match reports add gold-lead charts, key
  moments, teamfights, heatmaps and round analysis that open the replay at any moment. See
  `docs/UPGRADE_REPORT.md`.
* **Custom competitions**: create league / groups / single or double elimination / Swiss events for
  any of the seven games, invite teams or take qualifiers, set match lengths, dates and prizes,
  validate and preview, publish one-off or every season.

Not ported: department budgets (facilities cover their effect) and the web version's full
movement-by-movement spatial engines (the native view replays the fast engines' events on the
real maps).

## Build

```
cd native-android
ANDROID_HOME=/opt/android-sdk ./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
ANDROID_HOME=/opt/android-sdk ./gradlew testDebugUnitTest    # engine, season, save and screen tests (JVM)
```

The real-data snapshot is shared with the web build: `src/data/real/` is this app's asset folder.
Countries, regions and name pools are exported from the web sources with
`scripts/native-export.ts` into `app/src/main/assets/gamedata.json`.

`ScreenshotTest` renders the real screens on the JVM (Robolectric, no device) into
`app/build/screens/*.png` for review.

## Layout

| Package | Contents |
| --- | --- |
| `core` | seeded RNG (sfc32 / cyrb128, as in the web version), calendar, helpers |
| `model` | the 86 attributes; the saved world (players, teams, orgs, leagues, competitions, fixtures) |
| `titles` | the seven games: roles, phase weightings, drafts, objectives, economies, stats |
| `data` | data loading; countries, regions, names |
| `gen` | world and player generation from the real-data snapshot |
| `comp` | season calendar, stages (round robins, groups, Swiss, single / double elimination), progress |
| `sim` | ratings, team contexts, drafts, the MOBA and shooter engines, series simulation |
| `game` | the day loop (parallel series), development, rollover, box-score pages |
| `persist` | saves |
| `ui` | Compose screens |
