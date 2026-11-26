#!/usr/bin/env python3
"""
Real past rosters of the teams in the real-data snapshot, by year, written to
src/data/real/pastRosters.json (team page -> Past rosters, "Before the save").

* League of Legends: Games of Legends (gol.gg) season team pages, 2013-2026 (who played for the team
  that season, with position and flag), joined with the Leaguepedia tenures already in careers.json
  (months, staff).
* Dota 2: OpenDota's public SQL explorer: every premium/professional league match since 2012, per team
  and player (first and last match, games).
* VALORANT: vlr.gg team transaction logs (joins and departures of players and staff, with dates).
* CS2: Valve Regional Standings (monthly standings with five-player rosters, Aug 2024 on) and the Major
  champion / runner-up rosters (honours.json via careers.json).
* MLBB: the official MPL Indonesia (Season 4 on) and MPL Philippines (Season 6 on) team pages; the sites
  redirect an old team page to the club's current one, which links a club's seasons together.
* Wild Rift: the 2021 Wild Rift Esports Wiki rosters (wildrift.json). Honor of Kings: none (fictional).
* Every game: the career spells in careers.json (title rosters, last team seen).

Polite: robots.txt respected, requests spaced (gol.gg 1.5 s, vlr.gg 1 s, OpenDota 4 s, MPL 0.6 s),
every response cached under a sha1 of its URL; an HTTP 429 stops that source (no retry).

Usage: python3 scripts/data/past_rosters.py [cache-dir] [--offline] [--vrs <clone of
ValveSoftware/counter-strike_regional_standings>]
"""
import hashlib, html, json, os, re, sys, time, unicodedata, urllib.parse, urllib.request
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REAL = os.path.join(ROOT, "src", "data", "real")
argv = sys.argv[1:]
VRS = None
if "--vrs" in argv:
    i = argv.index("--vrs"); VRS = argv[i + 1]; del argv[i:i + 2]
OFFLINE = "--offline" in argv
ARGS = [a for a in argv if not a.startswith("--")]
CACHE = ARGS[0] if ARGS else "/tmp/past-rosters-cache"
os.makedirs(CACHE, exist_ok=True)
UA = "esports-manager-data/1.0 (data snapshot script)"
last = defaultdict(float)
stopped = set()
stats = defaultdict(int)


def fetch(url, gap, host_key=None):
    """Text of a URL (cached), or None. Returns (final_url, text)."""
    key = host_key or urllib.parse.urlparse(url).netloc
    path = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest() + ".json")
    if os.path.exists(path):
        stats["cached"] += 1
        d = json.load(open(path))
        return d.get("final", url), d.get("text")
    if OFFLINE or key in stopped:
        return url, None
    wait = gap - (time.time() - last[key])
    if wait > 0: time.sleep(wait)
    final, text = url, None
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=120) as r:
            final = r.geturl()
            text = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        if e.code == 429:
            print("429 from %s: that source stops here (no retry)" % key, file=sys.stderr)
            stopped.add(key)
            last[key] = time.time()
            return url, None
        text = None
    except Exception as e:  # network trouble: not cached, try once only
        print("error %s: %s" % (url, e), file=sys.stderr)
        last[key] = time.time()
        return url, None
    last[key] = time.time()
    stats["requests"] += 1
    json.dump({"final": final, "text": text}, open(path, "w"))
    return final, text


def norm(s): return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", s or "").lower())


GENERIC = ["esports", "esport", "gaming", "team", "club", "gg", "the"]
CITIES = ["beijing", "xian", "shenzhen", "suzhou", "shanghai", "hangzhou", "chengdu", "wuxi", "guangzhouhuadu", "guangzhou", "chongqing",
          "saigon", "hanoi", "tphcm", "vungtau", "fukuoka"]


def norm2(s):
    """Looser key: no generic words, no city prefix."""
    words = [norm(w) for w in re.split(r"[\s\-_.]+", unicodedata.normalize("NFKD", s or "").lower()) if norm(w)]
    words = [w for w in words if w not in GENERIC]
    k = "".join(words)
    for c in CITIES:
        if k.startswith(c) and len(k) > len(c) + 2: k = k[len(c):]
    return k


# Same club, earlier names (renames and the clubs' own continuations; slot purchases are not included).
ALIASES = {
    "lol": {
        "Gen.G Esports": ["Gen.G", "Gen.G eSports", "KSV eSports", "KSV", "Samsung Galaxy"],
        "Hanwha Life Esports": ["Hanwha Life eSports", "Hanwha Life"],
        "T1": ["SK Telecom T1", "SK Telecom T1 K", "SK Telecom T1 2", "SKT T1"],
        "Dplus KIA": ["DAMWON Gaming", "DWG KIA", "DAMWON", "Dplus"],
        "kt Rolster": ["KT Rolster"],
        "BNK FEARX": ["FearX", "FEARX", "Liiv SANDBOX", "SANDBOX Gaming", "BNK FearX"],
        "HANJIN BRION": ["BRION", "OKSavingsBank BRION", "Fredit BRION", "hyFresh BLADE", "Brion Blade", "HANJIN BRION"],
        "NONGSHIM RED FORCE": ["Nongshim RedForce", "Team Dynamics", "NS RedForce"],
        "KIWOOM DRX": ["DRX", "DragonX", "Kingzone DragonX", "Longzhu Gaming"],
        "DN SOOPers": ["DN Freecs", "Kwangdong Freecs", "Afreeca Freecs", "DN FREECS"],
        "Beijing JDG Esports": ["JD Gaming", "JDG Esports", "JDG", "Beijing JDG Intel Esports"],
        "Xi'an Team WE": ["Team WE", "WE"],
        "TOP ESPORTS": ["Top Esports", "Topsports Gaming"],
        "BILIBILI GAMING": ["Bilibili Gaming"],
        "LGD GAMING": ["LGD Gaming"],
        "Shenzhen NINJAS IN PYJAMAS": ["Ninjas in Pyjamas"],
        "THUNDER TALK GAMING": ["ThunderTalk Gaming"],
        "EDWARD GAMING": ["EDward Gaming"],
        "WeiboGaming": ["Weibo Gaming", "Suning", "Suning Gaming"],
        "Suzhou LNG Esports": ["LNG Esports", "LNG", "Snake Esports"],
        "G2 Esports": ["Gamers2"],
        "Movistar KOI": ["MAD Lions", "MAD Lions KOI"],
        "Team Vitality": ["Vitality"],
        "GIANTX": ["Excel Esports", "EXCEL"],
        "Team Liquid Alienware": ["Team Liquid", "Team Liquid Honda"],
        "LYON": ["LYON Gaming"],
        "Cloud9 Kia": ["Cloud9"],
        "Dignitas": ["Team Dignitas", "Dignitas QNTMPAY"],
        "NRG Kia": ["NRG", "NRG Esports"],
        "Immortals Progressive": ["Immortals"],
        "RED Kalunga": ["RED Canids", "RED Canids Kalunga"],
        "Vivo Keyd Stars": ["Vivo Keyd", "Keyd Stars"],
        "Fluxo W7M": ["Fluxo"],
        "KaBuM!": ["KaBuM! e-Sports", "KaBuM! Esports"],
        "Team Secret Whales": ["Team Whales"],
        "CTBC Flying Oyster": ["Flying Oyster"],
        "Fukuoka SoftBank HAWKS gaming": ["SoftBank HAWKS gaming", "Fukuoka SoftBank HAWKS"],
        "Relove Deep Cross Gaming": ["Deep Cross Gaming"],
        "PSG Talon": ["PSG Talon", "Talon Esports"],
    },
    "cs2": {"fnatic": ["Fnatic"], "Liquid": ["Team Liquid"], "FaZe": ["FaZe Clan"], "Vitality": ["Team Vitality"], "Spirit": ["Team Spirit"],
            "Falcons": ["Team Falcons"], "paiN": ["paiN Gaming"], "HEROIC": ["Heroic"], "Astralis": [], "Natus Vincere": ["NAVI"],
            "Ninjas in Pyjamas": ["NIP"], "MOUZ": ["mousesports"], "G2": ["G2 Esports"], "Cloud9": ["Cloud9"], "BIG": [], "ENCE": []},
    "valorant": {},
    "dota2": {},
}


def main():
    rosters = json.load(open(os.path.join(REAL, "rosters.json")))
    history = json.load(open(os.path.join(REAL, "history.json")))
    careers = json.load(open(os.path.join(REAL, "careers.json")))["careers"]
    wildrift = json.load(open(os.path.join(REAL, "wildrift.json")))
    as_of = 2026

    # ------------------------------------------------------------------ the teams of the game
    targets = defaultdict(dict)  # title -> norm(name) -> display name
    urls = {}  # (title, display) -> source url
    for t in rosters["teams"] + history["teams"]:
        targets[t["title_id"]].setdefault(norm(t["name"]), t["name"])
        urls.setdefault((t["title_id"], t["name"]), t.get("source_url", ""))
    for t in wildrift.get("teams", []):
        targets["wildrift"].setdefault(norm(t["name"]), t["name"])
    index = defaultdict(dict)  # title -> key -> display (exact, alias, loose)
    loose = defaultdict(lambda: defaultdict(set))
    for ti, d in targets.items():
        for k, name in d.items():
            index[ti][k] = name
            loose[ti][norm2(name)].add(name)
        for name, al in ALIASES.get(ti, {}).items():
            if norm(name) not in d: continue
            for a in al: index[ti].setdefault(norm(a), name)
    def resolve_game(ti, team):
        """The game's team for a team name in a source (exact, earlier name, or a unique loose match)."""
        if not team: return None
        k = norm(team)
        if k in index[ti]: return index[ti][k]
        c = loose[ti].get(norm2(team))
        if c and len(c) == 1 and len(norm2(team)) >= 3: return next(iter(c))
        return None

    # Historical teams: champions and finalists of the real events (honours.json) that are not teams of the
    # game (Taipei Assassins, Azubu Frost, Moscow Five...). Matched by exact name only, after the game's teams,
    # so nothing the game's teams had changes. The game adds them as dormant real teams.
    honours = json.load(open(os.path.join(REAL, "honours.json")))["events"]
    hist_index = defaultdict(dict)
    for e in honours:
        for k in ("w", "r"):
            nm = (e.get(k) or "").strip()
            if nm and not resolve_game(e["t"], nm): hist_index[e["t"]].setdefault(norm(nm), nm)
    historical = {(ti, name) for ti, d in hist_index.items() for name in d.values()}
    for ti, d in hist_index.items():
        for k, name in d.items(): targets[ti].setdefault(k, name)

    def resolve(ti, team):
        if not team: return None
        return resolve_game(ti, team) or hist_index[ti].get(norm(team))

    out = defaultdict(lambda: {"names": defaultdict(set), "people": []})  # (title, display) -> data
    SRC = ["Leaguepedia tenures", "Games of Legends (gol.gg)", "OpenDota pro matches", "vlr.gg transactions",
           "Valve Regional Standings", "MPL official site", "Wild Rift Esports Wiki (2021)", "title rosters", "real-data snapshot"]

    def add(ti, team_display, at_name, handle, role, frm, to, src, nat="", staff=False):
        if not handle or not team_display: return
        d = out[(ti, team_display)]
        d["people"].append([handle.strip(), role or "", frm or "", to or "", SRC.index(src), nat or "", 1 if staff else 0])
        y0 = int(frm[:4]) if frm[:4].isdigit() else None
        y1 = int(to[:4]) if to[:4].isdigit() else as_of
        if at_name and y0:
            for y in range(y0, y1 + 1): d["names"][y].add(at_name)

    STAFF = {"head-coach", "coach", "assistant", "strategic-coach", "analyst", "positional-coach", "manager", "owner", "performance"}

    # ------------------------------------------------------------------ careers.json (every game)
    POS = {"lol": {"top", "jungle", "mid", "bot", "support"}, "wildrift": {"baron", "jungle", "mid", "dragon", "support"},
           "mlbb": {"exp", "jungle", "mid", "gold", "roam"}, "dota2": {"pos1", "pos2", "pos3", "pos4", "pos5"}}
    def role_kind(ti, role):
        """(role, staff) for a careers role, or None for roles that are neither playing nor coaching (streamer, creator...)."""
        r = (role or "").strip().lower()
        if r in ("", "sub") or r in POS.get(ti, set()) or ti in ("valorant", "cs2") and r in ("duelist", "initiator", "controller", "sentinel", "flex", "igl", "awper", "rifler", "entry", "support", "lurker"):
            return r, False
        if r in STAFF: return r, True
        if "director" in r or "manager" in r: return "manager", True
        if "coach" in r: return "coach", True
        if "analyst" in r: return "analyst", True
        return None
    for key, spells in careers.items():
        ti, _ = key.split("|", 1)
        for team, frm, to, role, src in spells:
            tgt = resolve(ti, team)
            if not tgt: continue
            rk = role_kind(ti, role)
            if rk is None: continue
            role = rk[0]
            # The handle as written: careers keys are normalised, so take it from the other files when possible.
            add(ti, tgt, team, key.split("|", 1)[1], role, frm, to,
                {"wiki": "Leaguepedia tenures", "honours": "title rosters"}.get(src, "real-data snapshot"), staff=rk[1])
    # Display handles for the normalised career keys.
    handles = defaultdict(dict)
    for t in rosters["teams"]:
        for p in t.get("roster", []): handles[t["title_id"]].setdefault(norm(p.get("handle")), p.get("handle"))
    for p in history["players"]: handles[p["title_id"]].setdefault(norm(p["handle"]), p["handle"])
    for s in json.load(open(os.path.join(REAL, "staff.json")))["staff"]: handles[s["title"]].setdefault(norm(s["handle"]), s["handle"])
    for (ti, _), d in out.items():
        for p in d["people"]:
            p[0] = handles[ti].get(p[0], p[0]) if p[4] in (0, 7, 8) else p[0]

    # ------------------------------------------------------------------ LoL: gol.gg season team pages
    ROLE = {"TOP": "top", "JUNGLE": "jungle", "MID": "mid", "ADC": "bot", "BOT": "bot", "SUPPORT": "support"}
    gol_pages = 0
    for season in range(3, as_of - 2010 + 1):
        year = 2010 + season
        _, lst = fetch("https://gol.gg/teams/list/season-S%d/split-ALL/tournament-ALL/" % season, 1.5)
        if not lst: continue
        teams = re.findall(r"team-stats/(\d+)/split-ALL/tournament-ALL/' title='([^']+?) stats'", lst)
        seen = set()
        for tid, name in teams:
            name = html.unescape(name)
            tgt = resolve("lol", name)
            if not tgt or tid in seen: continue
            seen.add(tid)
            _, page = fetch("https://gol.gg/teams/team-stats/%s/split-ALL/tournament-ALL/" % tid, 1.5)
            if not page: continue
            gol_pages += 1
            body = page[page.find("player's stats</caption>"):] if "player's stats</caption>" in page else page
            for m in re.finditer(r"_img/role/([A-Z]+)\.png'[^>]*>\s*\w*</td><td>(?:<img class='champion_icon_light' src='[^']*' alt='([A-Z]{2})'/>&nbsp;)?<a title='([^']+?) stats'", body):
                add("lol", tgt, name, html.unescape(m.group(3)), ROLE.get(m.group(1), ""), str(year), str(year), "Games of Legends (gol.gg)", m.group(2) or "")
        print("gol.gg S%d (%d): %d of %d teams" % (season, year, len(seen), len(teams)), file=sys.stderr)

    # ------------------------------------------------------------------ Dota 2: OpenDota
    names = sorted(targets["dota2"].values())
    tq = "select team_id, name, tag from teams where lower(name) in (%s)" % ",".join("'" + n.lower().replace("'", "''") + "'" for n in names)
    _, tx = fetch("https://api.opendota.com/api/explorer?sql=" + urllib.parse.quote(tq), 4.0)
    team_of = {}
    for r in (json.loads(tx).get("rows", []) if tx else []):
        tgt = resolve("dota2", r["name"])
        if tgt: team_of[r["team_id"]] = (tgt, r["name"])
    _, pp = fetch("https://api.opendota.com/api/proPlayers", 2.0)
    pro = {p["account_id"]: p.get("name") for p in (json.loads(pp) if pp else []) if p.get("name")}
    spans = defaultdict(list)  # (team_id, account) -> [[a, b, n]]
    if team_of:
        ids = ",".join(str(i) for i in team_of)
        edges = [int(time.mktime((y, 1, 1, 0, 0, 0, 0, 0, 0))) for y in range(2012, as_of + 2)]
        for a, b in zip(edges, edges[1:]):
            sql = ("select case when pm.player_slot < 128 then m.radiant_team_id else m.dire_team_id end team_id, pm.account_id, count(*) n, "
                   "min(m.start_time) a, max(m.start_time) b from player_matches pm join matches m on m.match_id=pm.match_id "
                   "join leagues l on l.leagueid=m.leagueid where m.start_time >= %d and m.start_time < %d and l.tier in ('premium','professional') "
                   "and pm.account_id is not null and (m.radiant_team_id in (%s) or m.dire_team_id in (%s)) group by 1,2 having count(*) >= 3" % (a, b, ids, ids))
            _, j = fetch("https://api.opendota.com/api/explorer?sql=" + urllib.parse.quote(sql), 4.0)
            for r in (json.loads(j).get("rows", []) if j else []):
                if r["team_id"] in team_of: spans[(r["team_id"], r["account_id"])].append([r["a"], r["b"], r["n"]])
    def ymd(ts): return time.strftime("%Y-%m", time.gmtime(ts))
    dn = 0
    for (tid, acc), lst in spans.items():
        h = pro.get(acc)
        if not h: continue
        lst.sort()
        cur = list(lst[0])
        merged = []
        for s in lst[1:]:
            if s[0] - cur[1] < 150 * 86400: cur[1] = max(cur[1], s[1]); cur[2] += s[2]
            else: merged.append(cur); cur = list(s)
        merged.append(cur)
        tgt, cur_name = team_of[tid]
        for a, b, n in merged:
            if n < 5: continue
            add("dota2", tgt, cur_name, h, "", ymd(a), ymd(b), "OpenDota pro matches")
            dn += 1
    print("opendota: %d teams, %d spells" % (len(team_of), dn), file=sys.stderr)

    # ------------------------------------------------------------------ VALORANT: vlr.gg transactions
    VROLE = {"player": "", "head coach": "head-coach", "coach": "coach", "assistant coach": "assistant", "analyst": "analyst", "manager": "manager",
             "performance coach": "performance", "substitute": "sub", "sub": "sub", "inactive": "inactive", "strategic coach": "strategic-coach",
             "positional coach": "positional-coach", "staff": "staff"}
    vteams = {}
    for name in sorted(targets["valorant"].values()):
        u = urls.get(("valorant", name), "")
        m = re.search(r"vlr\.gg/team/(\d+)/([^/?#]+)", u)
        if m:
            vteams[name] = m.group(1); continue
        if "vlr.gg/" in u:
            _, page = fetch(u, 1.0)
            if page:
                best = None
                for tid, slug, txt in re.findall(r'href="/team/(\d+)/([^"]+)"[^>]*>(.*?)</a>', page, re.S):
                    label = re.sub(r"<[^>]+>", " ", txt)
                    if norm(slug) and (norm(slug) in norm(name) or norm(name) in norm(label) or norm2(label.split("[")[0]) == norm2(name)):
                        best = tid; break
                if best: vteams[name] = best
    vn = 0
    for name, tid in vteams.items():
        _, page = fetch("https://www.vlr.gg/team/transactions/%s/" % tid, 1.0)
        if not page: continue
        rows = []
        for tr in re.findall(r'<tr class="wf-card mod-flex txn-item[^"]*"[^>]*>(.*?)</tr>', page, re.S):
            tds = re.findall(r"<td[^>]*>(.*?)</td>", tr, re.S)
            if len(tds) < 5: continue
            date = re.sub(r"\s+", "", re.sub(r"<[^>]+>", "", tds[0])).replace("/", "-")
            action = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", tds[1])).strip().lower()
            flag = re.search(r"mod-([a-z]{2})\b", tds[2])
            hm = re.search(r'href="/player/\d+/[^"]*"[^>]*>\s*([^<]+?)\s*</a>', tds[3])
            pos = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", tds[4])).strip().lower()
            if not hm: continue
            rows.append((date if re.match(r"\d{4}-\d{2}-\d{2}", date) else "", action, html.unescape(hm.group(1)), pos, flag.group(1).upper() if flag else ""))
        rows.sort(key=lambda r: r[0] or "0000")
        open_ = {}
        for date, action, h, pos, nat in rows:
            role = VROLE.get(pos, "coach" if "coach" in pos else pos)
            k = (norm(h), role in STAFF or role == "staff")
            if action == "join":
                if k not in open_: open_[k] = [h, role, date, nat]
            elif action in ("leave", "inactive", "bench"):
                o = open_.pop(k, None)
                if o is None: o = [h, role, "", nat]
                if role == "inactive" and o[1] not in STAFF: pass
                add("valorant", name, None, o[0], "" if o[1] in ("", "inactive") else o[1], o[2][:7], (date or "")[:7] or o[2][:7], "vlr.gg transactions", o[3], staff=o[1] in STAFF or o[1] == "staff")
                vn += 1
        for o in open_.values():
            add("valorant", name, None, o[0], "" if o[1] in ("", "inactive") else o[1], o[2][:7], "", "vlr.gg transactions", o[3], staff=o[1] in STAFF or o[1] == "staff")
            vn += 1
    print("vlr.gg: %d teams, %d spells" % (len(vteams), vn), file=sys.stderr)

    # ------------------------------------------------------------------ CS2: Valve Regional Standings
    if VRS:
        months = defaultdict(set)  # (team display, at name, handle) -> {YYYY-MM}
        for year in sorted(os.listdir(os.path.join(VRS, "live"))):
            d = os.path.join(VRS, "live", year)
            for f in sorted(os.listdir(d)):
                m = re.match(r"standings_(global|europe|americas|asia)_(\d{4})_(\d\d)_\d\d\.md$", f)
                if not m: continue
                ym_ = m.group(2) + "-" + m.group(3)
                for line in open(os.path.join(d, f), encoding="utf-8"):
                    c = [x.strip() for x in line.split("|")]
                    if len(c) < 6 or not c[1].isdigit(): continue
                    tgt = resolve("cs2", c[3])
                    if not tgt: continue
                    for h in [x.strip() for x in c[4].split(",") if x.strip()]:
                        months[(tgt, c[3], h)].add(ym_)
        cn = 0
        def mnum(s): return int(s[:4]) * 12 + int(s[5:7])
        for (tgt, at, h), ms in months.items():
            ms = sorted(ms)
            a = b = ms[0]
            for x in ms[1:] + [None]:
                if x is not None and mnum(x) - mnum(b) <= 3: b = x; continue
                add("cs2", tgt, at, h, "", a, b, "Valve Regional Standings"); cn += 1
                if x is not None: a = b = x
        print("vrs: %d spells" % cn, file=sys.stderr)

    # ------------------------------------------------------------------ MLBB: MPL ID / PH seasons
    MROLE = {"exp lane": "exp", "exp": "exp", "exp laner": "exp", "gold lane": "gold", "gold": "gold", "gold laner": "gold", "mid lane": "mid", "midlane": "mid",
             "midlaner": "mid", "mid laner": "mid", "mid": "mid", "jungle": "jungle", "jungler": "jungle", "roam": "roam", "roamer": "roam", "tank/support": "roam",
             "tank, support": "roam", "support": "roam", "tank": "roam"}
    slug_team = {}
    for name in targets["mlbb"].values():
        m = re.search(r"(id|ph)-mpl\.com/(?:s\d+/)?team/([a-z0-9]+)", urls.get(("mlbb", name), ""))
        if m: slug_team.setdefault((m.group(1), m.group(2)), name)
    mn = 0
    for site, first in (("id", 4), ("ph", 6)):
        base = "https://%s-mpl.com" % site
        for sn in range(first, 19):
            year = 2017 + (sn + 1) // 2
            _, lst = fetch("%s/s%d/teams" % (base, sn), 0.6)
            if not lst: continue
            for slug in sorted(set(re.findall(r'href="%s/s%d/team/([a-z0-9]+)"' % (re.escape(base), sn), lst))):
                final, page = fetch("%s/s%d/team/%s" % (base, sn, slug), 0.6)
                if not page: continue
                fm = re.search(r"/team/([a-z0-9]+)", final)
                cur = fm.group(1) if fm else slug
                tgt = slug_team.get((site, cur)) or slug_team.get((site, slug))
                if not tgt: continue
                people = [(html.unescape(a).strip(), html.unescape(b).strip()) for a, b in re.findall(r'class="player-name">\s*([^<]+?)\s*</div>\s*<div class="player-role[^"]*">\s*([^<]*?)\s*</div>', page)]
                ign = {}
                for k_, f_, v_ in re.findall(r'id="player-(\d+)-(ign|role)" value="([^"]*)"', page): ign.setdefault(k_, {})[f_] = html.unescape(v_)
                people += [(e["ign"], e.get("role", "")) for e in ign.values() if e.get("ign")]
                for h, rt in people:
                    low = rt.lower()
                    staff = bool(re.search(r"coach|analyst|manager", low))
                    role = ("head-coach" if "head" in low else "analyst" if "analyst" in low else "manager" if "manager" in low else "coach") if staff else MROLE.get(low, "")
                    add("mlbb", tgt, None, h, role, str(year), str(year), "MPL official site", staff=staff)
                    mn += 1
            print("mpl %s s%d" % (site, sn), file=sys.stderr)
    print("mpl: %d entries" % mn, file=sys.stderr)

    # ------------------------------------------------------------------ Wild Rift: 2021 wiki rosters
    for t in wildrift.get("teams", []):
        for p in t.get("roster", []):
            if isinstance(p, dict) and p.get("handle"):
                st = p.get("status") != "player"
                add("wildrift", t["name"], None, p["handle"], p.get("role", "") if not st else "coach", "2021", "2021", "Wild Rift Esports Wiki (2021)", staff=st)

    # ------------------------------------------------------------------ display handles
    # Careers keys are normalised; take the spelling another source uses for the same person (same game).
    spell_of = defaultdict(lambda: defaultdict(lambda: defaultdict(int)))
    for (ti, _), d in out.items():
        for p in d["people"]:
            if p[4] not in (0, 7, 8) or p[0] != norm(p[0]): spell_of[ti][norm(p[0])][p[0]] += 1
    for (ti, _), d in out.items():
        for p in d["people"]:
            if p[0] == norm(p[0]) and spell_of[ti].get(p[0]):
                c = spell_of[ti][p[0]]
                p[0] = max(c, key=lambda h: (c[h], h != h.lower()))

    # ------------------------------------------------------------------ by year
    def mon(x): return int(x[5:7]) if len(x) >= 7 else 0
    res = {}
    for (ti, team), d in sorted(out.items()):
        years = defaultdict(dict)  # year -> norm handle -> entry
        for h, role, frm, to, src, nat, staff in d["people"]:
            y0 = int(frm[:4]) if frm[:4].isdigit() else None
            if y0 is None: continue
            y1 = int(to[:4]) if to[:4].isdigit() else as_of
            if y1 < y0: y1 = y0
            if mon(frm) >= 11 and y1 > y0: y0 += 1; frm = ""
            if to and mon(to) == 1 and y1 > y0: y1 -= 1; to = ""
            for y in range(y0, min(y1, as_of) + 1):
                a = frm if y == y0 and len(frm) >= 7 else ""
                b = to if y == y1 and len(to) >= 7 else ""
                k = (norm(h), staff)
                prev = years[y].get(k)
                exact = len(frm) >= 7 or len(to) >= 7
                if prev is None:
                    years[y][k] = {"h": h, "r": role, "a": a, "b": b, "s": {src}, "n": nat, "x": exact, "st": staff}
                else:
                    prev["s"].add(src)
                    if not prev["r"] and role: prev["r"] = role
                    if not prev["n"] and nat: prev["n"] = nat
                    if exact and not prev["x"]: prev.update({"a": a, "b": b, "x": True})
                    elif exact and prev["x"]:
                        if a and (not prev["a"] or a < prev["a"]): prev["a"] = a
                        if (b and prev["b"] and b > prev["b"]) or not b: prev["b"] = b if b else ""
                    if h != prev["h"] and h[:1].isupper() and not prev["h"][:1].isupper(): prev["h"] = h
        ys = {}
        for y in sorted(years):
            staff_keys = {k[0] for k in years[y] if k[1]}
            rows = sorted((e for k, e in years[y].items() if k[1] or k[0] not in staff_keys), key=lambda e: (e["st"], e["h"].lower()))
            if not any(not e["st"] for e in rows): continue
            ys[str(y)] = {
                "p": [[e["h"], e["r"], e["n"], e["a"], e["b"], sorted(e["s"])] for e in rows if not e["st"]],
                "s": [[e["h"], e["r"], e["n"], e["a"], e["b"], sorted(e["s"])] for e in rows if e["st"]],
                "as": sorted({norm(n): n for n in sorted(d["names"].get(y, set())) if norm(n) != norm(team)}.values()),
            }
        if ys: res[ti + "|" + norm(team)] = dict({"n": team, "y": ys}, **({"h": 1} if (ti, team) in historical else {}))
    doc = {"snapshot_id": 1, "as_of": time.strftime("%Y-%m-%d"), "sources": SRC,
           "format": "title|norm(team) -> {n: name, y: {year: {p: players, s: staff, as: earlier names that year}}, h: 1 for a historical team not in the real-data snapshot}; "
                     "a person: [handle, role, flag, from YYYY-MM or '', to YYYY-MM or '', [source index]]",
           "teams": res}
    json.dump(doc, open(os.path.join(REAL, "pastRosters.json"), "w"), ensure_ascii=False, separators=(",", ":"))
    per = defaultdict(int)
    for k in res: per[k.split("|")[0]] += 1
    print("pastRosters: %d teams %s · requests %d, cached %d" % (len(res), dict(per), stats["requests"], stats["cached"]), file=sys.stderr)


if __name__ == "__main__":
    main()
