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

### Access notes

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

## Licences and attribution

* Riot Data Dragon: Riot Games developer data; used under Riot's developer policies for a non-commercial
  project; no assets bundled.
* Meraki Analytics, OpenDota, valorant-api.com, hokstats.gg, mlbbhub.com, vlr.gg: public community data;
  credited here; only facts (names, handles, roles, lanes, nationalities as listed) are stored.
* Valve Regional Standings: published by Valve on GitHub; team names and rosters (facts) only.
* MPL ID / MPL PH official sites, LoL Esports: official league data; handles, roles and published names only.
* Character names and game terms are trademarks of their publishers.
