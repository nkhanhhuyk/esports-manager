#!/usr/bin/env python3
"""
Build the MOBA item catalogue (development time only):

    python3 scripts/data/items_build.py <cache dir>   ->  src/data/real/items.json

Completed items (and upgraded boots) of the five MOBAs, with their real names, prices, types and
stats, and an image link:
 - League of Legends: Riot Data Dragon (ddragon.leagueoflegends.com, item.json)
 - Wild Rift: League of Legends Wiki, Module:WRItemData/data (fandom, CC BY-SA), via the MediaWiki API
 - Mobile Legends: Mobile Legends Wiki, Module:Equipment/data (fandom, CC BY-SA), via the API
 - Honor of Kings: Honor of Kings Wiki, "Equipment" and the item pages (fandom, CC BY-SA), via the API
 - Dota 2: odota/dotaconstants (items.json, MIT) on GitHub
Stats use common keys: ad (physical attack), ap (magic power), hp, armor, mr (magic defence), as
(attack speed %), crit (%), haste (ability haste; cooldown reduction % is converted), ms (flat move
speed), mspct (move speed %), ls (life steal %), pen (penetration / lethality), mana.
Requests are spaced and cached; pages that answer with a browser challenge are not used.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse

UA = "EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)"
CACHE = sys.argv[1] if len(sys.argv) > 1 else ".cache/items"
os.makedirs(CACHE, exist_ok=True)
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
_last = {}


def get(url, gap=1.5, raw=False):
    f = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest()[:16])
    if os.path.exists(f):
        body = open(f, encoding="utf-8").read()
        return body if raw else json.loads(body)
    host = urllib.parse.urlparse(url).netloc
    wait = _last.get(host, 0) + gap - time.time()
    if wait > 0:
        time.sleep(wait)
    _last[host] = time.time()
    body = subprocess.run(["curl", "-sS", "-m", "180", "--compressed", "-A", UA, url], capture_output=True, check=True).stdout.decode("utf-8")
    if "Just a moment..." in body[:600]:
        raise RuntimeError("browser challenge at " + url)
    open(f, "w", encoding="utf-8").write(body)
    return body if raw else json.loads(body)


def wiki_pages(host, titles):
    """Wikitext of pages (50 per request) through the MediaWiki API."""
    out = {}
    for i in range(0, len(titles), 50):
        batch = titles[i:i + 50]
        url = f"https://{host}/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&titles=" + urllib.parse.quote("|".join(batch))
        j = get(url)
        norm = {n["to"]: n["from"] for n in j["query"].get("normalized", [])}
        for p in j["query"]["pages"].values():
            if "revisions" in p:
                out[norm.get(p["title"], p["title"])] = p["revisions"][0]["slots"]["main"]["*"]
    return out


def wiki_images(host, files):
    """Direct image links of File: pages (50 per request)."""
    out = {}
    for i in range(0, len(files), 50):
        batch = ["File:" + f for f in files[i:i + 50]]
        url = f"https://{host}/api.php?action=query&prop=imageinfo&iiprop=url&format=json&titles=" + urllib.parse.quote("|".join(batch))
        j = get(url)
        norm = {n["to"]: n["from"] for n in j["query"].get("normalized", [])}
        for p in j["query"]["pages"].values():
            ii = p.get("imageinfo")
            if ii:
                out[norm.get(p["title"], p["title"])[5:]] = ii[0]["url"].split("/revision/")[0]
    return out


# ------------------------------------------------------------------ a small Lua table reader
def lua_table(src):
    s = re.sub(r"--\[\[.*?\]\]", "", src, flags=re.S)
    s = re.sub(r"--[^\n]*", "", s)
    i = s.index("return") + 6
    pos = [i]

    def ws():
        while pos[0] < len(s) and s[pos[0]] in " \t\r\n,;":
            pos[0] += 1

    def value():
        ws()
        c = s[pos[0]]
        if c == "{":
            pos[0] += 1
            d, arr = {}, []
            while True:
                ws()
                if s[pos[0]] == "}":
                    pos[0] += 1
                    return d if d else arr
                if s[pos[0]] == "[":
                    pos[0] += 1
                    k = value()
                    ws(); assert s[pos[0]] == "]"; pos[0] += 1
                    ws(); assert s[pos[0]] == "="; pos[0] += 1
                    d[k] = value()
                else:
                    m = re.match(r"([A-Za-z_][A-Za-z0-9_]*)\s*=", s[pos[0]:])
                    if m and not s[pos[0]:].startswith(("true", "false", "nil")) or (m and s[pos[0] + len(m.group(1)):].lstrip().startswith("=")):
                        pos[0] += m.end()
                        d[m.group(1)] = value()
                    else:
                        arr.append(value())
        if c in "\"'":
            q = c; pos[0] += 1; out = []
            while s[pos[0]] != q:
                if s[pos[0]] == "\\":
                    pos[0] += 1; out.append({"n": "\n", "t": "\t"}.get(s[pos[0]], s[pos[0]]))
                else:
                    out.append(s[pos[0]])
                pos[0] += 1
            pos[0] += 1
            return "".join(out)
        if s.startswith("[[", pos[0]):
            e = s.index("]]", pos[0]); v = s[pos[0] + 2:e]; pos[0] = e + 2; return v
        m = re.match(r"-?\d+(\.\d+)?", s[pos[0]:])
        if m:
            pos[0] += m.end(); return float(m.group(0)) if "." in m.group(0) else int(m.group(0))
        for w, v in (("true", True), ("false", False), ("nil", None)):
            if s.startswith(w, pos[0]):
                pos[0] += len(w); return v
        raise ValueError("lua at " + s[pos[0]:pos[0] + 40])

    return value()


def clean(stats):
    return {k: round(v, 2) for k, v in stats.items() if v}


def item_type(st, name="", support=False, boots=False):
    if boots:
        return "movement"
    if support:
        return "support"
    magic_pen = st.get("ap", 0) >= st.get("ad", 0)
    dmg = st.get("ad", 0) / 60 + st.get("crit", 0) / 25 + st.get("as", 0) / 35 + (0 if magic_pen else st.get("pen", 0) / 20)
    mag = st.get("ap", 0) / 80 + (st.get("pen", 0) / 20 if magic_pen and st.get("ap", 0) > 0 else 0)
    dfn = st.get("hp", 0) / 400 + st.get("armor", 0) / 40 + st.get("mr", 0) / 40
    best = max(dmg, mag, dfn)
    if best <= 0:
        return "support"
    return "physical" if best == dmg else "magical" if best == mag else "defense"


out = {}

# ------------------------------------------------------------------ League of Legends
ver = get("https://ddragon.leagueoflegends.com/api/versions.json")[0]
lol = get(f"https://ddragon.leagueoflegends.com/cdn/{ver}/data/en_US/item.json")["data"]
items = []
DD = {"FlatPhysicalDamageMod": ("ad", 1), "FlatMagicDamageMod": ("ap", 1), "FlatHPPoolMod": ("hp", 1), "FlatArmorMod": ("armor", 1), "FlatSpellBlockMod": ("mr", 1),
      "PercentAttackSpeedMod": ("as", 100), "FlatCritChanceMod": ("crit", 100), "FlatMovementSpeedMod": ("ms", 1), "PercentMovementSpeedMod": ("mspct", 100),
      "PercentLifeStealMod": ("ls", 100), "FlatMPPoolMod": ("mana", 1)}
for iid, it in lol.items():
    tags = it.get("tags", [])
    if not it["maps"].get("11") or not it["gold"]["purchasable"] or it.get("inStore") is False or it.get("requiredChampion") or it.get("requiredAlly"):
        continue
    if "Consumable" in tags or "Trinket" in tags:
        continue
    boots = "Boots" in tags
    if not boots and (it.get("depth", 1) < 3 and it["gold"]["total"] < 2200):
        continue
    if boots and it["gold"]["total"] < 900:
        continue
    st = {}
    for k, v in it.get("stats", {}).items():
        if k in DD:
            key, mul = DD[k]
            st[key] = st.get(key, 0) + v * mul
    desc = it.get("description", "")
    for m in re.finditer(r"<attention>\s*(\d+)%?\s*</attention>\s*(Ability Haste|Lethality|Magic Penetration|Armor Penetration|Life Steal|Omnivamp)", desc):
        v = int(m.group(1)); key = {"Ability Haste": "haste", "Lethality": "pen", "Magic Penetration": "pen", "Armor Penetration": "pen", "Life Steal": "ls", "Omnivamp": "ls"}[m.group(2)]
        st[key] = st.get(key, 0) + (v / 2 if m.group(2) == "Armor Penetration" else v)
    support = "GoldPer" in tags and st.get("ap", 0) == 0 and st.get("ad", 0) == 0
    items.append({"id": f"lol-{iid}", "name": it["name"], "cost": it["gold"]["total"], "type": item_type(st, it["name"], support, boots), "stats": clean(st),
                  "img": f"https://ddragon.leagueoflegends.com/cdn/{ver}/img/item/{iid}.png"})
# Same name twice (map variants): keep one.
seen = set(); lol_items = []
for it in sorted(items, key=lambda x: x["id"]):
    if it["name"] in seen:
        continue
    seen.add(it["name"]); lol_items.append(it)
out["lol"] = lol_items

# ------------------------------------------------------------------ Wild Rift
wr_src = wiki_pages("leagueoflegends.fandom.com", ["Module:WRItemData/data"])["Module:WRItemData/data"]
wr = lua_table(wr_src)
WRK = {"ad": "ad", "ap": "ap", "hp": "hp", "armor": "armor", "mr": "mr", "as": "as", "crit": "crit", "ah": "haste", "msflat": "ms", "ms": "mspct", "lifesteal": "ls", "omnivamp": "ls",
       "armpen": "pen", "armpenflat": "pen", "lethality": "pen", "mpen": "pen", "mpenflat": "pen", "physvamp": "ls", "mana": "mana", "magicpen": "pen"}
wr_items = []
for name, it in wr.items():
    if not isinstance(it, dict):
        continue
    tier = it.get("tier", 0)
    menu = it.get("menu", {}) or {}
    boots = "Boots" in (it.get("type") or []) or "Boots" in name or "Greaves" in name
    if tier < 3 and not (boots and tier >= 2):
        continue
    st = {}
    for k, v in (it.get("stats") or {}).items():
        if k in WRK and isinstance(v, (int, float)):
            st[WRK[k]] = st.get(WRK[k], 0) + v
    cost = it.get("buy")
    if isinstance(cost, dict):
        cost = cost.get("total", 0)
    if not isinstance(cost, (int, float)):
        continue
    support = bool(menu.get("support")) or bool(menu.get("starting"))
    wr_items.append({"id": "wildrift-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-"), "name": name, "cost": int(cost or 0), "type": item_type(st, name, support, boots),
                     "stats": clean(st), "file": f"{name} WR item.png"})
imgs = wiki_images("leagueoflegends.fandom.com", [i["file"] for i in wr_items])
for i in wr_items:
    i["img"] = imgs.get(i.pop("file"))
out["wildrift"] = wr_items

# ------------------------------------------------------------------ Mobile Legends
ml_src = wiki_pages("mobile-legends.fandom.com", ["Module:Equipment/data"])["Module:Equipment/data"]
ml = lua_table(ml_src)
MLK = [("Physical Attack", "ad"), ("Magic Power", "ap"), ("Physical Defense", "armor"), ("Magic Defense", "mr"), ("Physical Penetration", "pen"), ("Magic Penetration", "pen"),
       ("Attack Speed", "as"), ("Crit Chance", "crit"), ("CD Reduction", "haste"), ("Cooldown Reduction", "haste"), ("Physical Lifesteal", "ls"), ("Lifesteal", "ls"),
       ("Spell Vamp", "ls"), ("Movement Speed", "ms"), ("Mana", "mana"), ("HP", "hp")]
ml_items = []
for key, it in ml.items():
    if not isinstance(it, dict):
        continue
    typ = (it.get("type") or "").lower()
    price = int(re.sub(r"[^0-9]", "", str(it.get("price") or "0")) or 0)
    name = it.get("name") or key
    if "potion" in name.lower():
        continue
    if price < 600 and "boot" not in name.lower() and "walker" not in name.lower() and "tread" not in name.lower() and "shoes" not in name.lower():
        continue
    if not it.get("recipe") and price < 1000:
        continue
    st = {}
    for part in str(it.get("bonus") or "").split(","):
        m = re.match(r"\s*\+?\s*([\d.]+)\s*(%?)\s*(.+)", part)
        if not m:
            continue
        v = float(m.group(1)); label = m.group(3).strip()
        for lbl, k in MLK:
            if label.lower().startswith(lbl.lower()):
                if k == "ms" and m.group(2) == "%":
                    k = "mspct"
                if k == "haste" and m.group(2) == "%":
                    v = v / max(0.01, 1 - v / 100)  # cooldown reduction % as ability haste
                st[k] = st.get(k, 0) + v
                break
    t = {"attack": None, "magic": None, "defense": None, "movement": "movement", "jungling": "jungle", "roaming": "support"}.get(typ.split()[0] if typ else "", None)
    ml_items.append({"id": "mlbb-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-"), "name": name, "cost": price,
                     "type": t or item_type(st, name), "stats": clean(st), "file": f"{name}.png"})
imgs = wiki_images("mobile-legends.fandom.com", [i["file"] for i in ml_items])
for i in ml_items:
    i["img"] = imgs.get(i.pop("file"))
out["mlbb"] = ml_items

# ------------------------------------------------------------------ Honor of Kings
eq = wiki_pages("honor-of-kings.fandom.com", ["Equipment"])["Equipment"]
names = []
for m in re.finditer(r"^\|\s*\[\[([^\]|]+)(?:\|[^\]]*)?\]\]", eq, flags=re.M):
    if m.group(1) not in names:
        names.append(m.group(1))
pages = wiki_pages("honor-of-kings.fandom.com", names)
HK = [("Physical Attack", "ad"), ("Magical Attack", "ap"), ("Magic Attack", "ap"), ("Max Health", "hp"), ("Max HP", "hp"), ("Physical Defense", "armor"), ("Magical Defense", "mr"),
      ("Magic Defense", "mr"), ("Attack Speed", "as"), ("Critical Rate", "crit"), ("Crit Rate", "crit"), ("Cooldown Reduction", "haste"), ("Physical Lifesteal", "ls"),
      ("Magical Lifesteal", "ls"), ("Movement Speed", "mspct"), ("Physical Pierce", "pen"), ("Magical Pierce", "pen"), ("Max Mana", "mana"), ("Mana", "mana")]
hok_items, files = [], []
for name in names:
    w = pages.get(name)
    if not w or "Infobox item" not in w:
        continue
    pm = re.search(r"\|\s*price\s*=\s*(\d+)", w)
    cls = (re.search(r"\|\s*class\s*=\s*([^\n|]+)", w) or [None, ""])[1].strip().lower()
    level = (re.search(r"\|\s*level\s*=\s*([^\n|]+)", w) or [None, ""])[1].strip().lower()
    img = (re.search(r"\|\s*image1\s*=\s*([^\n|]+)", w) or [None, ""])[1].strip()
    if "removed" in w.lower()[:400] or not pm:
        continue
    price = int(pm.group(1))
    if level and level not in ("advanced", "top-tier", "top tier", "legendary") and price < 1300 and "movement" not in cls:
        continue
    st = {}
    eff = re.search(r"\|\s*effect\s*=(.*?)\n\s*\|\s*(price|recipe)", w, flags=re.S)
    for m in re.finditer(r"\+\s*([\d.]+)\s*(%?)\s*([A-Za-z ]+)", eff.group(1) if eff else ""):
        v = float(m.group(1)); label = m.group(3).strip()
        for lbl, k in HK:
            if label.lower().startswith(lbl.lower()):
                if k == "haste" and m.group(2) == "%":
                    v = v / max(0.01, 1 - v / 100)
                st[k] = st.get(k, 0) + v
                break
    t = {"physical": None, "magical": None, "magic": None, "defense": None, "movement": "movement", "jungling": "jungle", "roaming": "support"}.get(cls.split()[0] if cls else "", None)
    hok_items.append({"id": "hok-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-"), "name": name, "cost": price, "type": t or item_type(st, name), "stats": clean(st), "file": img})
    files.append(img)
imgs = wiki_images("honor-of-kings.fandom.com", [f for f in files if f])
for i in hok_items:
    i["img"] = imgs.get(i.pop("file"))
out["hok"] = hok_items

# ------------------------------------------------------------------ Dota 2
dota = get("https://raw.githubusercontent.com/odota/dotaconstants/master/build/items.json")
SUPPORT = {"mekansm", "guardian_greaves", "pipe", "glimmer_cape", "force_staff", "solar_crest", "vladmir", "spirit_vessel", "lotus_orb", "holy_locket", "ghost",
           "ancient_janggo", "boots_of_bearing", "urn_of_shadows", "medallion_of_courage", "aether_lens", "rod_of_atos", "pavise", "wraith_pact", "veil_of_discord"}
BOOTS = {"power_treads", "phase_boots", "arcane_boots", "tranquil_boots", "travel_boots", "travel_boots_2", "guardian_greaves", "boots_of_bearing"}
dota_items = []
for key, it in dota.items():
    cost = it.get("cost") or 0
    if it.get("qual") in ("consumable", "component") or key.startswith("recipe_") or key.startswith("river_painter") or "tango" in key:
        continue
    if not it.get("created") and key not in BOOTS:
        continue
    if cost < 900 and key not in BOOTS:
        continue
    a = {x["key"]: x.get("value") for x in it.get("attrib", []) if isinstance(x, dict)}

    def num(k):
        v = a.get(k)
        try:
            return float(str(v).split("/")[0].strip())
        except Exception:
            return 0.0
    stat_all = num("bonus_all_stats")
    stg, agi, intel = num("bonus_strength") + stat_all, num("bonus_agility") + stat_all, num("bonus_intellect") + stat_all
    st = {"ad": num("bonus_damage") + (stg + agi + intel) * 0.33, "hp": num("bonus_health") + stg * 22, "armor": num("bonus_armor") + agi / 6,
          "mr": num("bonus_magical_armor") + num("bonus_magic_resistance") + num("bonus_spell_resist"), "as": num("bonus_attack_speed") + agi, "mana": num("bonus_mana") + intel * 12,
          "ap": num("bonus_spell_amp") * 6 + intel * 1.2, "ms": num("bonus_movement_speed") + num("bonus_movement"), "ls": num("lifesteal_percent") + num("bonus_lifesteal"),
          "haste": num("bonus_cooldown"), "crit": num("crit_chance") if num("crit_multiplier") else 0}
    dota_items.append({"id": "dota2-" + key, "name": it.get("dname") or key, "cost": int(cost), "type": item_type(st, key, key in SUPPORT, key in BOOTS and key not in SUPPORT),
                       "stats": clean(st), "img": "https://cdn.cloudflare.steamstatic.com" + str(it.get("img", "")).split("?")[0] if it.get("img") else None})
out["dota2"] = dota_items

doc = {"snapshot_id": "items-" + time.strftime("%Y-%m-%d"), "sources": {
    "lol": f"Riot Data Dragon {ver}", "wildrift": "League of Legends Wiki (Module:WRItemData/data, CC BY-SA)", "mlbb": "Mobile Legends Wiki (Module:Equipment/data, CC BY-SA)",
    "hok": "Honor of Kings Wiki (Equipment and item pages, CC BY-SA)", "dota2": "odota/dotaconstants (MIT)"}, "items": out}
json.dump(doc, open(os.path.join(ROOT, "src/data/real/items.json"), "w"), ensure_ascii=False, separators=(",", ":"))
for k, v in out.items():
    from collections import Counter
    print(k, len(v), dict(Counter(i["type"] for i in v)), "with image", sum(1 for i in v if i.get("img")))
