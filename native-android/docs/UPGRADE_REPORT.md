# Upgrade report: deep 2D simulation, analysis, custom tournaments (21 Nov 2026)

Scope: the seven titles only (MLBB, Wild Rift, Honor of Kings, LoL, Dota 2, Valorant, CS2). No roster /
database updater was built and no real team or player data was changed. Findings table before the
work: `docs/UPGRADE_AUDIT.md`.

## What changed and what now works

| Area | Files / classes | Behaviour now |
|---|---|---|
| Ability model | `assets/abilities.json`, `sim/Abilities.kt`, `scripts/abilities_fetch.py`, `scripts/abilities_build.py` | 723 of 724 characters have a sourced kit (HoK Mulan uses a class template). Every ability: slot, type, targeting (self / around self / targeted / line / area / cone / ally / global), range in map units, cooldown by rank, cost, typed effects (damage with type, damage over time, stun, knock-up, knock-back, suppression, charm, taunt, fear, root, silence, slow, blind, heal, shield, dash, stealth, immunity, attack/move speed, damage reduction, summon, reveal, execute, terrain, global, revive), an AI usage tag and a visual-effect id. Kits are kept within a power band per class (outliers from text parsing do not decide games). Valorant agents: ability kinds, charges, cost, ultimate points. |
| MOBA engine | `sim/MobaEngine.kt` (rewritten, same entry point), `sim/MobaMap.kt` | Positional simulation on each title's map in 2-second steps: heroes walk the walkability grid (distance fields around terrain); minion waves spawn, walk, clash and siege; towers, inhibitors / barracks, base towers and the core have hit points and shoot (the Nexus and Ancient do not); jungle camps with respawns; the title's objectives (Turtle/Lord, Drakes/Herald/Grubs/Baron/Elder, Tyrant/Overlord/Shadow/Tempest, Wisdom runes/Roshan/Tormentor) spawn, are fought over, smite/retribution steals, and give their kind of reward (team gold, stacks and soul, push buffs and super minions, Aegis, execute, XP, stat buffs). Heroes cast their own kit under cooldowns and mana; crowd control stops actions or movement; shields, heals, dashes, stealth (broken by attacking) and immunity act on the fight; skillshots can miss (aim vs dodging) and are blocked by walls; vision from heroes, towers, wards and minions (fog of war). Dota 2: buyback, Aegis, BKB, courier shopping. Teams plan from their macro rating (laning, ganks, objectives, group pushes, defending, base sieges with escalation in stalemates). The game ends only when a core is destroyed. Each mechanic reads one phase rating (laning, mechanics/skirmish, teamfight, macro, jungle, vision, clutch, objective, survival, movement, economy), so ratings are not double-counted. |
| MOBA replays | `sim/Replay.kt`, `model.Replay` (fields appended) | Replay v2: every hero's position, health and state every 2 s; minion wave fronts; casts (caster, target, slot, effects, target point); structure health steps; wards and sweeps; recalls; items; objective spawns; teamfights (start, place, kills, duration); kills with assists and respawn times; executions (tower / monster deaths, not team kills). |
| MOBA viewer | `ui/MobaView2.kt`, `ui/LiveMap.kt` | Plays the recorded state: real positions, health bars, levels, dead / respawn timers, CC and recall markers, stealth; ability effects drawn by type (projectiles, areas, beams, dashes, heals/shields, CC rings); minion waves; structure health; objectives alive/taken; wards; movement paths. 1×–32× speed, pause, skip, scrub, previous/next event, next kill / teamfight / objective / structure, follow a player, auto camera on the action, zoom/pan/reset, spectator or either team's (approximate) vision. Old v1 replays keep the previous event view. |
| Shooter rounds | `sim/ShooterRound.kt`, `sim/TacticalEngine.kt` | Rounds are played on the map graph: legal paths (teleporters, one-way drops, vents, stairs, levels), line of sight through the map walls, smokes and walls block sight, flashes/concussions blind, recon and traps reveal, slows, mollies/HE burn, heals. Weapons: fire rate, body/head damage, range, armour penetration (CS2), shields (Valorant). Buys become weapons, armour, kits (Valorant charges / signatures / ultimates) or CS2 grenades and defuse kits. Attack plans (execute, split, default, lurk), defender holds with angle advantage, rotations on information, plants, post-plant positions, retakes, defuses (kit in CS2) in real time. Economy, halves and overtime unchanged. |
| Shooter replays / viewer | `ui/ShooterView2.kt` | Per-second positions, health, blindness, plant/defuse progress, utility areas, kill lines with weapons, bomb; round jumps, kill / utility / plant jumps, speeds, follow. |
| Analysis | `ui/Analysis.kt`, `ui/MatchScreen.kt` | MOBA: gold-lead chart (tap to watch), key moments, laning by lane at the end of laning, teamfights, players' damage / healing / crowd control / casts / wards, heatmaps (positions, deaths, wards, abilities) per team or player, "observed" facts vs "analyst's reading". Shooters: every round (buys, winner, how, opening kill), opening duels per player, trades, attack/defence results, site conversions, observed vs opinion. Every row opens the replay at that moment. Games recorded before positions were stored say so instead of inventing data. |
| Custom competitions | `comp/CustomComps.kt`, `ui/CompEditor.kt`, `model.CustomComp`, `CompTier.CUSTOM` | Create a competition for any of the seven games: league, double league, groups + single or double elimination, single elimination (byes), double elimination, Swiss + single elimination (8 or 16 teams); invited teams, whole leagues or the top N of a finished competition; best-of per stage with title checks (MOBAs 1/3/5/7, shooters 1/3/5); dates; prize pool and entry fees; Fearless rule for MOBAs; LAN/online; one-off or every season. Live validation (team count, format fit, legal match lengths, schedule fits the season, duplicates) with a preview (stages, byes, matches, days, prize pool). Publishing creates an edition played and paid like any competition; recurring events get a new edition each season; cancelling stops future editions; finished editions are never rewritten. Reached from Events (+ Create) and More. |

## Follow-up after the first play-through

| Report | Change |
|---|---|
| The series score showed the result before the game had played out | The live header leaves the current game out of the score until its replay reaches the end (`ui/LiveScreens.kt`). "Sim rest" / "Skip to result" still jump straight to the result. |
| The game "just ends"; the last tower and the heroes around it were not shown | The positional viewer slows to at most 2× for the last 20 s, the auto camera moves to the falling core with the heroes around it, the core explodes, and a banner says who destroyed it (`ui/MobaView2.kt`). Shooters show a round-result banner (defuse / detonation / elimination / time, score) after each round (`ui/ShooterView2.kt`). The screenshots in the report were the old event view, which is only used for games recorded before the upgrade (a series that started in an older version keeps those). |
| Simulate to the World Championships | New option in the ▾ menu next to Continue and in the sidebar: stops the day before your title's (or the earliest) world championship of the season starts (`GameVm.Span.WORLDS`). |
| A sidebar with more options | ☰ in the top bar opens a drawer: simulate (next match, day, week, month, Worlds, season end), my club, matches (match centre, events, schedule, create a competition), world (teams, players, stats, tier lists, patches, history, world), save, settings (`ui/SideMenu.kt`). |
| Teams always banned the same five characters | Each team drafts with a ban philosophy for the series (meta bans / comfort bans / flexible / handshake). A character is "overpowered" only above an absolute meta strength, so a patch can have none, one or two, or seven. Teams target the opponent's comfort picks (weighted by the player's comfort and the coach), leave an overpowered pick open when they pick next and can take it (handshake), and there is noise so drafts vary (`sim/Draft.kt`). Each patch has its own spread of power (`mgmt/Patches.kt`), so some patches are flat and others have outliers. `DraftVarietyTest`: in 60 drafts per title the most-banned character is banned in 28–93 % of drafts depending on the patch (it was 100 % everywhere), 80–140 different characters are banned, and the fifth most banned is under 70 %. |
| Kills per game, snowballing | Less gold and XP per kill (kill bounty is 0.42 × a minute of income, assists share 35 %), catch-up XP for heroes below the average level, a bounty bonus for the team behind on gold, diminishing returns on items (items^0.8), a team "form" that changes each minute, and a per-title caution before fights. The team ahead pushes only when it is clearly ahead, outnumbers, or the game runs long. |

Two engine bugs found while measuring these changes, both fixed:

* **Red side won ~65–70 % in LoL, MLBB and Wild Rift.** Blue's minion waves moved first each step and
  red's then walked up to them, so waves met nearer red's tower every time; red ended laning ~300–950
  gold ahead and took the first tower 68–78 % of the time. Waves now move together and meet where
  both sides' movement takes them (`MobaSim.tickWaves`). The walking grid is also made symmetric (the
  wall drawing was not; structures, camps and objectives already were). New `MobaSideTest` (100
  games per title, near-equal rosters swapping sides) fails if either side wins outside 35–65 %:
  now blue 42–50 % in the four Riot / Moonton / Tencent games and Radiant 58 % in Dota 2 (the real
  game also favours Radiant slightly).
* **Ratings leaked between worlds in one app session.** Player ratings were cached by player id and
  attribute version, not by world, so loading a different save or starting a new game without
  restarting the app could give players the previous world's ratings. The cache is now held on each
  player object (not saved). New `WorldIsolationTest`: the same seed gives the same world after
  another world has been played for a season. Fictional characters released by patches in two worlds
  can share an id, so their ability kits are now cached by id and class.

Balance after these changes (seeded; `MobaBalanceTest` 40 games strongest vs weakest and 20 vs the
3rd strongest; `ShooterBalanceTest` 40 maps):

```
mlbb      17.2 min  kills 27.9  structures  9.8  objectives 3.7  strongest wins 87%  vs peer 45%  laning gold leader wins 93%
wildrift  19.8 min  kills 29.3  structures 12.5  objectives 6.5  strongest wins 72%  vs peer 50%  laning gold leader wins 71%
hok       18.5 min  kills 27.3  structures 10.0  objectives 6.4  strongest wins 95%  vs peer 75%  laning gold leader wins 91%
lol       32.3 min  kills 34.1  structures 17.7  objectives 8.2  strongest wins 72%  vs peer 55%  laning gold leader wins 70%
dota2     38.3 min  kills 41.9  structures 11.9  objectives 9.3  strongest wins 67%  vs peer 45%  laning gold leader wins 67%
valorant  kills/round 7.0  attack wins 43%  plants 38% of rounds  strongest wins 90%
cs2       kills/round 6.4  attack wins 51%  plants 44% of rounds  strongest wins 85%
```

Draft variety (60 drafts per title, one patch each): most-banned character MLBB 74 %, Wild Rift 49 %,
HoK 93 %, LoL 28 %, Dota 2 81 %; 80–140 different characters banned. The LoL patch in this world is
flat (no must-ban), the HoK and Dota 2 patches have one or two outliers.

"Laning gold leader wins" is how often the team ahead on gold at the end of laning wins: in pro games it
is about 70–75 %. Honor of Kings is higher in this test because the strongest and weakest HoK rosters are
far apart.

## Second follow-up: management depth, tools and faster simulation

| Request | What was built |
|---|---|
| Links on every page | Names of teams, organisations, competitions, leagues and players in any text (news, inbox, info rows, descriptions, commentary) open their page (`ui/Links.kt`); lists and headers link directly. New organisation page (all its teams, supporters, trophies). |
| Pictures for fictional characters and guns | Generated portraits by class (helmet, hood, visor, mask, circlet, horned helm; sword, daggers, bow, staff, shield, orb) and generated weapons by category (rifle, SMG, sniper, shotgun, LMG, pistol, knife) with seeded finishes (`ui/GenArt2.kt`). |
| Draft recap | Every game records its ban/pick order (`GameRecord.draftOrder`); the match report shows it step by step. |
| Match report order | Result, then draft and box score, then team comparison and analysis. |
| Unlimited save files | Save (current file), Save as new file (any number; each file has its own box-score folder, hard-linked so copies take no extra space), rename, delete, file details (club, season, date). |
| Solo queue | Ladders for every game and server with each game's ranks (Challenger LP, Mythical Immortal stars, Immortal #, Radiant RR, Premier rating, ...). The ladder reflects individual skill and hours played, not teamwork or macro, so ladder and pro level differ; players have queue habits. Weekly play gives practice (character pool, mechanics) and fatigue; teams choose light / normal / heavy queue practice. Amateurs appear on the ladders for scouting (inbox tips). Player page card and ladder screen (`mgmt/SoloQueue.kt`). |
| Counters | Matchups from kits (lockdown vs mobility, dive vs immobile carries, range vs short range, sustain vs poke, reveal vs stealth, burst vs fragile) and classes, shifting every patch (`sim/Counters.kt`). Character pages show countered by / strong against / works well with. The engine applies them (fight damage edge, lane matchups). |
| Smarter draft AI | Counter-picks (lane opponent first), synergy, kit strength, weighted by the coaching staff's patch knowledge; bans protect the team's comfort picks and leave an overpowered pick open when the team has a comfortable counter; teams have a seasonal ban tendency. |
| Scouting report | On the match preview: the opponent's bans, first picks, bans against them, players' most played and most comfortable characters, side and map records, form, the analysts' read of their ban approach and ideas from the current patch. |
| Tactical timeouts (shooters) | CS2 3 and Valorant 2 per map by default (competitions can change it). AI coaches call them after losing streaks or at map point; in your live maps you call them from the viewer and the map is replayed from the next round (earlier rounds stay identical: each round has its own random stream). A timeout breaks the opponent's momentum and gives a short coached boost. |
| Coach comms (MOBAs) | A major patch can introduce (or withdraw) coaches joining voice during fights: three times per game for 45 seconds. Each competition follows the game's rule, allows or bans it (custom competition editor and commissioner). Coaches use it in big fights; better coaches help more. |
| Commentary | Caster-style lines under both 2D viewers: first blood, streaks, multi-kills by each game's names, shutdowns, teamfights, objectives and steals, towers, coach comms, gold lead every five minutes; buys, opening duels, multi-kills, clutches, plants, defuses, timeouts and round ends. |
| Chemistry | Pairs of teammates: time together, shared language or a language barrier, big egos, loyalty, settled duos; lineup chemistry (pairs and drilled cohesion) affects match play; team page shows the strongest and weakest pairs. |
| Sponsors, merchandise, streaming | Sponsorship offers to accept, decline or negotiate (ask +10 % / +25 %, longer deal; sponsors may meet you halfway or walk away), trophy bonuses; players' streaming (revenue share and fans vs fatigue, some players dislike a ban); merchandise range (sales vs running cost). Commercial work can be delegated. |
| Import limits | Each league allows a number of imports in a lineup (2 by default; none in CS2; the commissioner can change it); imports joining from another region wait 14 days for a work visa. |
| Tuning | Valorant attack 50 % and CS2 47 % of rounds (120 maps); LoL about 30 kills per game. |
| Faster simulation | Settings → Simulation detail → Fast: matches without your or a followed team that keep no replay use a quick statistical model (results, drafts, full box scores; no replay). 60 days of the full seven-game calendar (831 series): 0.9 s instead of 3.5 s (warmed desktop JVM). Full mode also got faster: counter tables built with precomputed class bits and an integer hash instead of per-pair string hashing, cached ban candidates, lane lookups, item scaling, events built only when recorded. Phones are several times slower per core. |
| Database editor | Export / import spreadsheets (.xlsx for Google Sheets / Excel, .csv): players, teams, organisations, leagues, competitions, staff, character pictures; picture links; applied to this save and optionally every new game. See `docs/DATABASE_EDITOR.md` (columns and what data is missing per game). |
| Dota 2 objectives | Wisdom runes and Tormentors now spawn on both sides and Roshan alternates pits (all used only their first pit before, favouring Radiant). |

## Third follow-up: history, real data, logos, offline pictures, world events

| Request | What was built |
|---|---|
| Past rosters | Team page tab: the current roster, then every season's roster at its end (and who left during it). Rosters are kept at each season end from now on; older seasons are rebuilt from who played for the team. |
| Transfer history | Team page tab with a season selector: arrivals (from which club, free agency, academy) and departures (to which club, released, contract ended), with fees and dates. Releases, expiring contracts, free-agent signings, loan returns and academy moves are now recorded too. |
| Follow all | Teams screen: Follow all / Unfollow all for the game shown. |
| Generated logos | Teams without a real logo get a logo with a motif from the name (wolf, dragon, tiger, eagle, fox, bear, snake, crown, star, flame, bolt, ice, ghost, sword, mountain, wave, rocket, skull) or a monogram, in the organisation's colours; competitions get a trophy (world championships), globe (internationals), shield (leagues), banner (custom), and their own emblems for the Esports World Cup and Nations Cup. |
| No transfers in the first season | The market is closed for the whole first season (no window, no AI signings, bids or trades); only a team that cannot field a lineup makes an emergency signing. |
| No generated players on real teams | Real teams keep the snapshot roster; a team listed with fewer than a full lineup is completed with real free agents (same position, same region first). No substitute is generated. |
| Real player ratings | The same in every save: each real player and team has its own random stream (game + name), not the save's. Recalibrated: curated team strengths for every real LoL, Valorant, MLBB and Dota 2 team; CS2 from Valve Regional Standings points; curated current ability and potential for well-known players (`assets/ratings.json`, editable with the database editor). E.g. Chovy 91, Faker 87, ZywOo 94. |
| Spreadsheet: new competitions | A Competitions row with an empty id creates a (recurring) competition, checked like the in-game editor. |
| Pictures offline for good | Pictures are downloaded into the app's own storage (not a cache) and shown from there without internet: Start menu → Settings → Download all pictures (every character, weapon, logo, player photo, all flags and your picture links; a few GB at most), also in a career's Settings. |
| Esports World Cup | After each game's mid-season event: its top four and the four strongest other tier-1 teams, groups then playoffs; the Club Championship totals every organisation's results over all games' events and pays the best clubs. |
| Esports Nations Cup | After each game's World Championship (November): the eight strongest nations' teams. Replaces the old December Nations Cup. |
| All nations | 250 countries and territories (ISO 3166-1) with their English names, flags, regions, languages and name cultures. |
| Fixes | World Championships waited for every unfinished international of the game (the Nations Cup would have blocked them); now only for internationals that start before them. |

## Fourth follow-up: Nations Cup qualifiers and world rankings

| Request | What was built |
|---|---|
| Continental qualifiers | Every game, every season, after First Stand: an Esports Nations Cup qualifier for Asia, Europe, North America, South America, Africa and Oceania. Every inhabited country and territory of the continent enters (243 in all; Antarctica and uninhabited territories have no team). Best-of-one groups Monday to Wednesday (so they never clash with league days), then a best-of-three bracket the next week; the two finalists qualify. |
| National squads | The best player of the nationality for every position (any club or free agency), then the best others off-role, plus a substitute. A nation without enough professionals calls up amateurs, created once with a level that follows the country's scene, and kept: they can be found on the national team page and signed. |
| Nations Cup format | 12 nations (two per continent): two groups of six, the top four of each group to the quarterfinals, then semifinals and the final. A page shows the road to the Nations Cup (the six qualifiers and their finalists). |
| World rankings (More → World rankings) | Clubs per game and organisations over all games; countries and territories per game and over all games. Points from results only: each finished event gives prestige × 10 points by placing (winner 100 %, runner-up 70 %, 3rd–4th 50 %, ...). Clubs: last 12 months (older half at half value). Countries: national team results over two years plus a quarter of the club points of their players. Until results exist, the order is by strength. |
| Speed | Qualifier matches are always simulated in fast mode (unless you follow a side) and only their finals keep replays: a season simulates about 17 % slower with roughly 2,900 more series. |

## Fifth follow-up: scrims, top lane, Fearless, nationalities, call-ups, the 2029 crash

| Request | What was built / found |
|---|---|
| Crash in 2029, phone heating | Found the memory problem with a four-season soak test (live heap 310 MB by 2029, a typical phone limit is 256-512 MB; now 160 MB after three seasons): the player rating cache kept every old attribute version (~140 MB after three seasons) and the MOBA path-distance cache (~47 MB, bounded by the map size) is now built without a lock. New **Simulation speed** setting (Start menu and career Settings): Full speed (every core), Balanced (half, default), Cool (two cores) for a cooler phone. A comparator that used random keys inside a sort (new scrim pairing) was caught and fixed before release. |
| Fearless | Bans could be spent on champions already out (the ban check ignored the Fearless locks); a ban now only counts on a champion the opponent could still pick. The Nations Cup and its qualifiers used the mid-season event's rule (Insane Fearless) instead of the Nations Cup's; fixed, older saves corrected on load. A best-of-five test checks that no picked or banned champion comes back and no ban is wasted. |
| Nationalities, birth dates | The snapshot matched Leaguepedia players by handle only, so same-handle players were mixed up and the right ones rejected (Viper, Tarzan, Knight, Ruler, Peyz, Caps ...), leaving the club's country as an estimate. Players are now matched by real name, current team or residency; Wikidata adds day-precise birth dates and citizenship for professional gamers; OpenDota adds Dota 2 nationalities; an estimated nationality is checked against Korean and Vietnamese real names. |
| Second nationality | Listed second nationalities are kept; three straight seasons at clubs of one country give its nationality as a second one (no longer an import there, eligible for its national team). All nationalities are shown; spreadsheet column "second nationality". |
| National call-ups | By this season's form (average match rating, games played, club level), not ratings; re-picked between the qualifier and the Nations Cup (squad news). Dual nationals play for their second country only when their first would not pick them. |
| Showcase | Standout players at internationals (Nations Cup and qualifiers included) gain reputation; unsigned or lower-league standouts are noticed (scouting message) and young ones gain a little potential. |
| Scrims | Simulated scrim blocks every week (Team.scrims) between teams of the same servers and level: picks, results, a per-patch record of what works for each team (it moves their draft priorities), practice on what they played, and what partners picked. **Training & scrims** tab on every team page (spectators too) and in your squad's training. |
| Top lane deaths | Measured over real league matches: the top / EXP / Baron / Clash lane died 1.2-1.3 times a game in every MOBA (others 3.5-5). Causes found: junglers almost never ganked successfully outside mid (laners did not join, junglers ganked on low health), split-pushers were never collapsed on, the top laner stayed out of most team fights, and tanks looked twice as dangerous as they were. All changed; top-lane deaths rose to about 1.5-1.6 a game (LoL 1.21 to 1.57, Wild Rift 1.20 to 1.60), total kills stay realistic and the balance tests pass. The rest of the gap comes from how much tanks and bruisers survive in fights, which needs a broader combat-balance pass. |
| Solo queue | Solo queue ladders added to the More menu. |

## Tests and results (JVM, `./gradlew testDebugUnitTest`)

All suites pass (23 classes, including `DraftVarietyTest`, `MobaSideTest`, `WorldIsolationTest`). New or extended:

* `MobaBalanceTest` — 5 titles × 40 seeded games (strongest vs weakest) + 20 vs a near peer. Checks:
  length within 75–130 % of the title's average, kills in range, the strongest roster wins most
  games, near peers both win games, every game ends with a core falling, recorded kills equal the
  score, no ability recast before its shortest cooldown, no dead hero casts, the same seed gives the
  same game with or without recording.
* `ShooterBalanceTest` — Valorant and CS2 × 40 seeded games over all pool maps: kills per round,
  attack win rate, plant rate, defuses, utility per round, strongest roster wins, recorded kills =
  box score, one round-end event per round.
* `CustomCompTest` — 6/8/16-team league, groups+SE, SE with byes, DE, Swiss+SE, groups+DE played to
  a champion with prizes paid; invalid drafts (1 team, Swiss of 10, Bo7 in a shooter, groups too
  small) create nothing; duplicates count once; cancellation; recurring edition next season with the
  old result kept; drafts and editions survive save/load.
* `IntegrityTest.twentySeasonSoak` (UEM_SOAK=1) — now also checks a recurring custom event is played
  exactly once per season and fixture ids stay unique.
* Existing `ReplayTest` (recording/watching never changes results, replays add up to box scores,
  interrupted live series resume identically, results applied once), `IntegrityTest`, `SaveTest`,
  `SeasonTest`, `ScreenshotTest` (renders the new viewers and analysis) pass.

Last measured balance (seeded, 4-core desktop JVM, includes recording):

```
mlbb     length 15.3 min  kills 27.5  structures 7.8  objectives 3.2  casts 480   strongest wins 90%  vs peer 62%
wildrift length 19.9 min  kills 35.6  structures 11.0 objectives 6.4  casts 848   strongest wins 95%  vs peer 75%
hok      length 18.1 min  kills 30.9  structures 8.6  objectives 6.2  casts 480   strongest wins 95%  vs peer 81%
lol      length 34.8 min  kills 40.6  structures 14.5 objectives 8.6  casts 1252  strongest wins 70%  vs peer 50%
dota2    length 42.4 min  kills 50.3  structures 13.2 objectives 11.1 casts 418   strongest wins 65%  vs peer 62%
valorant kills/round 7.0  attack wins 52%  plants 45% of rounds  defuses 15% of plants  strongest wins 90%
cs2      kills/round 6.5  attack wins 52%  plants 46% of rounds  defuses 11% of plants  strongest wins 92%
```

Performance (desktop JVM, 4 cores): a MOBA game ≈ 6–25 ms, a shooter map ≈ 13–23 ms; 120 days of
the full seven-title calendar (≈ 4,900 games) in 6.8 s. Phones are slower per core (not measured
here: no device or emulator in this environment).

20-season soak (`UEM_SOAK=1`, all seven titles, user career, a recurring custom event): passed. Each
season ≈ 44–59 s on 4 desktop cores (the previous abstract engine needed ≈ 8 s), heap after each
season 109–227 MB, save 3.3 → 19.3 MB, box-score / replay pages 66 → 238 MB (replays older than
about two seasons are pruned; box scores are kept). The custom event was played exactly once every
season; fixture ids stayed unique; every league team could field a lineup in every season.

Expect a simulated season on a phone to take a few minutes (it is several times slower per core than
a desktop); long "simulate 10 seasons" runs save after every season and can be stopped. Settings →
Replays → "Important" (default) or "Off" keeps the page storage down.

## Approximations (and where numbers come from)

* Ability effects are classified from official / community descriptions; damage magnitude is a
  multiplier on the engine's per-title standard (from the source where it lists damage: Dota 2,
  MLBB). Durations, Wild Rift cooldowns (PC × 0.8), HoK numbers and Valorant credit costs / charges /
  ultimate points are approximations and marked `approx` per ability in `abilities.json`.
* Weapons: public values (Valorant from valorant-api; CS2 approximate 2025 values); Valve and Riot
  change them in patches.
* MOBA geometry: a 50 × 50 walkability grid from the map's wall rectangles (approximate layout, not
  the games' navigation meshes); vision radius per title; tower, objective and minion numbers are
  engine values tuned so game length, kills and structures match each title's typical pro games.
* Shooter geometry: the existing node graphs plus a line-of-sight grid from the wall rectangles
  (approximate layout); movement speed and timings (round 100 s / 115 s, spike 45 s / bomb 40 s,
  plant 4 s / 3.2 s, defuse 7 s / 10 s, 5 s with a kit) follow the games' rules.

## Saves and migration

All schema changes are appended fields (`Replay.per/step/posN/pos/posT`, `Competition.finalBo/
customId`, `World.customComps`, `CompTier.CUSTOM` at the end), so existing saves load. Replays made
before this upgrade (v1) still play in the previous event view; their analysis shows "unavailable"
rather than invented heatmaps.

## Known limitations

* Not run on a phone or emulator here (no hardware acceleration in this environment): frame rate,
  battery and ANR behaviour on devices are unmeasured. The viewer's toggles (abilities, minions,
  paths, names) serve as a reduced-effects mode.
* Kills in LoL (~34 per game) are still a little above the pro average (~25–30); Dota 2 games are a
  few minutes longer than average. Occasional outlier games (a hero dying very often) remain and are
  logged by the tuning tests (`MobaTraceTest`, `MobaDiagTest`, `MobaCharBalanceTest`, env-gated).
* Valorant attackers win 43 % of rounds in the seeded sample (pro play is close to 48–50 %).
* Character win rates spread roughly 35–65 % (10th–90th percentile) in random drafts; the draft AI
  does not yet know which kits are strong in the engine.
* Release build: the APK is a debug build. To make a signed release: create a keystore outside the
  repository (`keytool -genkeypair -v -keystore ~/keys/uem.jks -alias uem -keyalg RSA -keysize 2048
  -validity 10000`), add a `signingConfigs.release` reading `UEM_STORE_FILE`, `UEM_STORE_PASSWORD`,
  `UEM_KEY_ALIAS`, `UEM_KEY_PASSWORD` from the environment, and run `./gradlew assembleRelease`.
  Never commit the keystore or passwords. A release build cannot be installed over the debug build
  (different signature): export the save first.

APK: `native-android/app/build/outputs/apk/debug/app-debug.apk` (copied to
`release/esports-manager-native-debug.apk`).
