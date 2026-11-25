#!/usr/bin/env python3
"""
Real career movements (team spells) for the players and staff in the real-data snapshot,
written to src/data/real/careers.json.

* League of Legends: Leaguepedia Cargo API (lol.fandom.com) — the Tenures table joined to
  RosterChanges for the role of each spell (player position or staff role).
* VALORANT: the VALORANT Esports Wiki Cargo API (valorant-esports.fandom.com), same tables; that
  wiki's roster records are thinner after 2022.
* Every game: the spells the snapshot already holds — the team on the current roster
  (rosters.json, wildrift.json), the last team seen (history.json) and the champion and runner-up
  rosters of honours.json (a team and a year).

Read-only queries, four seconds apart, every response cached under a sha1 of its URL; a
rate-limit answer stops the script (no retry).

Usage: python3 scripts/data/careers.py [cache-dir] [--offline]
"""
import hashlib, json, os, re, sys, time, unicodedata, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REAL = os.path.join(ROOT, "src", "data", "real")
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
OFFLINE = "--offline" in sys.argv
CACHE = ARGS[0] if ARGS else "/tmp/careers-cache"
os.makedirs(CACHE, exist_ok=True)
UA = "esports-manager-data/1.0 (data snapshot script)"
WIKI = {"lol": "https://lol.fandom.com/api.php", "valorant": "https://valorant-esports.fandom.com/api.php"}
last = [0.0]
stats = {"requests": 0, "cached": 0}


def cargo(api, **q):
    q.update({"action": "cargoquery", "format": "json"})
    url = api + "?" + urllib.parse.urlencode(q)
    path = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest() + ".json")
    if os.path.exists(path):
        stats["cached"] += 1
        return json.load(open(path))
    if OFFLINE: return []
    wait = 4.0 - (time.time() - last[0])
    if wait > 0: time.sleep(wait)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=90) as r:
        data = json.loads(r.read().decode("utf-8"))
    last[0] = time.time()
    stats["requests"] += 1
    if "error" in data: raise SystemExit("stopped: " + json.dumps(data["error"])[:300])
    rows = [x["title"] for x in data.get("cargoquery", [])]
    json.dump(rows, open(path, "w"))
    return rows


def norm(s): return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", s or "").lower())
def q(s): return '"' + s.replace('"', '\\"') + '"'


def ym(d):
    m = re.match(r"(\d{4})-(\d{2})", d or "")
    return m.group(1) + "-" + m.group(2) if m else ""


STAFF_ROLE = {"head coach": "head-coach", "coach": "coach", "assistant coach": "assistant", "strategic coach": "strategic-coach",
              "analyst": "analyst", "positional coach": "positional-coach", "manager": "manager", "team manager": "manager",
              "general manager": "manager", "owner": "owner", "performance coach": "performance", "sub": "sub", "substitute": "sub"}


def role_of(r):
    r = (r or "").strip()
    low = r.lower()
    if low in STAFF_ROLE: return STAFF_ROLE[low]
    if "coach" in low: return "coach"
    if "analyst" in low: return "analyst"
    if "manager" in low: return "manager"
    return low  # positions: top, jungle, mid, bot, support, duelist...


def merge(spells):
    """One line per unbroken spell at a team in one capacity (records split a spell at each roster edit)."""
    spells.sort(key=lambda s: (s[1] or "9999", s[2] or "9999"))
    out = []
    for s in spells:
        prev = next((o for o in reversed(out) if norm(o[0]) == norm(s[0]) and (o[3] in ("", "sub") or s[3] in ("", "sub") or (o[3] in STAFF_ROLE.values()) == (s[3] in STAFF_ROLE.values()))), None)
        if prev is not None and prev[2] and s[1] and s[1] <= next_month(prev[2], 2):
            prev[2] = s[2] if (s[2] == "" or s[2] > prev[2]) else prev[2]
            if not prev[3] or prev[3] == "sub": prev[3] = s[3] or prev[3]
            continue
        if prev is not None and not prev[2]: continue  # an open spell already covers it
        out.append(list(s))
    # A player is at one team at a time: an open spell ends when the next spell elsewhere begins (stale records).
    staff = set(STAFF_ROLE.values()) - {"sub"}
    for i, o in enumerate(out):
        if o[2] or o[3] in staff: continue
        later = [x for x in out[i + 1:] if x[3] not in staff and norm(x[0]) != norm(o[0]) and x[1] and x[1] >= o[1]]
        if later: o[2] = later[0][1]
    return out


def next_month(m, k):
    y, mo = int(m[:4]), int(m[5:7]) + k
    while mo > 12: y += 1; mo -= 12
    return "%04d-%02d" % (y, mo)


def main():
    rosters = json.load(open(os.path.join(REAL, "rosters.json")))
    history = json.load(open(os.path.join(REAL, "history.json")))
    staff = json.load(open(os.path.join(REAL, "staff.json")))["staff"]
    honours = json.load(open(os.path.join(REAL, "honours.json")))
    wildrift = json.load(open(os.path.join(REAL, "wildrift.json")))
    names = {}  # title -> {norm handle: handle}
    def add(t, h):
        if h: names.setdefault(t, {}).setdefault(norm(h), h)
    for tm in rosters["teams"]:
        for p in tm.get("roster", []): add(tm["title_id"], p.get("handle"))
    for p in history["players"]: add(p["title_id"], p["handle"])
    for s in staff: add(s["title"], s["handle"])
    facts = json.load(open(os.path.join(REAL, "playerFacts.json")))["players"]
    for k in facts:
        t, h = k.split("|", 1)
        if t in ("lol", "valorant"): pass  # keys are normalised; the display handles come from the lists above
    # Stage records (several groups, a regular season) once merged many teams' players into one "winner"
    # roster: rosters of more than nine names are not one team's and are left out here.
    def roster_ok(lst): return lst and len(lst) <= 9

    # What the snapshot records: the current roster, the last team seen, champion and runner-up rosters.
    snap = {}
    def seen(t, h, team, year, role, src):
        if not h or not team: return
        lst = snap.setdefault(t + "|" + norm(h), [])
        y = str(year) if year else ""
        for s in lst:
            if norm(s[0]) == norm(team):
                if y and (not s[1] or y < s[1][:4]): s[1] = y
                if y and s[2] and y > s[2][:4]: s[2] = y
                return
        lst.append([team, y, y, role or "", src])
    as_of = int(str(rosters.get("as_of", "2026"))[:4]) if str(rosters.get("as_of", ""))[:4].isdigit() else 2026
    for tm in rosters["teams"]:
        for p in tm.get("roster", []):
            seen(tm["title_id"], p.get("handle"), tm["name"], as_of, p.get("role", ""), "roster")
    for tm in wildrift.get("teams", []):
        for p in tm.get("roster", []) if isinstance(tm.get("roster"), list) else []:
            h = p.get("handle") if isinstance(p, dict) else p
            seen("wildrift", h, tm.get("name"), 2021, (p.get("role", "") if isinstance(p, dict) else ""), "wiki-2021")
    for p in history["players"]:
        seen(p["title_id"], p["handle"], p.get("last_team"), p.get("last_season"), p.get("role", ""), "last-seen")
    for e in honours["events"]:
        for side in ("wp", "rp"):
            team = e.get("w" if side == "wp" else "r")
            if roster_ok(e.get(side)):
                for h in e.get(side): seen(e["t"], h, team, e.get("y"), "", "honours")
    for s in staff:
        for pe in s.get("past", []):
            seen(s["title"], s["handle"], pe.get("team"), pe.get("y"), s.get("role", ""), "honours")

    def same_team(a, b):
        a, b = norm(a), norm(b)
        return a == b or (len(a) >= 3 and len(b) >= 3 and (a in b or b in a)) or a[:6] == b[:6]

    out = dict(snap)  # "title|norm" -> [[team, from, to, role, src]]
    # Wiki tenures (LoL, VALORANT): exact months and roles. A wiki page can belong to another person with the
    # same handle, so a wiki career is used only when it shares a team with what the snapshot knows.
    mismatched = 0
    for t, api in WIKI.items():
        lst = sorted(names.get(t, {}).values(), key=str.lower)
        rows = []
        for i in range(0, len(lst), 40):
            chunk = lst[i:i + 40]
            off = 0
            while True:
                r = cargo(api, tables="Tenures=T,RosterChanges=RC", join_on="T.RosterChangeIdJoin=RC.RosterChangeId",
                          fields="T.Player=Player,T.Team=Team,T.DateJoin=DateJoin,T.DateLeave=DateLeave,RC.Role=Role",
                          where="T.Player IN (" + ",".join(q(n) for n in chunk) + ")", limit="500", offset=str(off), order_by="T.DateJoin")
                rows += r
                if len(r) < 500: break
                off += 500
        by = {}
        for r in rows:
            if not r.get("Team"): continue
            by.setdefault(norm(r["Player"]), []).append([r["Team"], ym(r.get("DateJoin")), ym(r.get("DateLeave")), role_of(r.get("Role")), "wiki"])
        used = 0
        for k, sp in by.items():
            key = t + "|" + k
            known = [x[0] for x in snap.get(key, [])]
            if known and not any(same_team(a[0], b) for a in sp for b in known):
                mismatched += 1
                continue
            out[key] = merge(sp)
            used += 1
        print("%s: %d people with wiki tenures (%d records), %d used" % (t, len(by), len(rows), used), file=sys.stderr)
    print("wiki careers left out (another person with the same handle): %d" % mismatched, file=sys.stderr)
    for k in out: out[k].sort(key=lambda s: (s[1] or "9999", s[2] or "9999"))
    res = {"snapshot_id": 19, "as_of": time.strftime("%Y-%m-%d"),
           "sources": {"lol": "Leaguepedia Cargo API (lol.fandom.com): Tenures joined to RosterChanges (team, join and leave month, role)",
                       "valorant": "VALORANT Esports Wiki Cargo API (valorant-esports.fandom.com): Tenures joined to RosterChanges; thinner after 2022",
                       "snapshot": "Other games and people the wikis miss: the current roster (rosters.json, wildrift.json), the last team seen (history.json) and champion/runner-up rosters (honours.json) — a team and a year, not exact dates"},
           "format": "title|normalised handle -> [team, from YYYY-MM or YYYY, to (empty while current), role, source]",
           "careers": out}
    json.dump(res, open(os.path.join(REAL, "careers.json"), "w"), ensure_ascii=False, separators=(",", ":"))
    print("careers: %d people · requests %d, cached %d" % (len(out), stats["requests"], stats["cached"]), file=sys.stderr)


if __name__ == "__main__":
    main()
