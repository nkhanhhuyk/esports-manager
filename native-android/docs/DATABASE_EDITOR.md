# Database editor: fill in the data yourself

Settings → **Database editor** exports the world to a spreadsheet (.xlsx). Open it in Google Sheets
(File → Import → Upload → "Replace spreadsheet") or Excel, edit it, download it as .xlsx (Google
Sheets: File → Download → Microsoft Excel) and import it back. A single sheet saved as .csv can also
be imported. Every value is checked; the import report lists anything it could not use.

**Apply to this save and new games** keeps the file on the phone. Every new real-world career is
then built from the snapshot plus your edits, matched by *match key* (game + original name), so
new careers start with your data. **Use the original** in the same card switches this off.

## Sheets and columns

| Sheet | Editable | Read only / notes |
|---|---|---|
| Players | handle, first and last name, nationality (2-letter code), date of birth (YYYY-MM-DD), team (id or exact team name; "free agent" releases), main and second role (id or name, see *Roles*), picture link, potential, salary, contract end season, retired (Y/N), all 86 attributes (1–99) | current ability is calculated after import. A row with an empty id creates a new player (game, handle, nationality and main role needed) |
| Teams | name, short name, organisation (id or name), style, aggression (0–100), picture link | league membership (it decides the calendar), roster list |
| Organisations | name, short name, country, colours (#RRGGBB, comma separated), picture link, reputation, fans, balance (not your own club's) | the organisation's teams |
| Leagues | name, short name, picture link | region, tier |
| Competitions | name, short name, prize pool (not started yet), picture link. **A row with an empty id creates a new competition**: game, name, start date, format (league, double-league, groups-se, groups-de, se, de, swiss-se), teams (team names separated by `;`, or `league:<league name>`, or `top:<competition id>:<count>`), optional short name, prize pool, best of (e.g. `3/3/5` for groups / playoffs / final), groups, advance per group, every season (Y/N), entry fee, picture link | tier, region; only this season and later are listed. New competitions are checked like the in-game editor; one that does not fit stays as a draft in Create a competition with the reason |
| Staff | names, nationality, date of birth, quality (1–99), team | role |
| Characters | picture link | name and class come from the game data |

Picture links must be direct links to an image (png, jpg, webp) starting with http:// or https://.
The game loads them when the phone is online (Settings → download pictures keeps them offline).

Pictures: put any direct picture link in an *image url* column, import, then Start menu → Settings →
**Download all pictures** (or Settings in a career) stores every picture in the app for good, so they
show without internet.

Not possible from the sheet (to keep calendars and saves consistent): creating new teams,
organisations or leagues, or moving a team to another league. Rename the fictional
ones instead (for example the Honor of Kings teams) and fill their rosters.

## What is missing or estimated in the snapshot (where your edits help most)

From `DATA_PROVENANCE.md` (starting world of 21 Nov 2026):

| Game | What to fill in |
|---|---|
| Honor of Kings | everything: no source could be used, so all leagues, teams and players are fictional. Rename the leagues (KPL, ...), teams and organisations; replace the players (IGN, real name, nationality, date of birth, position), add logos and photos |
| Wild Rift | current rosters (the snapshot is early 2021); dates of birth (all estimated); positions (89 % estimated) |
| Dota 2 | nationality, date of birth and position of all 288 real players (estimated); the regional leagues are fictional (rename them) |
| Mobile Legends | dates of birth (all estimated), nationality (37 % estimated); three of five leagues are fictional (MPL MY, SG, ... — rename them and fill rosters) |
| Valorant | dates of birth (96 % estimated); positions (all estimated) |
| Counter-Strike 2 | positions (all estimated; the source has none); some dates of birth |
| League of Legends | 29 % of dates of birth and 18 % of nationalities estimated |
| All games | attributes, potential and ratings are always game estimates — adjust any you disagree with; team and organisation logos and player photos where none are shown |
