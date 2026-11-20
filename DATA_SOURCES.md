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

### Access notes

* **Liquipedia** was tried first for rosters and hero lists; this environment's shared IP was rate-limited
  and served a CAPTCHA that explicitly asks automated agents not to solve it, so Liquipedia is **not used**.
* The MLBB official site signs its API requests; that was not reverse-engineered. The community database
  above was used instead.
* Riot's LoL Esports gateway (public website key) is used for LoL team/player rosters (next step).

## Licences and attribution

* Riot Data Dragon: Riot Games developer data; used under Riot's developer policies for a non-commercial
  project; no assets bundled.
* Meraki Analytics, OpenDota, valorant-api.com, hokstats.gg, mlbbhub.com: public community data; credited
  here; only names/classes/roles/lanes (facts) are stored.
* Character names and game terms are trademarks of their publishers.
