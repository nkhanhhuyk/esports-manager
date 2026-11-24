# Five selected expansions — checkpoint report

Order followed: audit → A (qualification & league ecosystems) → E (amateur, collegiate and
specialised circuits) → B (mentorship & development pathway) → C (Prepare for Match & strategy
laboratory) → D (broadcast-style 2D presentation) → integration, soak, migration, APK.

Out of scope and untouched in this phase, as the specification says: social media and the database
updater (no Liquipedia access, scraping, roster refresh, importer, image downloader or data repairs).
The game stays limited to its seven titles.

Nothing here was tested on a phone or emulator: verification is the JVM unit/integration test suite
and a debug APK build.

## Save compatibility

Every new field is appended with a default (ProtoBuf, `SaveStore.FORMAT_VERSION` unchanged), so older
careers load as they are. `OldSaveFixtureTest` / `MigrationTest` load a real 2027 career saved before
this phase (4.3 MB, 1,836 played series), check its history, advance it 40 days and save/reload it.
New world lists: `invites`, `mentorships`, `devPlans`, `preps`; new competition fields: `defKey`,
`rules`, `entries` (ledger), `circuit`, `elig`; team `kind`; player `enrolled`, `circuits`.

## Checkpoint 1 — Feature A: qualification and league ecosystems

- An edition (`Competition`) records its definition key (`defKey`, stable across seasons) and the
  ruleset version it was planned with (`rules`). Editions keep their ruleset when later seasons use
  another one.
- Qualification ledger (`Competition.entries`, `QualEntry`): every entrant with its route, source
  event and place, how it got in, a note (place passed down, replacement), status
  (`in` / `declined` / `withdrawn` / `promoted`) and day. Built once when the edition starts
  (`Progress.entrants` at `startDue`); repeating the start-up is a no-op.
- One entry per team even when two routes would qualify it; the place then passes to the next team
  of the later route. Withdrawn (inactive) teams pass their place on.
- Invitations (`Invite`): issued 24 days before an event to the best-ranked teams of the route;
  five days to answer; a declined or expired invitation goes to the next team. The user answers in
  the inbox / Road screen unless *Competition invitations* is delegated. AI teams answer by the same
  rule (fixture clashes, prestige).
- Versioned rulesets (`comp/Ecosystem.kt`, `Rulesets`), each labelled with what is official:

| Ruleset | Seasons | Status |
|---|---|---|
| `lol-2026` | 2026–2027 | Official in part: LoL Esports 2026 handbook (First Stand, MSI and Worlds routes); slot counts and split formats are the game's |
| `lol-2026-carried` | later | Fictional continuation of the 2026 rules |
| `vct-2026` | 2026 | Fictional preset modelled on the 2026 public structure |
| `vct-2027` | 2027–2028 | Official announcement (Riot, 8 Apr 2026): open qualifiers, Kickoff and two Cups per territory, Masters Seoul, Masters Valencia, Champions Austin; Masters slot counts are the game's |
| `vct-2027-evolved` | later | Fictional continuation (generic Masters names) |
| `cs2-vrs` | all | Method from Valve's published Regional Standings (standings invitations + open qualifiers); the formula and slot counts are the game's approximation |
| `dota2-ti-preset`, `mlbb-preset`, `hok-preset`, `wr-preset` | all | Fictional presets modelled on public structures; no official rulebook used |

  Sources: https://lolesports.com/en-SG/season/115547545029543948/handbook ·
  https://valorantesports.com/en-US/news/the-new-valorant-champions-tour ·
  https://github.com/ValveSoftware/counter-strike_regional_standings
- UI: *Road to the next events* (Home card and Team page) — ruleset card, open invitations with
  accept / decline, this season's routes with clinched / eliminated / what is needed, and why the team
  was in or out in earlier seasons. Each competition's *Entrants* tab shows its rules in words, the
  ledger and where its places lead.
- Tests: `QualificationTest` — rulesets per season, VCT 2027 structure ≠ 2026, LoL/CS2/Dota routes,
  user invitation declined and replaced, withdrawn winner's place passed down, idempotent start-up,
  three full cycles to 2030 with ledgers checked, no team booked twice on a day, reproducible slots.

## Checkpoint 2 — Feature E: amateur, collegiate and specialised circuits

- Development clubs (`Team.kind` = `amateur`, `collegiate`, `gc`): 4–6 amateur clubs per tier-1
  league region, university teams (LoL, VALORANT, CS2 in the Americas / EMEA) and VALORANT Game
  Changers clubs outside China. All fictional and labelled so.
- A roster place there is a registration, not a contract (`player.teamId` stays −1): professional
  teams sign these players normally and they leave the club the next day.
- Events: four Community Cups and an Amateur Championship (by Community Cup points) per region,
  University League, Game Changers events and a Game Changers Championship. Eligibility per event
  (`elig`: amateur status, enrolment, age, Game Changers circuit) is checked at registration and in
  lineups.
- Linked to the professional graph: each region's Amateur Championship winner joins the independent
  lower league (contracts signed, ledger status `promoted`) and that league's lowest independent team
  drops to the amateur circuit; open qualifiers accept amateur clubs where the ruleset has them.
- Same people throughout: the same player ids move between circuit, academy and professional teams,
  with career entries; no player plays two series on one day (club, national team and development
  club fixtures are ordered and busy players are replaced by free roster players).
- Scouting evidence: circuit results per player (games, events, average rating, opposition Elo,
  confidence) on the player page and the circuits screen — not hidden ratings.
- UI: More → *Amateur & development circuits* (per game and region: events, clubs with points,
  who moves up, your eligibility, best prospects).
- Tests: `CircuitTest` — club counts, idempotent setup, unsigned and unique rosters, collegiate
  enrolment, Game Changers flags, a signed amateur leaves the club, a full season of circuit events,
  eligible registrations, a promotion into a league, no player in two series on a day.

## Checkpoint 3 — Feature B: mentorship and development pathway

- Mentorships (`Mentorship`): a player or staff mentor with limited capacity (players 1–3, staff
  2–4 by quality), teaching ability (communication, leadership, professionalism, patience; staff
  quality) — not the same as being a star — and title / role knowledge where the goal needs it.
- Weekly sessions only when both are available (injuries, visas, fatigue, capacity); missed sessions
  are recorded; clashes cool the relationship. Gains go only to the goal's attributes, scale with
  teaching, the mentee's coachability and the relationship, and are capped (4 points per attribute).
- Reviews every 16 weeks (inbox for the user); AI organisations end relationships that reached the
  goal or broke down.
- Pathway stage, development plan (`DevPlan`), succession plan by position, opportunity gaps (ready
  players not playing), trials of unsigned players; AI succession at season start promotes some
  academy prospects (not all).
- UI: player page *Development & mentoring* card; team page *Development* tab.
- Tests: `MentorTest` — capacity, no gain while injured, capped gains, title-specific staff
  refusal, one person through promotion, role change keeps attributes, save/load, two seasons of AI
  mentoring and promotions (87 promotions of 434 academy players in the last run).

## Checkpoint 4 — Feature C: Prepare for Match and the strategy laboratory

- Prepare for Match (Home match-day card and Match screen): opponent report from recorded matches
  only, each line labelled *observed*, *assumed* or *estimated*, with sample size, age of the newest
  evidence and a confidence; lineup readiness (fatigue, form, injury, role knowledge, comfort pool);
  the staff's recommendation and why.
- Plan: emphasis (balanced, counter the opponent, comfort picks, map focus, rest), composition
  identity for MOBAs, the map for shooters. The plan is locked when the series starts (live or
  simulated) and later edits are refused; only the share the analysts and coaches can deliver that
  week counts; study costs a little freshness, rest restores it; the workload is charged once.
- What it changes in the sim: counter study improves the draft read of the opponent and removes the
  opponent's best map in the veto; comfort makes the starters' best characters and scrim learning
  count more; a composition identity steers the draft and its evaluation; map focus keeps the map in
  the veto. AI coaches (and delegated user teams) prepare their own matches by the same rules.
- Strategy laboratory: replays the upcoming series many times per plan on the real engines with
  the same seeds per plan, in the background with progress and Stop; reports wins and an 80% range
  and says when ranges overlap. Sandbox only — throwaway fixtures, nothing recorded, paid or applied.
- Match report: each side's locked preparation.
- Tests: `PrepTest` — no hidden ratings in the report, plans change the draft inputs, the laboratory
  is seeded and leaves the world unchanged (fingerprint), cancellation, effects bounded, locking and
  one-time workload, AI coach plans (2,809 in the run, three emphases), save/load.

## Checkpoint 5 — Feature D: broadcast-style 2D presentation

- Derived only from each game's recorded events (`sim/Broadcast.kt`), the same stream the 2D viewer,
  box score and commentary use: kill feed with each game's announcer tags (MLBB Maniac / Savage,
  Dota Ultra Kill / Rampage, LoL-style Quadra / Penta, First Blood, Shutdown), key moments with
  priorities (first blood, multi-kills, team wipes / Ace, objectives and steals, first tower, base,
  teamfights, the core), objective timers from the recorded spawns, and a director that cuts the
  camera to moments with a cooldown between cuts (sooner only for a clearly bigger moment).
- MOBA viewer (MLBB first, the same for LoL, Wild Rift, HoK, Dota 2): series bar (event, game
  number, series score before the game, series point / decider), title slate (event, stage, teams,
  game, map, patch), kill feed, timers, moment banner, player card (KDA, gold, level now), key-moment
  bookmarks that seek the replay; toggles for the overlay and the director.
- Shooter viewer (VALORANT, then CS2): round HUD with sides, buys, bank and loadout value per team,
  players alive, buy phase, spike / bomb timer from the plant (45 s / 40 s), match point; kill feed
  with weapons and 3K / ACE / opening tags; moments (multi-kills, aces, clutches, plants, defuses,
  eco wins, match points); director camera; bookmarks.
- Tests: `BroadcastTest` — for every title, kill feed totals equal the box-score kills, first blood
  is the first kill, the core moment belongs to the winner, timers match the recorded spawns, the
  director respects its cooldown, shooter HUD ends on the recorded score, everyone is alive at each
  round start, the bomb timer counts from the plant, reading the presentation twice gives the same.

## Checkpoint 6 — integration

- Determinism fix found by the reproducibility test: the draft's preference cache was global and keyed
  only by patch and player while its value depends on the player's current character comfort; when
  the shared cache was cleared (its size counted every world in the process) drafts were recomputed
  at different moments, so a second world with the same seed could differ. The key now holds what the
  value depends on (comfort fingerprint, character roster), so clearing, saving/loading or another
  world never changes a draft.
- Lists that grow with time are bounded: answered invitations of old events, ended mentorships (a
  year; the user's three), mentor logs (40 entries), preparation plans (other teams' two weeks, the
  user's six months).
- `SoakTest` (UEM_SOAK=20): twenty seasons of a managed career on autopilot, saved and reloaded every
  five, with every season's editions finished, one ledger entry per entrant, no contract on two
  rosters, no player in two series on a day, circuits running and the new lists bounded. Results are in
  the reply for this phase and `build/soak.txt` when run.

### Results of the last full run (24 Nov 2026)

- `./gradlew assembleDebug testDebugUnitTest` with `UEM_SOAK=20`: 73 tests, 72 passed and 1 failed
  (MgmtTest: an amateur club one player short on the day a player left it for a professional team).
  Fixed — clubs refill the next day; MgmtTest checks professional rosters — and MgmtTest, MigrationTest
  and SaveTest re-run and pass.
- 20-season soak (2027–2046, saved and reloaded every five seasons, managed career on autopilot): every
  season's editions finished, one ledger entry per entrant, no contract on two rosters, no player in two
  series on a day, 154 circuit events a season, new lists bounded (preparation plans ≤ 2,829, ended
  mentorships pruned, invitations ≤ 578). Per season on the test machine: 43 s in 2027 rising to 83 s
  in 2046; save 9.0 MB → 51 MB; heap 0.2–1.0 GB.
- Honor of Kings: winners still die about as often as losers, so KDA follows results only weakly there
  (`KdaTest` reports it; not asserted).
