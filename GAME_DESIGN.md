# Game Design — Ultimate Esports Manager

Consolidated mechanics as implemented. Fixed requirements from the brief are marked **[spec]**;
tunable design choices are documented in DESIGN_DECISIONS.md.

## 1. Scope

* **Exactly seven titles [spec, 19 Nov 2026]** in two subfamilies: five MOBAs — S220 Mobile Legends:
  Bang Bang, S221 League of Legends: Wild Rift, S222 Honor of Kings, S223 League of Legends, S224 Dota 2 —
  and two tactical 5v5 bomb-defusal shooters — S232 Valorant, S234 Counter-Strike 2. No other videogame
  or sport exists in the game (no battle royale, hero shooters, mobile Valorant, RTS, cards, ...).
* Competitive inputs **[spec]**: touchscreen for MLBB, Wild Rift and HoK; mouse & keyboard for LoL, Dota 2,
  Valorant and CS2; no controller titles. All 5v5. Verified by `tests/model.test.ts`.
* Formats **[spec]**: MLBB/Wild Rift/HoK BO3/BO5/BO7; LoL BO3/BO5/BO5; Dota 2 BO3/BO3/BO5; Valorant and
  CS2 BO3/BO3/BO5 (standard/playoffs/final). A series stops when a team reaches the required wins; one
  map/game is one series game.
* The wider source Bible's other sports and titles are inspirations for **management mechanics only**.
  Old saves from the 30-title edition are converted (see DESIGN_DECISIONS.md → Seven-title edition).
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
style, role coverage (front line, damage, support), class counter matrix vs enemy picks, comfort.

## 6. Engines

Exactly two engine families **[spec]**:

| Engine | Titles | Model |
| --- | --- | --- |
| A: 5v5 MOBA | MLBB, Wild Rift, HoK, LoL, Dota 2 (five parameterised adapters) | Tick simulation: lane duels → gold/CS (denies in Dota), ganks & counter-ganks, title-specific objectives (MLBB Turtle/Lord, Wild Rift & LoL dragons/Herald/Baron/Elder with different timings, HoK Tyrant/Overlords, Dota Roshan/Aegis/Tormentor/Wisdom runes), teamfights with gold leads, comeback bounties, respawn windows converting fights into structures, title-specific structure layouts and core durability. **Dota 2 buyback** (net-worth cost, cooldown, blunts high-ground pushes). A game ends only when the core (Base Crystal / Nexus / Ancient) is destroyed. Fresh draft every game; side choice: coin toss, then the previous game's loser picks first. Fearless only as an optional named LoL preset |
| B: 5v5 tactical shooter | Valorant, CS2 | Rounds with buy logic (Valorant credits, CS2 money with loss bonus and saved equipment), utility & info (Valorant agent abilities; CS2 grenades, no abilities), entry duels, trades, man advantage, Spike/bomb plant, retake, defuse, clutches, KAST, multikills, halftime side swap. First to 13; Valorant overtime alternates sides each round until a two-round lead; CS2 overtime in 6-round blocks (3 per side; 4 wins take the block, 3-3 another). Map veto before the series |

Every game records title-appropriate statistics, ratings (≈1.00 average), MVP, draft and commentary.
Series keep every game.

## 7. Competitions

* Formats per title follow the specification tables **[spec]**; editable per title (Database Editor) and per
  competition (`formatOverrides`).
* Stage types: round robin, double round robin, groups, Swiss, single elimination (byes), double elimination
  (4/8, no bracket reset — finals start 0-0), page playoff.
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
* **Treasury**: the organisation's cash backs a fundable wage authority (revenue + half of cash − fixed costs)
  that is allocated to departments (divisions) as wage budgets. Finances → Treasury shows cash, allocated and
  unallocated authority, salary commitments, per-department payroll/budget/headroom, lets permitted roles set
  or move budgets (logged), and forecasts 12 months of committed cash with shortfall warnings. User
  signings, renewals and trades must fit the department's budget.
* **Training sessions**: every week has 14 practice blocks (morning/afternoon). The coaches build the
  timetable from the development objective (14 focuses), the calendar (light sessions before a match, the
  match in the afternoon, review the morning after, a rest day, tapering before playoffs/finals/international
  events), fatigue, the patch and their own capacity; a manager can plan their own weekly template instead.
  Session types: individual work, tactics & set plays, scrims, scrim/VOD review, hero/agent pool, maps &
  sides, opponent preparation, patch adaptation, mental skills, rest. Practice is scarce: an organisation's
  practice rooms (from the training facility) are shared by all its divisions, coaches have weekly capacity
  (beyond it sessions run unsupervised at half value), and match days limit what can run. **Cancelled
  sessions grant nothing.** Quality still comes from coaching staff and facilities; diminishing returns after
  8 weeks on one objective; fatigue & health setbacks follow the load actually trained.
* **Scrims** are practice matches booked with real partner teams of the same title that want a scrim in the
  same block: similar strength first, same region preferred, the next three weeks' opponents avoided (or only
  teams outside the league). Partners can cancel at short notice; clubs with an academy fall back to internal
  scrims. Scrims give practice sharpness (a small match-performance edge), meta knowledge and chemistry —
  more against stronger partners and when reviewed — and leak strategy: both sides gain intel on each other
  for 35 days (a small preparation edge if they meet officially). The Training page shows the week as it
  happened (held, cancelled with reasons, unsupervised, scrim partners and results) and the intel.
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

* **Competition registration**: contracts and registrations are separate. Each competition locks up to
  eight registered players per team three days before it starts (your standing plan on the Team page, or
  the coach's choice); only registered players can play; 2 changes per competition during transfer
  windows; emergency registrations when injuries leave fewer than five. Tactics warns about unregistered
  starters; the inbox reminds you before splits lock and when a signing still needs registering.

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
* **Delegation** (Settings): signings, offers for your players, contract renewals, staff hiring, wage
  budgets and competition registration can be handed to your staff even when your role covers them, and
  lineups, drafts and training per team to the head coach; "Delegate everything" makes a hands-off career.
* **Spectator mode**: instead of (or after) managing, follow any number of teams — or every team — and
  simulate through the seasons while every club's own staff make all decisions (lineups, drafts, training and
  scrims, signings, contracts, staff). Followed teams get full match detail, match reports, training
  timetables, a dashboard, optional stops before their matches and live viewing of any match. Settings →
  Game mode switches between managing and spectating at any time.
* **Links everywhere**: team, player, organisation, competition, staff and title names are tappable — in
  tables, match screens, inbox messages, news, commentary, histories and notes.

## 10. History, awards & legacy

* Per competition: champion, placements, MVP (in-competition ratings), Finals MVP.
* Per season & title: Player of the Year, best by role, Rookie of the Year, Most Improved, statistical
  leaders (title-specific), Team of the Year, Coach of the Year; Organisation of the Year (multi-title).
* Legacy (GOAT) = prestige-weighted achievements (stamped with title prestige at the time) + peak + longevity
  + volume; per title and cross-title; Hall of Fame threshold 110 for retired players.
* Season history snapshots (champions, standings, popularity), team season records, org events, retired
  archive for long saves.

## 11. Supporters, audiences & rivalries

* Every organisation's followers form six segments that care about different things — local loyalists
  (identity, home-country players, rivalry results), winning-focused fans (results), player fans (stars —
  they arrive and leave with them), style enthusiasts (aggressive, entertaining play), youth & community
  followers (academy graduates' debuts) and legacy supporters (finals, trophies, history). They react only to
  recorded events; a glamorous signing can attract attention while frustrating identity-minded fans.
* Followers (reach) and paying supporters (conversion by mood) are separate; merchandise, sponsorship value,
  audiences and reputation follow them. Fans never change a player's ability. Organisation page →
  Supporters shows segments, moods, paying supporters, a follower history and recent reactions; the inbox
  warns when a segment turns unhappy.
* Rivalries between divisions grow from finals, playoff series, deciders, upsets, star transfers and
  same-country derbies in multi-country leagues, and fade when the teams stop meeting. They raise audiences,
  fan reactions and match pressure (composure), never ability. Team page → History → Rivalries; match
  screens show a rivalry badge with the head-to-head and the series audience.

## 12. Saves & platforms

* Five named manual save slots plus one autosave per career **[spec: user request 19 Nov 2026]**. The
  header Save button (and Ctrl+S) writes the current slot; the first save asks for a slot; overwriting a
  slot that holds another career needs confirmation. Autosave runs after every advance inside the
  simulation worker; Android also autosaves when the app is backgrounded. Export/import JSON; corrupt
  newest copies fall back to the previous one.
* Platforms: the web build (dev server, static hosting, or the single-file portable build) and an Android
  APK built from the same bundle with Capacitor (offline; system Back; safe areas). See ANDROID_BUILD.md.
