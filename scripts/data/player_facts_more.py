#!/usr/bin/env python3
"""
Second pass over the player-facts snapshot (development time only):

    python3 scripts/data/player_facts_more.py <cache dir>   ->  src/data/real/playerFacts.json

Fills facts the first pass (player_facts_fix.py) left out:
 - Valorant: the whole Players table of the VALORANT Esports Wiki (Fandom Cargo API) plus its
   handle redirects, so renamed players and players whose handle differs in case are found.
 - Wild Rift: the Players table of the Wild Rift Esports Wiki (Fandom Cargo API): birthdays,
   nationalities and positions of the wiki rosters the game uses.
 - League of Legends: players still without a birthday or nationality are looked up again on
   Leaguepedia through its handle redirects (old and changed handles).
 - CS2: nicknames shared by several bo3.gg players are settled by team, else by the only one with a world rank or recent rated matches; an
   entry that stays ambiguous is removed rather than guessed.
 - Dota 2: nationality by agreement of Wikidata, Valve's pro-player country and the Steam flag,
   the team's region breaking ties (no single one is reliable; Valve's unset default "af" is ignored).
 - Dota 2: positions 1-5 from the lanes and farm of each player in pro matches since January 2024
   (OpenDota public SQL explorer); the pro-player list maps handles to accounts.
Only entries that agree with what the game already knows (real name, team) are taken; a player
whose handle belongs to several people and nothing tells them apart is left as it was. Requests
are spaced (8 s, longer after a rate-limit answer) and cached; only public facts are kept.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
import urllib.parse

UA = "EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)"
CACHE = sys.argv[1] if len(sys.argv) > 1 else ".cache/facts3"
os.makedirs(CACHE, exist_ok=True)
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
REAL = os.path.join(ROOT, "src/data/real")
rosters = json.load(open(os.path.join(REAL, "rosters.json")))
history = json.load(open(os.path.join(REAL, "history.json")))
wild = json.load(open(os.path.join(REAL, "wildrift.json")))
facts = json.load(open(os.path.join(REAL, "playerFacts.json")))
_last = {}


def get(url, gap=8.0, tries=3):
    f = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest()[:16])
    if os.path.exists(f):
        return json.load(open(f))
    host = urllib.parse.urlparse(url).netloc
    wait = _last.get(host, 0) + gap - time.time()
    if wait > 0:
        time.sleep(wait)
    _last[host] = time.time()
    try:
        body = subprocess.run(["curl", "-sS", "-m", "120", "--compressed", "-A", UA, url], capture_output=True, check=True).stdout.decode()
        j = json.loads(body)
    except Exception as e:  # noqa: BLE001
        print("fetch failed", url[:140], e, file=sys.stderr)
        return None
    if "error" in j:
        if j["error"].get("code") == "ratelimited" and tries > 1:
            print("rate limited: waiting 90 s as asked", file=sys.stderr)
            time.sleep(90)
            return get(url, gap, tries - 1)
        print("api error", url[:140], j["error"], file=sys.stderr)
        return None
    open(f, "w").write(body)
    return j


def norm(s):
    return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", str(s or "")).lower())


ALIAS = {"jung": "jeong", "chung": "jeong", "yi": "lee", "rhee": "lee", "ri": "lee", "bak": "park", "pak": "park", "gim": "kim", "choe": "choi",
         "kang": "gang", "cho": "jo", "yoon": "yun", "lim": "im", "suh": "seo", "sin": "shin", "noh": "no", "roh": "no", "ahn": "an", "koo": "gu",
         "ku": "gu", "kwon": "gwon", "ko": "go", "goh": "go", "woo": "u"}


def name_key(s):
    toks = [norm(t) for t in re.split(r"[\s,]+", unicodedata.normalize("NFKD", str(s or "")).replace("-", "")) if len(norm(t)) >= 2]
    return "_".join(sorted(ALIAS.get(t, t) for t in toks))


def same_name(a, b):
    ka, kb = set(name_key(a).split("_")) - {""}, set(name_key(b).split("_")) - {""}
    return bool(ka) and bool(kb) and (ka == kb or (len(ka & kb) >= 1 and min(len(ka), len(kb)) == 1) or len(ka & kb) >= 2)


COUNTRY = {}
for c in json.load(open(os.path.join(ROOT, "native-android/app/src/main/assets/gamedata.json")))["countries"]:
    COUNTRY[c["name"].lower()] = c["code"]
COUNTRY.update({"korea": "KR", "south korea": "KR", "republic of korea": "KR", "taiwan": "TW", "chinese taipei": "TW", "hong kong": "HK", "macau": "MO", "macao": "MO",
                "vietnam": "VN", "viet nam": "VN", "russia": "RU", "united states": "US", "usa": "US", "united kingdom": "GB", "uk": "GB", "england": "GB",
                "scotland": "GB", "wales": "GB", "northern ireland": "GB", "czech republic": "CZ", "czechia": "CZ", "turkey": "TR", "türkiye": "TR", "turkiye": "TR",
                "iran": "IR", "laos": "LA", "bolivia": "BO", "venezuela": "VE", "moldova": "MD", "syria": "SY", "tanzania": "TZ", "north macedonia": "MK",
                "macedonia": "MK", "kosovo": "XK", "palestine": "PS", "brunei": "BN", "ivory coast": "CI", "côte d'ivoire": "CI", "cote d'ivoire": "CI",
                "the netherlands": "NL", "philippines": "PH", "indonesia": "ID", "malaysia": "MY", "singapore": "SG", "thailand": "TH", "japan": "JP"})


def cc(name):
    if not name:
        return None
    s = str(name).strip()
    if len(s) == 2 and s.isalpha():
        return s.upper()
    return COUNTRY.get(s.lower())


# Players of the snapshot: (title, handle, real name, team name)
people = []
for t in rosters["teams"]:
    for p in t.get("roster", []):
        if p.get("status") == "staff":
            continue
        rn = p.get("real_name") or " ".join(x for x in [p.get("first_name"), p.get("last_name")] if x)
        people.append((t["title_id"], p["handle"], rn or "", t.get("name", "")))
for t in wild["teams"]:
    for p in t.get("roster", []):
        if p.get("status") == "staff":
            continue
        people.append(("wildrift", p["handle"], p.get("real_name") or "", t.get("name", "")))
for p in history["players"]:
    rn = " ".join(x for x in [p.get("first_name"), p.get("last_name")] if x)
    people.append((p["title_id"], p["handle"], rn, p.get("last_team") or ""))

out = {"snapshot_id": facts.get("snapshot_id"), "as_of": facts.get("as_of"), "sources": dict(facts.get("sources", {})), "players": dict(facts["players"])}


def cargo_all(base, table, fields, where="1=1", order=None):
    rows, off = [], 0
    while True:
        url = (f"{base}/api.php?action=cargoquery&format=json&tables={table}&fields={urllib.parse.quote(fields)}"
               f"&where={urllib.parse.quote(where)}&limit=500&offset={off}" + (f"&order_by={urllib.parse.quote(order)}" if order else ""))
        j = get(url)
        got = [r["title"] for r in (j or {}).get("cargoquery", [])]
        rows += got
        if len(got) < 500:
            return rows
        off += 500


def cargo_in(base, table, fields, col, values):
    rows = []
    vals = sorted(set(v for v in values if v))
    for i in range(0, len(vals), 40):
        where = "%s IN (%s)" % (col, ",".join('"%s"' % v.replace('"', "") for v in vals[i:i + 40]))
        j = get(f"{base}/api.php?action=cargoquery&format=json&tables={table}&fields={urllib.parse.quote(fields)}&where={urllib.parse.quote(where)}&limit=500")
        rows += [r["title"] for r in (j or {}).get("cargoquery", [])]
    return rows


def fact_of(r, src, role_map=None):
    f = {"src": src}
    if r.get("Name"):
        f["name"] = r["Name"]
    c1 = cc(r.get("Country"))
    if c1:
        f["countryCode"] = c1
    nats = [cc(x) for x in re.split(r"[,;]", (r.get("Nationality") or "") + "," + (r.get("NationalityPrimary") or "")) if x.strip()]
    if not c1 and nats and nats[0]:
        f["countryCode"] = nats[0]
    c2 = next((x for x in nats if x and x != f.get("countryCode")), None)
    if c2:
        f["countryCode2"] = c2
    b = r.get("Birthdate") or ""
    if re.match(r"^\d{4}-\d{2}-\d{2}$", b) and r.get("Birthdate__precision") in ("1", None, ""):
        f["birth"] = b
    elif re.match(r"^\d{4}", b) and int(b[:4]) > 1980:
        f["birthYear"] = int(b[:4])
    if role_map is not None:
        role = role_map.get(norm(r.get("Role")))
        if role:
            f["role"] = role
    return f


def pick_of(cands, rn, team):
    if not cands:
        return None
    if rn:
        named = [r for r in cands if same_name(rn, r.get("Name"))]
        if len(named) >= 1:
            return named[0]
        if all(r.get("Name") for r in cands):
            return None  # every candidate has a different real name: another person
    if team:
        nt = norm(team)
        same = [r for r in cands if norm(r.get("Team")) and (norm(r.get("Team")) in nt or nt in norm(r.get("Team")))]
        if len(same) == 1:
            return same[0]
    people_ = {name_key(r.get("Name")) or norm(r.get("ID")) for r in cands}
    return cands[0] if len(people_) == 1 else None


def merge(key, new, overwrite_birth=False):
    """Keep what the first pass found; add missing facts (and the name when there was none)."""
    old = dict(out["players"].get(key, {}))
    if old.get("name") and new.get("name") and not same_name(old["name"], new["name"]):
        return False
    changed = False
    for k in ("countryCode", "countryCode2", "name"):
        if new.get(k) and not old.get(k) and not (k == "countryCode" and old.get("country")):
            old[k] = new[k]; changed = True
    if new.get("birth") and (overwrite_birth or not old.get("birth")):
        if old.get("birth") != new["birth"]:
            old["birth"] = new["birth"]; old.pop("birthYear", None); changed = True
    elif new.get("birthYear") and not old.get("birth") and not old.get("birthYear"):
        old["birthYear"] = new["birthYear"]; changed = True
    if new.get("role") and not old.get("role"):
        old["role"] = new["role"]; changed = True
    if changed:
        old.setdefault("src", new["src"])
        out["players"][key] = old
    return changed


stats = {}
# ----------------------------------------------------------------- Valorant: the whole wiki player table
VAL = "https://valorant-esports.fandom.com"
vrows = cargo_all(VAL, "Players", "ID,OverviewPage,Name,Country,Nationality,Birthdate,Team,Residency", order="Players._pageID")
vred = cargo_all(VAL, "PlayerRedirects", "AllName,OverviewPage", order="PlayerRedirects._pageID")
by_page = {r.get("OverviewPage"): r for r in vrows}
vidx = {}
for r in vrows:
    vidx.setdefault(norm(r.get("ID")), []).append(r)
for r in vred:
    p = by_page.get(r.get("OverviewPage"))
    if p is not None and norm(r.get("AllName")) and p not in vidx.setdefault(norm(r.get("AllName")), []):
        vidx[norm(r.get("AllName"))].append(p)
n = 0
for (ti, h, rn, team) in people:
    if ti != "valorant":
        continue
    pick = pick_of(vidx.get(norm(h), []), rn, team)
    vf = fact_of(pick, "valorant-esports.fandom.com") if pick is not None else None
    if vf is not None:
        vf.pop("countryCode2", None)  # the wiki's nationality list mixes heritage in: not used
    if vf is not None and merge(f"valorant|{norm(h)}", vf):
        n += 1
stats["valorant"] = n
out["sources"]["valorant"] = {"url": VAL, "name": "VALORANT Esports Wiki (Cargo API)", "matched": sum(1 for k in out["players"] if k.startswith("valorant|"))}

# ----------------------------------------------------------------- Wild Rift: the wiki player table
WR = "https://wildrift-esports.fandom.com"
WR_ROLE = {"top": "baron", "baron": "baron", "jungle": "jungle", "mid": "mid", "bot": "dragon", "adc": "dragon", "dragon": "dragon", "support": "support"}
wh = sorted({h for (ti, h, _, _) in people if ti == "wildrift"})
wrows = cargo_in(WR, "Players", "ID,OverviewPage,Name,Country,Nationality,Birthdate,Role,Team,Residency", "ID", wh)
widx = {}
for r in wrows:
    widx.setdefault(norm(r.get("ID")), []).append(r)
n = 0
for (ti, h, rn, team) in people:
    if ti != "wildrift":
        continue
    pick = pick_of(widx.get(norm(h), []), rn, team)
    if pick is not None and merge(f"wildrift|{norm(h)}", fact_of(pick, "wildrift-esports.fandom.com", WR_ROLE)):
        n += 1
stats["wildrift"] = n
out["sources"]["wildrift"] = {"url": WR, "name": "Wild Rift Esports Wiki (Cargo API)", "matched": sum(1 for k in out["players"] if k.startswith("wildrift|"))}

# ----------------------------------------------------------------- LoL: players still missing facts, through redirects
LOL = "https://lol.fandom.com"
LOL_ROLE = {"top": "top", "jungle": "jungle", "mid": "mid", "bot": "bot", "adc": "bot", "support": "support"}
missing = sorted({h for (ti, h, _, _) in people if ti == "lol" and not (out["players"].get(f"lol|{norm(h)}") or {}).get("birth")})
red = cargo_in(LOL, "PlayerRedirects", "AllName,OverviewPage", "AllName", missing)
pages = sorted({r.get("OverviewPage") for r in red if r.get("OverviewPage")})
lrows = cargo_in(LOL, "Players", "ID,OverviewPage,Name,Country,NationalityPrimary,Nationality,Birthdate,Role,Team,Residency", "OverviewPage", pages)
lpage = {r.get("OverviewPage"): r for r in lrows}
lidx = {}
for r in red:
    p = lpage.get(r.get("OverviewPage"))
    if p is not None and p not in lidx.setdefault(norm(r.get("AllName")), []):
        lidx[norm(r.get("AllName"))].append(p)
n = 0
for (ti, h, rn, team) in people:
    if ti != "lol" or h not in missing:
        continue
    pick = pick_of(lidx.get(norm(h), []), rn, team)
    if pick is not None and merge(f"lol|{norm(h)}", fact_of(pick, "lol.fandom.com", LOL_ROLE)):
        n += 1
stats["lol (redirects)"] = n

# ----------------------------------------------------------------- Dota 2: positions from lanes and farm in pro matches
# OpenDota's public SQL explorer (pro matches): per player and lane, games and average gold per minute,
# January 2024 to now in windows (one long query times out). Mid -> 2; safe lane with core farm -> 1,
# else 5; off lane with core farm -> 3, else 4.
pro = get("https://api.opendota.com/api/proPlayers", 2.0) or []
acc_of = {}
for p in pro:
    if p.get("name") and p.get("account_id"):
        acc_of.setdefault(norm(p["name"]), []).append(p)
votes = {}
edges = [1704067200, 1711929600, 1719792000, 1727740800, 1735689600, 1743465600, 1751328000, 1759276800, 1893456000]
for a, b in zip(edges, edges[1:]):
    sql = ("select pm.account_id, pm.lane_role, count(*) n, avg(pm.gold_per_min) gpm from player_matches pm join matches m on m.match_id=pm.match_id "
           f"where m.start_time > {a} and m.start_time <= {b} and pm.account_id is not null group by pm.account_id, pm.lane_role")
    j = get("https://api.opendota.com/api/explorer?sql=" + urllib.parse.quote(sql), 4.0) or {}
    for r in j.get("rows", []):
        lane, n_, gpm = r.get("lane_role"), int(r.get("n") or 0), float(r.get("gpm") or 0)
        pos = {2: "pos2", 1: "pos1" if gpm >= 470 else "pos5", 3: "pos3" if gpm >= 430 else "pos4", 4: "pos4"}.get(lane)
        if pos:
            v = votes.setdefault(r["account_id"], {})
            v[pos] = v.get(pos, 0) + n_
n = 0
for (ti, h, rn, team) in people:
    if ti != "dota2":
        continue
    cands = acc_of.get(norm(h), [])
    if len(cands) > 1 and team:
        cands = [p for p in cands if norm(p.get("team_name")) and (norm(p.get("team_name")) in norm(team) or norm(team) in norm(p.get("team_name")))] or cands
    accs = {p["account_id"] for p in cands if p["account_id"] in votes}
    if len(accs) != 1:
        continue
    v = votes[accs.pop()]
    total = sum(v.values())
    pos, cnt = max(v.items(), key=lambda kv: kv[1])
    if total < 10 or cnt < total * 0.45:
        continue
    key = f"dota2|{norm(h)}"
    f = dict(out["players"].get(key, {}))
    if not f.get("role"):
        f["role"] = pos
        f.setdefault("src", "opendota.com")
        out["players"][key] = f
        n += 1
stats["dota2 positions"] = n

# Dota 2 nationalities. No single open source is reliable: Valve's pro-player country (OpenDota
# country_code) is "af" / "ax" (the first entries of the list) when a player never set it, the Steam profile
# flag (loccountrycode) is often a joke, and a Wikidata match is by handle alone (Dota players have
# no real names here). Two signals that agree win; otherwise a country on the team's continent,
# then Valve > Wikidata > Steam. A Wikidata entry that loses keeps no birthday or name. Several
# OpenDota accounts with the handle and none on the player's team: OpenDota is not used for him.
CONT = {c["code"]: c.get("continent", "") for c in json.load(open(os.path.join(ROOT, "native-android/app/src/main/assets/gamedata.json")))["countries"]}
REG_CONT = {"WEU": "Europe", "EEU": "Europe", "EU": "Europe", "CIS": "Europe", "CN": "Asia", "SEA": "Asia", "NA": "North America", "SA": "South America", "MENA": "Asia"}
team_region = {}
for t in rosters["teams"]:
    if t["title_id"] == "dota2":
        team_region[norm(t["name"])] = t.get("region") or ""
for k in list(out["players"]):
    v = out["players"][k]
    if k.startswith("dota2|") and v.get("src") == "opendota.com" and v.get("countryCode") in ("AF", "AX"):
        v.pop("countryCode")
n = 0
for (ti, h, rn, team) in people:
    if ti != "dota2":
        continue
    key = f"dota2|{norm(h)}"
    f = dict(out["players"].get(key, {}))
    cands = acc_of.get(norm(h), [])
    if len(cands) > 1:
        cands = [p for p in cands if team and norm(p.get("team_name")) and (norm(p.get("team_name")) in norm(team) or norm(team) in norm(p.get("team_name")))]
        if len(cands) > 1:
            cands = sorted(cands, key=lambda p: p.get("last_match_time") or "", reverse=True)[:1]
    sig = []  # (country, priority)
    wd = f.get("countryCode") if f.get("src") == "wikidata.org" else None
    if wd:
        sig.append((wd, 2))
    for p in cands:
        v = (p.get("country_code") or "").upper()
        if v and v not in ("AF", "AX"):  # the first entries of the country list: never set
            sig.append((v, 3))
        v = (p.get("loccountrycode") or "").upper()
        if v and v not in ("AF", "AX"):
            sig.append((v, 1))
    if not sig:
        continue
    counts = {}
    for c, _ in sig:
        counts[c] = counts.get(c, 0) + 1
    agreed = [c for c, k in counts.items() if k >= 2]
    if agreed:
        best = agreed[0]
    else:
        cont = REG_CONT.get(team_region.get(norm(team), ""), "")
        pool = [x for x in sig if cont and CONT.get(x[0]) == cont] or sig
        best = max(pool, key=lambda x: x[1])[0]
    if best != f.get("countryCode"):
        if wd and best != wd:
            f = {k: v for k, v in f.items() if k == "role"}
            f["src"] = "opendota.com"
        f["countryCode"] = best
        f.setdefault("src", "opendota.com")
        out["players"][key] = f
        n += 1
stats["dota2 nationalities by agreement"] = n


# ----------------------------------------------------------------- CS2: same nickname, different players (bo3.gg)
# The first pass took the most recently updated bo3.gg player of a nickname. Nicknames are shared
# by many players across all levels, so pick the one on the same team, else the one with prize
# rank / recent rated matches (a professional), and only when that choice is clear.
B3 = "https://api.bo3.gg/api/v1"
countries = {}
for off in range(0, 400, 100):
    j = get(f"{B3}/countries?page%5Blimit%5D=100&page%5Boffset%5D={off}", 1.3) or {}
    for c in j.get("results", []):
        countries[c["id"]] = c.get("code")
    if len(j.get("results", [])) < 100:
        break
first = get(f"{B3}/players?page%5Blimit%5D=100&page%5Boffset%5D=0", 1.3) or {}
allp = list(first.get("results", []))
for off in range(100, (first.get("total") or {}).get("count", 0), 100):
    allp += (get(f"{B3}/players?page%5Blimit%5D=100&page%5Boffset%5D={off}", 1.3) or {}).get("results", [])
bynick = {}
for p in allp:
    if p.get("is_deleted") or p.get("is_coach") or p.get("discipline_id", 1) != 1:
        continue
    bynick.setdefault(norm(p.get("nickname")), []).append(p)
team_name = {}
tfirst = get(f"{B3}/teams?page%5Blimit%5D=100&page%5Boffset%5D=0", 1.3) or {}
for t in tfirst.get("results", []):
    team_name[t["id"]] = t.get("name") or ""
for off in range(100, (tfirst.get("total") or {}).get("count", 0), 100):
    for t in (get(f"{B3}/teams?page%5Blimit%5D=100&page%5Boffset%5D={off}", 1.3) or {}).get("results", []):
        team_name[t["id"]] = t.get("name") or ""


def b3team(tid):
    return team_name.get(tid, "") if tid is not None else ""


n_changed = n_dropped = 0
for (ti, h, rn, team) in people:
    if ti != "cs2":
        continue
    cands = bynick.get(norm(h), [])
    key = f"cs2|{norm(h)}"
    if len(cands) <= 1:
        continue
    nt = norm(team)
    same = [p for p in cands if nt and norm(b3team(p.get("team_id"))) and (norm(b3team(p.get("team_id"))) in nt or nt in norm(b3team(p.get("team_id"))))]
    if len(same) == 1:
        pick = same[0]
    else:
        # Rated over the last six months = playing at a level bo3.gg tracks (a professional).
        rated = sorted([p for p in cands if p.get("six_month_avg_rating")], key=lambda p: -float(p["six_month_avg_rating"]))
        ranked = [p for p in cands if p.get("rank")]
        pick = ranked[0] if len(ranked) == 1 else (rated[0] if len(rated) == 1 else None)
    if pick is None:
        if key in out["players"] and out["players"][key].get("src") == "bo3.gg":
            del out["players"][key]
            n_dropped += 1
        continue
    new = {"name": " ".join(x for x in [pick.get("first_name"), pick.get("last_name")] if x) or None, "countryCode": countries.get(pick.get("country_id")),
           "birth": pick.get("birthday") if re.match(r"^\d{4}-\d{2}-\d{2}$", pick.get("birthday") or "") else None, "src": "bo3.gg"}
    new = {k: v for k, v in new.items() if v}
    if out["players"].get(key) != new:
        out["players"][key] = new
        n_changed += 1
stats["cs2 re-picked"] = n_changed
stats["cs2 dropped (ambiguous)"] = n_dropped

json.dump(out, open(os.path.join(REAL, "playerFacts.json"), "w"), ensure_ascii=False, separators=(",", ":"))
print("added / completed", stats, "entries", len(out["players"]), "valorant rows", len(vrows), "redirects", len(vred), "wild rift rows", len(wrows), "lol redirect pages", len(pages))
for k in ["valorant|aspas", "valorant|tenz", "valorant|something", "wildrift|tartetatin", "lol|faker"]:
    print(k, out["players"].get(k))
