# Data sources and provenance

The game ships an **offline, dated snapshot** of real-world data. Nothing is fetched while playing; the
snapshot is rebuilt with the scripts in `scripts/data/` during development (`.snapshot-cache/` holds the raw
responses locally and is not committed). Each record keeps its source and collection time.

Reality labels used throughout: **official/verified** (publisher or official site), **public community
data** (named community database), **estimate** (derived by a documented rule), **fictional game
simulation** (ratings, personalities, contracts, future seasons) and **unknown**.

This is a personal, non-commercial project with no affiliation to Riot Games, Valve, Moonton, Tencent /
TiMi / Level Infinite or any team. **No proprietary art, icons, map textures or game files are bundled**:
characters are shown by name with original schematic visuals.

## Snapshot `real-2026-11-20` — playable characters

`node scripts/data/characters.mjs` → `src/data/real/characters.json` (collected 20 Nov 2026).

| Game | Count | Patch / as of | Names & classes | Lanes / positions |
| --- | --- | --- | --- | --- |
| League of Legends (S223) | 173 champions | Data Dragon 16.23.1 | **Official** — Riot Data Dragon `champion.json` (tags → classes; Support → Enchanter) | **Community** — Meraki Analytics `champions.json` positions; 2 newest champions (Locke, Zaahen) **estimated** from classes |
| Wild Rift (S221) | 143 champions | official site, 20 Nov 2026 | **Official** — wildrift.leagueoflegends.com champion list and each champion page (roles, difficulty) | **Estimate** — PC League positions of the same champion mapped to Wild Rift lanes (the WR site lists roles, not lanes); Norra and Nunu & Willump estimated from classes |
| Honor of Kings (S222) | 118 heroes (global) | 20 Nov 2026 | **Community** — hokstats.gg global hero list and per-hero profiles (English names, classes, lanes); cross-checked against the **official** Tencent CN list `pvp.qq.com/web201605/js/herolist.json` (hero ids, CN names, classes) | **Community** — hokstats.gg hero profiles |
| Mobile Legends: Bang Bang (S220) | 133 heroes | 20 Nov 2026 | **Community** — mlbbhub.com hero pages (roles, specialties, difficulty, durability/offense/control ratings) | **Community** — mlbbhub.com lanes (Gold, EXP, Mid, Jungle, Roam) |
| Dota 2 (S224) | 127 heroes | 20 Nov 2026 | **Community API of game data** — OpenDota `/api/constants/heroes` (roles, primary attribute, attack type) | **Estimate** — positions 1–5 derived from OpenDota roles (Carry → 1, Nuker/escape carry → 2, Initiator/Durable → 3, Support with disable/initiation/nuke → 4, Support → 5) |
| Valorant (S232) | 30 agents | 20 Nov 2026 | **Community mirror of game data** — valorant-api.com agents (role, ability names) | n/a (agents have roles) |
| Counter-Strike 2 (S234) | — | — | CS2 has no heroes/agents; weapons, equipment and maps arrive with the shooter milestone | — |

Game-side mapping (src/data/titles/realCharacters.ts): each character's first class that exists in the game's
class list becomes its primary class; drafts and hero-pool training use the real lanes. Play-style numbers
(early/scaling/teamfight/pick…) are **game tuning** derived from the real classes and, where published,
kit ratings — not official measurements.

Dota 2 groupings (Agility Carry, Strength Core, Intelligence Nuker, Initiator, Support, Pusher) are game
labels derived from OpenDota roles and primary attribute.

Older saves (fictional characters) are remapped once, deterministically, to real characters of the same
class (`src/game/characterMigration.ts`).

## Snapshot `real-2026-11-20` — teams, players and staff

`node scripts/data/rosters.mjs && node scripts/data/rosters_shooters.mjs <clone of ValveSoftware/counter-strike_regional_standings>`
→ `src/data/real/rosters.json` (collected 20 Nov 2026). Choose **Real teams & pro players** on the New Game screen.

| Game | Leagues in the game | Teams | Source of teams/handles | Roles | Notes |
| --- | --- | --- | --- | --- | --- |
| MLBB | MPL Indonesia, MPL Philippines (Season 18) | 17 | **Official** — id-mpl.com and ph-mpl.com team pages (players, roles, coaches) | **Official** (site roles) | Starters = first listed player per role (estimate) |
| League of Legends | LCK, LPL, LEC, LCS, CBLOL, LCP | 56 | **Official** — LoL Esports gateway (`getLeagues`, `getSchedule`, `getTeams`) | **Official** — participants of each team's latest completed league game (feed.lolesports.com live-stats metadata) | Real names from the official player records; team strength ordering from the official series record of the last ~4 months |
| Dota 2 | "Dota 2 Europe & CIS League" (game structure) | 13 | **Community API** — OpenDota `/api/teams` (rating, last match), `/teams/{id}/players`, `/proPlayers` | **Estimate** — lanes and last hits in each team's latest parsed pro matches (safe core = pos 1, mid = 2, off core = 3, off support = 4, safe support = 5) | Teams active in the last 120 days; each player assigned to the team he played for most recently; Steam-profile countries ignored (self-reported); region estimated from game-server region of recent matches |
| Valorant | VCT Americas, EMEA, Pacific, China (VCT 2026) | 32 | **Community** — vlr.gg event pages (Kickoff ∪ Stage 1 ∪ Stage 2) and team pages (current rosters, nationality flags, staff) | **Estimate** — agents played in the last 90 days (agent roles from valorant-api.com; ≥ 55 % one role, else Flex) | Off-season rosters can be incomplete: teams with ≥ 3 listed players are kept and the open slots are filled with fictional players |
| CS2 | "CS2 Europe League" + "Challengers", "CS2 Americas League", "CS2 Asia-Pacific League" (game structure) | 46 | **Official** — Valve Regional Standings, `live/2026/standings_{europe,americas,asia}_2026_11_02.md` (team, points, five-player roster) | **Unknown** — not published by the source; the game assigns roles (labelled "game assignment") | Standings points order team strength; ranks 13–24 in Europe form the challenger league |
| Honor of Kings, Wild Rift | fictional | — | No accessible, verifiable roster source in this environment (KPL / HoK World Cup pages and Wild Rift league pages were unreachable or blocked) | — | Fully fictional, unmarked teams |

What the game adds and labels: every rating/attribute/personality, contracts, salaries, finances, owners,
sponsors, facilities (game simulation); ages (estimate); nationalities not published by the source (estimate
from the league region); organisation countries without a source (estimate); **organisations are grouped
across games by name** (e.g. T1, Gen.G, Team Liquid, Natus Vincere) — an estimate shown on the organisation
page. Real entities carry `real = { src, snap, est[] }`; anything without it is fictional (shown as
"Fictional" in real-world careers). Regions without real data are filled with fictional teams in the
Standard/Large world sizes only.

## Snapshot `real-2026-11-20` — past teams, former pros and retired pros

`node scripts/data/history.mjs <clone of ValveSoftware/counter-strike_regional_standings>` → `src/data/real/history.json`
(collected 20 Nov 2026): 344 teams and 1,857 players (LoL 121/618, Valorant 40/303, CS2 116/530, Dota 2 56/219,
MLBB 11/187).

| Game | Seasons covered | Source |
| --- | --- | --- |
| League of Legends | 2024–2026 | **Official** — LoL Esports gateway schedules; one game per team per season through the live-stats feed (participants); archived teams with their last listed rosters |
| Valorant | 2023–2026 | **Community** — vlr.gg VCT league events, one match per team per season |
| CS2 | 2024–2026 | **Official** — Valve Regional Standings, monthly global standings (top 60 with rosters) |
| Dota 2 | last three years | **Community API** — OpenDota top-rated teams and their members |
| MLBB | earlier MPL ID / PH seasons | **Official** — id-mpl.com / ph-mpl.com team pages |

How a real-world start uses it (`addRealHistory` in `src/gen/realWorld.ts`):

* **Old teams** (not in the current snapshot) exist as **dormant** teams: no league, no players, not an
  active division of their organisation (an organisation with only dormant teams is inactive). They show on
  the organisation page ("Dormant and former divisions") and in the team directory ("Dormant real teams").
  When a league place is sold (a division closes), a dormant real team from that league's region takes the
  place first, re-signs its former players who are still free agents and fills the rest with fictional
  players (`reviveDormant` in `src/game/rollover.ts`).
* **Former pros** on no current roster are all **free agents** — including players not seen at the top
  level for a long time (at the user's request nobody from the snapshot is archived as retired; their
  provenance says "free agent — no top-league game … since …"). Ability is a game estimate a little below
  the league average (lower for long-inactive players). Their career shows "Last real team". Unsigned
  real pros retire more slowly than fictional free agents.
* **Comebacks** (`src/systems/comeback.ts`): real pros who retire *during* a career can be asked to return
  within six seasons (History → Retired, or the retired player's page); a few return by themselves at each
  rollover. Saves from the build that archived snapshot pros as retired convert them to free agents on load.
* **Competitions**: league names are the real leagues (above). In real-world careers the mid-season
  internationals use the real circuits' names (LoL First Stand / Mid-Season Invitational / World
  Championship, MLBB Mid Season Cup / M-Series, VALORANT Masters (spring, summer) / Champions,
  Counter-Strike Majors, Dota 2 The International, Honor of Kings World Cup / International Championship). Formats, dates,
  entrants and prize pools are game simulation (shown on the competition page).

## Snapshot `real-2026-11-20` — birth dates, nationalities, names, roles

`node scripts/data/player_facts.mjs <cache dir>` → `src/data/real/playerFacts.json` (collected 20 Nov 2026).
Applied to real players when a real-world career starts and once to older real-world saves
(`src/gen/realFacts.ts`); the player page shows the source ("Birth date, nationality and name: …").

| Game | Source | Matched (of current + past pros) | With exact birth date |
| --- | --- | --- | --- |
| League of Legends | [Leaguepedia](https://lol.fandom.com) Cargo API, table Players (CC BY-SA 3.0) — also the listed role | 808 / 898 | 652 |
| CS2 | [bo3.gg](https://bo3.gg) public API (players: names, birthday, country) — no roles | 732 / 760 | 651 |
| Valorant | [VALORANT Esports Wiki](https://valorant-esports.fandom.com) Cargo API (sparse) — nationalities of current rosters already come from vlr.gg team pages | 26 / 468 | 17 |
| Dota 2, MLBB | no reachable source (Liquipedia blocked; bo3.gg's player list is CS2 only) | — | — |

Where no birth date is known the age stays an estimate (labelled "age").

### Access notes

* **Liquipedia API (20 Nov 2026, second attempt, at the user's request for HoK / Wild Rift)**: one
  `siteinfo` request through the documented MediaWiki API succeeded; the next request (a search, 2.5 s
  later) returned Liquipedia's "Rate Limited" page with a Cloudflare challenge for this environment's
  shared IP. Access was stopped there (no retries, no workarounds). HoK and Wild Rift stay fictional.

* **Liquipedia** was tried first for rosters and hero lists; this environment's shared IP was rate-limited
  and served a CAPTCHA that explicitly asks automated agents not to solve it, so Liquipedia is **not used**.
* The MLBB official site signs its API requests; that was not reverse-engineered. The community database
  above was used instead.
* Riot's LoL Esports gateway (the public key used by the lolesports.com website) is used for LoL rosters.
* vlr.gg's robots.txt allows team, player and event pages; requests were spaced ≥ 0.8 s apart and cached.
* OpenDota requests were spaced ≥ 1.1 s apart (free-tier limit). Bayes Esports' tower-coordinate page and
  several HoK guide pages were unreachable from the build environment.

## Battlefield maps (MOBAs) — `src/sim/moba/maps.ts`

Original schematic geometry (no game art, textures or client files). Topology, structure counts, objective
locations and timers come from the references below; see LIVE_2D_SIM_SPEC.md for what is modelled.

| Map | References | Status of positions |
| --- | --- | --- |
| MLBB — Land of Dawn | mlbbhub.com/map (community interactive schematic: lanes, turrets, buffs, creeps, crabs, Turtle/Lord pits, timers); ONE Esports Turtle guide; VCGamers lane guide | community schematic, scaled |
| Wild Rift — Summoner's Rift | riftpatchnotes.com Wild Rift unit histories (Baron 11:00 & respawn 3:30, Herald, Elder 19:00); official Wild Rift patch notes 5.2 | layout schematic; dragon timing / Nexus turrets unverified |
| Honor of Kings — Kings' Canyon | Sportskeeda map guide; BlueStacks map guide; BitTopup 2025 monster timers | schematic; sides follow the CN-server convention |
| League of Legends — Summoner's Rift | Riot dev blog "2026 Season One gameplay preview" (objectives); Sheep Esports 2026 S1 summary; Riot match-timeline coordinate system (riot-api-libraries docs); Bayes Esports tower-coordinate reference (blue top outer turret 981, 10441 confirmed via search; page unreachable from the build environment) | Riot coordinates scaled; values other than the confirmed turret approximate |
| Dota 2 | GosuGamers 7.38 (Roshan pits, day/night); bo3.gg 7.39 (pit changes, Tormentor 20:00); Hawk Live 7.41 (Tormentor near Lotus Pools, Twin Gates); PlayNews 7.41e objectives guide | schematic |

Objective timings were updated in `src/data/titles/catalogue.ts` with the same references (comments in code).

## Shooter maps — `src/sim/shooter/`

| Game | Source | Status |
| --- | --- | --- |
| Valorant (13 competitive maps) | valorant-api.com `/v1/maps` (community mirror of game data): callout region/super-region names and in-game coordinates, minimap transform. Pool: Strafe "Valorant Map Pool: Current Competitive Maps (V26 Act 6)", 14 Oct 2026 | positions official (transformed and rotated); connections inferred (estimate) |
| CS2 (9 maps) | csdb.gg map hub (July 2026 active duty), PlayNews 2026 callout guide, tradeit.gg callout guide | schematic: callout names and routes from the guides, positions approximate |

## Pictures — `src/data/real/images.json`

`node scripts/data/images.mjs <cache dir>` collects **links only** (no image files are copied into the
repository or the APK). Pictures load from the owners' own servers when the device is online; offline,
when switched off (Settings → Display → Online pictures) or when a picture fails, the game shows its own
badges. Riot's fan-content policy covers Riot game art (LoL, Wild Rift, Valorant); other art is shown
from its owners' servers for this personal, non-commercial game and is not redistributed.

| Pictures | Source | Count |
| --- | --- | --- |
| LoL & Wild Rift champions | Riot Data Dragon (Wild Rift by champion name; Norra has none) | 173 + 142 |
| Valorant agents and weapons | valorant-api.com | 30 agents, 21 weapons |
| Dota 2 heroes | OpenDota hero list → Valve's Dota 2 CDN | 127 |
| Honor of Kings heroes | Tencent's official CDN by official hero id (12 global-only heroes have none) | 106 |
| MLBB heroes | mlbbhub.com hero pages → Moonton's official CDN | 131 |
| CS2 weapons | ByMykel/CSGO-API (Steam images) | 66 |
| Team logos | LoL Esports gateway (177), vlr.gg (32), OpenDota (67), MPL Indonesia (9), bo3.gg (150) | 435 |
| Player photos | LoL Esports gateway (533), vlr.gg (188), bo3.gg (743); MPL photos are expiring signed links — not used | 1,464 |
| League & event logos | LoL Esports gateway (incl. First Stand, MSI, Worlds), vlr.gg VCT events, MPL Indonesia | 59 |

## Licences and attribution

* Riot Data Dragon: Riot Games developer data; used under Riot's developer policies for a non-commercial
  project; no assets bundled.
* Meraki Analytics, OpenDota, valorant-api.com, hokstats.gg, mlbbhub.com, vlr.gg: public community data;
  credited here; only facts (names, handles, roles, lanes, nationalities as listed) are stored.
* Valve Regional Standings: published by Valve on GitHub; team names and rosters (facts) only.
* MPL ID / MPL PH official sites, LoL Esports: official league data; handles, roles and published names only.
* Character names and game terms are trademarks of their publishers.
