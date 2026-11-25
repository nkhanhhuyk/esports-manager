#!/usr/bin/env python3
"""
Finals MVPs for the League of Legends events in src/data/real/honours.json, from the Leaguepedia
Cargo API (lol.fandom.com): Tournaments (event pages by name) and MatchSchedule (the last series
between the champion and the runner-up, and its MVP where the page records one). Read-only queries,
four seconds apart, every response cached; a rate-limit answer stops the script (no retry).

Usage: python3 scripts/data/finals_mvp.py [cache-dir]
"""
import hashlib, json, os, re, sys, time, unicodedata, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HON = os.path.join(ROOT, "src", "data", "real", "honours.json")
CACHE = sys.argv[1] if len(sys.argv) > 1 else "/tmp/finals-cache"
os.makedirs(CACHE, exist_ok=True)
API = "https://lol.fandom.com/api.php"
UA = "esports-manager-data/1.0 (data snapshot script)"
last = [0.0]


def cargo(**q):
    q.update({"action": "cargoquery", "format": "json"})
    url = API + "?" + urllib.parse.urlencode(q)
    path = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest() + ".json")
    if os.path.exists(path): return json.load(open(path))
    wait = 4.0 - (time.time() - last[0])
    if wait > 0: time.sleep(wait)
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=60) as r:
        data = json.loads(r.read().decode("utf-8"))
    last[0] = time.time()
    if "error" in data: raise SystemExit("stopped: " + json.dumps(data["error"])[:300])
    rows = [x["title"] for x in data.get("cargoquery", [])]
    json.dump(rows, open(path, "w"))
    return rows


def norm(s): return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", s or "").lower())
def q(s): return '"' + s.replace('"', '\\"') + '"'


def main():
    h = json.load(open(HON))
    evs = [e for e in h["events"] if e["t"] == "lol" and e.get("w") and e.get("r")]
    names = sorted(set(e["n"] for e in evs))
    pages = {}
    for i in range(0, len(names), 40):
        chunk = names[i:i + 40]
        for r in cargo(tables="Tournaments", fields="Name,OverviewPage", where="Name IN (" + ",".join(q(n) for n in chunk) + ")", limit="500"):
            pages.setdefault(r["Name"], []).append(r["OverviewPage"])
    # Events kept under one name with their final on a stage page ("Worlds 2024" -> "Worlds 2024 Main Event").
    for n in names:
        if n in pages: continue
        for r in cargo(tables="Tournaments", fields="Name,OverviewPage", where="Name LIKE " + q(n + " %"), limit="50"):
            pages.setdefault(n, []).append(r["OverviewPage"])
    allp = sorted(set(p for v in pages.values() for p in v))
    matches = []
    for i in range(0, len(allp), 25):
        chunk = allp[i:i + 25]
        off = 0
        while True:
            rows = cargo(tables="MatchSchedule", fields="OverviewPage,Team1,Team2,Winner,MVP,DateTime_UTC=Date",
                         where="OverviewPage IN (" + ",".join(q(p) for p in chunk) + ")", limit="500", offset=str(off), order_by="DateTime_UTC DESC")
            matches += rows
            if len(rows) < 500: break
            off += 500
    byPage = {}
    for m in matches: byPage.setdefault(m["OverviewPage"], []).append(m)
    found = 0
    for e in evs:
        cands = [m for p in pages.get(e["n"], []) for m in byPage.get(p, []) if {norm(m["Team1"]), norm(m["Team2"])} == {norm(e["w"]), norm(e["r"])}]
        if not cands: continue
        final = max(cands, key=lambda m: m.get("Date") or "")
        if final.get("MVP"):
            e["fm"] = final["MVP"].split(" (")[0]
            found += 1
    h["sources"]["leaguepedia_mvp"] = "Leaguepedia Cargo API: MatchSchedule MVP of the last series between each LoL champion and runner-up (finals MVP)"
    json.dump(h, open(HON, "w"), ensure_ascii=False, separators=(",", ":"))
    print("finals MVPs: %d of %d LoL finals" % (found, len(evs)), file=sys.stderr)


if __name__ == "__main__":
    main()
