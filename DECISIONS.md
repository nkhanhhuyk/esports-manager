# Decisions, formulas and balancing constants

Fixed by the specification (not changed): the 30-title catalogue and input assignments, the 86-attribute
framework, the three input proficiencies, `Effective Mechanics = M × (0.65 + 0.35 × P/100)`,
`E = 0.40C + 0.30S + 0.15T + 0.10R + 0.05P`, the similarity guideline bands and the series format tables.
Everything below is a proposed, tunable default.

## Data & realism

* **Mechanics are simplified, editable presets**, not official real-world rules. Objective names used where
  well known (e.g. Baron Nashor, Roshan, Lord/Turtle, Tyrant/Overlord, Dark Slayer, Gold Fury/Fire Giant,
  Fangtooth/Orb Prime, Guardians/Walkers/Patron); uncertain ones are fictional placeholders (Vainglory,
  Marvel Super War, Unite legendaries, HotS battleground objectives). Overwatch Rush (5v5, Control-only) and
  Valorant Mobile details are fictional presets.
* **All characters, maps, POIs** are procedurally generated fictional names (seeded per title).
* **Popularity matrix** is a fictional-universe starting point (e.g. MLBB strongest in ID/PH, HoK in CN,
  CS2 in EUE/CIS/BR, PUBG Mobile in SA/MENA) and drifts every season.
* Deadlock uses a 4-lane "launch era" preset; rule eras are recorded on patches so later eras can differ.
* Double elimination has **no bracket reset** (upper-bracket finalist can be eliminated by one GF loss).
* HoK uses a team-scoped fearless draft; LoL uses global fearless; others standard drafts (editable).

## Effectiveness model

* Core weights by group: Mechanical 1.0, Combat 1.0, Tactical 0.95, Mental 0.9, Teamwork 0.8, Knowledge 0.8;
  Development attributes only via Consistency (0.6) and Stamina (0.3).
* Specialist relevance defaults: own family = 1, other family = 0, overridden per title (e.g. Last Hitting
  0 in HotS, Projectile Leading 0.6 in SMITE 2, Recoil 1.3 in CS2, building/multitasking emphasis in Fortnite).
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
| MOBA | teamfight logit = ln(powerA/powerB) × 4.5 (3.4 shared-XP); lane edge /9; objective control /10; pick-offs /9; structure pressure 0.42 × tick / avgLength |
| Tactical | duel scale 26 rating pts/logit (`DUEL_SCALE`); round swing σ 0.32; buy quality ×1.7; utility /60, info /80, IGL /80; defender edge per title |
| Hero | fight logit = Δ/10 + comp ×0.6, ult +0.35 |
| Arena | gun edge Δ/15, objective edge Δ/16, hill control noise σ 0.65 |
| Brawl | Δ/9 per engagement |
| BR | fight logit = ln ratio × 3, zone/position edges ±0.2 |

Latest calibration (tiny worlds, 6 games per pairing): LoL 3–5 pts 75%; MLBB 73%; Dota 78%; Valorant 85%;
CS2 89%; R6 81%; Overwatch 82%; CoD 81%; Brawl Stars 64%. Tactical shooters compound most (≈22 rounds).

Core durability (`coreHits`): 2 for most MOBAs, 3 for Dota 2 / SMITE 2 / Deadlock, 4 for HotS.

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
* Prize split: 32/18/11/9/6/6/4/4/2… %, org keeps (1 − 0.6 × average player prize share).
* Transfer valuation = expected salary × 1.4 × youth/headroom factor × contract-length factor; clubs add
  +35% for starters, strategy modifiers (moneyball −15%, win-now/prestige +25%), listed −20%.

## Development

* Permanent attributes progress every second week with a double step (same yearly rate, half the work).

* Weekly growth budget = `0.062 × growthRate × (gap/10)^0.85 × ageFactor × trainingQuality × (0.5 + ethic) ×
  (0.6 + 0.6·intensity) × focusRepetition × overtraining × playingTime`, converted to ≈110 attribute points
  distributed by focus × trainability × room to grow.
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

* **Nations Cup**: odd seasons, 3–18 December, six most popular non-BR titles, 8 nations by average CA of
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
