#!/usr/bin/env python3
"""
Real coaching staff for src/data/real/staff.json.

* League of Legends: Leaguepedia Cargo API (lol.fandom.com/api.php, read-only queries, a few
  seconds between requests, responses cached): the coaches each team registered for its most
  recent tournaments (head coach, coach, assistant, positional coaches, analysts), the coaches of
  every champion and runner-up in honours.json, and their nationality and birth date.
* MLBB (official MPL team pages) and VALORANT (vlr.gg team pages): the staff already collected in
  rosters.json; Wild Rift: the staff listed on the Wild Rift Esports Wiki pages in wildrift.json.
* CS2, Dota 2, VALORANT and LoL legends: compiled_staff.json next to this script (public records,
  only coaches whose role and titles are well documented; not checked against a live source here).

Usage: python3 scripts/data/staff.py [cache-dir]
"""
import hashlib, json, os, re, sys, time, unicodedata, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REAL = os.path.join(ROOT, "src", "data", "real")
CACHE = sys.argv[1] if len(sys.argv) > 1 else os.path.join("/tmp", "staff-cache")
os.makedirs(CACHE, exist_ok=True)
API = "https://lol.fandom.com/api.php"
UA = "esports-manager-data/1.0 (data snapshot script)"
last = [0.0]


def cargo(tables, fields, where, join_on=None, order_by=None, limit=500, offset=0, group_by=None):
    q = {"action": "cargoquery", "format": "json", "tables": tables, "fields": fields, "where": where, "limit": str(limit), "offset": str(offset)}
    if join_on: q["join_on"] = join_on
    if order_by: q["order_by"] = order_by
    if group_by: q["group_by"] = group_by
    url = API + "?" + urllib.parse.urlencode(q)
    key = hashlib.sha1(url.encode("utf-8")).hexdigest()
    path = os.path.join(CACHE, key + ".json")
    if os.path.exists(path):
        return json.load(open(path))
    wait = 3.0 - (time.time() - last[0])
    if wait > 0: time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.loads(r.read().decode("utf-8"))
    last[0] = time.time()
    if "error" in data:
        raise RuntimeError(json.dumps(data["error"])[:400])
    rows = [x["title"] for x in data.get("cargoquery", [])]
    json.dump(rows, open(path, "w"))
    return rows


def all_rows(**kw):
    out, off = [], 0
    while True:
        rows = cargo(offset=off, **kw)
        out += rows
        if len(rows) < kw.get("limit", 500): return out
        off += len(rows)


def norm(s):
    s = unicodedata.normalize("NFKD", s or "")
    return re.sub(r"[^a-z0-9]", "", s.lower())


def q(s): return '"' + s.replace('"', '\\"') + '"'


COUNTRY = None
def country_code(name):
    """Leaguepedia country names → ISO codes (from the game's country table)."""
    global COUNTRY
    if COUNTRY is None:
        gd = json.load(open(os.path.join(ROOT, "native-android", "app", "src", "main", "assets", "gamedata.json")))
        COUNTRY = {}
        for c in gd.get("countries", []):
            COUNTRY[norm(c.get("name", ""))] = c["code"]
        COUNTRY.update({norm("South Korea"): "KR", norm("Korea"): "KR", norm("China"): "CN", norm("Taiwan"): "TW", norm("Hong Kong"): "HK", norm("United States"): "US",
                        norm("United Kingdom"): "GB", norm("Vietnam"): "VN", norm("Russia"): "RU", norm("Turkey"): "TR", norm("Czech Republic"): "CZ", norm("Brazil"): "BR"})
    return COUNTRY.get(norm(name or ""))


def role_of(r):
    """Leaguepedia tournament roster role → game staff role (+ position)."""
    parts = [p.strip() for p in (r or "").split(",")]
    pos = next((p.lower() for p in parts if p in ("Top", "Jungle", "Mid", "Bot", "Support")), "")
    pos = {"bot": "adc"}.get(pos, pos)
    if "Head Coach" in parts: return "head-coach", ""
    if "Assistant Coach" in parts: return ("position-coach", pos) if pos else ("assistant-coach", "")
    if "Coach" in parts: return ("position-coach", pos) if pos else ("coach", "")
    if "Analyst" in parts: return "analyst", ""
    return None, ""


def lol(teams, honours):
    out = {}
    # 1. Coaches registered for tournaments since mid-2025 (each team's latest tournament counts).
    recent = all_rows(tables="TournamentPlayers=TP,Tournaments=T", join_on="TP.OverviewPage=T.OverviewPage",
                      fields="TP.Team=Team,TP.Link=Link,TP.Role=Role,T.Name=Tournament,T.DateStart=Start,TP.N_PlayerInTeam=N",
                      where='T.DateStart >= "2025-06-01" AND (TP.Role LIKE "%Coach%" OR TP.Role LIKE "%Analyst%")', order_by="T.DateStart DESC")
    redirects = {}
    names = [t["name"] for t in teams]
    for i in range(0, len(names), 40):
        chunk = names[i:i + 40]
        for r in cargo(tables="TeamRedirects", fields="AllName,_pageName=Page", where="AllName IN (" + ",".join(q(n) for n in chunk) + ")"):
            redirects[norm(r["AllName"])] = r["Page"]
    byteam = {}
    for r in recent: byteam.setdefault(norm(r["Team"]), []).append(r)

    ALIAS = {"Beijing JDG Esports": "JD Gaming", "Shenzhen NINJAS IN PYJAMAS": "Ninjas in Pyjamas.CN", "RED Kalunga": "RED Canids",
             "GZ Academy": "Ground Zero Academy", "Saigon MVKE Academy": "MVK Esports Academy"}

    def match(t):
        keys = [norm(ALIAS.get(t["name"], t["name"])), norm(redirects.get(norm(t["name"]), ""))]
        for k in keys:
            if k and k in byteam: return byteam[k]
        # "Gen.G Esports" / "Gen.G", "Cloud9 Kia" / "Cloud9": the longest listed team name inside ours.
        best = None
        for k, v in byteam.items():
            if len(k) >= 3 and (k in keys[0] or keys[0] in k) and (best is None or len(k) > len(best[0])): best = (k, v)
        return best[1] if best else None
    unmatched = []
    for t in teams:
        rows = match(t)
        if not rows: unmatched.append(t["name"]); continue
        latest = max(r["Start"] or "" for r in rows)
        seen = set()
        for r in sorted(rows, key=lambda r: int(r.get("N") or 99)):
            if (r["Start"] or "") != latest or r["Link"] in seen: continue
            seen.add(r["Link"])
            role, pos = role_of(r["Role"])
            if not role: continue
            e = out.setdefault(r["Link"], {"title": "lol", "handle": r["Link"].split(" (")[0], "page": r["Link"], "past": []})
            e.update({"team": t["name"], "role": role, "spec": pos, "src": "https://lol.fandom.com/wiki/" + urllib.parse.quote(r["Link"].replace(" ", "_"))})
        # Several "Coach" entries without a head coach: the first listed leads, the others assist.
    for t in teams:
        mine = [e for e in out.values() if e.get("team") == t["name"]]
        if mine and not any(e["role"] == "head-coach" for e in mine):
            c = next((e for e in mine if e["role"] == "coach"), None)
            if c: c["role"] = "head-coach"
        for e in mine:
            if e["role"] == "coach": e["role"] = "assistant-coach"
    print("LoL: %d current staff for %d teams; unmatched: %s" % (len(out), len(teams) - len(unmatched), unmatched), file=sys.stderr)

    # 2. Coaches of every champion and runner-up in honours.json.
    evs = [e for e in honours["events"] if e["t"] == "lol" and e.get("w")]
    names = sorted(set(e["n"] for e in evs))
    coached = []
    for i in range(0, len(names), 30):
        chunk = names[i:i + 30]
        coached += all_rows(tables="TournamentPlayers=TP,Tournaments=T", join_on="TP.OverviewPage=T.OverviewPage",
                            fields="TP.Team=Team,TP.Link=Link,TP.Role=Role,T.Name=Tournament,TP.N_PlayerInTeam=N",
                            where="T.Name IN (" + ",".join(q(n) for n in chunk) + ') AND TP.Role LIKE "%Coach%"')
    byev = {}
    for r in coached: byev.setdefault(r["Tournament"], []).append(r)
    # Events kept as one name whose rosters sit on their stages ("Worlds 2024" → "Worlds 2024 Main Event").
    for n in names:
        if n in byev: continue
        rows = all_rows(tables="TournamentPlayers=TP,Tournaments=T", join_on="TP.OverviewPage=T.OverviewPage",
                        fields="TP.Team=Team,TP.Link=Link,TP.Role=Role,T.Name=Tournament,TP.N_PlayerInTeam=N",
                        where="T.Name LIKE " + q(n + " %") + ' AND TP.Role LIKE "%Coach%"')
        seen = set()
        for r in rows:
            if (r["Team"], r["Link"]) in seen: continue
            seen.add((r["Team"], r["Link"])); byev.setdefault(n, []).append(r)
    for v in byev.values(): v.sort(key=lambda r: int(r.get("N") or 99))
    n_hon = 0
    for e in evs:
        for team, won in ((e["w"], True), (e.get("r"), False)):
            if not team: continue
            for r in byev.get(e["n"], []):
                if norm(r["Team"]) != norm(team) and not (norm(team) in norm(r["Team"]) or norm(r["Team"]) in norm(team)): continue
                role, _ = role_of(r["Role"])
                mates = [x for x in byev[e["n"]] if norm(x["Team"]) == norm(r["Team"])]
                marked = any(role_of(x["Role"])[0] == "head-coach" for x in mates)
                first = next((x for x in mates if role_of(x["Role"])[0] in ("coach", "head-coach")), None)
                head = role == "head-coach" or (not marked and role == "coach" and first is not None and first["Link"] == r["Link"])
                c = out.setdefault(r["Link"], {"title": "lol", "handle": r["Link"].split(" (")[0], "page": r["Link"], "past": [], "team": "",
                                               "role": "head-coach" if head else "assistant-coach", "spec": "", "src": "https://lol.fandom.com/wiki/" + urllib.parse.quote(r["Link"].replace(" ", "_"))})
                key = (e["n"], won)
                if any((p["label"], p["won"]) == key for p in c["past"]): continue
                c["past"].append({"y": e["y"], "label": e["n"], "team": team, "tier": e["tier"], "pr": e.get("pr", 1.0), "won": won, "head": head})
                n_hon += 1
    print("LoL: %d coach honours (champions and runners-up)" % n_hon, file=sys.stderr)

    # 3. Who they are: name, nationality, birth date, retired.
    pages = sorted(out.keys())
    for i in range(0, len(pages), 40):
        chunk = pages[i:i + 40]
        for r in cargo(tables="Players", fields="OverviewPage,ID,Name,NativeName,Country,Birthdate,IsRetired,Team",
                       where="OverviewPage IN (" + ",".join(q(p) for p in chunk) + ")"):
            e = out.get(r["OverviewPage"])
            if not e: continue
            e["handle"] = r.get("ID") or e["handle"]
            e["name"] = r.get("Name") or ""
            e["nat"] = country_code(r.get("Country"))
            e["birth"] = r.get("Birthdate") or None
            e["retired"] = r.get("IsRetired") == "1" and not e.get("team")
    return list(out.values())


def from_rosters():
    out = []
    d = json.load(open(os.path.join(REAL, "rosters.json")))
    for t in d["teams"]:
        staff = [r for r in t["roster"] if r.get("status") == "staff"]
        has_head = any((r.get("role_text") or "").lower() in ("head coach",) for r in staff)
        for r in staff:
            txt = (r.get("role_text") or "").lower()
            if t["title_id"] == "mlbb":
                role = {"coach": "head-coach", "asst. coach": "assistant-coach", "analyst": "analyst"}.get(txt)
            else:
                role = {"head coach": "head-coach", "coach": "assistant-coach" if has_head else "head-coach", "assistant coach": "assistant-coach",
                        "performance coach": "performance-coach", "analyst": "analyst", "manager": "team-manager"}.get(txt)
            if not role: continue
            out.append({"title": t["title_id"], "handle": r["handle"], "name": r.get("real_name") or "", "nat": r.get("nationality") or r.get("nationality_estimate"),
                        "birth": None, "team": t["name"], "role": role, "spec": "", "src": t.get("source_url", ""), "past": []})
    w = json.load(open(os.path.join(REAL, "wildrift.json")))
    for t in w["teams"]:
        for r in t["roster"]:
            if r.get("status") != "staff": continue
            m = re.match(r"(.*?)\s*\"'*([^'\"]+)'*\"\s*(.*)", r["handle"])
            handle, name = (m.group(2), (m.group(1) + " " + m.group(3)).strip()) if m else (r["handle"], "")
            out.append({"title": "wildrift", "handle": handle, "name": name, "nat": None, "birth": None, "team": t["name"] if t.get("league_id") else "",
                        "role": "head-coach", "spec": "", "src": t.get("source_url", ""), "past": []})
    return out


def main():
    honours = json.load(open(os.path.join(REAL, "honours.json")))
    rosters = json.load(open(os.path.join(REAL, "rosters.json")))
    lol_teams = [t for t in rosters["teams"] if t["title_id"] == "lol"]
    # The LoL result is kept between runs (lol.json in the cache): re-running only the merge asks Leaguepedia nothing.
    keep = os.path.join(CACHE, "lol.json")
    if os.path.exists(keep): lol_staff = json.load(open(keep))
    else:
        lol_staff = lol(lol_teams, honours)
        json.dump(lol_staff, open(keep, "w"), ensure_ascii=False)
    staff = lol_staff + from_rosters()
    comp = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "compiled_staff.json")))
    have = {(s["title"], norm(s["handle"])) for s in staff}
    for s in comp["staff"]:
        key = (s["title"], norm(s["handle"]))
        if key in have:
            # A compiled entry adds titles to a coach already found (never replaces the live data).
            for e in staff:
                if (e["title"], norm(e["handle"])) == key: e["past"] += [p for p in s.get("past", []) if p not in e["past"]]
            continue
        s.setdefault("src", "compiled"); s.setdefault("past", []); s.setdefault("spec", "")
        staff.append(s)
    for s in staff:
        for k in ("page",): s.pop(k, None)
        s["past"].sort(key=lambda p: -p["y"])
    staff.sort(key=lambda s: (s["title"], s.get("team") or "~", s["role"], norm(s["handle"])))
    snap = {"snapshot_id": rosters["snapshot_id"], "as_of": rosters["as_of"],
            "sources": {"leaguepedia": "Leaguepedia Cargo API (lol.fandom.com): tournament rosters (coaches) of 2025–2026 events and of every champion and runner-up in honours.json; Players table for name, nationality, birth date",
                        "rosters": "Staff listed on the team pages collected in rosters.json (MLBB: official MPL sites; VALORANT: vlr.gg) and wildrift.json (Wild Rift Esports Wiki)",
                        "compiled": "Compiled from public records (CS2, Dota 2, VALORANT and LoL coaches whose role and titles are well documented); not checked against a live source in this export"},
            "staff": staff}
    json.dump(snap, open(os.path.join(REAL, "staff.json"), "w"), ensure_ascii=False, separators=(",", ":"))
    by = {}
    for s in staff: by[s["title"]] = by.get(s["title"], 0) + 1
    print("staff.json: %d people %s" % (len(staff), by), file=sys.stderr)


if __name__ == "__main__":
    main()
