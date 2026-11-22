#!/usr/bin/env python3
"""
Refresh the player-facts snapshot (development time only):

    python3 scripts/data/player_facts_fix.py <cache dir>   ->  src/data/real/playerFacts.json

Fixes same-handle mix-ups and fills more facts:
 - League of Legends / Valorant: Fandom Cargo (Leaguepedia, VALORANT Esports Wiki), every player
   with the handle is kept and the right one is chosen by real name, current team or residency.
   Country = nationality; a different listed nationality is kept as a second nationality.
 - Every game: Wikidata (query.wikidata.org, CC0) professional gamers with a pseudonym: day-precise
   birth dates and citizenship, accepted only when the real name matches, or the handle is unique.
 - Dota 2: OpenDota pro players (the country set on the player's profile).
Requests are spaced and cached; only public facts are kept.
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
CACHE = sys.argv[1] if len(sys.argv) > 1 else ".cache/facts2"
os.makedirs(CACHE, exist_ok=True)
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
REAL = os.path.join(ROOT, "src/data/real")
rosters = json.load(open(os.path.join(REAL, "rosters.json")))
history = json.load(open(os.path.join(REAL, "history.json")))
facts = json.load(open(os.path.join(REAL, "playerFacts.json")))
_last = {}


def get(url, gap, accept=None):
    f = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest()[:16])
    if os.path.exists(f):
        return json.load(open(f))
    host = urllib.parse.urlparse(url).netloc
    wait = _last.get(host, 0) + gap - time.time()
    if wait > 0:
        time.sleep(wait)
    _last[host] = time.time()
    args = ["curl", "-sS", "-m", "180", "--compressed", "-A", UA]
    if accept:
        args += ["-H", "Accept: " + accept]
    try:
        body = subprocess.run(args + [url], capture_output=True, check=True).stdout.decode()
        j = json.loads(body)
    except Exception as e:  # noqa: BLE001
        print("fetch failed", url[:120], e, file=sys.stderr)
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
    toks = [ALIAS.get(t, t) for t in toks]
    return "_".join(sorted(toks))


def same_name(a, b):
    ka, kb = set(name_key(a).split("_")) - {""}, set(name_key(b).split("_")) - {""}
    return bool(ka) and bool(kb) and (ka == kb or (len(ka & kb) >= 1 and min(len(ka), len(kb)) == 1) or len(ka & kb) >= 2)


COUNTRY = {}
for c in json.load(open(os.path.join(ROOT, "native-android/app/src/main/assets/gamedata.json")))["countries"]:
    COUNTRY[c["name"].lower()] = c["code"]
COUNTRY.update({"korea": "KR", "south korea": "KR", "republic of korea": "KR", "taiwan": "TW", "chinese taipei": "TW", "hong kong": "HK", "macau": "MO", "macao": "MO",
                "vietnam": "VN", "viet nam": "VN", "russia": "RU", "united states": "US", "usa": "US", "united kingdom": "GB", "uk": "GB", "england": "GB",
                "scotland": "GB", "wales": "GB", "czech republic": "CZ", "czechia": "CZ", "turkey": "TR", "türkiye": "TR", "turkiye": "TR", "iran": "IR",
                "laos": "LA", "bolivia": "BO", "venezuela": "VE", "moldova": "MD", "syria": "SY", "tanzania": "TZ", "north macedonia": "MK", "macedonia": "MK",
                "kosovo": "XK", "palestine": "PS", "brunei": "BN", "ivory coast": "CI", "côte d'ivoire": "CI", "cote d'ivoire": "CI", "the netherlands": "NL"})


def cc(name):
    if not name:
        return None
    return COUNTRY.get(str(name).strip().lower())


# Players of the snapshot: (title, handle, real name, team name, team region)
people = []
for t in rosters["teams"]:
    for p in t.get("roster", []):
        if p.get("status") == "staff":
            continue
        rn = p.get("real_name") or " ".join(x for x in [p.get("first_name"), p.get("last_name")] if x)
        people.append((t["title_id"], p["handle"], rn or "", t.get("name", ""), t.get("region", "")))
for p in history["players"]:
    rn = " ".join(x for x in [p.get("first_name"), p.get("last_name")] if x)
    people.append((p["title_id"], p["handle"], rn, p.get("last_team") or "", p.get("region") or ""))

out = {"snapshot_id": facts.get("snapshot_id"), "as_of": facts.get("as_of"), "sources": dict(facts.get("sources", {})), "players": dict(facts["players"])}
RESIDENCY = {"KR": ["korea"], "CN": ["china"], "EUW": ["europe", "emea"], "EUE": ["europe", "emea"], "NA": ["north america"], "BR": ["brazil"],
             "VN": ["vietnam"], "PCS": ["pcs", "taiwan", "lms", "asia pacific"], "JP": ["japan"], "LATN": ["latin america"], "LATS": ["latin america"], "OCE": ["oceania"]}


# ----------------------------------------------------------------- Fandom Cargo (LoL, Valorant)
def cargo(base, title, fields):
    handles = sorted({h for (ti, h, _, _, _) in people if ti == title})
    rows = {}
    for i in range(0, len(handles), 40):
        batch = handles[i:i + 40]
        where = "ID IN (%s)" % ",".join('"%s"' % h.replace('"', "") for h in batch)
        url = f"{base}/api.php?action=cargoquery&format=json&tables=Players&fields={urllib.parse.quote(fields)}&where={urllib.parse.quote(where)}&limit=500"
        j = get(url, 2.1)
        for r in (j or {}).get("cargoquery", []):
            r = r["title"]
            rows.setdefault(norm(r.get("ID")), []).append(r)
    return rows


def fact_of(r, src):
    birth = r.get("Birthdate") if re.match(r"^\d{4}-\d{2}-\d{2}$", r.get("Birthdate") or "") and r.get("Birthdate__precision") == "1" else None
    year = int(r["Birthdate"][:4]) if re.match(r"^\d{4}", r.get("Birthdate") or "") and int(r["Birthdate"][:4]) > 1980 else None
    c1 = cc(r.get("Country"))
    nats = [cc(x) for x in re.split(r"[,;]", (r.get("Nationality") or "") + "," + (r.get("NationalityPrimary") or "")) if x.strip()]
    c2 = next((x for x in nats if x and x != c1), None)
    f = {"name": r.get("Name") or None, "countryCode": c1, "role": r.get("Role") or None, "src": src}
    if c2:
        f["countryCode2"] = c2
    if birth:
        f["birth"] = birth
    elif year:
        f["birthYear"] = year
    return {k: v for k, v in f.items() if v is not None}


stats = {}
for title, base, fields in [("lol", "https://lol.fandom.com", "ID,OverviewPage,Name,Country,NationalityPrimary,Nationality,Birthdate,Role,Residency,Team"),
                            ("valorant", "https://valorant-esports.fandom.com", "ID,Name,Country,Nationality,Birthdate,Role,Team,Residency")]:
    rows = cargo(base, title, fields)
    src = base.replace("https://", "")
    fixed = 0
    for (ti, h, rn, team, region) in people:
        if ti != title:
            continue
        cands = rows.get(norm(h), [])
        if not cands:
            continue
        for r in cands:  # every candidate by real name, for players of either era
            if r.get("Name"):
                out["players"][f"{title}|{norm(h)}|{name_key(r['Name'])}"] = fact_of(r, src)
        pick = None
        if rn:
            pick = next((r for r in cands if same_name(rn, r.get("Name"))), None)
        if pick is None and team:
            pick = next((r for r in cands if norm(r.get("Team")) and (norm(r.get("Team")) in norm(team) or norm(team) in norm(r.get("Team")))), None)
        if pick is None and not rn and region in RESIDENCY:
            res = [r for r in cands if any(x in (r.get("Residency") or "").lower() for x in RESIDENCY[region])]
            if len(res) == 1:
                pick = res[0]
        if pick is None and len(cands) == 1 and (not rn or not cands[0].get("Name")):
            pick = cands[0]
        if pick is not None:
            key = f"{title}|{norm(h)}"
            old = out["players"].get(key)
            new = fact_of(pick, src)
            if old is None or old.get("name") != new.get("name"):
                fixed += 1
            out["players"][key] = new
    stats[title] = fixed
    out["sources"][title] = {"url": base, "name": out["sources"].get(title, {}).get("name", src + " (Cargo API)"), "matched": sum(1 for k in out["players"] if k.startswith(title + "|"))}

# ----------------------------------------------------------------- Wikidata (every game)
q = """SELECT ?p ?pLabel ?pseud ?dob ?cc WHERE {
  ?p wdt:P106 wd:Q4379701 ; wdt:P742 ?pseud .
  OPTIONAL { ?p p:P569 ?ds . ?ds psv:P569 ?dv . ?dv wikibase:timeValue ?dob ; wikibase:timePrecision ?prec . FILTER(?prec >= 11) }
  OPTIONAL { ?p wdt:P27 ?c . ?c wdt:P297 ?cc . }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
}"""
wd = get("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q), 2.0, "application/sparql-results+json")
byh = {}
for b in (wd or {}).get("results", {}).get("bindings", []):
    e = {k: v["value"] for k, v in b.items()}
    byh.setdefault(norm(e["pseud"]), {})[e["p"]] = e
wd_n = 0
for (ti, h, rn, team, region) in people:
    ents = list(byh.get(norm(h), {}).values())
    if not ents:
        continue
    named = [e for e in ents if e.get("pLabel") and not re.match(r"^Q\d+$", e["pLabel"]) and norm(e["pLabel"]) != norm(h)]
    pick = None
    if rn:
        pick = next((e for e in named if same_name(rn, e["pLabel"])), None)
    elif len(ents) == 1 and ti not in ("mlbb", "hok"):
        # A unique handle among all Wikidata pro gamers is not enough for MLBB / HoK players (rosters give
        # no real names): "ARTHUR" of RRQ matched a Korean "Park Mir", "Allen" of AP Bren a Swede.
        pick = ents[0]
    if pick is None:
        continue
    key = f"{ti}|{norm(h)}"
    f = dict(out["players"].get(key, {}))
    if f.get("name") and rn and not same_name(rn, f.get("name")):
        f = {}
    changed = False
    if pick.get("dob") and "birth" not in f:
        f["birth"] = pick["dob"][:10]
        f.pop("birthYear", None)
        changed = True
    if pick.get("cc") and not (f.get("countryCode") or f.get("country")):
        f["countryCode"] = pick["cc"]
        changed = True
    if changed:
        f.setdefault("src", "wikidata.org")
        if named and not f.get("name"):
            f["name"] = pick.get("pLabel")
        out["players"][key] = f
        wd_n += 1
out["sources"]["wikidata"] = {"url": "https://www.wikidata.org", "name": "Wikidata (professional gamers, CC0)", "matched": wd_n}

# ----------------------------------------------------------------- OpenDota (Dota 2 nationalities)
od = get("https://api.opendota.com/api/proPlayers", 1.5) or []
odh = {}
for p in od:
    if p.get("name"):
        odh.setdefault(norm(p["name"]), []).append(p)
od_n = 0
for (ti, h, rn, team, region) in people:
    if ti != "dota2":
        continue
    cands = [p for p in odh.get(norm(h), []) if (p.get("country_code") or p.get("loccountrycode"))]
    if not cands:
        continue
    same_team = [p for p in cands if team and norm(p.get("team_name")) and (norm(p.get("team_name")) in norm(team) or norm(team) in norm(p.get("team_name")))]
    pick = same_team[0] if same_team else (max(cands, key=lambda p: p.get("last_match_time") or "") if len(cands) == 1 or len({(p.get("country_code") or "").upper() for p in cands}) == 1 else None)
    if pick is None:
        continue
    code = (pick.get("country_code") or pick.get("loccountrycode") or "").upper()
    if len(code) != 2:
        continue
    key = f"dota2|{norm(h)}"
    f = dict(out["players"].get(key, {}))
    if not f.get("countryCode"):
        f["countryCode"] = code
        f.setdefault("src", "opendota.com")
        out["players"][key] = f
        od_n += 1
out["sources"]["dota2"] = {"url": "https://www.opendota.com", "name": "OpenDota pro players (profile country)", "matched": od_n}

json.dump(out, open(os.path.join(REAL, "playerFacts.json"), "w"), ensure_ascii=False, separators=(",", ":"))
print("re-matched", stats, "wikidata", wd_n, "opendota", od_n, "entries", len(out["players"]))
for k in ["lol|viper", "lol|tarzan", "lol|knight", "lol|ruler", "lol|peyz", "lol|caps", "lol|faker", "dota2|yatoro", "valorant|aspas", "cs2|zywoo"]:
    print(k, out["players"].get(k))
