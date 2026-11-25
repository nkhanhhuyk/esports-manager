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

## Sixth follow-up: deaths, items, economy, objectives, runes, leagues, data audit

| Request | What was built / found |
|---|---|
| Fewer deaths for the other roles | League-match deaths per game (LoL): mid 5.2 → 3.3, ADC 4.6 → 3.0, support 4.1 → 3.2, jungle 3.9 → 2.5, top 1.6 → 1.4; kills a game ~39 → ~27 (pro LCK ~25, LPL ~30). Found: junglers died chasing ganked laners under their tower (no dives now unless the dive kills); after laning, skirmishes need a clearer edge and the back line does not start them; carries and mages fight from behind their front line (less damage lands, more skill shots miss, the front line shields them in target choice); damage builds gain armour / magic penetration so tanks are no longer near-unkillable. Per-game tuning for the mobile MOBAs and Dota 2. The role test now checks no position dies more than 4.2 times a game and the top lane dies at least 45% as often as the other cores. |
| Fearless and best-of-sevens | Soft Fearless from 100 characters, Fearless from 120, Insane Fearless from 150; a rule the pool is too small for steps down (Wild Rift 143, MLBB 133, Dota 2 127: up to Fearless; HoK 118: Soft Fearless; LoL 173: all). |
| Items | Real items for all five MOBAs (Data Dragon, Wild Rift / MLBB / HoK wikis, dotaconstants), built one by one during the match by class and game state (hybrid and full-damage or tank builds), patched and newly released (fictional, with generated art), shown in the box score. A snowballing tank may now take up to two damage items. |
| Valorant economy, CS2 loadouts | Per-player banks with the real prices, round rewards, loss bonus, kill rewards per weapon, plant / defuse money; pistol / eco / force / full buys; Valorant abilities and shields, CS2 grenades and kits; round-by-round loadouts in the match screen. |
| Neutral objectives | Every objective's real buff, applied as stats: LoL's six elemental drakes (and the rift rule), Dragon Souls, Elder (only after a soul), Baron, Herald, Voidgrubs; Wild Rift's (approximate); MLBB Turtle / Turtle's Blessing / Lord; HoK Tyrant, Overlord, Shadow Tyrant, Shadow Overlord, Tempest Dragon; Dota 2 Roshan's drops by kill, Tormentors, Wisdom Runes. Patches buff / nerf them and can add fictional kinds (a new elemental drake with its own soul, a new form of an objective). Meta screen card; commentary names drakes and souls. |
| Runes and equivalents | LoL runes (Data Dragon), Wild Rift runes (summaries), MLBB emblem sets and talents, HoK arcana, Dota 2 talents and facets; each hero takes a page that fits its class; stats in the engine; box-score column; patches and fictional additions; meta-screen card. |
| LJL, PCS, VCS | Real teams of the three leagues (official LoL Esports feed) as tier-2 leagues under the LCP; real academies tied to their LCP teams; each October an LCP Promotion tournament: the LCP's last two against the lower leagues' best; the top two play the LCP next season, the others go to the lower league of their region. |
| Database audit | The main cause of wrong nationalities was matching by handle alone (a fact belonging to another player with the same handle); such facts are now refused when they contradict the roster source. More real birth dates (Valorant wiki table + redirects, Leaguepedia redirects), Dota 2 positions from lanes and farm in pro matches, Dota 2 nationalities by agreement of three sources (Valve's unset "af" no longer reads as Afghanistan), CS2 same-nickname mix-ups settled by team. See DATA_PROVENANCE.md for what is still estimated (MLBB birth dates, CS2 positions: no open source). |

## Seventh follow-up: objectives by kind, top lane deaths

| Request | What was built / found |
|---|---|
| Objectives by kind | Every game record keeps each neutral objective taken (kind and team; full and fast simulation). The match report has a **Neutral objectives** table per game (each elemental drake, Dragon Soul, Elder, Baron, Herald, Voidgrubs one by one; Turtles and Lords; Tyrant, Overlord, Shadow Tyrant, Shadow Overlord, Tempest Dragon; Roshan, Tormentors, Wisdom Runes), and the live / replay viewer shows a per-team strip under the scoreboard that updates as the game plays. Found while doing it: the box score lost every player's item build and rune page when a MOBA game was saved to the match record; fixed. |
| Top lane deaths | Top laners were never killed in the laning phase (0 of 216 early deaths): melee solo laners stood far behind their wave and hardly ever traded, so they stayed at full health, and ganks and collapses could not catch a fleeing laner on 2-second steps. Now melee top / EXP / Baron / Clash laners contest their wave and trade, gankers hit at the end of a chase step and open with crowd control (or flash in), a lone laner hit by two or more enemies takes more damage (no peel, chained CC, ignite), two healthy heroes may dive a low laner, a strong lead in a solo lane is an all-in, early fights are slightly more lethal and front-liners stay in fights longer. League deaths per game, top against the other cores: LoL 2.6 vs 2.8-3.3, Wild Rift 2.7 vs 3.1-3.4, MLBB 2.4 vs 3.2-3.8, HoK 2.2 vs 3.0-3.3 (about 50% before, now 70-85%). |

## Eighth follow-up: spells, live builds, game libraries, role metas, speed

| Request | What was built / found |
|---|---|
| Items, runes and spells in the live scoreboard | Each player's row in the live / replay scoreboard shows the items bought **so far** (from the recorded purchase events, in order, empty slots until then), the spells (darkened with the seconds left after each use) and the rune / emblem / arcana / facet picture with its name. The match report's box score keeps the full build, and now has a Spells column and the rune column as pictures with names under them. |
| Spells | Summoner spells (LoL, Wild Rift: Flash plus a second spell, Smite for junglers), MLBB battle spells (one; Recall and Healing fixed; Retribution for junglers), HoK common skills (one; Recovery fixed; Smite for junglers); real icons and cooldowns. Used in the engine (Flash to escape or engage, Ignite / Execute / Exhaust / Petrify / Flameshot in fights, Heal / Barrier / Aegis / Vengeance when low, Cleanse / Purify against crowd control, Teleport / Arrival to rejoin, Disrupt on a tower, the jungle spell to secure objectives). Patches buff and nerf them (cooldown and strength), take one out of the game and bring it back, and release fictional spells. Dota 2 has none (as in the game). |
| Pictures of neutral objectives | Every drake element, souls, Elder, Baron, Herald, Voidgrubs, Turtle, Lord, Tyrants, Overlords, Tempest Dragon, Roshan and its drops, Tormentor and Wisdom Rune: the game's own picture in the objective table, in the live strip and inside the objective's circle on the 2D map (the kind actually spawned, e.g. the drake's element). |
| Objective effects on the 2D map | Minions empowered by Baron, the Lord, an Overlord or Shadow Overlord are drawn purple; heroes holding Baron's buff carry a purple aura until they die; the MLBB Lord, the Rift Herald and HoK's Overlord / Tempest vanguards are drawn walking at the front of the pushing wave while their effect lasts. |
| No wards in MLBB / HoK | Heroes place no wards in Mobile Legends and Honor of Kings (vision from heroes, towers and bushes); no ward column, heatmap or ward lines in those games' reports. |
| More tab: game libraries | **Items** (per game, by type: physical, magic, defence, support, boots & movement, jungle), **Runes, emblems, arcana & talents** (LoL / Wild Rift trees with keystones and rows; MLBB sets, standard and core talents; HoK arcana; Dota 2 facets and talents per hero), **Spells**, **Neutral objectives** — pictures with the name underneath, tap for the numbers the engine uses and the patch changes. |
| Kills by position and role metas | Kills were credited to whoever landed the last hit, so burst mid laners led every game (LoL mid 3.6 kills a game, ADC 2.0). Teams now let their carry take kills it helped with, and the carry farms a little more. League matches (first split, full engines), kills a game: LoL ADC 3.9 > mid 3.6 > top 2.2 > jungle 1.7 > support 0.6; Wild Rift Dragon lane 4.6 > mid 3.9 > Baron lane 3.4 > jungle 1.7 > support 0.8; MLBB Gold lane 4.3 > mid 4.0 > EXP 2.3 ≈ jungle 2.2 > Roam 0.4; HoK Farm lane 4.0 > mid 3.6 > Clash 2.5 > jungle 2.0 > Roaming 0.4; Dota 2 pos 1 10.8 > pos 2 8.9 > pos 4 3.4 > pos 3 3.0 > pos 5 1.0. A mid-carry meta turns it around (in the test, mid's kills go from 0.8× to 2.4× the bot laner's). **Role metas** per game, shifted by patches and listed in the patch notes' current meta (with the expected kill order): carry (bot-side, mid, top or jungle carry), jungle (gank, farm, objective control), support (roam, babysit, engage), mid (roam, lane control, utility), top (tank, bruiser, split-push) and pace (early skirmish, scaling, objective control). The engine plays them: who is handed the kills, farm share, gank frequency, roaming mids and supports, supports peeling for their carry, top laners on tanks, more or fewer early fights. Patch notes say when a meta changes. |
| Top and mid deaths | Deaths per game are now within about ±10% across positions in every MOBA except Dota 2 (where the safe-lane carry dying least is normal): e.g. LoL 3.0-3.2 for every position, Wild Rift 3.0-3.5, MLBB EXP 2.8 vs 2.5-3.1, HoK Clash 2.7 vs 2.8-3.0 (before: EXP 2.4 vs 3.2-3.8, Clash 2.2 vs 3.0-3.3, mids the highest). A per-position calibration of damage taken (from the role diagnostic) evens out what the behaviour changes left. |
| Faster season simulation | Profiled a season stretch: the engine re-chose the next item from the whole catalogue every second a hero stood in base (≈12% of all time); the choice is now kept for 30 s of game time; fast-mode builds value each item once instead of six times; rune pages score each rune once (the old sort drew a random number inside its comparisons — slow, and the kind of comparator that can crash a sort); ban choices take each player's preference lists once. New default **Balanced** detail: the top leagues of the games you manage or follow and every international event play on the 2D engines, second-tier leagues and the other games' regular leagues on the quick model (your and followed teams' matches always on the engines; Settings → Simulation detail has Full, Balanced, Fast). 60 days of a season (desktop JVM, 4 cores, `SeasonSpeedTest`): 15.0 s before; in the same run now 7.3 s on Full and 2.8 s on Balanced following one game. Not measured on a phone. |

## Ninth follow-up: social media, patch-note wording, top-lane influence, MLBB data

| Request | What was built / found |
|---|---|
| Social media and fan culture | **Social media** in the More tab: four fictional networks — **Agora** (forum: post-match threads, player ratings out of 10, prediction polls, transfer and patch threads, up-votes, comment trees where fans argue), **Chirp** (quick takes, team accounts' results and signings, analysts' numbers, press "breaking" posts, reposts and likes), **Lens** (picture posts: scorelines between crests, MVP and signing pictures, title celebrations) and **Fanbase** (supporters' groups with 👍❤️😂😮😢😡 reactions, angrier during losing streaks; a fan mood per team). Everything comes from this world's events — results (upsets, sweeps, close series, MVPs, poor series), matches in the next five days (predictions, rivalry hype), transfers, patches and role-meta shifts, winning and losing streaks, titles, players in or out of form — and changes with every day. Filters: network, game, your and followed teams, kind of post (reactions, ratings, debates, appreciation, criticism, overreactions, predictions, memes, news). Fans and accounts are fictional; team accounts only announce results and signings; fans criticise performances, not people. |
| "Nerfed (+2.7% armour)" | Rune, arcana and objective notes printed the new value with its plus sign, so a nerf looked like a buff. Every change now shows before → after ("armour 3% → 2.7%"); items show their real current numbers (earlier patches included); spells their cooldown and strength. Found while checking: patching an objective's gold, experience or empowered-minion time did nothing (now applied), objectives with only an extra life or a cooldown reset were "nerfed" without any change, and items without stats were patched (both no longer picked). A test generates 120 patches per game and checks every buff goes up and every nerf down (4,200 lines, 0 contradictions). |
| Top deciding matches | Measured: giving one player +10 on every rating moves the win rate about as much at every position (LoL top 49%, jungle 48%, mid 56%, ADC 56%, support 48%; similar in the other MOBAs), so top-lane skill was not deciding results — but top laners had the highest game ratings and took most series MVPs (LoL 35%, Wild Rift 33%), because the rating counted their towers, damage and assists. Ratings now compare each player with what that position usually produces (each position's usual level is shifted, so a support's big-assist game is not inflated), tilted slightly toward the meta's carries. Series MVP share in league play, LoL: top 35% → 16%, jungle 27%, mid 18%, ADC 27%, support 13%; Wild Rift Baron lane 33% → 20%; Dota 2 mid 41% → 29%. |
| MLBB player data | Six MLBB players carried facts of unrelated people with the same handle (RRQ's ARTHUR as a Korean born 2000, AP Bren's Allen as a Swede born 1988, others Chinese / Taiwanese / South African / Italian): removed, and handle-only matches are no longer used for MLBB / HoK. Kairi (ONIC) is now Filipino, born 21 Sep 2005 (Liquipedia). Team Liquid PH's Teddy had no position ("Goldlane" written without a space). Liquipedia rate-limited the second request, so the other MLBB nationalities (league country) and birth dates stay estimates, marked on player pages. |

## Tenth follow-up: five selected expansions

Qualification ecosystems with a ledger and versioned rulesets (official where labelled), amateur /
university / Game Changers circuits linked to the leagues, mentoring and the academy pathway, Prepare
for Match with a sandbox strategy laboratory, and a broadcast layer on the 2D viewers (MLBB first,
then the other MOBAs, VALORANT and CS2). Details, tests and what is official or fictional:
[EXPANSIONS_REPORT.md](EXPANSIONS_REPORT.md). Also fixed: a draft cache that made a second
same-seed world differ in the same process, and a shooter crash with a short-handed lineup (now a
stand-in plays).

## Eleventh follow-up: KDA, MVPs, transfers, ambitions, rebuilds, national teams, fan threads

| Request | What was built / found |
|---|---|
| KDA dominated by junglers and top laners | Measured first (`KdaTest`): bot-lane carries had the lowest KDA in every MOBA (LoL ADC 1.75 vs jungle 2.37) because after laning they stayed farming their side lane while everyone else grouped, so they missed most fights; assists were far below pro levels; and winners died almost as often as losers. Now carries group with the team most of the time, everyone fighting near a kill gets the assist (as the games count it), carries are harder to reach behind their front line, last hits go to the meta's carry more often, and a team's form carries over through a game (momentum) instead of being redrawn every minute. Event player tables have a minimum-games filter (default 40% of the most games) so a team that went out early cannot top rate stats on a handful of games. |
| Worlds MVP from a team that missed playoffs | Event MVPs now come from the top four of the final standings (top two in small events), from players who played most of their team's games, by average rating plus a bonus for the placing. MVPs of finished events in older saves are chosen again by this rule when the save loads. |
| Players re-sold within a week | A player who joined a club by transfer cannot be bought again for 150 days (60 if he asks to leave). The test counts 0 re-sales within two months over three seasons (562 transfers). |
| Player ambitions | Every player weighs nine ambitions summing to 100%: winning, money, loyalty, legacy, prestige, a starting place, an uncontested position, development and his home region (from personality, age and chance). A team and salary are scored on each; the weighted fit sets the asking salary (money-minded players ask more, a team that gives them what they want gets a discount), whether he signs, renews or turns an offer down (with the reason), which club a free agent picks, and his mood at his club: an unhappy player asks to leave (AI clubs list him; you get an inbox message). Loyal players sign longer deals. Shown on the player page (weights, fit at his club, what pleases and bothers him). |
| Rosters blown up | Organisations have a strategy (win now, contender, development, moneyball, brand, patient) shown on the team page. At season end a club that paid for a contender and finished in the bottom half (or missed the world championship with a top-three payroll) may blow up the roster — likely for win-now and brand clubs, rare for patient ones: its expensive starters are listed, it shops harder through the window, and the news says why. Strategy also sets spending, whom a club targets (young high-potential players, undervalued ones, stars) and whom it renews. |
| Players staying at one club for years | Renewals now need the club to want the player and the player to want to stay; one- or two-year deals for players chasing money or a move. Over three seasons 60% of professionals changed club at least once; loyal players averaged 2.6 years at their club against 2.1 for the least loyal. |
| Tier-2 players stuck / in national teams | Clubs bid for second-division and academy players (139 moves up into top leagues over three seasons in the test). National selection discounts second-division and academy form and adds a level gap, so a comparable top-division player is picked first. |
| Repetitive comments; post-match threads | Comments are written from each series' own facts — stat lines with the character played, game lengths and kill scores, map scores and overtimes, objectives, reverse sweeps and deciders, standings, the head-to-head, a player beating his old team, streaks, upsets — spoken by winning fans, losing fans, neutrals, analysts and trolls with many phrasings, never the same phrasing twice in a thread. Agora's post-match thread ([PMT]) lists every game or map, the MVP and both teams' records. Transfer posts talk about the fee, last season's numbers, a second-division player getting his shot, who gets benched, years at the old club, reunions, rebuilds and what the player wanted. |

## Twelfth follow-up: Honor of Kings fights, a redesigned interface

| Asked | What changed |
|---|---|
| HoK: winners died as often as losers | Measured first with a new `FightOutcomeTest` (120 games per MOBA between league teams): in HoK the game's winners died *more* than its losers (1.09), the team with more kills won only half its games, and the team with more objectives won 83–90%. Causes found in the recorded games: objectives were taken while the fight around them was being lost, both teams took separate objectives at the same time instead of contesting, and late dives into towers (in MLBB and HoK every base structure shoots) killed the side that was ahead. Now a hero shot by an enemy tower without enough health steps out of range, heroes with an enemy next to them fight instead of hitting the objective, a team that is not clearly weaker goes to contest the enemy's objective (unless its own target is worth more), and teams go up the high ground with a numbers advantage, a lead or a buffed wave rather than into full defences. HoK winners now die about 15% less than losers (0.84) and the team with more kills wins about two thirds of games; the other MOBAs became more lopsided too (LoL 0.91 → 0.73, Wild Rift 0.76 → 0.69, MLBB 0.88 → 0.75, Dota 2 0.73 → 0.61; MLBB games 16 minutes with 3.5 items a player). Pro games are still more one-sided (LoL ≈ 0.5). |
| KDA by position after that | Stepping out of tower range stopped solo laners dying on their own dives, which put the top lane back on top of KDA tables; the per-position damage calibration was redone (solo lane more, bot-side carry less) and ratings re-normalised so series MVPs spread over the positions (LoL league KDA: top 2.5, jungle 3.2, mid 3.4, ADC 3.3, support 2.3). |
| Rebuild the UI / UX, keep every feature | New design system (`ui/Common.kt`): darker canvas, two raised surface levels with hairline borders, one accent for actions, violet highlights, gold for your club, Material 3 typography and shapes; cards, tabs, chips, selects, tables (uppercase headers, row dividers, soft highlight), stat tiles and bars restyled everywhere, so every screen follows it. New shell: the top bar names the screen you are on, shows the date and a *MATCH DAY* badge, keeps *Continue* one tap away and puts the longer simulations and saving in its ⋮ menu; the bottom bar has icons and an unread badge; the sidebar has a club header, icons, the current screen highlighted and a 2 / 5 / 10-season picker. Home opens on a next-match card (both teams, when, *Play live* / *Prepare* / *Simulate*, or *Go to match day*), then shortcuts (squad, market, inbox, schedule, finances, matches, social, stats), the club at a glance, the road to events, upcoming matches, each team with its form strip and table, board, inbox and news. *More* is a grouped grid (your club, matches and events, world, game knowledge); the title screen leads with continuing your career; team and player pages open on a header card. No screen or action was removed: every route, menu entry and button of the previous layout is still there. |

Last full run (24 Nov 2026, `./gradlew assembleDebug testDebugUnitTest`): 74 tests in 54 classes, 71 passed,
3 skipped (the environment-gated `SoakTest`, `CrossWorldDeterminismTest` and `IntegrityTest`, not run this
time), 0 failed. The redesign was checked on the JVM-rendered screens of `ScreenshotTest` (59 screens), not
on a phone.


## Thirteenth follow-up: foldables, social, bot metas, real history, live depth, business

| Asked | What changed |
|---|---|
| Fill the whole screen (Oppo Find N5: cover 20.7:9 and inner 9.9:9, both ways up) | The activity declares itself resizable for any shape and orientation (`resizeableActivity`, `screenOrientation="fullUser"`, `android.max_aspect` 3.0), handles folding / rotation without restarting (`configChanges`) and draws edge to edge into the status / navigation bars and display cutouts in every orientation. The bars' colours reach the screen edges and the content is padded by the insets. Wide screens (≥ 600 dp or landscape) use a navigation rail instead of the bottom bar; Home flows into two or more columns, More's grid widens, stat tiles take 4–6 a row, and the 2D match viewers put the map beside the scoreboard and controls (full height on the cover screen sideways, score + map left and controls right on the inner screen). Checked on JVM-rendered screens at the Find N5's shapes (`ScreenshotWideTest`, which also checks the window is fully painted); not on the phone itself. If ColorOS still letterboxes the app, Settings → Display → App display / full-screen apps lets you switch it to full screen. |
| Repeated social media comments | The feed remembers every phrasing it has used and each call site takes its least-used option next; each commenter writes in a consistent style of their own (casual lower case, emoji, measured, hype, ...); no line appears twice anywhere in the feed (a repeat is reworded in its writer's style or dropped); the phrase pools roughly tripled. In the test feed: 1,195 comments, 1,175 with a phrasing used once, none repeated word for word. |
| Bot-lane metas in the patch notes (incl. mage APC) | A seventh meta group, the bot lane: marksman, mage APC, hyper-carry, lethality / early-burst, bruiser, utility carry (named per game: Gold lane, Farm lane, Dragon lane, safe lane). Patches move it like the other groups (drifting back to marksmen); the patch notes and the meta panel show it. Drafts follow it: in a mage APC meta bot laners draft mid-lane mages (42–79% of bot picks against 0–8% normally) using part of their carry comfort; hyper-carry / lethality / utility metas favour those marksmen; kill share and farm shift a little. |
| Real-life history, awards, GOAT and Hall of Fame | See `DATA_PROVENANCE.md`: 490 real events and 12 awards from before the save. A real player's past titles, finals and awards are honours from day one (player page: "Before the save"), count toward legacy (shown as "real past") and the GOAT ranking; legends who retired before the save — in the database or not — are inducted into the Hall of Fame on their real records (e.g. Wolf, Bang, Bengi); History has a "Before the save" tab with the real champions by year. Faker's legacy is 203, 163 of it from the real career. |
| MOBA fights round 2 | Three more changes were tried and measured over 120 games per MOBA — winners backing off from won fights, weaker comeback gold, coordinated focus fire — and none beat the current tuning (each made LoL or HoK worse), so the engine is unchanged: winners die 61–84% as often as losers (pro games ≈ 50%). |
| UI round 2 | Light theme (and "follow phone") in Settings and on the title screen; first-career tips on Home (eight, one at a time, "Show me" opens the screen); search from the top bar (players, teams, organisations, events, characters, screens; accents and case ignored); wide-screen layouts as above. |
| Live match depth | MOBA coach calls in your live games — play safe, force a fight, play for objectives, split-push, go for the end — three per game, each holding 2½ minutes: the game is played again from that second with the call in effect (everything before stays identical, tested) and the view keeps its place. A draft assistant in your live drafts suggests three bans or picks with the reasons (comfort, patch strength, lane counters, synergy, the opponents' comfort picks, threats to yours, the bot-lane meta); tap one to take it. |
| Business side | A Business screen: sponsor offers (now also "performance clauses": a lower fee for a much bigger trophy bonus), content creators to sign (audience, platform, style, monthly fee; fans, reputation and revenue; room grows with the venue), a venue to build (studio, arena, stadium: build cost, upkeep, rentals) and fan events (meet & greet, watch party, charity stream, tour abroad, fan festival, stadium showcase) with forecasts, a two-week gap, fatigue and morale for the players. AI organisations sign creators, hold events and build venues by the same rules; everything is in the monthly accounts. |

Last full run (25 Nov 2026, `./gradlew assembleDebug testDebugUnitTest`): 82 tests, 79 passed, 3 skipped (the
environment-gated soak, cross-world determinism and integrity runs), 1 failed — `MgmtTest`'s count of amateur clubs a
player short at the end of the last day (4, limit 3). The check now runs the next morning's refill and counts the clubs
still short (0); `MgmtTest` re-run and passes. New tests: `HistoryTest`, `LiveDepthTest` (coach calls change only what
comes after them; draft assistant; business), `ScreenshotWideTest` (foldable shapes, light theme), the bot-lane meta
draft test in `MetaTest`, and the social feed's no-repeat and phrasing-variety checks in `SocialTest`.


## Fourteenth follow-up: crash on opening the app

The app closed immediately on start on the phone. Cause: the game data (titles, characters, maps) loads on a
background thread at start-up, while the title screen's first frame already read the list of games (the game tags
in its header). It had always been a race the data won; the real-history file added in the thirteenth follow-up was
read first and made the data later, so the first frame lost it and the app stopped with
`UninitializedPropertyAccessException: lateinit property BY_ID has not been initialized` (reproduced on the JVM by
starting the app with the data unloaded and slow to load). Fix: nothing is drawn until the game data is loaded (a
short loading screen); the history file loads once, after the core data; a failure while loading shows the error on
screen instead of closing the app, and the app keeps the last crash on the phone and shows it (with a Copy button) on
the title screen at the next launch. `LaunchTest` opens the real activity (phone, the foldable's inner screen,
its cover screen sideways, rotation) and the slow, unloaded start-up.

## Fifteenth follow-up: drafts that adapt to the earlier games

Teams used to draft every game of a series as if it were the first: the same bans, the same comfort picks.
Now each draft reads what the earlier games showed (`sim/SeriesIntel.kt`), on top of everything it already
weighed (patch strength, comfort, counters, synergy, the team's ban style, scrims, preparation, Fearless):

| Step | What changes from game 2 |
|---|---|
| Bans | What the other team played and won with is a ban target, more when it won with it twice, more when its player rated well on it; the best-working one is named "the heart of their comp". A ban that did not stop you losing gives its slot to what beat you (an overpowered character stays banned); a ban in a game you won tends to stay. |
| Picks | The winner keeps what worked (more when the player did well on it); the loser moves off what lost (less for a signature pick); a team takes away a character the opponent won with when one of its players can play it, takes a comfort pick the opponent banned before and left open, and picks answers to what the opponent keeps playing. |
| Who adapts more | The team that lost the last game adapts more than the winner, more again when behind in the series; better analysts and coaches read it better. |
| Across matches | Each team keeps a short, fading memory of the characters it showed in recent official series (`Team.shown`); opponents' bans lean a little toward what that team has been winning with, so league bans follow the opponent instead of being the same every week. |
| Shown | The match report's draft recap marks adapted steps with ↻ and the reason ("the heart of BLG's comp: won 2 games with it", "switched off Azir after the loss", "replaces last game's Kalista ban"); the live draft shows the other side's last adapted step and what each side has won with so far; the draft assistant gives the same series reasons. |

Measured in `DraftAdaptTest` (50 Bo5 series per MOBA without Fearless, same teams drafted again without memory): a
character a team won with twice is banned in the next game 70–97 % of the time (15–29 % before); last game's
winning picks are banned 40–75 % (18–25 %); the loser keeps 29–33 % of its picks (about 52 % before) while the
winner keeps 50–57 %; bans repeated from the last game fall from 27–44 % to 20–31 %. In a Bo1 league, the
opponent's best recent character is banned 60–68 % of the time (46–50 % without the memory).

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
* `DraftAdaptTest` — drafts adapt within a series (bans on what won, dropped bans, winner keeps / loser
  switches, measured against the same series without memory), league bans follow each opponent's recent
  picks, the real series path records readable notes, same inputs give the same draft, the memory stays small.
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
customId`, `World.customComps`, `CompTier.CUSTOM` at the end, `GameRecord.draftNotes`, `Team.shown`), so existing saves load. Replays made
before this upgrade (v1) still play in the previous event view; their analysis shows "unavailable"
rather than invented heatmaps.

## Known limitations

* Not run on a phone or emulator here (no hardware acceleration in this environment): frame rate,
  battery, ANR behaviour and the time to simulate a season on devices are unmeasured (desktop JVM
  timings only). The viewer's toggles (abilities, minions,
  paths, names) serve as a reduced-effects mode.
* Kills in LoL (~28 per game in league matches) match the pro average (~25–30); deaths are within
  about ±10% across positions (a per-position calibration of damage taken does part of that, see
  `MobaEngine.DEATH_CAL`). Kills by position follow the meta's carry (bot-side carry, then mid);
  in Dota 2 the mid still takes nearly as many kills as the safe-lane carry. Dota 2
  games are a few minutes longer than average. Occasional outlier games (a hero dying very often) remain and are
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
