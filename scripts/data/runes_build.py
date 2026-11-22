#!/usr/bin/env python3
"""
Runes, emblems, arcana and talents of the five MOBAs (development time only):

    python3 scripts/data/runes_build.py <cache dir>   ->  src/data/real/runes.json

 - League of Legends: Riot Data Dragon runesReforged.json (official): the five trees, keystones
   and minor runes with their descriptions and icons.
 - Wild Rift: no machine-readable source could be used; the keystones and minor runes are listed
   from the game's rune system with short summaries (marked approximate).
 - Mobile Legends: the Mobile Legends Wiki "Emblems" page (Fandom MediaWiki API): the 7 emblem sets,
   their attributes, standard talents and core talents.
 - Honor of Kings: the Honor of Kings Wiki "Arcana" page (Fandom MediaWiki API): every arcana and
   its level-5 effects.
 - Dota 2: odota/dotaconstants (GitHub): every hero's talent tree (levels 10/15/20/25) and facets.

Each entry carries the real wording and the stats the match engine uses (titles.Neutrals stat
keys plus hp / pen), converted from the game's numbers to the engine's scale. Requests are cached.
"""
import hashlib
import html
import json
import os
import re
import subprocess
import sys
import time

CACHE = sys.argv[1] if len(sys.argv) > 1 else ".cache/runes"
os.makedirs(CACHE, exist_ok=True)
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
OUT = os.path.join(ROOT, "src/data/real/runes.json")
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


def wiki(base, title):
    j = get(f"{base}/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2&redirects=1&titles={title}")
    return j["query"]["pages"][0]["revisions"][0]["slots"]["main"]["content"]


def strip(s):
    s = re.sub(r"<[^>]+>", "", s or "")
    return re.sub(r"\s+", " ", html.unescape(s)).strip()


def r(x):
    return round(x, 4)


# Minor effects from wording (a short summary of what the game's numbers do in a fight).
KEYWORDS = [
    (r"ability haste|cooldown", {"haste": 4.0}), (r"attack speed", {"as": 0.04}), (r"lifesteal|life steal|omnivamp|heal|restore|regenerat", {"heal": 0.03, "regen": 0.003}),
    (r"armor|armour|magic resist|defen[cs]e", {"armor": 0.03, "mr": 0.03}), (r"health|\bhp\b", {"hp": 0.025}), (r"movement speed|move speed", {"ms": 0.015}),
    (r"tenacity|slow resist", {"tenacity": 0.05}), (r"shield", {"heal": 0.03}), (r"penetration|lethality|pierce", {"pen": 0.03}),
    (r"damage|adaptive|true damage|burst", {"dmg": 0.02}), (r"gold|bounty|cash", {"dmg": 0.005}),
]


def by_words(text, scale=1.0):
    t = text.lower()
    out = {}
    for pat, st in KEYWORDS:
        if re.search(pat, t):
            for k, v in st.items():
                out[k] = r(out.get(k, 0) + v * scale)
            if len(out) >= 3:
                break
    return out or {"dmg": r(0.01 * scale)}


out = {"snapshot_id": "real-2026-11-22", "as_of": "2026-11-22", "sources": {}, "entries": []}
E = out["entries"]

# ------------------------------------------------------------------------------ League of Legends
versions = get("https://ddragon.leagueoflegends.com/api/versions.json")
ver = versions[0]
lol = get(f"https://ddragon.leagueoflegends.com/cdn/{ver}/data/en_US/runesReforged.json")
KEYSTONE = {
    "Press the Attack": {"dmg": 0.05}, "Lethal Tempo": {"as": 0.12}, "Fleet Footwork": {"regen": 0.01, "ms": 0.02}, "Conqueror": {"ad": 0.04, "ap": 0.04, "heal": 0.04},
    "Electrocute": {"dmg": 0.05}, "Dark Harvest": {"dmg": 0.03, "execute": 0.03}, "Hail of Blades": {"as": 0.15},
    "Summon Aery": {"heal": 0.08, "dmg": 0.02}, "Arcane Comet": {"dmg": 0.045}, "Phase Rush": {"ms": 0.08},
    "Grasp of the Undying": {"hp": 0.05, "regen": 0.004}, "Aftershock": {"dr": 0.06}, "Guardian": {"heal": 0.08, "dr": 0.02},
    "Glacial Augment": {"dr": 0.02, "dmg": 0.02}, "Unsealed Spellbook": {"haste": 6.0}, "First Strike": {"dmg": 0.035},
}
for tree in lol:
    for si, slot in enumerate(tree["slots"]):
        for ru in slot["runes"]:
            desc = strip(ru.get("shortDesc"))
            stats = KEYSTONE.get(ru["name"]) if si == 0 else by_words(desc)
            E.append({"id": f"lol-{ru['key'].lower()}", "title": "lol", "name": ru["name"], "group": tree["name"], "slot": "keystone" if si == 0 else f"row{si}",
                      "desc": desc, "stats": stats or {"dmg": 0.03}, "img": f"https://ddragon.leagueoflegends.com/cdn/img/{ru['icon']}"})
out["sources"]["lol"] = {"name": "Riot Data Dragon runesReforged.json", "url": f"https://ddragon.leagueoflegends.com/cdn/{ver}/data/en_US/runesReforged.json"}

# ------------------------------------------------------------------------------ Wild Rift (approximate)
WR = [
    ("Domination", "keystone", "Electrocute", "Hitting an enemy champion 3 times in 3 s deals bonus adaptive damage.", {"dmg": 0.05}),
    ("Domination", "keystone", "Dark Harvest", "Damaging a low-health champion deals bonus damage and harvests a soul.", {"dmg": 0.03, "execute": 0.03}),
    ("Domination", "keystone", "Hail of Blades", "The first 3 attacks against a champion gain large attack speed.", {"as": 0.15}),
    ("Precision", "keystone", "Conqueror", "Damaging champions grants adaptive force stacks; at full stacks, heal for part of the damage.", {"ad": 0.04, "ap": 0.04, "heal": 0.04}),
    ("Precision", "keystone", "Lethal Tempo", "Attacking a champion grants attack speed stacks and extends range.", {"as": 0.12}),
    ("Precision", "keystone", "Fleet Footwork", "Energized attacks heal and grant movement speed.", {"regen": 0.01, "ms": 0.02}),
    ("Precision", "keystone", "Kraken Slayer", "Every third attack deals bonus true damage, more against tankier targets.", {"dmg": 0.05}),
    ("Resolve", "keystone", "Grasp of the Undying", "Every few seconds the next attack on a champion deals bonus damage, heals and permanently adds health.", {"hp": 0.05, "regen": 0.004}),
    ("Resolve", "keystone", "Aftershock", "After immobilizing a champion, gain resistances, then burst for damage.", {"dr": 0.06}),
    ("Resolve", "keystone", "Glacial Augment", "Immobilizing a champion creates slowing rays.", {"dr": 0.02, "dmg": 0.02}),
    ("Inspiration", "keystone", "Summon Aery", "Attacks and abilities send Aery to damage enemies or shield allies.", {"heal": 0.08, "dmg": 0.02}),
    ("Inspiration", "keystone", "Arcane Comet", "Damaging a champion with an ability hurls a comet.", {"dmg": 0.045}),
    ("Inspiration", "keystone", "Phase Rush", "Hitting a champion 3 times grants a burst of movement speed.", {"ms": 0.08}),
    ("Inspiration", "keystone", "First Strike", "Damaging a champion first deals bonus damage and grants gold.", {"dmg": 0.035}),
    ("Domination", "row1", "Brutal", "Bonus adaptive force; more penetration later.", {"ad": 0.02, "ap": 0.02, "pen": 0.02}),
    ("Domination", "row1", "Gathering Storm", "Adaptive force that grows every few minutes.", {"ad": 0.025, "ap": 0.025}),
    ("Domination", "row1", "Sudden Impact", "Bonus penetration after a dash or leaving stealth.", {"pen": 0.04}),
    ("Domination", "row2", "Cheap Shot", "Bonus true damage to impaired champions.", {"dmg": 0.02}),
    ("Resolve", "row1", "Bone Plating", "Take less damage from a champion's next attacks.", {"dr": 0.03}),
    ("Resolve", "row1", "Second Wind", "Regenerate missing health after taking champion damage.", {"regen": 0.006}),
    ("Resolve", "row2", "Backbone", "Bonus armour or magic resistance, whichever is lower.", {"armor": 0.04, "mr": 0.04}),
    ("Resolve", "row2", "Loyalty", "Gain and give a nearby ally resistances.", {"armor": 0.03, "mr": 0.03}),
    ("Inspiration", "row1", "Hunter - Genius", "Ability haste that grows with unique takedowns.", {"haste": 5.0}),
    ("Inspiration", "row2", "Pack Hunter", "Movement speed near allies and bonus gold for allied takedowns.", {"ms": 0.015}),
    ("Inspiration", "row2", "Nimbus Cloak", "Movement speed after a summoner spell.", {"ms": 0.02}),
]
for g, slot, name, desc, stats in WR:
    E.append({"id": "wildrift-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-"), "title": "wildrift", "name": name, "group": g, "slot": slot, "desc": desc, "stats": stats, "approx": True})
out["sources"]["wildrift"] = {"name": "Wild Rift rune system (summaries; no machine-readable source used)", "url": ""}

# ------------------------------------------------------------------------------ Mobile Legends emblems
ml = wiki("https://mobile-legends.fandom.com", "Emblems")
ATTR = {  # game unit -> engine stat (MLBB heroes: about 2500-3000 HP and 120-150 attack at level 1)
    "hp": ("hp", 1 / 15000), "hybrid defense": ("armor", 1 / 250), "hp regen": ("regen", 1 / 1500), "hybrid regen": ("regen", 1 / 3000), "adaptive penetration": ("pen", 1 / 250),
    "adaptive attack": ("ad", 1 / 700), "movement speed": ("ms", 1 / 100), "magic power": ("ap", 1 / 700), "cooldown reduction": ("haste", 1.0), "magic penetration": ("pen", 1 / 250),
    "hybrid lifesteal": ("heal", 1 / 200), "healing effect": ("heal", 1 / 100), "attack speed": ("as", 1 / 300),
}
CORE = {"Impure Rage": {"dmg": 0.05}, "Quantum Charge": {"ms": 0.03, "regen": 0.008}, "War Cry": {"dmg": 0.06}, "Temporal Reign": {"haste": 10.0}, "Concussive Blast": {"dmg": 0.03, "hp": 0.02},
        "Killing Spree": {"regen": 0.015, "ms": 0.03}, "Lethal Ignition": {"dmg": 0.06}, "Brave Smite": {"regen": 0.015, "heal": 0.03}, "Focusing Mark": {"dmg": 0.04}, "Weakness Finder": {"dmg": 0.03, "dr": 0.02}}
for m in re.finditer(r"\{\{New-(?:basic|custom)-emblem-set\|(.*?)\n\}\}", ml, re.S):
    body = m.group(1)
    kv = dict(re.findall(r"\|\s*([\w.\-]+)\s*=\s*([^|]*)", "|" + body))
    name = kv.get("name", "").strip()
    sid = "mlbb-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    stats = {}
    for i in range(1, 4):
        a = (kv.get(f"attr{i}") or "").strip().lower()
        v = float(re.sub(r"[^0-9.]", "", kv.get(f"attr-val{i}") or "0") or 0)
        if a in ATTR:
            k, f = ATTR[a]
            stats[k] = r(min(stats.get(k, 0) + v * f, 0.12 if k not in ("haste",) else 12))
            if a == "hybrid defense":
                stats["mr"] = stats["armor"]
            if a == "adaptive attack":
                stats["ap"] = stats["ad"]
    attrs = ", ".join(f"{kv.get(f'attr{i}', '').strip()} {kv.get(f'attr-val{i}', '').strip()}" for i in range(1, 4) if kv.get(f"attr{i}"))
    E.append({"id": sid, "title": "mlbb", "name": name, "group": name, "slot": "set", "desc": attrs, "stats": stats})
    for key, val in kv.items():
        mm = re.match(r"(standardtalent|coretalent)(\d)\.(\d)$", key)
        if not mm:
            continue
        tname = val.strip()
        desc = strip(kv.get(f"{mm.group(1)}-descr{mm.group(2)}.{mm.group(3)}", ""))
        slot = "core" if mm.group(1) == "coretalent" else f"talent{mm.group(2)}"
        E.append({"id": "mlbb-" + re.sub(r"[^a-z0-9]+", "-", tname.lower()).strip("-"), "title": "mlbb", "name": tname, "group": name, "slot": slot, "desc": desc,
                  "stats": CORE.get(tname) or by_words(desc)})
out["sources"]["mlbb"] = {"name": "Mobile Legends Wiki: Emblems (Fandom API)", "url": "https://mobile-legends.fandom.com/wiki/Emblems"}

# ------------------------------------------------------------------------------ Honor of Kings arcana
hok = wiki("https://honor-of-kings.fandom.com", "Arcana")
UNIT = {  # one arcana at level 5 -> engine stat (a page holds 10 of each of 3 kinds)
    "physical attack": ("ad", 1 / 1500), "magical attack": ("ap", 1 / 2000), "attack speed bonus": ("as", 1 / 500), "critical rate": ("dmg", 1 / 600), "critical damage": ("dmg", 1 / 2000),
    "physical pierce": ("pen", 1 / 1200), "magical pierce": ("pen", 1 / 1200), "physical lifesteal": ("heal", 1 / 400), "magical lifesteal": ("heal", 1 / 400),
    "max health": ("hp", 1 / 80000), "health/5s": ("regen", 1 / 40000), "movement speed": ("ms", 1 / 1000), "physical defense": ("armor", 1 / 1200), "magical defense": ("mr", 1 / 1200),
    "cooldown reduction": ("haste", 0.1),
}
rows = re.split(r"\n\|-\n", hok)
for row in rows:
    nm = re.search(r"^!\s*([^\n|]+)\s*$", row.strip().split("\n")[0])
    if not nm:
        continue
    name = nm.group(1).strip()
    cells = [c for c in row.split("\n") if c.startswith("|") and "File:" not in c]
    if not cells:
        continue
    eff = cells[-1].lstrip("|")
    stats = {}
    parts = []
    for p in re.split(r"<br\s*/?>", eff):
        p = strip(p)
        mm = re.match(r"(.+?)\s*\+?\s*([0-9.]+)%?$", p)
        if not mm:
            continue
        parts.append(p)
        key = mm.group(1).lower().strip()
        if key in UNIT:
            k, f = UNIT[key]
            stats[k] = r(stats.get(k, 0) + float(mm.group(2)) * f)
    if stats:
        E.append({"id": "hok-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-"), "title": "hok", "name": name, "group": "Arcana", "slot": "arcana", "desc": "; ".join(parts) + " (level 5, per arcana)", "stats": stats})
out["sources"]["hok"] = {"name": "Honor of Kings Wiki: Arcana (Fandom API)", "url": "https://honor-of-kings.fandom.com/wiki/Arcana"}

# ------------------------------------------------------------------------------ Dota 2 talents and facets
ha = get("https://raw.githubusercontent.com/odota/dotaconstants/master/build/hero_abilities.json", 1.0)
ab = get("https://raw.githubusercontent.com/odota/dotaconstants/master/build/abilities.json", 1.0)
heroes = get("https://raw.githubusercontent.com/odota/dotaconstants/master/build/heroes.json", 1.0)
# Our Dota 2 character ids by hero name (src/data/real/characters.json).
our = {re.sub(r"[^a-z0-9]", "", c["name"].lower()): c["character_id"] for c in json.load(open(os.path.join(ROOT, "src/data/real/characters.json")))["titles"]["dota2"]["characters"]}
DOTA = [(r"damage(?! (block|reduction|taken))", {"ad": 0.035}), (r"attack speed", {"as": 0.05}), (r"\barmor\b", {"armor": 0.05}), (r"magic resist", {"mr": 0.05}),
        (r"health regen|hp regen", {"regen": 0.004}), (r"\bhealth\b|\bhp\b", {"hp": 0.04}), (r"movement speed|move speed", {"ms": 0.02}), (r"cooldown", {"haste": 6.0}),
        (r"spell amplification|spell amp", {"ap": 0.05}), (r"lifesteal|spell lifesteal", {"heal": 0.04}), (r"all attributes|all stats", {"hp": 0.02, "ad": 0.015}),
        (r"strength", {"hp": 0.025}), (r"agility", {"as": 0.03, "armor": 0.01}), (r"intelligence", {"ap": 0.02}), (r"evasion", {"dr": 0.04}), (r"stun|duration|slow", {"dmg": 0.02})]
for hid, h in ha.items():
    slugname = hid.replace("npc_dota_hero_", "")
    hero = next((x for x in heroes.values() if x.get("name") == hid), None)
    hname = (hero or {}).get("localized_name") or slugname
    cid = our.get(re.sub(r"[^a-z0-9]", "", hname.lower()))
    if cid is None:
        continue
    for t in h.get("talents", []):
        dn = strip((ab.get(t["name"]) or {}).get("dname") or "")
        if not dn:
            continue
        low = dn.lower()
        stats = {}
        for pat, st in DOTA:
            if re.search(pat, low):
                stats = dict(st)
                break
        lv = {1: 10, 2: 15, 3: 20, 4: 25}.get(t.get("level"))
        if lv is None:
            continue
        E.append({"id": "dota2-" + t["name"], "title": "dota2", "name": dn, "group": cid, "slot": f"level{lv}", "desc": dn, "stats": stats or {"dmg": 0.02}})
    for fct in h.get("facets", []):
        if fct.get("deprecated") in ("true", True):
            continue
        E.append({"id": f"dota2-facet-{fct['name']}", "title": "dota2", "name": fct.get("title") or fct["name"], "group": cid, "slot": "facet",
                  "desc": strip(fct.get("description")), "stats": by_words(strip(fct.get("description")), 0.8)})
out["sources"]["dota2"] = {"name": "odota/dotaconstants (hero talents and facets)", "url": "https://github.com/odota/dotaconstants"}

json.dump(out, open(OUT, "w"), ensure_ascii=False, separators=(",", ":"))
from collections import Counter
print(Counter((e["title"], e["slot"]) for e in E))
for t in ["lol", "wildrift", "mlbb", "hok", "dota2"]:
    print(t, [(e["name"], e["stats"]) for e in E if e["title"] == t][:4])
