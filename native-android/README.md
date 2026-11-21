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
  and the real Valorant / CS2 layouts (callouts, sites, spawns).

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
