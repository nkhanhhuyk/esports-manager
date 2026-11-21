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

## Stages

1. **Stage 1 (this build)** — real-data world generation (leagues, teams, players with real
   birthdays / nationalities / positions, dormant teams, free agents), the full season calendar
   (three splits, First Stand with hard Fearless in MOBAs, MSI double elimination, Worlds by circuit
   points), the fast MOBA and shooter engines with drafts, side / pick-order choice and map veto,
   standings, brackets, per-competition player stats (KDA and stat groups), match reports with the
   blue side on top and character icons, team and player pages, pictures, monthly development,
   season rollover with contracts and roster upkeep, saves and weekly autosave.
2. **Stage 2** — transfers and negotiations, contracts, finances, staff, training, scouting, board.
3. **Stage 3** — the live 2D match viewer, playing your matches live (lineups, drafts, veto),
   patches with detailed notes, history and records, awards.

The previous app keeps the stage 2–3 features until they are ported.

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
