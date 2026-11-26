#!/usr/bin/env python3
"""
Real competition history for the games beyond League of Legends, added to src/data/real/honours.json
(development-time only; facts and links, nothing copied):

    python3 scripts/data/history_more.py <cache dir> [--offline]

  * VALORANT: every VCT event vlr.gg lists for 2021-2026 (regional Challengers, the international leagues,
    Kickoffs, Stages, Last Chance Qualifiers), champion and runner-up from the event's prize distribution, the
    players from the event's stats page (by team tag, from the team page).
  * Dota 2: every OpenDota 'premium' league with a final (its last match): winner, runner-up, both line-ups.
  * MLBB: MPL Indonesia and MPL Philippines, every season: the grand final on the season schedule, the
    teams' season rosters from their season team pages.
  * CS2: every finished S-tier tournament on bo3.gg: the final (its last match), both line-ups (players are
    placed with the side whose team they were on that year when the match lists their current team).
Events already in honours.json (Majors, The International, VCT Masters / Champions) are not duplicated.
Requests are spaced (vlr.gg 1.3 s, OpenDota 4 s, MPL 0.6 s, bo3.gg 1.2 s) and cached; a 429 stops a source.
"""
import hashlib
import html
import json
import os
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from collections import defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
REAL = os.path.join(ROOT, "src", "data", "real")
CACHE = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else ".cache/history-more"
OFFLINE = "--offline" in sys.argv
UA = "EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)"
os.makedirs(CACHE, exist_ok=True)
last = defaultdict(float)
stopped = set()
stats = {"requests": 0, "cached": 0}
SOURCES = ("vlr.gg", "opendota", "mpl", "bo3.gg")


def fetch(url, gap):
    """(final url, text) of a URL, cached (the same cache format as past_rosters.py), or (url, None)."""
    key = urllib.parse.urlparse(url).netloc
    path = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest() + ".json")
    if os.path.exists(path):
        stats["cached"] += 1
        d = json.load(open(path))
        return d.get("final", url), d.get("text")
    if OFFLINE or key in stopped: return url, None
    wait = gap - (time.time() - last[key])
    if wait > 0: time.sleep(wait)
    final, text = url, None
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=120) as r:
            final = r.geturl(); text = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        last[key] = time.time()
        if e.code == 429:
            print("429 from %s: that source stops here (no retry)" % key, file=sys.stderr); stopped.add(key)
        return url, None
    except Exception as e:
        print("error %s: %s" % (url[:120], e), file=sys.stderr); last[key] = time.time(); return url, None
    last[key] = time.time(); stats["requests"] += 1
    json.dump({"final": final, "text": text}, open(path, "w"))
    return final, text


def jget(url, gap):
    _, t = fetch(url, gap)
    try: return json.loads(t) if t else None
    except Exception: return None


def norm(s): return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", s or "").lower())
def clean(s): return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s or ""))).strip()


def event(t, n, y, lg, tier, pr, src, w, wp, r, rp):
    return {"t": t, "n": n, "y": y, "lg": lg, "tier": tier, "pr": pr, "src": src, "w": w or "", "wp": wp or [], "r": r or "", "rp": rp or []}


# ------------------------------------------------------------------ VALORANT (vlr.gg)
def valorant(known):
    out = []
    tags = {}
    def team_tag(tid):
        if tid in tags: return tags[tid]
        _, page = fetch("https://www.vlr.gg/team/%s/" % tid, 1.3)
        m = re.search(r'class="wf-title team-header-tag">\s*([^<]+?)\s*<', page or "")
        tags[tid] = m.group(1).strip() if m else ""
        return tags[tid]
    seen = set()
    for year in range(2021, 2027):
        _, page = fetch("https://www.vlr.gg/vct-%d" % year, 1.3)
        for eid, slug in re.findall(r'href="/event/(\d+)/([^"]+)"', page or ""):
            if eid in seen: continue
            seen.add(eid)
            s = slug.lower()
            if any(k in s for k in ("game-changers", "showmatch", "china-qualifier", "ascension")) or ("qualifier" in s and "last-chance" not in s): continue
            if re.search(r"masters|valorant-champions|lock-in|champions-20\d\d$", s): continue  # in honours.json already
            _, ev = fetch("https://www.vlr.gg/event/%s/%s" % (eid, slug), 1.3)
            if not ev: continue
            title = clean((re.search(r'<h1 class="wf-title">(.*?)</h1>', ev, re.S) or [None, slug])[1])
            if norm(title) in known: continue
            places = {}
            for place, tid, body in re.findall(r'(\d)<sup>(?:st|nd)</sup>\s*</div>.*?href="/team/(\d+)/[^"]*"[^>]*>(.*?)</a>', ev, re.S):
                name = clean(re.sub(r'<div class="ge-text-light".*?</div>', "", body, flags=re.S))
                places.setdefault(int(place), (tid, name))
            if 1 not in places: continue
            _, st = fetch("https://www.vlr.gg/event/stats/%s/%s" % (eid, slug), 1.3)
            by_tag = defaultdict(list)
            for h, tg in re.findall(r'<div class="st-pl-name text-of">\s*([^<]+?)\s*</div>\s*<div class="st-pl-country">\s*([^<]*?)\s*</div>', st or ""):
                by_tag[norm(tg)].append(html.unescape(h))
            def roster(p):
                if p not in places: return []
                return by_tag.get(norm(team_tag(places[p][0])), [])[:7]
            low = title.lower()
            intl = any(k in low for k in ("americas", "emea", "pacific", "china")) and ("league" in low or "kickoff" in low or "stage" in low)
            tier, pr = ("cup", 0.3) if "last chance" in low or "lcq" in low else ("league", 1.0 if intl else 0.45)
            lg = re.sub(r"\s*(?:20\d\d)\s*", " ", title).strip()
            out.append(event("valorant", title, year, lg, tier, pr, "vlr.gg", places[1][1], roster(1), places.get(2, ("", ""))[1], roster(2)))
    return out


# ------------------------------------------------------------------ Dota 2 (OpenDota)
def dota(known):
    sql = ("select distinct on (m.leagueid) m.leagueid, l.name, m.match_id, m.start_time, m.radiant_team_id, m.dire_team_id, m.radiant_win "
           "from matches m join leagues l on l.leagueid = m.leagueid where l.tier = 'premium' and m.radiant_team_id is not null and m.dire_team_id is not null "
           "order by m.leagueid, m.start_time desc")
    rows = (jget("https://api.opendota.com/api/explorer?sql=" + urllib.parse.quote(sql), 4.0) or {}).get("rows", [])
    if not rows: return []
    ids = sorted({r["radiant_team_id"] for r in rows} | {r["dire_team_id"] for r in rows})
    names = {}
    for i in range(0, len(ids), 300):
        q = "select team_id, name from teams where team_id in (%s)" % ",".join(str(x) for x in ids[i:i + 300])
        for r in (jget("https://api.opendota.com/api/explorer?sql=" + urllib.parse.quote(q), 4.0) or {}).get("rows", []): names[r["team_id"]] = r["name"]
    mids = [r["match_id"] for r in rows]
    lineup = defaultdict(lambda: ([], []))
    pro = {p["account_id"]: p.get("name") for p in (jget("https://api.opendota.com/api/proPlayers", 2.0) or []) if p.get("name")}
    for i in range(0, len(mids), 200):
        q = "select match_id, account_id, player_slot from player_matches where match_id in (%s)" % ",".join(str(x) for x in mids[i:i + 200])
        for r in (jget("https://api.opendota.com/api/explorer?sql=" + urllib.parse.quote(q), 4.0) or {}).get("rows", []):
            n = pro.get(r["account_id"])
            if n: lineup[r["match_id"]][0 if r["player_slot"] < 128 else 1].append(n)
    out = []
    for r in rows:
        name = (r["name"] or "").strip()
        if not name or norm(name) in known or re.search(r"\binternational\b", name, re.I) and "qualifier" not in name.lower(): continue
        if re.search(r"qualifier|league season|dpc|division", name, re.I): continue
        rad, dire = names.get(r["radiant_team_id"]), names.get(r["dire_team_id"])
        if not rad or not dire: continue
        w, l = (rad, dire) if r["radiant_win"] else (dire, rad)
        wl, ll = (lineup[r["match_id"]][0], lineup[r["match_id"]][1]) if r["radiant_win"] else (lineup[r["match_id"]][1], lineup[r["match_id"]][0])
        y = time.gmtime(r["start_time"]).tm_year
        major = "major" in name.lower()
        out.append(event("dota2", name, y, name, "international", 0.7 if major else 0.4, "opendota", w, wl, l, ll))
    return out


# ------------------------------------------------------------------ MLBB (MPL ID / PH)
def mlbb(known):
    out = []
    for site, first, last_ in (("id", 1, 18), ("ph", 1, 18)):
        base = "https://%s-mpl.com" % site
        country = "Indonesia" if site == "id" else "Philippines"
        for sn in range(first, last_ + 1):
            _, sched = fetch("%s/s%d/schedule" % (base, sn), 0.6)
            if not sched: continue
            i = sched.find("Grand Final")
            blk = sched[max(0, i - 500): i + 16000] if i >= 0 else ""
            # The grand final block: team1 (logo slug, name), score, ..., score, team2.
            m = re.search(r'class="team team1.*?/teams/([a-z0-9-]+?)(?:-b)?-\d+\.\w+.*?<div class="name">\s*([^<]+?)\s*</div>.*?<div class="score[^"]*">\s*(\d+)\s*</div>.*?Grand Final.*?<div class="score[^"]*">\s*(\d+)\s*</div>.*?class="team team2.*?/teams/([a-z0-9-]+?)(?:-b)?-\d+\.\w+.*?<div class="name">\s*([^<]+?)\s*</div>', blk, re.S)
            if not m: continue
            s1, s2 = int(m.group(3)), int(m.group(4))
            if s1 == s2: continue
            a, b = (m.group(1), m.group(2)), (m.group(5), m.group(6))
            (wslug, wtag), (lslug, ltag) = (a, b) if s1 > s2 else (b, a)
            def team(slug, tag):
                final, page = fetch("%s/s%d/team/%s" % (base, sn, slug), 0.6)
                name = tag
                if page:
                    t = re.search(r"<title>\s*([^<|–-]+)", page)
                    h = re.search(r'class="team-name[^"]*">\s*([^<]+?)\s*<', page)
                    name = clean(h.group(1)) if h else (clean(t.group(1)) if t and "MPL" not in t.group(1) else tag)
                people = [html.unescape(x).strip() for x in re.findall(r'class="player-name">\s*([^<]+?)\s*</div>', page or "")]
                ign = [html.unescape(v) for v in re.findall(r'id="player-\d+-ign" value="([^"]*)"', page or "")]
                roles = re.findall(r'class="player-role[^"]*">\s*([^<]*?)\s*</div>', page or "")
                players = [p for p, r_ in zip(people, roles + [""] * len(people)) if not re.search(r"coach|analyst|manager", r_, re.I)] or ign
                return name, list(dict.fromkeys(players))[:8]
            wn, wp = team(wslug, wtag)
            ln, lp = team(lslug, ltag)
            year = 2017 + (sn + 1) // 2
            n = "MPL %s Season %d" % (site.upper(), sn)
            if norm(n) in known: continue
            out.append(event("mlbb", n, year, "MPL %s" % country, "league", 1.0, "mpl", wn, wp, ln, lp))
    return out


# ------------------------------------------------------------------ CS2 (bo3.gg)
def cs2(known, past):
    out = []
    tours = []
    off = 0
    while True:
        j = jget("https://api.bo3.gg/api/v1/tournaments?page%%5Blimit%%5D=100&page%%5Boffset%%5D=%d&filter%%5Btournaments.tier%%5D%%5Beq%%5D=s&filter%%5Btournaments.status%%5D%%5Beq%%5D=finished&filter%%5Btournaments.discipline_id%%5D%%5Beq%%5D=1&sort=start_date" % off, 1.2)
        rs = (j or {}).get("results") or []
        tours += rs
        if len(rs) < 100: break
        off += 100
    for t in tours:
        name = (t.get("name") or "").strip()
        if not name or norm(name) in known or re.search(r"\bmajor\b", name, re.I): continue
        ms = (jget("https://api.bo3.gg/api/v1/matches?filter%%5Bmatches.tournament_id%%5D%%5Beq%%5D=%d&sort=-start_date&page%%5Blimit%%5D=1" % t["id"], 1.2) or {}).get("results") or []
        if not ms or not ms[0].get("winner_team_id"): continue
        m = jget("https://api.bo3.gg/api/v1/matches/%s?with=teams,players" % ms[0]["slug"], 1.2) or {}
        wid, lid = m.get("winner_team_id"), m.get("loser_team_id")
        tn = {x["id"]: x["name"] for x in (m.get("team1"), m.get("team2")) if isinstance(x, dict)}
        if wid not in tn or lid not in tn: continue
        y = int((t.get("start_date") or "0000")[:4])
        sides = {wid: [], lid: []}
        loose = []
        for p in m.get("players") or []:
            (sides[p["team_id"]] if p.get("team_id") in sides else loose).append(p["nickname"])
        # Players listed with their current team: the side whose roster that year had them.
        for h in loose:
            for tid in (wid, lid):
                if norm(h) in past.get((norm(tn[tid]), y), set()) and len(sides[tid]) < 5: sides[tid].append(h); break
        big = re.search(r"katowice|cologne|world final|grand slam", name, re.I)
        out.append(event("cs2", name, y, name, "international", 0.7 if big else 0.4, "bo3.gg", tn[wid], sides[wid], tn[lid], sides[lid]))
    return out


def main():
    path = os.path.join(REAL, "honours.json")
    doc = json.load(open(path))
    doc["events"] = [e for e in doc["events"] if e.get("src") not in SOURCES]
    known = {norm(e["n"]) for e in doc["events"]}
    past = defaultdict(set)
    pr = json.load(open(os.path.join(REAL, "pastRosters.json")))["teams"]
    for k, v in pr.items():
        if not k.startswith("cs2|"): continue
        for y, o in v["y"].items():
            for e in o["p"]: past[(norm(v["n"]), int(y))].add(norm(e[0]))
    added = {}
    for name, fn in (("valorant", lambda: valorant(known)), ("dota2", lambda: dota(known)), ("mlbb", lambda: mlbb(known)), ("cs2", lambda: cs2(known, past))):
        evs = fn()
        added[name] = len(evs)
        doc["events"] += evs
        known |= {norm(e["n"]) for e in evs}
        print("%s: %d events" % (name, len(evs)), file=sys.stderr)
    doc["sources"]["vlr.gg"] = "vlr.gg event pages: VCT regional Challengers, international leagues, Kickoffs, Stages and LCQs 2021-2026 (champion, runner-up, event players)"
    doc["sources"]["opendota"] = "OpenDota: every premium league's final (last match), winner, runner-up and line-ups"
    doc["sources"]["mpl"] = "MPL Indonesia / Philippines official sites: every season's grand final and the finalists' season rosters"
    doc["sources"]["bo3.gg"] = "bo3.gg API: every finished S-tier CS tournament's final and line-ups"
    doc["coverage"] = ("League of Legends: Worlds, MSI, First Stand, EWC, IEM and every top domestic league; CS: every Major and every S-tier "
                       "tournament on bo3.gg; Dota 2: every TI and every premium event (Majors and more); VALORANT: Masters, Champions and every "
                       "VCT league, Kickoff, Stage, Challengers and LCQ on vlr.gg; MLBB: every MPL Indonesia and MPL Philippines season. "
                       "Not included: Honor of Kings (the game's HoK players are fictional) and Wild Rift.")
    json.dump(doc, open(path, "w"), ensure_ascii=False, separators=(",", ":"))
    print("added %s · requests %d, cached %d" % (added, stats["requests"], stats["cached"]), file=sys.stderr)


if __name__ == "__main__":
    main()
