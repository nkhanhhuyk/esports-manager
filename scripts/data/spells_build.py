#!/usr/bin/env python3
"""
Spells and pictures for the five MOBAs (development time only):

    python3 scripts/data/spells_build.py <cache dir>   ->  src/data/real/spells.json, runes.json (img)

 - League of Legends: Riot Data Dragon summoner.json (the Summoner's Rift spells, descriptions,
   cooldowns, icons). Wild Rift uses the same spells (no Teleport or Cleanse), shown with the same
   icons; cooldowns from League (marked approximate).
 - Mobile Legends: the Mobile Legends Wiki "Battle spells" page (name, cooldown, description;
   File:<name>.png). Recall and Healing are fixed for every hero; one battle spell is chosen.
 - Honor of Kings: the Honor of Kings Wiki "Common Skills" pages (infobox cooldown, description,
   File:<name>.png). Recovery is fixed for every hero; one common skill is chosen.
 - Dota 2 has no summoner spells.
Pictures: MLBB emblem sets and talents, HoK arcana (level V), Wild Rift runes (League icons of the
same rune), Dota 2 facets (Valve's facet icons) and every neutral objective (League, MLBB, HoK and
Dota 2 wikis). Wiki files are resolved to their image URLs through the MediaWiki API (imageinfo).
Requests are spaced and cached.
"""
import hashlib
import html
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse

CACHE = sys.argv[1] if len(sys.argv) > 1 else ".cache/spells"
os.makedirs(CACHE, exist_ok=True)
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
REAL = os.path.join(ROOT, "src/data/real")
UA = "EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)"
_last = {}


def get(url, gap=3.0):
    f = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest()[:16])
    if os.path.exists(f):
        return json.load(open(f))
    host = url.split("/")[2]
    wait = _last.get(host, 0) + gap - time.time()
    if wait > 0:
        time.sleep(wait)
    _last[host] = time.time()
    body = subprocess.run(["curl", "-sS", "-m", "120", "--compressed", "-A", UA, url], capture_output=True, check=True).stdout.decode()
    j = json.loads(body)
    open(f, "w").write(body)
    return j


def api(base, **params):
    params.update({"format": "json", "formatversion": "2"})
    return get(f"{base}/api.php?" + urllib.parse.urlencode(params))


def files(base, names):
    """File page names -> image URLs (missing files are left out)."""
    out = {}
    names = sorted(set(names))
    for i in range(0, len(names), 40):
        j = api(base, action="query", prop="imageinfo", iiprop="url", titles="|".join("File:" + n for n in names[i:i + 40]))
        norm = {n["from"]: n["to"] for n in j.get("query", {}).get("normalized", [])}
        back = {v: k for k, v in norm.items()}
        for p in j.get("query", {}).get("pages", []):
            ii = p.get("imageinfo")
            if ii:
                t = back.get(p["title"], p["title"])
                out[t.replace("File:", "", 1)] = ii[0]["url"].split("/revision/")[0]
    return out


def page(base, title):
    j = api(base, action="query", prop="revisions", rvprop="content", rvslots="main", titles=title, redirects="1")
    p = j["query"]["pages"][0]
    return p.get("revisions", [{}])[0].get("slots", {}).get("main", {}).get("content", "")


def strip(s):
    s = re.sub(r"\{\{(?:b|ai|ii|scale)\|([^}|]*)[^}]*\}\}", r"\1", s or "")
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"<[^>]+>|''+", "", s)
    return re.sub(r"\s+", " ", html.unescape(s)).strip()


# What each spell does in the match engine (sim.Spells): its effect kind.
EFFECT = {
    "flash": "blink", "flicker": "blink", "teleport": "teleport", "arrival": "teleport", "ignite": "ignite", "execute": "execute", "smite": "smite",
    "retribution": "smite", "heal": "heal", "revitalize": "heal", "barrier": "shield", "aegis": "shield", "exhaust": "weaken", "weaken": "weaken",
    "intimidate": "weaken", "ghost": "haste", "sprint": "haste", "cleanse": "cleanse", "purify": "cleanse", "petrify": "stun", "stun": "stun",
    "flameshot": "poke", "inspire": "frenzy", "frenzy": "frenzy", "vengeance": "reflect", "disrupt": "disrupt",
}
out = {"snapshot_id": "real-2026-11-22", "spells": [], "fixed": {}, "slots": {"lol": 2, "wildrift": 2, "mlbb": 1, "hok": 1}, "objectiveImages": {}}

# ------------------------------------------------------------------------------ League of Legends, Wild Rift
ver = get("https://ddragon.leagueoflegends.com/api/versions.json")[0]
summ = get(f"https://ddragon.leagueoflegends.com/cdn/{ver}/data/en_US/summoner.json")["data"]
LOL = ["SummonerFlash", "SummonerTeleport", "SummonerDot", "SummonerSmite", "SummonerHeal", "SummonerBarrier", "SummonerExhaust", "SummonerBoost", "SummonerHaste"]
WR = {"SummonerFlash", "SummonerDot", "SummonerSmite", "SummonerHeal", "SummonerBarrier", "SummonerExhaust", "SummonerHaste"}
for key in LOL:
    s = summ.get(key)
    if not s:
        continue
    name = s["name"]
    img = f"https://ddragon.leagueoflegends.com/cdn/{ver}/img/spell/{s['image']['full']}"
    base = {"name": name, "cd": float(s["cooldownBurn"]), "desc": strip(s.get("description")), "effect": EFFECT.get(name.lower(), "blink"), "img": img}
    out["spells"].append(dict(base, id="lol-" + name.lower(), title="lol"))
    if key in WR:
        out["spells"].append(dict(base, id="wildrift-" + name.lower(), title="wildrift", approx=True))

# ------------------------------------------------------------------------------ Mobile Legends
MLW = "https://mobile-legends.fandom.com"
ml = page(MLW, "Battle spells")
current = ml.split("==Removed Battle Spells==")[0]  # removed spells are not offered
names = []
for chunk in current.split("{{Spell-box-skill")[1:]:
    kv = {}
    for line in chunk.split("\n"):
        mm = re.match(r"\|\s*([\w\-]+)\s*=\s*(.*)$", line)
        if mm:
            kv[mm.group(1)] = mm.group(2)
    name = strip(kv.get("name"))
    if not name or name in ("Recall", "Healing"):
        continue
    cd = float(re.sub(r"[^0-9.]", "", kv.get("cooldown", "0")) or 0)
    names.append(name)
    out["spells"].append({"id": "mlbb-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-"), "title": "mlbb", "name": name, "cd": cd,
                          "desc": strip(kv.get("description")), "effect": EFFECT.get(name.lower(), "frenzy")})
mlimg = files(MLW, [n + ".png" for n in names] + ["Turtle.png", "Lord.png", "Lithowanderer.png"])
for s in out["spells"]:
    if s["title"] == "mlbb":
        s["img"] = mlimg.get(s["name"] + ".png")
out["fixed"]["mlbb"] = ["Recall", "Healing"]

# ------------------------------------------------------------------------------ Honor of Kings
HKW = "https://honor-of-kings.fandom.com"
hk = ["Flash", "Smite", "Heal", "Execute", "Frenzy", "Disrupt", "Stun", "Purify", "Intimidate", "Sprint"]
hkimg = files(HKW, [n + ".png" for n in hk] + [f"HoK Data Dump {i}.png" for i in range(277, 282)])
for n in hk:
    c = page(HKW, n)
    kv = dict((k.strip(), v.strip()) for k, v in re.findall(r"\|\s*([\w\-]+)\s*=\s*([^|\n]*)", c))
    cd = float(re.sub(r"[^0-9.]", "", kv.get("cooldown", "")) or {"Smite": 30, "Stun": 90, "Intimidate": 90}.get(n, 90))
    out["spells"].append({"id": "hok-" + n.lower(), "title": "hok", "name": n, "cd": cd, "desc": strip(kv.get("description")) or n,
                          "effect": EFFECT.get(n.lower(), "frenzy"), "img": hkimg.get(n + ".png")})
out["fixed"]["hok"] = ["Recovery"]

# ------------------------------------------------------------------------------ Neutral objective pictures
LOLW = "https://leagueoflegends.fandom.com"
lol_obj = {"lol-infernal": "Infernal DrakeSquare.png", "lol-mountain": "Mountain DrakeSquare.png", "lol-ocean": "Ocean DrakeSquare.png", "lol-cloud": "Cloud DrakeSquare.png",
           "lol-hextech": "Hextech DrakeSquare.png", "lol-chemtech": "Chemtech DrakeSquare.png", "lol-elder": "Elder DragonSquare.png", "lol-baron": "Baron NashorSquare.png",
           "lol-herald": "Rift HeraldSquare.png", "lol-grubs": "VoidgrubSquare.png",
           "soul:lol-infernal": "Infernal Dragon Soul buff.png", "soul:lol-mountain": "Mountain Dragon Soul buff.png", "soul:lol-ocean": "Ocean Dragon Soul buff.png",
           "soul:lol-cloud": "Cloud Dragon Soul buff.png", "soul:lol-hextech": "Hextech Dragon Soul buff.png", "soul:lol-chemtech": "Chemtech Dragon Soul buff.png"}
lolimg = files(LOLW, lol_obj.values())
for k, f in lol_obj.items():
    if f in lolimg:
        out["objectiveImages"][k] = lolimg[f]
        if not k.startswith("soul:"):
            out["objectiveImages"][k.replace("lol-", "wildrift-")] = lolimg[f]
out["objectiveImages"].update({k: v for k, v in {"mlbb-turtle": mlimg.get("Turtle.png"), "mlbb-turtle-blessing": mlimg.get("Turtle.png"), "mlbb-lord": mlimg.get("Lord.png"),
                                                  "hok-tyrant": hkimg.get("HoK Data Dump 277.png"), "hok-shadowtyrant": hkimg.get("HoK Data Dump 278.png"),
                                                  "hok-overlord": hkimg.get("HoK Data Dump 279.png"), "hok-shadow": hkimg.get("HoK Data Dump 280.png"),
                                                  "hok-tempest": hkimg.get("HoK Data Dump 281.png")}.items() if v})
D2W = "https://dota2.fandom.com"
d2 = files(D2W, ["Roshan icon.png", "Aegis of the Immortal icon.png", "Cheese icon.png", "Refresher Shard icon.png", "Aghanim's Shard - Roshan icon.png", "Wisdom Rune minimap icon.png"])
for k, f in {"obj:dota2-roshan": "Roshan icon.png", "dota2-aegis": "Aegis of the Immortal icon.png", "dota2-cheese": "Cheese icon.png", "dota2-refresher": "Refresher Shard icon.png",
             "dota2-tormentor": "Aghanim's Shard - Roshan icon.png", "dota2-wisdom": "Wisdom Rune minimap icon.png"}.items():
    if f in d2:
        out["objectiveImages"][k] = d2[f]

# ------------------------------------------------------------------------------ Rune / emblem / arcana / facet pictures
runes = json.load(open(os.path.join(REAL, "runes.json")))
lol_icon = {e["name"]: e.get("img") for e in runes["entries"] if e["title"] == "lol" and e.get("img")}
mlnames = [e["name"] for e in runes["entries"] if e["title"] == "mlbb"]
ML_FILE = {"Inspire": "Inspire (Talent).png", "Weapon Master": "Weapon Master (Talent).png"}
mlr = files(MLW, [ML_FILE.get(n, (n + " Emblem.png") if n.startswith(("Custom ", "Basic ")) else n + ".png") for n in mlnames])
hkr = files(HKW, [e["name"] + " Lv V.png" for e in runes["entries"] if e["title"] == "hok"])
ha = get("https://raw.githubusercontent.com/odota/dotaconstants/master/build/hero_abilities.json", 1.0)
facet_icon = {f"dota2-facet-{fc['name']}": fc.get("icon") for h in ha.values() for fc in h.get("facets", [])}
n_img = 0
for e in runes["entries"]:
    img = None
    if e["title"] == "wildrift":
        img = lol_icon.get(e["name"]) or lol_icon.get({"Hunter - Genius": "Ultimate Hunter", "Brutal": "Sudden Impact", "Kraken Slayer": "Lethal Tempo",
                                                       "Backbone": "Conditioning", "Loyalty": "Revitalize", "Pack Hunter": "Relentless Hunter"}.get(e["name"], ""))
    elif e["title"] == "mlbb":
        n = e["name"]
        img = mlr.get(ML_FILE.get(n, (n + " Emblem.png") if n.startswith(("Custom ", "Basic ")) else n + ".png"))
    elif e["title"] == "hok":
        img = hkr.get(e["name"] + " Lv V.png")
    elif e["title"] == "dota2" and e["slot"] == "facet" and facet_icon.get(e["id"]):
        img = f"https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/icons/facets/{facet_icon[e['id']]}.png"
    if img and not e.get("img"):
        e["img"] = img
        n_img += 1
json.dump(runes, open(os.path.join(REAL, "runes.json"), "w"), ensure_ascii=False, separators=(",", ":"))
json.dump(out, open(os.path.join(REAL, "spells.json"), "w"), ensure_ascii=False, separators=(",", ":"))
from collections import Counter
print("spells", Counter(s["title"] for s in out["spells"]), "with img", sum(1 for s in out["spells"] if s.get("img")))
print("objective images", len(out["objectiveImages"]), sorted(out["objectiveImages"]))
print("rune images added", n_img, Counter(e["title"] for e in runes["entries"] if e.get("img")))
for s in out["spells"]:
    print(" ", s["title"], s["name"], s["cd"], s["effect"], "img" if s.get("img") else "-", s["desc"][:70])
