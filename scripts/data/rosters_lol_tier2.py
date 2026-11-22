#!/usr/bin/env python3
"""
Add the Pacific second-tier leagues to the roster snapshot (development time only):

    python3 scripts/data/rosters_lol_tier2.py <cache dir>   ->  src/data/real/rosters.json

LJL (Japan), PCS (Hong Kong, Macau, Taiwan, Southeast Asia) and VCS (Vietnam) from the Riot LoL
Esports gateway with the public website key, as scripts/data/rosters.mjs does for the tier-1
leagues: the teams of each league's latest split (the 120 days up to its last completed match),
starters from the official live-stats feed of each team's most recent game, real names from the
gateway's team list. The leagues are tier 2 under the LCP (their best teams play the LCP
promotion tournament in the game). Teams already in a tier-1 league are left out; academies of
LCP teams are marked (academy_of). Requests are spaced and cached.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
from datetime import datetime, timezone

CACHE = sys.argv[1] if len(sys.argv) > 1 else ".cache/lol-tier2"
os.makedirs(CACHE, exist_ok=True)
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
PATH = os.path.join(ROOT, "src/data/real/rosters.json")
KEY = "x-api-key: 0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"
UA = "EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)"
_last = [0.0]


def fetch(url, key=True):
    f = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest()[:16])
    if os.path.exists(f):
        return json.load(open(f))
    wait = _last[0] + 0.6 - time.time()
    if wait > 0:
        time.sleep(wait)
    _last[0] = time.time()
    args = ["curl", "-sS", "-m", "90", "--compressed", "-A", UA] + (["-H", KEY] if key else []) + [url]
    try:
        body = subprocess.run(args, capture_output=True, check=True).stdout.decode()
        j = json.loads(body)
    except Exception as e:  # noqa: BLE001
        print("fetch failed", url[:120], e, file=sys.stderr)
        return None
    open(f, "w").write(body)
    return j


def gw(path):
    return fetch("https://esports-api.lolesports.com/persisted/gw/" + path)


def slug(s):
    return re.sub(r"^-|-$", "", re.sub(r"[^a-z0-9]+", "-", unicodedata.normalize("NFKD", s.lower()).encode("ascii", "ignore").decode()))


def norm(s):
    return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", str(s or "")).lower())


LEAGUES = [("LJL", "ljl", "JP"), ("PCS", "pcs", "PCS"), ("VCS", "vcs", "VN")]
ROLE = {"top": "top", "jungle": "jungle", "mid": "mid", "bottom": "bot", "support": "support"}
# Academies of LCP organisations (their parent team plays in the LCP).
ACADEMY = {"ctbcflyingoysteracademy": "CTBC Flying Oyster", "dfmacademy": "DetonatioN FocusMe", "gzacademy": "Ground Zero Gaming",
           "saigonmvkeacademy": "MVK Esports", "teamsecretwhalesacademy": "Team Secret Whales", "gamacademy": "GAM Esports"}

# Home countries of the LCP organisations (the gateway gives none; a team that leaves the LCP plays
# the lower league of its country).
LCP_COUNTRY = {"teamsecretwhales": "VN", "ctbcflyingoyster": "TW", "mvkesports": "VN", "gamesports": "VN", "detonationfocusme": "JP",
               "groundzerogaming": "AU", "relovedeepcrossgaming": "TW", "fukuokasoftbankhawksgaming": "JP"}

snap = json.load(open(PATH))
for t in snap["teams"]:
    if t["league_id"] == "lol-lcp" and not t.get("country") and norm(t["name"]) in LCP_COUNTRY:
        t["country"] = LCP_COUNTRY[norm(t["name"])]
snap["leagues"] = [l for l in snap["leagues"] if l["league_id"] not in {"lol-" + s for _, s, _ in LEAGUES}]
snap["teams"] = [t for t in snap["teams"] if t["league_id"] not in {"lol-" + s for _, s, _ in LEAGUES}]
tier1 = {norm(t["name"]) for t in snap["teams"] if t["title_id"] == "lol"}
leagues = gw("getLeagues?hl=en-US")["data"]["leagues"]
all_teams = gw("getTeams?hl=en-US")["data"]["teams"]
now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
added = {}
taken = {norm(r["handle"]) for t in snap["teams"] if t["title_id"] == "lol" for r in t.get("roster", [])}
for lname, sl, region in LEAGUES:
    lg = next((l for l in leagues if l["name"] == lname), None)
    if lg is None:
        continue
    snap["leagues"].append({"league_id": "lol-" + sl, "title_id": "lol", "name": lname, "short": lname, "region": region, "tier": 2, "upper": "lol-lcp",
                            "source_url": f"https://lolesports.com/en-US/leagues/{lg['slug']}", "official_id": lg["id"]})
    events = gw(f"getSchedule?hl=en-US&leagueId={lg['id']}")["data"]["schedule"]["events"]
    done = sorted([e for e in events if e["type"] == "match" and e["state"] == "completed"], key=lambda e: e["startTime"], reverse=True)
    if not done:
        continue
    latest = datetime.fromisoformat(done[0]["startTime"].replace("Z", "+00:00"))
    recent = [e for e in done if (latest - datetime.fromisoformat(e["startTime"].replace("Z", "+00:00"))).days < 120]
    codes = []
    for e in recent:
        for t in e["match"]["teams"]:
            if t["code"] not in codes:
                codes.append(t["code"])
    n = 0
    for code in codes:
        last = next(e for e in recent if any(t["code"] == code for t in e["match"]["teams"]))
        meta = next(t for t in last["match"]["teams"] if t["code"] == code)
        if norm(meta["name"]) in tier1:
            continue
        full = next((t for t in all_teams if t["code"] == code and t["name"] == meta["name"]), None) or next((t for t in all_teams if t["name"] == meta["name"]), None)
        starters = []
        det = gw(f"getEventDetails?hl=en-US&id={last['match']['id']}")
        games = ((det or {}).get("data", {}).get("event", {}).get("match", {}) or {}).get("games", [])
        g = next((x for x in reversed(games) if x.get("state") == "completed"), None)
        if g:
            win = fetch(f"https://feed.lolesports.com/livestats/v1/window/{g['id']}", key=False)
            md = (win or {}).get("gameMetadata")
            if md:
                sides = [md["blueTeamMetadata"], md["redTeamMetadata"]]
                side = next((s for s in sides if full and s.get("esportsTeamId") == full["id"]), None) or \
                    next((s for s in sides if any(p["summonerName"].startswith(code + " ") for p in s["participantMetadata"])), None)
                for p in (side or {}).get("participantMetadata", []):
                    starters.append({"handle": re.sub(r"^" + re.escape(code) + r"\s*(?=\S)", "", p["summonerName"]).strip(), "role": ROLE.get(p["role"]), "id": p.get("esportsPlayerId")})
        fps = (full or {}).get("players", [])
        roster = []
        for s in starters:
            info = next((p for p in fps if p["id"] == s["id"]), None) or next((p for p in fps if p["summonerName"].lower() == s["handle"].lower()), None)
            first = (info or {}).get("firstName", "").strip() or None
            last_n = (info or {}).get("lastName", "").strip() or None
            roster.append({"handle": ((info or {}).get("summonerName") or s["handle"]).strip(), "real_name": " ".join(x for x in [first, last_n] if x) or None,
                           "first_name": first, "last_name": last_n, "nationality": None, "role": s["role"], "status": "player", "starter": True})
        # Substitutes listed on the gateway's team (no position given there for most).
        for p in fps:
            if p.get("role") in ROLE and not any(norm(r["handle"]) == norm(p["summonerName"]) for r in roster):
                first = (p.get("firstName") or "").strip() or None
                last_n = (p.get("lastName") or "").strip() or None
                roster.append({"handle": p["summonerName"].strip(), "real_name": " ".join(x for x in [first, last_n] if x) or None, "first_name": first, "last_name": last_n,
                               "nationality": None, "role": ROLE[p["role"]], "status": "player", "starter": False})
        if len([r for r in roster if r["starter"]]) < 3 and len(roster) < 5:
            continue  # no usable roster for this team
        # A team renamed during the split shows up twice; the most recent name comes first, so a team
        # sharing three or more players with one already taken is its older name. A player listed on
        # two teams stays on the more recent one.
        if sum(1 for r in roster if norm(r["handle"]) in taken) >= 3:
            continue
        roster = [r for r in roster if norm(r["handle"]) not in taken]
        taken.update(norm(r["handle"]) for r in roster)
        rec = {"w": 0, "l": 0}
        for e in recent:
            me = next((t for t in e["match"]["teams"] if t["code"] == code), None)
            out = ((me or {}).get("result") or {}).get("outcome")
            if out == "win":
                rec["w"] += 1
            elif out == "loss":
                rec["l"] += 1
        team = {"record": rec, "team_id": f"lol-{slug(meta['name'])}", "title_id": "lol", "league_id": "lol-" + sl, "name": meta["name"], "short": code,
                "org": re.sub(r"\s+(Esports|Gaming|e-Sports Club)$", "", meta["name"], flags=re.I), "country": region if region in ("JP", "VN") else None,
                "region": region, "source_url": f"https://lolesports.com/en-US/leagues/{lg['slug']}", "collected_at": now, "roster": roster, "starters_from": last["startTime"]}
        if norm(meta["name"]) in ACADEMY:
            team["academy_of"] = ACADEMY[norm(meta["name"])]
        snap["teams"].append(team)
        n += 1
    added[lname] = n
json.dump(snap, open(PATH, "w"), ensure_ascii=False, separators=(",", ":"))
print("added teams", added)
for t in snap["teams"]:
    if t["league_id"] in ("lol-ljl", "lol-pcs", "lol-vcs"):
        print(t["league_id"], t["name"], t.get("academy_of", ""), [(r["handle"], r["role"], r["starter"]) for r in t["roster"]][:7])
