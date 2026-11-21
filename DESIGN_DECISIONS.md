# Design decisions, formulas and balancing constants

Fixed by the specification (not changed): the **seven-title registry** (S220 MLBB, S221 Wild Rift, S222
Honor of Kings, S223 League of Legends, S224 Dota 2, S232 Valorant, S234 Counter-Strike 2) with its input
assignments and series formats, the 86-attribute framework (62 core + 10 MOBA + 14 shooter), the three
input proficiencies, `Effective Mechanics = M × (0.65 + 0.35 × P/100)`,
`E = 0.40C + 0.30S + 0.15T + 0.10R + 0.05P` and the similarity guideline bands. Everything below is a
proposed, tunable default — **not** a claim from the source PDF.

## Seven-title edition (19 Nov 2026)

* **Scope.** The 23 other titles of the earlier edition and their engines (battle royale, hero/arena
  shooters, Brawl sets, MOBA/shooter hybrid) were removed. Internal title IDs (`mlbb`, `wildrift`, `hok`,
  `lol`, `dota2`, `valorant`, `cs2`) are kept for save compatibility and mapped explicitly to the source IDs
  through `TitleDef.sourceId`.
* **Drafts.** Ordinary per-game drafts and bans for all five MOBAs. HoK's former team-scoped restriction is
  gone (any global-pick rule would be a named competition preset). LoL "Fearless" exists only as an
  optional named preset (`world.rulePresets.lol.fearless = 'global'`), never on by default.
* **Side choice (MOBA).** Game 1 by seeded coin toss; afterwards the previous game's loser chooses and takes
  the first-pick side. The source states this for MLBB; applying it to all five MOBAs is a proposal.
  Deterministic, so live drafting and quick simulation agree.
* **Dota 2 buyback (proposal).** Cost `200 + 60 × minute` gold, 8-minute cooldown. A dead hero of the
  losing side may buy back when its high ground is threatened (a lane one structure from open) or after
  75% of the average game length, if it keeps 1,500 gold in reserve; probability `0.35 + clutch/250`.
  Each buyback reduces the winner's push window by 0.9. Recorded as stat `bb` and a `buyback` event.
* **MOBA victory.** Only core destruction (Base Crystal / Nexus / Ancient, with `coreHits` durability)
  ends a game; kills never do. The engine's economy is gold for all five titles.
* **Shooter overtime.** Valorant: at 12-12 sides alternate every round until a two-round lead. CS2: MR12,
  then 6-round blocks (3 per side): 4 wins take the block, 3-3 starts another. Tested by score arithmetic.
* **Old saves.** Save version 5. A save that references removed titles is converted on load after the
  untouched original is stored (`backups[<id>#pre-7-title]`, exportable from the Load screen): removed
  titles' leagues, competitions, fixtures, records, picks, offers and divisions are deleted; every player
  and staff member keeps their identity and history and moves to the closest remaining title
  (`REMOVED_TITLE_MAP`, e.g. AoV → HoK, Valorant Mobile → Valorant, R6 → CS2; starting title familiarity
  `15 + 55 × similarity`, role familiarity `20 + 40 × similarity`) — players as free agents; past stats,
  awards and trophies keep the original title IDs and render as read-only "(archived)" labels. A save with
  only removed titles is refused with an explanation (backup still exportable). The user is informed in
  the inbox; if their organisation keeps no divisions they become free to take a new job.
* **Save slots.** Five manual slots (`slot-1` … `slot-5`) plus one autosave per career (`<saveId>`). A slot
  copy stores `manualSlot` and `careerId`; the world remembers `meta.activeSlot` for the Save button, which
  never overwrites a slot that now holds another career without asking.
* **Competition registration (proposal).** Registration is separate from employment. When a competition
  locks (3 days before it starts) each entrant registers up to `min(league roster max, teamSize + 3)` = 8
  players: the team's standing plan if it has at least five rostered players, otherwise the coach's choice
  (best lineup + most useful substitutes). Only registered players can play in that edition; fixture
  lineups are filtered to registered, available players. After the lock: **2 changes per edition** while a
  transfer window is open (swap, or replace a departed player); **emergency registrations** at any time
  when fewer than five registered players are available (recorded as emergencies, so teams never
  forfeit). A player can be registered for only one team per edition. AI clubs register signings when a
  place is free; the user is told when a signing is not registered and gets a reminder 7 days before a
  league split locks. Editions created before registration existed have no restriction.
* **Android export.** WebViews ignore `<a download>`, so on Android exported saves are written to the app
  cache (`@capacitor/filesystem`) and offered through the system share sheet (`@capacitor/share`) — no
  storage permission needed. Import uses the normal file picker.
* **Android.** Capacitor 8 (`dev.uem.esportsmanager`), web build unchanged; `SystemBars.insetsHandling:
  'css'` with `--safe-area-inset-*`; system Back navigates in-app history and exits from Home/Welcome;
  autosave on `pause`. The default `INTERNET` permission is kept (template default, no network use).

## Data & realism

* **Mechanics are simplified, editable presets**, not official real-world rules or patch contents. Objective
  names used where well known (Baron Nashor, Roshan, Lord/Turtle, Tyrant/Overlord); timings are presets.
* **All characters, maps, POIs** are procedurally generated fictional names (seeded per title).
* **Popularity matrix** is a fictional-universe starting point (e.g. MLBB strongest in ID/PH, HoK in CN,
  CS2 in EUE/CIS/BR) and drifts every season.
* Double elimination has **no bracket reset** (upper-bracket finalist can be eliminated by one GF loss);
  finals start 0-0 (no upper-bracket game advantage — matches the Dota 2 rule in the spec).

## Effectiveness model

* Core weights by group: Mechanical 1.0, Combat 1.0, Tactical 0.95, Mental 0.9, Teamwork 0.8, Knowledge 0.8;
  Development attributes only via Consistency (0.6) and Stamina (0.3).
* Specialist relevance defaults: own family = 1, other family = 0 (no Last Hitting in Valorant/CS2, no recoil
  in MOBAs), overridden per title (e.g. Last Hitting 1.2 in Dota 2, 0.5 in MLBB/HoK; Recoil 1.3 in CS2).
* Role emphasis multiplies attribute weights (full in E, square-rooted inside phase ratings).
* Input sensitivity per attribute: 1.0 Micro Control/Accuracy/Combo/Movement/aim mechanics; 0.5 Reaction
  Speed; 0.3–0.6 for spacing/timing/targeting/peeking; 0 for mental, tactical and knowledge attributes.
* Conditions: fatigue −12% at 100 (power 1.5), form ±3%, morale ±3%, injury −12%, map familiarity ±3%,
  big-match pressure ±2.5% in finals, consistency noise σ = 3.5% × (1.4 − Consistency/100) (×1.7 volatile).
* Team multipliers: chemistry 0.95–1.05 on team phases, head coach 0.985–1.015, meta adaptation 0.97–1.01,
  opponent preparation up to +1.2%, **team day-form σ = 3%** (`TEAM_FORM_SD`).

## Engine calibration (scripts/calibrate.ts)

Target: per-game win rate of the stronger side ≈ 55% for <1 rating point, 60–65% for 1–3, 70–85% for 3–5,
85–95% for >5 (large gaps are usually cross-region). Constants:

| Engine | Key constant |
| --- | --- |
| MOBA | teamfight logit = ln(powerA/powerB) × 4.5; lane edge /9; objective control /10; pick-offs /9; structure pressure 0.42 × tick / avgLength; Dota buyback (see Seven-title edition) |
| Tactical | duel scale 26 rating pts/logit (`DUEL_SCALE`); round swing σ 0.32; buy quality ×1.7; utility /60, info /80, IGL /80; defender edge per title |

Latest calibration (tiny worlds, 6 games per pairing, before the seven-title edition): LoL 3–5 pts 75%;
MLBB 73%; Dota 78%; Valorant 85%; CS2 89%. Tactical shooters compound most (≈22 rounds). The balance test
(`tests/engines.test.ts`) still requires the stronger side to win >60% for all five tested titles.

Core durability (`coreHits`): 2 for MLBB, Wild Rift, HoK and LoL; 3 for Dota 2.

## Economy

* Expected salary = `baseSalary(title) × (0.4 + 0.6·pop) × regionEconomy × exp((CA − leagueRefCA − 1)/12) ×
  (0.9 + 0.25·fame) × (0.9 + 0.25·greed)`, floor ≈ 4–6K. `leagueRefCA = 57 + 19 × (0.6·pop + 0.4·scene)/100`
  anchors pay to the strength of the scene (strong leagues are not automatically bankrupt).
* Staff salary = `baseSalary × 0.11 × (0.4 + 0.6·econ) × exp((quality − 55)/15)`.
* Division commercial value V = `baseSalary × rosterSize × econ × (0.4 + 0.6·pop)` (academies 25%).
  Monthly media = `V × 0.75 × (0.5 + rep/100) / 12`; sponsorship deals total `V × 0.065 × (0.5 + rep/100)`
  per month; merchandise = fans × 0.012 × econ; franchised league share = V × 0.25 / 12.
  Costs: salaries, staff, facility upkeep (level^1.5 × 350), operations (V × 0.06/12 + base).
  Result (economy.ts, Small world): median org slightly negative before prizes (−10% of costs), top decile
  +26%, ~2% in debt after one season; distress triggers owner injections or acquisitions.
* AI salary budgets (set each rollover) = `V × (0.85 + rep/250) × strategy + cushion`, strategy multipliers
  win-now 1.2, prestige 1.15, expansion 1.05, balanced 1, youth 0.85, moneyball 0.75; cushion = 8% of cash,
  bounded to [−30%, +35%] of V. Budgets never compound season over season (that previously let rich clubs
  stack every star and win 8+ consecutive titles).
* **Organisation treasury** (src/systems/treasury.ts; proposal, editable). Cash, department budgets,
  commitments and the forecast are kept apart: a budget is spending *authority*, not money — cash moves only
  when salaries, fees and bonuses are paid.
  * Fundable wage authority = 12-month projected revenue (sponsor deals until they end + media, merchandise
    and league share at the current rate) + 50% of positive cash − 12 months of fixed costs (staff,
    facilities, operations). Σ department wage budgets ≤ fundable; the difference is the unallocated
    reserve.
  * Raising a budget draws on the reserve; lowering it returns authority but never goes below the
    department's contracted payroll; moving budget between departments nets to zero and may only move the
    source's unused part. Every change is written to the organisation's budget log (last 120 entries).
  * User signings, extensions (only the raise counts) and trades must fit the receiving department's
    budget; AI clubs keep the board budget formula above. A user whose role lacks the *finance* permission
    gets board-set budgets at rollover; roles with it keep theirs.
  * Commitments = salaries owed until each contract's end (season end 22 Nov). The 12-month cash forecast
    uses only agreed income/costs (no prizes, transfers, bonuses or renewals); a projected negative balance
    within three months sends a monthly Finance warning.
* Prize split: 32/18/11/9/6/6/4/4/2… %, org keeps (1 − 0.6 × average player prize share).
* Transfer valuation = expected salary × 1.4 × youth/headroom factor × contract-length factor; clubs add
  +35% for starters, strategy modifiers (moneyball −15%, win-now/prestige +25%), listed −20%.

## Development

* Permanent attributes progress every second week with a double step (same yearly rate, half the work).

* Weekly growth budget = `0.062 × growthRate × (gap/10)^0.85 × ageFactor × trainingQuality × (0.5 + ethic) ×
  sessionDev × focusRepetition × overtraining × playingTime`, converted to ≈110 attribute points
  distributed by focus × trainability × room to grow. `sessionDev = min(1.05, devPoints / 6) × (0.6 +
  0.6·intensity)` where devPoints sums the blocks actually held (individual 1, tactics 0.7, scrim 0.6 ×
  partner quality, pool 0.5, maps 0.5, review 0.3, patch 0.3, prep 0.2, mental 0.2; unsupervised ×0.5). A
  typical in-season coach week scores ≈6; calibrated so a season's mean CA change is within 5% of the old
  flat model (small world: +1.39 vs +1.33, under-22s +2.15 vs +2.08). Title/role/input proficiency training
  is scaled by min(1, sessionDev) — a week without sessions trains nothing.
* **Sessions** (src/systems/sessions.ts): 14 blocks per week. Practice rooms = ceil(training facility / 2)
  per organisation per block (main teams first, then academies by Elo); coaches move a blocked session to
  meeting-room work, a manager's blocked session is cancelled. Coaching capacity per week: head/academy coach
  10, assistant 8, analyst 6, psychologist and performance coach 4, organisation-level staff shared between
  divisions. Match days: afternoon = match, morning light only (rest/prep/review/mental). Training load →
  fatigue `Σload × 0.45 × (0.5 + intensity) − 0.8 × rest blocks − 0.5` per week.
* **Scrims**: per title and block, teams wanting a scrim are paired greedily (strongest first) by score
  `−|ΔElo|/100 − 0.6 (other region) − 3 (opponent within 21 days) − 10 (same league under the outside-league
  policy) − 0.8 (already scrimmed this week)`, minimum −4; 7% short-notice cancellations; internal scrim vs
  an academy/parent team at quality 0.35–0.9, else cancelled. Partner quality `clamp(0.75 + ΔElo/500, 0.45,
  1.25)` × 0.85 cross-region; result by Elo expectation. Weekly: sharpness `scrimRating = 0.75 × previous +
  (Σquality × reviewBoost × 3.5 + 0.8 × tactics)` (review boost up to ×1.5 at one review per two scrims);
  match effect `× (0.99 + 0.02 × scrimRating/100)`; meta adaptation `+ patch + 0.25 scrim + 0.15 tactics`;
  chemistry `+0.012 tactics + 0.01 scrim + 0.015 mental`. Intel lasts 35 days and is worth ×1.005 against
  that opponent; opponent-prep level = min(1, prep blocks / 2) scales the analyst preparation bonus.
* Age curve: full growth until peak−3, 0.7 until peak, 0.25 until decline age; mechanical decline after the
  decline age at `(age − declineAge + 0.5) × 0.028 × declineRate` points per week per attribute.
* Generated potential headroom: ≤17 N(13,7), ≤20 N(6,5), ≤23 N(2.5,3), older N(0.8,1.5).
* Generated familiarity for established pros starts near its ceiling
  (`T = 40 + 58 × experience^0.6`) so playing does not inflate the whole league's ratings.
* Chemistry: +0.25 win / −0.15 loss per series, +0.08 per series played, weekly scrims small gain with mean
  reversion to 60; rollover ×0.85 + 8.
* Proficiency gains per game: title +0.12, role +0.15, character +0.6, map +0.4 (all × learning speed,
  diminishing toward 100).

## Competitive turnover

Dynasty check (scripts/dynasty.ts, 8 LoL seasons, seed `long`): dynasties of roughly 3–5 seasons followed by
turnover (e.g. OBS 2027–31, IL 2032–34). Drivers: aging/decline, contract expiry, poaching by win-now/prestige/rich clubs
(any club; the user's players only via inbox offers), regens, patches and meta adaptation, day-form noise.

## Competition extras

* **Nations Cup**: odd seasons, 3–18 December, six most popular titles, 8 nations by average CA of
  their top players in the title; groups of 4 → single elimination; squads = starters + up to 2 subs.
  National teams belong to a federation pseudo-organisation excluded from finance, market and org evolution.
* **Rookie drafts**: enabled by default in franchised tier-1 leagues (editable per league); prospect class of
  2 × teams + 4 aged 17–19 from the league's region; reverse regular-season order; picks valued by CA +
  0.8 × headroom with evaluation noise; two-season rookie deals at 1.5 × league minimum with a team option.
* **Draft picks**: `world.draftPicks` holds assets for the current and next season's drafts (2 rounds per
  team); past-season picks are pruned at rollover. Value = title base salary × regional economy × 1.4 ×
  (round 1: 1.6 − slot/n; round 2: 0.35 × (1.2 − slot/n)), × 0.85 for next season's draft. Projected slot is
  the original team's strength rank in its league (weakest picks first). The receiving club values picks it
  gives up at 1.1× in trades. If a pick's owner is no longer active, the original team selects.
* **Restricted free agency**: a qualifying offer is one season at max(110% salary, league minimum), so it
  always runs through the next season. Simplification: no offer sheets yet — the tender simply retains the
  player. AI tenders lineup players and those with > 5 points of headroom if the org's balance is positive.
* **Transfer negotiations**: club ask = club valuation (market value × 1.35 for starters, strategy factor,
  × 0.8 when listed, or the user's asking price) × 1.4 for a top-two starter of a win-now/prestige club. A
  sell-on clause is credited at fee × pct × 0.6 (≤ 22), 0.4 (≤ 25) or 0.2. Bids ≥ ask (±3%) are accepted;
  ≥ 60% of ask get a counter of ask × (1.06 − 0.02 × round) minus sell-on credit; three failed bids end
  talks for 30 days. Personal terms reuse the contract-appeal model: salary equivalent = salary + 0.9 ×
  bonus/seasons, −0.025 per season away from the preferred length (2 for ≤ 21, 3 for ≥ 26), +0.03 ×
  ambition for a release clause; the bar is the move threshold (0.82 + 0.12 × ambition, at least the
  appeal of staying + 0.03; an agreed sale lowers staying appeal by 0.05) plus a fixed hidden ±0.03 per
  negotiation, so counter-demands are honest. Agents pad asks by greed/500. Decisions are seeded by
  negotiation id, so the same world state gives the same answers.
* **Pre-contracts**: allowed in the final 183 days before season end for contracts without a team option;
  they block extensions and bids, and complete at the rollover (contract runs from next season). Offer
  sheets (restricted FAs) default to the user's RFA tender setting if undecided; AI matches lineup or
  high-upside players up to 1.35 × expected salary. AI clubs hold at most one pending pre-contract.
* **Selling**: AI bids carry a hidden ceiling of 1.08–1.3 × their valuation (≤ 60% of balance); counters up to
  it complete, up to 1.2 × it draw one final offer, beyond that the bidder walks.
* **Swiss pairing**: score groups are paired by bounded backtracking (highest vs lowest preferred) so
  avoidable rematches never happen; an odd group floats its lowest feasible team down; rematches are
  accepted only when no rematch-free pairing exists (greedy fallback).
* **Luxury tax**: soft caps tax payroll above the cap at the league tax rate (default 50%), charged monthly.

## Supporters & rivalries

Proposals (editable constants in `src/systems/fans.ts` and `src/systems/rivalries.ts`); calibrated with
`npx vite-node scripts/fans.ts [seed] [seasons]`.

* **Six segments** per organisation: local loyalists, winning-focused, player fans, style enthusiasts,
  youth & community, legacy supporters. The initial mix follows the organisation (age → local/legacy,
  reputation → winning, academy level and youth strategy → community, prestige → legacy); the follower
  total is unchanged (`org.fans` = Σ segment sizes).
* **Reach vs conversion**: paying supporters = size × conversion × (0.5 + mood/100); conversion local 6%,
  winning 2.5%, player 3.5%, style 3%, community 5%, legacy 8%. Merchandise = paying × $0.25 × region
  economy per month — a new world earns ≈ the old `followers × 0.012 × economy` (ratio 0.99–1.01 over three
  seasons). Sponsorship deals are valued × (0.85 + 0.3 × overall mood/100). Reputation moves at most
  ±0.08/month with overall mood.
* **Reactions** (mood change, diminishing near 0/100, logged when |Δ| ≥ 2.5) come only from recorded events:
  series results (winning ±(1 + 1.5·stakes), local ±(0.4 + 0.4·stakes)·rivalry, legacy ±(0.2 + 0.8·stakes)·
  rivalry, style from the team's aggression and sweeps/deciders; stakes league 0.6, playoff 1.6, final 3,
  ×1.5 internationally; losses weigh 1.1×; rivalry multiplier 1 + intensity/50; academy teams count 30% and
  multi-division organisations split the weight), trophies, star signings (winning +, foreign glamour
  signings worry local/community fans), favourites and academy graduates leaving, moves between rivals,
  academy debuts (community +4), promotions and retirements.
* **Sizes** drift monthly with mood relative to the club's own baseline (`50 + (rep − 40)/6`; bandwagon fans
  fastest, loyalists slowest) minus churn; recent attention ("buzz") converts a few casual followers; player
  fans track the stars on the roster (`(fame − 40)² × 15` followers each, 15%/month), so they arrive and
  leave with them. Placement growth keeps the old formula (`followers × repGain/60`), now credited to the
  winning, legacy and style segments.
* **Audience** per series (interest, not paying spectators) = (followers of both orgs × 0.05 + 2,000) ×
  stage (1 / 2.2 / 4) × event (Worlds 2.5, international 1.8, challenger 0.4) × (1 + rivalry/80) ×
  (0.4 + title popularity/100).
* **Rivalries** link two divisions of one title and are built only from recorded encounters: heat = stakes
  (league 1, playoff 4, final 9; ×1.4 internationally) + decider (+2.5 in playoffs/finals, +1 otherwise) +
  playoff upset (+1.5) + clash of contenders (both Elo > 1580, league only, +0.8), ×1.3 for a derby (same
  country in a multi-country league). A record starts only when one encounter is worth ≥ 2.5 — a single
  ordinary league defeat never starts a rivalry. Intensity += heat × (1 − intensity/125). Star transfers
  between the clubs add 5 (×1.4 if already a rivalry). Monthly fade ×0.985 while meeting, ×0.94 after 120
  days without contact; never-notable records are forgotten below 6; notable faded ones are remembered (40
  per title). Labels: Emerging 15, Rivalry 30 (news), Fierce 50, Historic 75 (news). Rivalry meetings add
  up to +0.4 match pressure (`intensity/250`), which tests composure — no attribute ever changes.
* Old (v5) saves: segments are created from the follower count and rivalries are seeded quietly from the
  save's own recorded finals (season history) and this season's results.

## Season rules: one game per season, breaks, AI trades

* One game per season: `seasonTitle(p)` = the title with most games this season; any move to a team of
  another title is refused until the rollover (checkPolicy, trades, promotions, loans, AI free-agent
  candidates). Verified by simulation: no player has games in two titles in one season.
* Breaks (src/systems/breaks.ts): weekly during the competitive season (10 Jan – 7 days before the season
  end) with probability `(0.0006 + 0.012·max(0, fatigue−55)/45 + 0.008·max(0, 40−morale)/40 + 0.003·[unhappy
  > 70]) × (1.4 − 0.8·resilience/100)` ≈ 3% of contracted players per season (small world: 32 of 1,053).
  Reason: fatigue > 65 burnout, morale < 35 mental/motivation, else personal/family/mental; 15% (30% for
  personal/family) are hiatuses until the season ends; lengths burnout 3–6 weeks, personal/family 1–4, others
  2–5. While away: fatigue −3/day, morale → 70 at 4%/day, unhappiness −0.5/day; no training; on return form 0
  and morale ≥ 62. Refusing: morale −8, unhappiness +12 (+25 for a hiatus), form −2; burnout with fatigue
  > 75 → a two-week break anyway. Club rest: morale +3 if fatigue > 50, else −ambition/25.
* Clubs count players out for more than 14 days as missing: the AI market treats the team as short (free
  agents any time) and their roles as uncovered; such players are never released as surplus.
* Trades (src/systems/trades.ts), weekly in windows, trade-permitting leagues, main teams. Every deal is
  judged by each club's greedy best-lineup value (Σ starters' effectiveness, roles re-picked) and market
  value; candidates that cannot lift the other lineup enough are pruned first (upper bound per role).
  * 1-for-1: both lineups +0.6; values within 35%, or within 70% with cash = 80% of the gap from the receiver
    of more value (balance ≥ 2× cash) or, in the same league, a draft pick worth 0.6–1.6× the gap.
  * 2-for-1: the consolidating club +1.5, the other club +0.3 and 110–180% of the value, roster room.
  * Player for picks: a rebuilding club (youth/moneyball or bottom 40% by Elo) sends a player aged ≥ 24 to a
    contender (win-now/prestige or top 40%) in the same league for ≤ 2 picks worth ≥ 80% of his value
    (picks ×1.3 to the rebuilder).
  * AI–AI: a club is considered with 25% chance, one deal per title per week, at most three traded players
    per club per season (small world: ~25 deals a season — roughly 7 one-for-one, 11 two-for-one, 8 for
    picks). AI → user: 35% chance per user team per window week, one pending offer per team, AI gains ≥ 1,
    the user receives ≥ 97% of the value given (÷ bargaining difficulty), 7 days to answer; delegated
    "offers for your players" → the GM accepts only deals that do not weaken the lineup. Traded contracts
    keep salary and length (no signing bonus).
* Youth intake (src/systems/youth.ts), 1 December: per organisation and main team, class size 2 +
  floor(academy/4); 60% own country; CA ~ N(42 + scene·0.06 + academy·0.6 + youthCoach·0.04, 5), potential
  boost ~ N(3 + academy·0.7 + youthCoach·0.05, 6); ages 15–17. AI: sign the best 1 (2 with academy ≥ 6) by
  potential — without an academy team only potential ≥ 78 — the rest never enter the pool (small world: ~40
  prospects join 900 players). User: 21 days to sign (3-season youth contract at 60% of expected salary,
  ≥ league minimum), trial or release; unsigned → free agents. Trials: 1–2 weeks, $1,500/week × (0.4 + region
  economy), reserved for the club (others' signings refused, AI skips him), scouting knowledge 70 → 100 at
  the end with a staff verdict.
* Season preview (13 Jan): power rankings by squad strength per league, top-5 favourites, players to watch
  (CA), breakout candidates (≤ 20, CA ≥ 50, largest headroom), biggest off-season fees. Review (season end,
  before pruning): champions, season awards, storylines (world champions/dynasties, underdog league winners
  tipped ≥ 4th, collapses of the tipped top two into the bottom half, breakouts ≥ +4 CA aged ≤ 22, rivalries
  born, deal of the season, retirements with legacy ≥ 60, prediction score) and preview vs final league
  tables (both splits' placements summed). Twelve seasons are kept.
* Head-to-head ledger: per sorted team pair `[meetings, wins A, wins B, games A, games B, finals, last
  season]` for every non-national series (small world: ~300 pairs after a season).

## Difficulty & sandbox (src/systems/difficulty.ts)

| | Relaxed | Standard | Hard |
| --- | --- | --- | --- |
| Club income (user organisation's commercial revenue) | ×1.15 | ×1 | ×0.88 |
| Board strictness (negative confidence changes) | ×0.6 | ×1 | ×1.35 |
| Injury frequency (all clubs) | ×0.6 | ×1 | ×1.4 |
| Break frequency (all clubs) | ×0.5 | ×1 | ×1.5 |
| AI bargaining (asking prices, trade demands, player terms threshold +0.5·(k−1)) | ×0.9 | ×1 | ×1.12 |

Chosen on the new-career form; Settings → Difficulty & sandbox offers the presets and sliders (custom).

## Modes & delegation

* `userHandles(area)` = the role has the permission and the area is not delegated. Areas → permissions:
  signings/sales → transfers, contracts → contracts, staff → staff, finance → finance, registration →
  tactics. Everything the user does not handle runs through the same AI code as other clubs (AI GM market,
  bid responses, renewals and RFA tenders, board budgets, coach registration). Delegated staff hiring also
  adds an assistant coach where a user team has none (each rollover).
* Spectator mode: `meta.mode = 'spectator'`, no user organisation, `meta.followed` team ids. Followed teams
  get full-detail fixtures, inbox match reports (only while ≤ 20 are followed), training timetables, and
  optional stops before their matches (`stopForFollowed`). Any match can be watched live the day before it
  is played (both coaching staffs veto and draft); results equal instant simulation.

## Board

* Objective target = squad-strength rank in the league + ambition (win-now/prestige 0, balanced 1,
  youth/moneyball 2). League finish is judged on the sum of both splits' final placements.
* Confidence update at season review: `0.7 × previous + 18 + Σ(objective deltas) + 8 × trophies`;
  league objective met `+10 + 3 × margin`, missed `−12 − 2 × shortfall`; Worlds ±10; finance +6/−15.
  Dismissal below 20. Owners and commissioners have no board.
* Mid-season reactions (per finished competition with user teams): trophy +10 Worlds / +6 international /
  +5 playoffs / +4 league / +3 cup or challenger; a non-final league split versus its objective: met
  `+3 + min(4, margin)`, missed `−3 − 2 × min(5, shortfall)`. The final split only produces a message (the
  season review judges the final standings, avoiding double counting). Below 30 after a negative result →
  ultimatum (once per season); a negative result on a later day with confidence below 12 under an
  ultimatum → mid-season dismissal. Monthly: −3 while the balance is negative (one warning per season), a
  warning when below the finance objective.
* Manager contract: 2 seasons on appointment (approaches offer 2–3); renewed for 2 more at the season
  review if confidence ≥ 40, otherwise the manager leaves (verdict "contract not renewed").
* Job approaches: after an excellent result (trophy, or a split ≥ 2 places above target), 35% chance, at most
  one open approach and a 60-day gap; from an active organisation with reputation +3…+30 above yours (any
  bigger one if none); 10 days to answer; declining gives +2 confidence.

## Persistence

* Saves are stored as structured-cloned objects (no JSON stringify) — fast and compact enough for long saves.
* Two physical slots per save (`<id>#a` / `<id>#b`) with the metadata row pointing at the newest; a save
  writes the other slot and flips the pointer in one transaction, so the previous copy is the backup without
  being read back and rewritten (the old layout cost ~3× the I/O). Saves are serialised (a promise chain) so
  an autosave and a manual save never interleave. Older single-slot saves load as before and are upgraded on
  the next two saves.
* Autosave after an advance runs **inside the simulation worker**, which already holds the world, so the
  large structured clone into IndexedDB never blocks the UI; if storage is unavailable there, the page saves.
  The UI tracks unsaved changes (Save •) and warns before closing the tab; storage errors are explained
  (quota full, storage blocked) and the game asks for persistent storage.
* World size is kept down on fixed simulation ticks (never at save time, so results do not depend on when
  you save): detailed box scores of other clubs' matches are slimmed after 21 days (user matches and finals
  keep full detail), proficiency values are rounded to 2 decimals monthly, and AI organisations keep 40
  ledger lines and 13 months of accounts (the user's organisation 250 and 36).
* The portable single-file build uses a classic (IIFE) inline worker: Chromium refuses module workers from a
  local file's opaque origin, which silently forced the simulation onto the main thread.
* Validation (zod) checks the top-level shape and a sample of ≤200 players; the original object is returned
  (zod re-creates objects and reorders keys, which we avoid).
* Rollover pruning: competitions/fixtures/matches older than the previous season are deleted (history keeps
  champions/standings); detail is compacted except finals and the user's matches; retirees with legacy < 35
  are archived to compact summaries after 4 seasons. 10 seasons × 3 titles: save grows 3.4MB → 5.5MB.

## Real-world data (snapshot real-2026-11-20)

* **Offline, dated snapshot**; nothing is fetched at runtime. Each real entity carries `real = { src, snap,
  est[] }`; anything without it is fictional. Ratings, attributes, personalities, contracts, salaries,
  finances, owners, sponsors and everything after the snapshot are game simulation, shown as such.
* **Sources preferred in this order**: official league/publisher data (MPL ID/PH sites, LoL Esports, Valve
  Regional Standings) → named community databases (OpenDota, vlr.gg). Liquipedia is not used: its CAPTCHA
  asks automated agents not to solve it. MLBB's signed API was not reverse-engineered.
* **Estimates are explicit**: Dota positions from lanes/last hits, Valorant roles from agents played, CS2 roles
  unknown (game assignment), ages and unpublished nationalities, organisation countries, cross-title
  organisation grouping by name.
* **No invented private facts**: only handles, roles, published real names (LoL official data) and listed
  nationalities are stored.
* **Structure**: real leagues where the source is a league; ranked pools (CS2 standings, Dota ratings) are
  grouped into game leagues and labelled as game structure; leagues with fewer than six real teams are topped up
  with fictional teams; HoK and Wild Rift stay fictional.

## Live 2D MOBA simulation

* **One authoritative spatial simulation per spatial fixture**; the renderer only plays back recorded frames.
  Recording and commentary never draw from the game RNG.
* **2-second step** (rates per second × step): fights last several steps; viewer interpolation keeps motion
  smooth. A 1-second step doubled the cost without visibly better outcomes.
* **Fidelity lock instead of simulating every background game spatially**: ≈ 2 700 MOBA games per season would
  add minutes per season on phones. Spatial = your/followed teams' fixtures and anything opened live or marked
  full detail before play; the decision is stored on the fixture (`spatial`) before any result exists.
* **Terrain derived from walkable corridors** so walls drawn on screen and movement rules always agree.
* **Engagements from visible information**: heroes and shot-callers act on what their team can see; losing
  teams play safer; chases are limited; escape spells exist — these, not hard caps, keep kill counts plausible.
* **Replays are session-only** (memory cache of the last 12 games); saves stay small.

## Season format (three splits)

* Every title: Split 1 (single round robin, Jan) → **First Stand** (Mar; split 1 finalists, the two
  strongest leagues send two) → Split 2 (double round robin) → **Mid-Season Invitational** (late Jun; play-in
  groups when more than eight teams, then a double-elimination bracket) → Split 3 (double round robin) →
  **World Championship** (late Sep; circuit points, the split 3 champion seeded). Circuit points weigh splits
  1 / 1.25 / 1.5. An international that would start before its qualifying split has finished waits for it.
* **Hard Fearless at First Stand** (`Competition.rules.fearless = 'global'`, MOBAs only): every champion
  either team has played in the series is unavailable to both teams for the rest of it. Valorant's
  simultaneous agent select and CS2 have no fearless rule.
* Mid-season transfer window: 12–23 July (between MSI and split 3); pre-season window unchanged.

## Sides and draft order

* MOBA games are simulated in map-side order (index 0 = Blue / Radiant, bottom-left); when the series'
  second team is on Blue the teams are swapped for the simulation and the result mapped back
  (`GameResult.blue`). Replays stay in side order; viewers and box scores use `blue` (Blue on top).
* **First selection** (LoL 2026 rule, also Dota 2 Captains Mode): game 1 chooser by coin toss, then the
  previous game's loser; the chooser takes either a side or a draft order, the opponent the other. AI: a team
  values first pick when the patch has standout champions (top − 6th meta strength) and by draft priority
  (meta/comfort want first, counter wants last); Blue side's value is a fixed per-title edge. On your own
  live matches you make the choice (or let the coach). MLBB, HoK and Wild Rift: the chooser takes Blue,
  which drafts first.

## Patches

* **Minor**: random during the splits (≈1/18 per day, ≥ 7 days apart, never during an international):
  2–3 nerfs and buffs, sometimes a class-wide item/utility change. **Medium**: exactly 10 days before First
  Stand, MSI and Worlds: 6–9 nerfs and buffs, 1–2 class changes, style shift, objective value, sometimes the
  map pool. **Major**: the day after the Worlds final (opens next season's numbering): 8–12 nerfs and buffs,
  2–3 class changes, map pool, plus 2–3 **gameplay rule changes** stored in `Patch.rules` and read by both
  engines of each family via `src/sim/common/rules.ts`: objective spawns (once → twice, a cap), first spawn
  and respawn timers, a different reward kind, reward size, new objectives (Atakhan, Rift Scuttler, a second
  Turtle, Void Grubs in Wild Rift, …; game patches, not real announcements) or removal of a patch-added one,
  gold income, structure health and death timers; shooters: bomb/spike, round, plant and defuse timers,
  economy, loss bonus, attacker/defender balance. Rules accumulate across seasons. World → Patches shows
  the rules in force.

## Saving and simulation on Android (APK-first)

* **Saves** are one gzip-compressed JSON value per slot (`src/persistence/codec.ts`, CompressionStream,
  available in Android System WebView) instead of a deep object graph in IndexedDB; older saves load.
* **Match records** are compacted when recorded: drafts keep bans, picks and first pick (each player's
  character is in the box score), rounded stats, no side text for background games.
* **Finished seasons**: competitions, stages, standings, brackets, placements, per-player competition stats
  and fixtures with results stay inside the save (`world.past`) — old competitions open instantly. Only
  old seasons' per-game box scores live in a separate compressed store, loaded with an old match report.
* **Simulation** runs in place on the page in ~90 ms slices with the screen updating between slices
  (`advanceInSlices`). The world is no longer copied to a worker and back on every advance (memory
  doubling on large worlds was what made phones drop progress). Errors keep progress up to the last day.
* **Background drafts**: fast-engine games use a quick draft from cached per-player preference lists
  (same rules); games you play or watch use the full draft AI. About 2× faster simulation.
* **Weekly box-score pages** (`src/game/boxPages.ts`): once a week the per-game box scores of background
  matches played at least two days earlier leave the live world and are written as one compressed page per
  season-week (grouped by the match's own season, so late-season matches paged after the rollover land in
  the right season). Each fixture remembers its page (`Fixture.pg`); results, standings, player and
  competition stats stay in the world. A match report loads its page on demand. Small batches wait for more
  matches but never longer than a week (off-season leftovers). The save is about half as large mid-season.
* **Finished seasons are written once** (`<id>|past|<season>` in the archive store) instead of with every
  save; the save itself carries only the live season.
* **Autosave cadence** (Settings): after every advance, weekly (default) or monthly, so a long run of short
  advances is not slowed by a save each time. The Save button always saves at once.
* **Screen updates while simulating**: progress updates redraw only the progress box (`BusyOverlay`), not
  the page underneath; components that only call store actions no longer subscribe to the whole store.
  Re-rendering a large page several times a second was slowing phones down as much as the simulation.
* **Per-game work trimmed**: character lookups by id map, quick-draft lists memoised per lineup slot,
  per-player fight weightings cached per game, lineup scoring computes character proficiency once per
  player, allocation-free weighted choices. About 25% less CPU per simulated day (same results: these
  change no numbers).
* **Side and pick-order choice** is stored compactly on every MOBA game (`GameResult.sel`: chooser, side or
  order, coin toss or loser's choice) so it shows on every match report, not only on detailed records.
