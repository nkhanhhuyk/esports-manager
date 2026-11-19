# Game Design — Ultimate Esports Manager

Consolidated mechanics as implemented. Fixed requirements from the brief are marked **[spec]**;
tunable design choices are documented in DECISIONS.md.

## 1. Scope

* Exactly two families **[spec]**: MOBA (13 titles) and Shooter (17 titles). No RTS, fighting, card or
  autobattler titles. Deadlock is a Shooter with hybrid MOBA mechanics; Brawl Stars is a MOBA with its own
  mode-based engine; Overwatch Rush is distinct from Overwatch **[spec]**.
* Competitive inputs are deliberate assignments **[spec]** (see `src/data/titles/catalogue.ts`, verified
  by `tests/model.test.ts`). Legacy titles remain playable and can be revived by popularity drift.
* Fictional organisations, players, staff, characters, maps and competitions; real countries, languages
  and regions **[spec]**.

## 2. World

* 20 esports regions (NA, LATN, LATS, BR, EUW, EUE, CIS, MENA, AFR, KR, CN, PCS, JP, VN, TH, ID, PH, MYSG,
  SA, OCE) grouped into 4 macro regions, 95 countries with culture-specific name pools (40 cultures,
  eastern name order where appropriate) and per-family scene strength (MOBA / shooter) that drifts.
* Title popularity is a title × region matrix (fictional starting values) that drives league creation,
  player nationality distribution, salaries, sponsorship, media revenue and talent generation, and drifts
  every season (booms, declines, revivals).
* Per title, leagues are created for the strongest single regions plus combined macro-region leagues for
  smaller scenes (so small countries still compete). World size controls depth (Tiny/Small/Standard/Large).
  Strong regions get a Challenger tier with promotion/relegation (unless franchised).
* Detail levels: user matches, internationals, chosen titles or chosen leagues can be simulated in full
  detail (box scores + commentary kept); everything else is aggregated. Results are identical either way.

## 3. Organisations & teams

* An organisation owns divisions (teams) across titles, with organisation-wide identity, reputation, fans,
  prestige, owner (type, wealth, patience), board strategy (win-now, balanced, youth, moneyball, prestige,
  expansion), facilities (6 kinds, levels 1–10), sponsors, ledger and monthly accounts.
* Each division has its own roster, lineup, staff, training plan, preferences, chemistry, Elo, meta
  adaptation, salary budget, season records and trophies.
* Supported structures: main teams, academy teams (attached to a parent, can play in Challenger leagues or
  as development squads), league slot purchases (expansion into new titles), division closures (slot sold
  to another organisation), rebrands, relocations, acquisitions after financial distress. History and
  trophies survive all of these.

## 4. Players

* Identity: legal name, handle (unique), birth date, nationality (+ optional second), languages.
* **86 attributes [spec]**: Mechanical 10, Combat 10, Tactical 12, Mental 10, Teamwork 6, Knowledge 7,
  Development 7, MOBA specialist 10, Shooter specialist 14. 0–100 with the specified interpretation bands.
  Derived summaries (Mechanics, Combat, Macro, Game Sense, Mentality, Teamplay, Strategic Intelligence,
  Development Potential, specialisms) are computed, never stored.
* **Three input proficiencies [spec]** (Touchscreen, Mouse & Keyboard, Controller), trainable, separate from
  attributes. Effective mechanic = `M × (0.65 + 0.35 × P/100)` scaled by each attribute's input sensitivity
  (1.0 for execution skills such as Micro Control, Accuracy, Movement, Combo Execution; 0 for mental).
* Proficiencies (0–100): title familiarity, role proficiency per title/role, character proficiency,
  map familiarity, weapon-class proficiency.
* Current Ability (CA) = effectiveness E in the primary title/role; hidden Potential (PA) with scouting
  uncertainty; form (−10..10), morale, fatigue, injuries; personality (ambition, loyalty, temperament, ego,
  greed, controversy, big-match pressure); development trajectory (early, steady, late, volatile, resurgent)
  with peak and decline ages; coaching aptitude for second careers.
* Career history, per season/title/team stat lines, awards with legacy points, relationships, CA history.

## 5. Effectiveness & match performance

`E = 0.40C + 0.30S + 0.15T + 0.10R + 0.05P` **[spec]** where C/S are weighted core/specialist attributes after
input adaptation (weights = engine base × title tilt × role emphasis × title specialist relevance), T title
familiarity, R role proficiency, P character/equipment proficiency.

The same structure is evaluated for 16 gameplay phases (laning, jungle, skirmish, teamfight, macro, vision,
aim, entry, utility, IGL, clutch, objective, survival, support, movement, economy). Team phase strength is
the role-involvement-weighted mean, multiplied by chemistry (team phases), head-coach quality, meta
adaptation, opponent preparation (analyst + analytics facility) and a per-game team form factor; each player
also has condition (fatigue, form, morale, injury), map familiarity, big-match pressure (composure +
personality) and consistency-driven day noise.

Draft/composition evaluation: meta strength (patch), style profile vs patch-favoured styles and team
style, role coverage (hero shooters: ideal class counts), class counter matrix vs enemy picks, comfort.

## 6. Engines

| Family | Titles | Model |
| --- | --- | --- |
| Lane MOBA | LoL, Dota 2, MLBB, HoK, Wild Rift, AoV, Vainglory, MSW, SMITE 2, Predecessor, HotS, Unite | Tick simulation: lane duels → gold/CS/denies, ganks & counter-ganks, title-specific objectives (stacking souls, push buffs, Aegis extra life, execute buffs, XP runes, score legendaries, siege objectives), teamfights with gold/level leads, comeback bounties, respawn windows converting fights into structures, title-specific structure layouts and core durability. Economies: gold, shared XP + talent tiers (HotS), score on a fixed clock (Unite) |
| Hybrid | Deadlock | Lane MOBA engine with souls economy, 4-lane preset, Guardians/Walkers/Shrines/Patron, Urn & Mid Boss, fights resolved with shooter aim |
| Brawl | Brawl Stars | 3v3 games on Gem Grab, Brawl Ball, Heist, Hot Zone, Knockout, Bounty; map openness & mode change class value; sets = first to 2 game wins, Bo3/5/7 sets |
| Tactical | Valorant, Valorant Mobile, CS2, R6 Siege | Rounds with buy logic (credits/money/none), utility & info, entry duels, trades, man advantage, plant/retake/defuse, clutches, KAST, multikills, halftime, overtime (win-by-two / MR3 / capped) |
| Hero | Overwatch, Marvel Rivals, Paladins, OW Rush | Fight-by-fight with ultimate economy & hero swaps; Control, Escort, Hybrid, Push, Flashpoint, Siege; per-map mode rotation and loser picks |
| Arena | CoD, CoD Mobile | Hardpoint (rotations, hill time), Search & Destroy (one-life rounds), Control (attack/defence), Domination |
| Battle royale | Apex, PUBG, PUBG Mobile, Fortnite, Warzone, Warzone Mobile | Full lobby: drop spots (contested drops), loot, shrinking zones with rotation timing and zone damage, positional encounters, third parties, beacons/Gulag/reboots, building, final circle; placement + kill points, match-point finals (Apex preset) |

Every game records title-appropriate statistics, ratings (≈1.00 average), MVP, draft and commentary.
Series keep every game.

## 7. Competitions

* Formats per title follow the specification tables **[spec]**; editable per title (Database Editor) and per
  competition (`formatOverrides`).
* Stage types: round robin, double round robin, groups, Swiss, single elimination (byes), double elimination
  (4/8, no bracket reset), page playoff, BR points (with optional match point).
* Season template per title on one shared calendar: Split 1 (Jan) → playoffs (Mar) → Mid-Season
  Invitational (late Apr) → Split 2 (Jun) → playoffs (Aug) → World Championship (late Sep–Oct) → rollover
  (22 Nov). International slots are allocated by regional strength; Worlds seeds by Split 2 champion and
  circuit points. Prize pools, circuit points, prestige weights per competition.

## 8. Management systems

* **Contracts**: salary, length, signing bonus, trophy bonus, prize share, buyout, option years, termination
  fee, loans (wage split). Validation against league policy (min/max salary, min age, hard cap, roster
  limits). Expected salary depends on ability relative to the league's reference level, title popularity,
  region economy, fame and greed.
* **Transfers**: free agents, bids with club valuation & buyouts, counters, trades (where permitted), loans,
  promotions/demotions inside the organisation, releases, extensions, cross-title signings (title switch
  keeps attributes; familiarity transfers by title similarity and shared functional classes). Windows:
  20 Nov–12 Jan and 10 May–1 Jun; free agents anytime.
* **AI clubs** act weekly in windows and immediately when short-handed: release surplus, promote academy
  talent, evaluate needs by role, scan free agents (sometimes cross-title) and players at other clubs,
  score candidates by strategy (win-now, youth, moneyball, prestige…), respect budgets, bid on user players
  (inbox offers with accept/reject), renew key contracts, list unhappy players.
* **Transfer negotiations**: bid a fee (optionally with a sell-on clause) → the club accepts, counters or
  rejects, losing patience after repeated low bids; release clauses force acceptance → agree personal terms
  (salary, seasons, signing bonus, release clause) with the player, who counters with their price → the
  deal completes. Players in their final six months can agree pre-contracts (free moves next season).
  Sellers set asking prices, offer players to clubs and counter incoming bids.
* **Draft picks & restricted free agency**: leagues with rookie drafts give every team two picks per draft for
  this and next season; picks trade alongside players and cash (valued by projected slot, round and how far
  away the draft is) and the holder makes the selection. In restricted-FA leagues clubs may retain expiring
  players aged ≤ 24 with a one-season qualifying offer (+10% salary), and may match rivals' offer sheets.
* **Finance**: division commercial value (title × region × popularity × roster size) drives media and
  sponsorship; merchandise from fans; franchised league revenue share; prize money; salaries, staff,
  facilities upkeep, operations; distress → owner injection or acquisition; sponsor renewal.
* **Training**: team focus (14 options), intensity, scrims, individual focus, opponent prep; quality from
  coaching staff and facilities; diminishing returns after 8 weeks on one focus; fatigue & health setbacks.
* **Progression**: weekly attribute growth toward potential by age curve and trajectory; mechanical decline
  after decline age; mind/knowledge grow longer; yearly potential revisions and resurgences; proficiencies
  grow by play and training (input proficiency included).
* **Patches & meta**: per-title version history; minor balance patches (~every 3 weeks) and majors (season
  start, mid-season): character strength, favoured styles, map pool rotation, objective values, rule eras.
  Teams lose meta alignment and recover via players' Patch Adaptation/Meta Understanding and staff.
* **Staff**: 9 roles with 16 staff attributes, title familiarity, contracts, development, retirement, hiring,
  ex-players become coaches/analysts/scouts (knowledge transfers).
* **Scouting**: knowledge per player for the user; hidden potential shown as a range narrowing with
  knowledge and scout judgement; focus list.
* **News & inbox**: transfers, upsets, champions, awards, retirements, prospects, patches, finance and
  organisation events; actionable inbox offers and board messages.

## 9. Career roles & the board

* Roles: Head Coach & GM (all football-manager style powers), Head Coach (lineups, drafts, live matches,
  training), General Manager (transfers, contracts, staff, budgets), Sporting Director (transfers, contracts,
  staff, expansion), Owner (finance, facilities, expansion, staff), Commissioner (sandbox, everything, no board).
  Actions outside the role are disabled with an explanation; AI staff perform them (AI GM recruits and renews,
  AI head coach picks lineups and drafts).
* Board: objectives per division each season (league finish based on squad strength rank and board strategy,
  World Championship qualification for contenders, promotion for strong Challenger sides, a cash floor).
  Confidence = 0.7 × previous + 18 + objective/trophy deltas; below 20 at the season review means dismissal
  and a choice of job offers from organisations of similar or lower reputation.
* During the season the board reacts to every finished competition involving your teams: trophies are
  praised, league splits are compared with the objective (praise, concern, alarm), months in the red cost
  confidence. A poor run triggers an **ultimatum**; failing again that season ends the job mid-season and
  time stops so you can choose your next move.
* You work on a **manager contract** (two seasons on appointment); at its end the board renews it only if
  confidence is at least 40, otherwise you leave. Excellent results attract **job offers** from bigger
  organisations while you are employed: accept to move immediately, or stay (the board appreciates loyalty).
* National-team coach job can be held in parallel (Nations Cup).

## 10. History, awards & legacy

* Per competition: champion, placements, MVP (in-competition ratings), Finals MVP.
* Per season & title: Player of the Year, best by role, Rookie of the Year, Most Improved, statistical
  leaders (title-specific), Team of the Year, Coach of the Year; Organisation of the Year (multi-title).
* Legacy (GOAT) = prestige-weighted achievements (stamped with title prestige at the time) + peak + longevity
  + volume; per title and cross-title; Hall of Fame threshold 110 for retired players.
* Season history snapshots (champions, standings, popularity), team season records, org events, retired
  archive for long saves.
