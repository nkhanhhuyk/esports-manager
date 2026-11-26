#!/usr/bin/env python3
"""
Photo links for the real players the game adds from the past rosters, the honours rosters and the career
records (development-time only; only LINKS go into src/data/real/images.json, nothing is copied):

    python3 scripts/data/past_pictures.py <cache dir> [--offline]

Sources, for players that have no photo link yet:
  * LoL: the LoL Esports gateway (getTeams lists archived teams with their last rosters), matched by handle
    and a team the player was on.
  * Wikidata (CC0) professional gamers with an image (P18) and a pseudonym, nickname or alias, every game: the photo on
    Wikimedia Commons (free licences), as a 240 px thumbnail. A match needs the handle AND one more fact:
    the real name (facts snapshot), a team the player was on (P54 against the rosters), or, for a handle
    that only one Wikidata gamer and one game have, the same country. MLBB / HoK need the name or a team.
  * VALORANT: the player's vlr.gg page (ids from the cached vlr.gg transaction pages), its avatar.
  * CS2: the bo3.gg public player list (nickname → photo), as for the current players.
Requests are spaced (Wikidata 2 s, vlr.gg 1.3 s, bo3.gg 1.2 s) and cached; a 429 stops that source.
"""
import hashlib
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
CACHE = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else ".cache/past-pictures"
OFFLINE = "--offline" in sys.argv
UA = "EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)"
os.makedirs(CACHE, exist_ok=True)
last = defaultdict(float)
stopped = set()
stats = {"requests": 0, "cached": 0}


def fetch(url, gap, accept=None, extra=None):
    key = urllib.parse.urlparse(url).netloc
    path = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest() + ".json")
    if os.path.exists(path):
        stats["cached"] += 1
        return json.load(open(path)).get("text")
    if OFFLINE or key in stopped:
        return None
    wait = gap - (time.time() - last[key])
    if wait > 0: time.sleep(wait)
    headers = {"User-Agent": UA}
    if accept: headers["Accept"] = accept
    if extra: headers.update(extra)
    text = None
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=180) as r:
            text = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code == 429:
            print("429 from %s: that source stops here (no retry)" % key, file=sys.stderr)
            stopped.add(key)
        last[key] = time.time()
        if e.code != 404: return None
    except Exception as e:
        print("error %s: %s" % (url[:120], e), file=sys.stderr)
        last[key] = time.time()
        return None
    last[key] = time.time()
    stats["requests"] += 1
    json.dump({"text": text}, open(path, "w"))
    return text


def norm(s): return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", s or "").lower())


GENERIC = {"esports", "esport", "gaming", "team", "club", "gg", "the", "e", "sports"}


def tkey(s):
    words = [norm(w) for w in re.split(r"[\s\-_.]+", unicodedata.normalize("NFKD", s or "").lower()) if norm(w)]
    return "".join(w for w in words if w not in GENERIC) or norm(s)


def parts(s):
    return {norm(x) for x in re.split(r"[^\w]+", unicodedata.normalize("NFKD", s or "").lower()) if len(norm(x)) >= 2}


def same_name(a, b):
    pa, pb = parts(a), parts(b)
    return bool(pa and pb and len(pa & pb) >= min(2, len(pa), len(pb)))


def main():
    images = json.load(open(os.path.join(REAL, "images.json")))
    have = images["players"]
    facts = json.load(open(os.path.join(REAL, "playerFacts.json")))["players"]
    past = json.load(open(os.path.join(REAL, "pastRosters.json")))["teams"]
    careers = json.load(open(os.path.join(REAL, "careers.json")))["careers"]
    honours = json.load(open(os.path.join(REAL, "honours.json")))["events"]
    rosters = json.load(open(os.path.join(REAL, "rosters.json")))
    history = json.load(open(os.path.join(REAL, "history.json")))

    # Every real player the game knows: title|norm(handle) -> handle, flags, teams.
    people = {}
    def person(ti, h):
        k = ti + "|" + norm(h)
        if not norm(h): return None
        return people.setdefault(k, {"t": ti, "h": h, "nat": set(), "teams": set()})
    for t in rosters["teams"]:
        for p in t.get("roster", []):
            e = person(t["title_id"], p["handle"])
            if e: e["teams"].add(tkey(t["name"])); e["nat"].add((p.get("nationality") or "").upper())
    for p in history["players"]:
        e = person(p["title_id"], p["handle"])
        if e and p.get("last_team"): e["teams"].add(tkey(p["last_team"]))
    for k, v in past.items():
        ti = k.split("|")[0]
        for y in v["y"].values():
            for r in y["p"]:
                e = person(ti, r[0])
                if e:
                    e["teams"].add(tkey(v["n"])); e["nat"].add((r[2] or "").upper())
                    for a in y.get("as", []): e["teams"].add(tkey(a))
    for ev in honours:
        for side in (("w", "wp"), ("r", "rp")):
            for h in ev.get(side[1]) or []:
                e = person(ev["t"], h)
                if e and ev.get(side[0]): e["teams"].add(tkey(ev[side[0]]))
    for k, spells in careers.items():
        e = people.get(k)
        if e:
            for sp in spells: e["teams"].add(tkey(sp[0]))
    for e in people.values():
        f = facts.get(e["t"] + "|" + norm(e["h"])) or {}
        if f.get("countryCode"): e["nat"].add(f["countryCode"].upper())
        e["name"] = f.get("name")
        e["nat"].discard("")
    missing = {k: e for k, e in people.items() if k not in have}
    print("real players %d, without a photo %d" % (len(people), len(missing)), file=sys.stderr)
    added = defaultdict(int)

    # ------------------------------------------------------------------ Wikidata / Wikimedia Commons
    # Handles: the pseudonym (P742), the nickname (P1449) or an English alias.
    q = """SELECT ?p ?label ?pseud ?img ?cc ?teamLabel WHERE {
  ?p wdt:P106 wd:Q4379701 ; wdt:P18 ?img .
  { ?p wdt:P742 ?pseud } UNION { ?p wdt:P1449 ?pseud } UNION { ?p skos:altLabel ?pseud . FILTER(LANG(?pseud) = "en") }
  OPTIONAL { ?p rdfs:label ?label . FILTER(LANG(?label) = "en") }
  OPTIONAL { ?p wdt:P27 ?c . ?c wdt:P297 ?cc . }
  OPTIONAL { ?p wdt:P54 ?team . ?team rdfs:label ?teamLabel . FILTER(LANG(?teamLabel) = "en") }
}"""
    wd = fetch("https://query.wikidata.org/sparql?format=json&query=" + urllib.parse.quote(q), 2.0, "application/sparql-results+json")
    ents = {}
    for b in (json.loads(wd) if wd else {}).get("results", {}).get("bindings", []):
        v = {k: x["value"] for k, x in b.items()}
        e = ents.setdefault(v["p"], {"pseud": set(), "img": v["img"], "label": v.get("label", ""), "cc": set(), "teams": set()})
        e["pseud"].add(v["pseud"])
        if v.get("cc"): e["cc"].add(v["cc"].upper())
        if v.get("teamLabel"): e["teams"].add(tkey(v["teamLabel"]))
    by_handle = defaultdict(list)
    for e in ents.values():
        for ps in e["pseud"]: by_handle[norm(ps)].append(e)
    titles_of = defaultdict(set)
    for k in people: titles_of[k.split("|", 1)[1]].add(k.split("|")[0])
    for k, p in list(missing.items()):
        cands = by_handle.get(norm(p["h"]), [])
        pick = None
        for e in cands:
            if p.get("name") and same_name(p["name"], e["label"]): pick = e; break
            if p["teams"] & e["teams"]: pick = e; break
        if pick is None and len(cands) == 1 and len(titles_of[norm(p["h"])]) == 1 and p["t"] not in ("mlbb", "hok"):
            e = cands[0]
            if p["nat"] and e["cc"] and p["nat"] & e["cc"]: pick = e
        if pick is None: continue
        fn = urllib.parse.unquote(pick["img"].rsplit("/", 1)[-1])
        have[k] = "https://commons.wikimedia.org/wiki/Special:FilePath/" + urllib.parse.quote(fn) + "?width=240"
        added["wikidata"] += 1
        missing.pop(k)
    print("wikidata gamers with a photo %d, matched %d" % (len(ents), added["wikidata"]), file=sys.stderr)

    # ------------------------------------------------------------------ LoL: LoL Esports gateway (every team it lists, archived too)
    # The same public gateway as the current players' photos; a match needs the handle and a team the player was on.
    gw = fetch("https://esports-api.lolesports.com/persisted/gw/getTeams?hl=en-US", 1.0, extra={"x-api-key": "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"})
    for t in ((json.loads(gw) if gw else {}).get("data") or {}).get("teams") or []:
        for pl in t.get("players") or []:
            k = "lol|" + norm(pl.get("summonerName"))
            p = missing.get(k)
            img = pl.get("image") or ""
            if p is None or not img or re.search(r"default|silhouette", img, re.I): continue
            if tkey(t.get("name")) not in p["teams"] and tkey(t.get("code")) not in p["teams"]: continue
            have[k] = img
            added["lolesports"] += 1
            missing.pop(k)

    # ------------------------------------------------------------------ VALORANT: vlr.gg player pages
    pr_cache = sys.argv[sys.argv.index("--vlr-cache") + 1] if "--vlr-cache" in sys.argv else None
    vids = {}
    if pr_cache and os.path.isdir(pr_cache):
        for f in os.listdir(pr_cache):
            try: text = json.load(open(os.path.join(pr_cache, f))).get("text") or ""
            except Exception: continue
            if "txn-item" not in text and "vlr.gg" not in text: continue
            for pid, slug, h in re.findall(r'href="/player/(\d+)/([^"]*)"[^>]*>\s*([^<]+?)\s*</a>', text):
                vids.setdefault(norm(h), pid)
    for k, p in list(missing.items()):
        if p["t"] != "valorant": continue
        pid = vids.get(norm(p["h"]))
        if not pid: continue
        page = fetch("https://www.vlr.gg/player/%s/" % pid, 1.3)
        m = re.search(r'wf-avatar mod-player[\s\S]{0,200}?<img src="([^"]+)"', page or "")
        if not m or "/base/ph/" in m.group(1) or "sil.png" in m.group(1): continue
        url = m.group(1)
        have[k] = ("https:" + url) if url.startswith("//") else url
        added["vlr.gg"] += 1
        missing.pop(k)

    # ------------------------------------------------------------------ CS2: bo3.gg players
    want = {norm(p["h"]): k for k, p in missing.items() if p["t"] == "cs2"}
    if want:
        first = fetch("https://api.bo3.gg/api/v1/players?page%5Blimit%5D=100&page%5Boffset%5D=0", 1.2)
        j = json.loads(first) if first else {}
        total = (j.get("total") or {}).get("count", 0)
        pages = [j]
        for off in range(100, total, 100):
            t = fetch("https://api.bo3.gg/api/v1/players?page%%5Blimit%%5D=100&page%%5Boffset%%5D=%d" % off, 1.2)
            pages.append(json.loads(t) if t else {})
        for pg in pages:
            for pl in pg.get("results") or []:
                k = want.get(norm(pl.get("nickname")))
                if not k or not pl.get("image_url") or pl.get("is_deleted") or k in have: continue
                have[k] = pl["image_url"]
                added["bo3.gg"] += 1
                missing.pop(k, None)

    images["sources"]["pastplayers"] = "Past players: Wikidata / Wikimedia Commons (P18 photos), LoL Esports gateway, vlr.gg player pages, bo3.gg API"
    for k, v in list(have.items()): have[k] = re.sub(r"^http://", "https://", v)
    json.dump(images, open(os.path.join(REAL, "images.json"), "w"), ensure_ascii=False, separators=(",", ":"))
    left = defaultdict(int)
    for k in missing: left[k.split("|")[0]] += 1
    print("added %s; still without a photo %s · requests %d, cached %d" % (dict(added), dict(left), stats["requests"], stats["cached"]), file=sys.stderr)


if __name__ == "__main__":
    main()
