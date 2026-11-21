#!/usr/bin/env python3
"""Build assets/abilities.json: a typed, versioned simulation ability model for the five MOBAs and
Valorant from the cached public sources in raw/. Descriptions are classified into reusable effect
types; numbers (cooldowns, costs, ranges, durations, damage) are taken from the source when it has
them, otherwise filled with marked approximations."""
import glob, html, json, os, re, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
RAW = os.environ.get("ABIL_RAW", os.path.join(ROOT, "abilities-raw"))  # downloaded pages (not committed)
CHARS = os.path.join(ROOT, "..", "src", "data", "real", "characters.json")
OUT = os.path.join(ROOT, "..", "native-android", "app", "src", "main", "assets", "abilities.json")
catalogue = json.load(open(CHARS))["titles"]


def norm(s):
    return re.sub(r"[^a-z0-9]", "", html.unescape(s or "").lower().replace("&", "and"))


def nums(s):
    return [float(x) for x in re.findall(r"\d+(?:\.\d+)?", s or "")]


def strip(s):
    s = html.unescape(s or "")
    s = re.sub(r"\{\{scale\|[^}]*\}\}", " damage ", s)
    s = re.sub(r"\{\{(?:ai|hi|Ai|Hi)\|([^}|]*)[^}]*\}\}", r"\1", s)
    s = re.sub(r"\{\{[^{}]*\}\}", " ", s)
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"<[^>]+>", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def templates(txt, name):
    """Bodies of {{name ...}} templates (brace-matched, nested templates kept)."""
    out = []
    i = 0
    while True:
        i = txt.find("{{" + name, i)
        if i < 0:
            return out
        j = i + 2
        depth = 1
        while j < len(txt) and depth > 0:
            if txt.startswith("{{", j):
                depth += 1; j += 2
            elif txt.startswith("}}", j):
                depth -= 1; j += 2
            else:
                j += 1
        body = txt[i + 2 + len(name):j - 2]
        if body[:1] in ("\n", "|", " "):
            out.append(body)
        i = j


def fields_of(body):
    """Top-level |key = value fields of a template body."""
    out = {}
    depth = 0
    cur = []
    parts = []
    k = 0
    while k < len(body):
        if body.startswith("{{", k) or body.startswith("[[", k):
            depth += 1; cur.append(body[k:k + 2]); k += 2; continue
        if body.startswith("}}", k) or body.startswith("]]", k):
            depth -= 1; cur.append(body[k:k + 2]); k += 2; continue
        if body[k] == "|" and depth == 0:
            parts.append("".join(cur)); cur = []; k += 1; continue
        cur.append(body[k]); k += 1
    parts.append("".join(cur))
    for p in parts:
        if "=" in p:
            a, b = p.split("=", 1)
            out[a.strip()] = b.strip()
    return out


# --------------------------------------------------------------------------- MOBA text classifier
HARD = [("suppress", r"suppress"), ("knockup", r"knock(?:s|ing|ed)? (?:up|airborne)|airborne|launch(?:es|ing)? .*into the air|hurled into the air|knocked into the air"),
        ("knockback", r"knock(?:s|ing|ed)? (?:back|away)|push(?:es|ing)? (?:the )?(?:enemy|enemies|target|them|back)|pulls? (?:the |an |all )?(?:enemy|enemies|target|them|champion|hero)|drags? (?:the |an )?(?:enemy|target)"),
        ("stun", r"\bstun|petrif|freez|frozen|sleep|transform(?:s|ing)? .*into|banish|hex"),
        ("charm", r"charm"), ("taunt", r"taunt|provok"), ("fear", r"fear|terrif|flee")]
KW = {"suppress": "suppress", "knockup": "(?:knock|airborne)", "knockback": "knock", "stun": "(?:stun|freez|petrif|sleep)", "charm": "charm", "taunt": "taunt", "fear": "(?:fear|terrif)"}
DEF_DUR = {"stun": 1.25, "knockup": 0.8, "knockback": 0.6, "suppress": 1.6, "charm": 1.4, "taunt": 1.25, "fear": 1.25, "root": 1.5, "silence": 1.5, "slow": 2.0, "blind": 1.5}


def dur_near(text, word):
    m = re.search(word + r"[^.]{0,80}?(\d+(?:\.\d+)?)\s*(?:s\b|sec|second)", text, re.I)
    if m:
        v = float(m.group(1))
        if 0.2 <= v <= 6:
            return v
    return None


def classify(text, slot, cls, title):
    """Effect list, targeting, AI tag from a description (approximate parse)."""
    t = " " + strip(text).lower() + " "
    fx = []
    if re.search(r"damag|deals? |strike|\bhit|blast|burn|\battack(?:s|ing)? (?:nearby |an |the |all )?(?:enem|target)|bolts?|explod", t) and not re.search(r"^ *(?:passive: )?(?:gain|increase)", t):
        d = "true" if "true damage" in t else "magic" if re.search(r"magic|magical", t) else "phys" if re.search(r"physical|attack damage", t) else ("magic" if cls in ("Mage", "Intelligence Nuker", "Support", "Enchanter") else "phys")
        fx.append({"k": "dmg", "d": d, "p": 1.0})
    if re.search(r"over time|each second|per second|burn|bleed|poison|damage over", t):
        fx.append({"k": "dot", "t": dur_near(t, r"(?:over time|burn|bleed|poison)") or 3.0})
    for k, rx in HARD:
        if re.search(rx, t):
            fx.append({"k": k, "t": dur_near(t, KW[k]) or DEF_DUR[k]})
            break
    if re.search(r"\broot|immobiliz|snare|entangl|bind|tether|imprison|pin(?:s|ned)? ", t):
        fx.append({"k": "root", "t": dur_near(t, r"(?:root|immobiliz|snare|bind)") or DEF_DUR["root"]})
    if re.search(r"silenc", t):
        fx.append({"k": "silence", "t": dur_near(t, r"silenc") or DEF_DUR["silence"]})
    if re.search(r"\bslow|reduc\w* .*movement speed|cripple", t):
        pct = re.search(r"slow\w*[^.%]{0,40}?(\d{1,2})\s*%", t)
        fx.append({"k": "slow", "v": round(float(pct.group(1)) / 100, 2) if pct else 0.3, "t": dur_near(t, r"slow") or DEF_DUR["slow"]})
    if re.search(r"\bblind|nearsight|obscur", t):
        fx.append({"k": "blind", "t": DEF_DUR["blind"]})
    if re.search(r"\bheal|restor\w* (?:\w+ )?(?:health|hp)|regenerat|lifesteal|spell vamp|life steal", t):
        fx.append({"k": "heal", "ally": bool(re.search(r"all(?:y|ies)|teammate|friendly", t)), "p": 1.0})
    if re.search(r"shield|barrier|absorb", t):
        fx.append({"k": "shield", "ally": bool(re.search(r"all(?:y|ies)|teammate|friendly", t)), "p": 1.0})
    if re.search(r"\bdash|blink|leap|lunge|jump|charges? (?:forward|toward|at)|teleport|rush(?:es)? (?:forward|toward)|vault|shift|flies|fly to|hop|roll|sprint toward|slides?", t):
        fx.append({"k": "dash", "v": 40 if "teleport" not in t else 120})
    if re.search(r"invisib|camouflag|stealth|unseen|vanish", t):
        fx.append({"k": "stealth", "t": 3.0})
    if re.search(r"untargetable|invulnerab|immune|immunity|stasis|cannot be targeted|spell immunity|magic immunity", t):
        fx.append({"k": "immune", "t": 2.0})
    if re.search(r"attack speed", t) and re.search(r"gain|increas|bonus|grant", t):
        fx.append({"k": "buff_as", "v": 0.3, "t": 4.0})
    if re.search(r"movement speed|move speed", t) and re.search(r"gain|increas|bonus|grant|boost", t) and not any(f["k"] == "slow" for f in fx):
        fx.append({"k": "buff_ms", "v": 0.3, "t": 2.5})
    if re.search(r"damage reduction|reduc\w* (?:the )?damage (?:taken|received)|takes? less damage|resistances?|armor and magic|tough", t):
        fx.append({"k": "dmg_reduce", "v": 0.25, "t": 3.0})
    if re.search(r"summon|spawns? (?:a|an|\d)|clone|illusion|turret|totem|creates? (?:a|an) (?:\w+ )?(?:ward|trap|turret|totem|clone)", t):
        fx.append({"k": "summon"})
    if re.search(r"reveal|true sight|vision of|grants? (?:\w+ )?vision", t):
        fx.append({"k": "reveal", "t": 4.0})
    if re.search(r"missing health|execute|below \d+% health|instantly kills?", t):
        fx.append({"k": "execute"})
    if re.search(r"wall|terrain|impassable|cage|arena", t):
        fx.append({"k": "terrain", "t": 3.0})
    if re.search(r"global|anywhere on the map|all enemy heroes|every enemy champion", t):
        fx.append({"k": "global"})
    if re.search(r"revive|resurrect|reincarnat|returns? to life", t):
        fx.append({"k": "revive"})
    # Targeting.
    if any(f["k"] == "global" for f in fx):
        target = "global"
    elif re.search(r"\bcone\b|in front of|fan of|arc", t):
        target = "cone"
    elif re.search(r"in a line|straight line|target direction|in the direction|direction|line of|skillshot|first enemy (?:hit|unit|champion|hero)|projectile|throws?|fires?|hurls?|launch|sends? (?:out|a|an|forth)|tosses|flings?|shoots?|forward", t):
        target = "line"
    elif re.search(r"target (?:enemy|unit|hero|champion)|an enemy (?:unit|hero|champion)|selected enemy|target an enemy|enemy target", t):
        target = "unit"
    elif re.search(r"target (?:area|location|position|point)|at a location|area|circle|zone|field|ground", t):
        target = "area"
    elif re.search(r"around (?:him|her|it|them|self)|nearby enemies|surrounding|all enemies (?:near|around)|in a radius|aura", t):
        target = "self_aoe"
    elif re.search(r"all(?:y|ies)|teammate|friendly", t) and any(f["k"] in ("heal", "shield") for f in fx):
        target = "ally"
    else:
        target = "self" if not any(f["k"] == "dmg" for f in fx) else "unit"
    return fx, target


def ai_tag(fx, slot):
    ks = {f["k"] for f in fx}
    hard = ks & {"stun", "knockup", "knockback", "suppress", "charm", "taunt", "fear", "root"}
    if "dash" in ks and hard:
        return "engage"
    if hard:
        return "engage" if slot in ("R", "ULT") else "control"
    if "execute" in ks:
        return "finish"
    if ks & {"heal", "shield"} and any(f.get("ally") for f in fx):
        return "protect"
    if ks & {"heal", "shield", "dmg_reduce", "immune"}:
        return "survive"
    if "dash" in ks or "stealth" in ks:
        return "mobility"
    if ks & {"buff_as", "buff_ms"} and "dmg" not in ks:
        return "buff"
    if "dot" in ks or "terrain" in ks:
        return "zone"
    if "dmg" in ks:
        return "burst" if slot in ("R", "ULT") else "poke"
    return "utility"


def vfx(target, fx):
    ks = {f["k"] for f in fx}
    if target == "line":
        return "projectile"
    if target in ("area", "self_aoe", "global"):
        return "area"
    if target == "cone":
        return "cone"
    if "dash" in ks:
        return "dash"
    if ks & {"heal", "shield"}:
        return "aura"
    return "beam" if target == "unit" else "self"


# Default numbers when a source has none (marked approximate).
DEF_CD = {"P": 0, "Q": 8, "W": 11, "E": 13, "R": 70}
DEF_RANGE = {"line": 60, "unit": 40, "area": 50, "cone": 30, "self_aoe": 22, "self": 0, "ally": 45, "global": 1000}


def ability(cid, slot, name, desc, cls, title, cd=None, cost=None, rng=None, ranks=None, extra_fx=None, approx=None):
    fx, target = classify(desc, slot, cls, title)
    if extra_fx:
        for e in extra_fx:
            if not any(f["k"] == e["k"] for f in fx):
                fx.append(e)
            else:
                for f in fx:
                    if f["k"] == e["k"]:
                        f.update({k: v for k, v in e.items() if v is not None})
    approx = list(approx or [])
    if slot == "P":
        kind = "passive"
    elif slot == "R":
        kind = "ultimate"
    else:
        kind = "active"
    if cd is None:
        cd = [DEF_CD.get(slot, 10)]
        if slot != "P":
            approx.append("cooldown")
    if rng is None:
        rng = DEF_RANGE.get(target, 40)
        approx.append("range")
    out = {"id": f"{cid}-{slot.lower()}", "slot": slot, "name": html.unescape(name).strip(), "type": kind, "target": target,
           "range": round(rng, 1), "cd": [round(x, 2) for x in cd], "fx": fx, "ai": ai_tag(fx, slot), "vfx": vfx(target, fx)}
    if cost:
        out["cost"] = [round(x, 1) for x in cost]
    if approx:
        out["approx"] = sorted(set(approx))
    return out


SLOTS_LOL = ["Q", "W", "E", "R"]
result = {}
report = {}

# ------------------------------------------------------------------------------------- League (PC)
lol = json.load(open(os.path.join(RAW, "lol/championFull.json")))["data"]
lol_by = {norm(v["name"]): v for v in lol.values()}
U_LOL = 1 / 14.87  # Summoner's Rift units -> map units (0..1000)
for cid, name in [(c["character_id"], c["name"]) for c in catalogue["lol"]["characters"]]:
    v = lol_by.get(norm(name))
    cls = (catalogue["lol"]["characters"][0]["classes"] or ["Fighter"])[0]
    if not v:
        continue
    cls = v["tags"][0] if v.get("tags") else "Fighter"
    abil = [ability(cid, "P", v["passive"]["name"], v["passive"]["description"], cls, "lol", approx=["damage magnitude (normalised)"])]
    for i, s in enumerate(v["spells"][:4]):
        r = s.get("range") or [0]
        rr = r[0] if isinstance(r, list) else r
        rr = None if rr in (0, 25000) else (1000 if rr >= 25000 else rr * U_LOL)
        abil.append(ability(cid, SLOTS_LOL[i], s["name"], s["description"], cls, "lol", cd=s.get("cooldown"), cost=s.get("cost"), rng=rr, approx=["damage magnitude (normalised)", "effect durations unless stated"]))
    st = v["stats"]
    result[cid] = {"src": "Riot Data Dragon 16.23.1", "basic": {"range": round(st["attackrange"] * U_LOL, 1), "ranged": st["attackrange"] > 300, "ms": st["movespeed"]}, "abilities": abil}

# Wild Rift: official champion pages (names, slots, descriptions); timings from the same champion on PC
# scaled to Wild Rift's shorter cooldowns, marked approximate.
wr_pages = {}
for f in glob.glob(os.path.join(RAW, "wr/champ/*.html")):
    s = open(f, encoding="utf-8", errors="ignore").read()
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', s, re.S)
    if not m:
        continue
    txt = m.group(1)
    try:
        d = json.loads(txt)
    except Exception:
        continue
    found = []

    def walk(o):
        if isinstance(o, dict):
            if "label" in o and isinstance(o.get("content"), dict) and "subtitle" in o["content"] and "description" in o["content"]:
                found.append((o["content"]["subtitle"], o["label"], (o["content"]["description"] or {}).get("body", "")))
            for x in o.values():
                walk(x)
        elif isinstance(o, list):
            for x in o:
                walk(x)
    walk(d)
    if found:
        wr_pages[os.path.basename(f)[:-5].replace("-", "")] = found
for c in catalogue["wildrift"]["characters"]:
    cid, name = c["character_id"], c["name"]
    found = wr_pages.get(norm(name)) or wr_pages.get(norm(cid.split("-", 1)[1]))
    if not found:
        continue
    pc = lol_by.get(norm(name))
    cls = (c.get("classes") or ["Fighter"])[0]
    abil = []
    seen = set()
    for sub, label, body in found:
        slot = {"PASSIVE": "P", "1": "Q", "2": "W", "3": "E", "ULTIMATE": "R", "4": "R"}.get(str(sub).upper().strip())
        if not slot or slot in seen:
            continue
        seen.add(slot)
        cd = rng = cost = None
        ap = ["damage magnitude (normalised)"]
        if pc and slot != "P":
            sp = pc["spells"][SLOTS_LOL.index(slot)]
            if sp.get("cooldown"):
                cd = [round(x * 0.8, 2) for x in sp["cooldown"]]
                ap.append("cooldown (PC value x0.8)")
            r = sp.get("range") or [0]
            rr = r[0] if isinstance(r, list) else r
            if rr and rr < 25000:
                rng = rr * U_LOL
                ap.append("range (PC value)")
        abil.append(ability(cid, slot, label, body, cls, "wildrift", cd=cd, rng=rng, approx=ap))
    order = {"P": 0, "Q": 1, "W": 2, "E": 3, "R": 4}
    abil.sort(key=lambda a: order[a["slot"]])
    result[cid] = {"src": "wildrift.leagueoflegends.com (abilities); timings approximate", "basic": {"range": (lol_by.get(norm(name), {}).get("stats", {}).get("attackrange", 175)) * U_LOL, "ranged": (lol_by.get(norm(name), {}).get("stats", {}).get("attackrange", 175)) > 300}, "abilities": abil}

# --------------------------------------------------------------------------------------- Dota 2
da = json.load(open(os.path.join(RAW, "dota/abilities.json")))
dh = json.load(open(os.path.join(RAW, "dota/hero_abilities.json")))
dheroes = json.load(open(os.path.join(RAW, "dota/heroes.json")))
dname = {norm(h["localized_name"]): h for h in dheroes.values()}
U_DOTA = 1 / 16.0
for c in catalogue["dota2"]["characters"]:
    cid, name = c["character_id"], c["name"]
    h = dname.get(norm(name))
    if not h:
        continue
    al = [a for a in dh.get(h["name"], {}).get("abilities", []) if a in da and not a.startswith("generic") and "hidden" not in a]
    cls = (c.get("classes") or ["Support"])[0]
    abil = []
    actives = []
    for a in al:
        x = da[a]
        if not x.get("dname"):
            continue
        beh = x.get("behavior") or []
        beh = [beh] if isinstance(beh, str) else beh
        if "Hidden" in beh or "Not Learned" in str(beh):
            continue
        actives.append((a, x, beh))
    ult_name = al[-1] if al else None
    basics = [p for p in actives if p[0] != ult_name][:4]
    slots = ["Q", "W", "E", "D"]
    pick = [(slots[i], p) for i, p in enumerate(basics[:3])] + ([("R", next(p for p in actives if p[0] == ult_name))] if any(p[0] == ult_name for p in actives) else [])
    for slot, (a, x, beh) in pick:
        attrib = {at["key"]: at.get("value") for at in x.get("attrib", []) if isinstance(at, dict)}

        def val(*keys):
            for k in keys:
                for ak, av in attrib.items():
                    if ak == k or ak.endswith(k):
                        vs = av if isinstance(av, list) else [av]
                        try:
                            return [float(str(z).split("/")[0]) for z in vs]
                        except Exception:
                            pass
            return None
        def flist(v):
            out = []
            for z in (v if isinstance(v, list) else [v]):
                try:
                    out.append(float(str(z).split("/")[0]))
                except Exception:
                    pass
            return out or None
        cd = flist(x.get("cd")) if x.get("cd") not in (None, "", "0") else None
        mc = flist(x.get("mc")) if x.get("mc") not in (None, "", "0") else None
        rng = val("abilitycastrange", "cast_range", "range")
        rng = rng[0] * U_DOTA if rng and rng[0] > 0 else None
        desc = x.get("desc", "")
        if "Passive" in beh:
            slot_use = "P" if slot != "R" else "R"
        else:
            slot_use = slot
        extra = []
        dmg = val("damage", "AbilityDamage", "nuke_damage", "base_damage")
        if dmg and x.get("dmg_type"):
            extra.append({"k": "dmg", "d": {"Magical": "magic", "Physical": "phys", "Pure": "true"}.get(x["dmg_type"], "magic"), "p": round(min(3.0, max(0.3, dmg[-1] / (300 if slot == "R" else 220))), 2)})
        sd = val("stun_duration", "duration")
        if sd and re.search(r"stun", desc, re.I):
            extra.append({"k": "stun", "t": round(min(5.0, sd[-1]), 2)})
        sl = val("movement_slow", "slow", "move_slow")
        if sl and re.search(r"slow", desc, re.I):
            extra.append({"k": "slow", "v": round(min(0.9, abs(sl[-1]) / 100), 2), "t": 2.0})
        ab = ability(cid, slot_use if slot_use != "D" else "E", x["dname"], desc, cls, "dota2", cd=cd, cost=mc, rng=rng, extra_fx=extra, approx=([] if dmg else ["damage magnitude (normalised)"]))
        if "Passive" in beh:
            ab["type"] = "passive"
        tt = x.get("target_team")
        if isinstance(tt, list):
            tt = tt[0] if tt else None
        if "Unit Target" in beh and tt in ("Friendly",):
            ab["target"] = "ally"
        elif "Unit Target" in beh and ab["target"] in ("self", "self_aoe"):
            ab["target"] = "unit"
        elif "Point Target" in beh and ab["target"] in ("self", "unit"):
            ab["target"] = "area" if "AOE" in beh else "line"
        elif "No Target" in beh and ab["target"] in ("unit", "line"):
            ab["target"] = "self_aoe"
        ab["vfx"] = vfx(ab["target"], ab["fx"])
        abil.append(ab)
    attack_range = h.get("attack_range") or (150 if h.get("attack_type") == "Melee" else 550)
    result[cid] = {"src": "OpenDota constants (Valve game files)", "basic": {"range": round(attack_range * U_DOTA, 1), "ranged": h.get("attack_type") == "Ranged", "ms": h.get("move_speed")}, "abilities": abil}

# ------------------------------------------------------------------------------------------ MLBB
U_MLBB = 6.2  # MLBB distance units -> map units
for f in sorted(glob.glob(os.path.join(RAW, "mlbb/batch*.json"))):
    q = json.load(open(f))["query"]
    alias = {n["to"]: n["from"] for n in q.get("normalized", []) + q.get("redirects", [])}
    for p in q["pages"].values():
        if "revisions" not in p:
            continue
        title = alias.get(p["title"], p["title"])
        c = next((c for c in catalogue["mlbb"]["characters"] if norm(c["name"]) == norm(title) or norm(c["name"]) == norm(p["title"])), None)
        if not c:
            continue
        cid = c["character_id"]
        cls = (c.get("classes") or ["Fighter"])[0]
        txt = p["revisions"][0]["slots"]["main"]["*"]
        # One ability per section (Passive / Skill 1-3 / Ultimate), template names in any case.
        low = txt.lower()
        heads = [(m.start(), m.group(1).strip().lower()) for m in re.finditer(r"\n=+\s*([^=\n]+?)\s*=+", txt)]
        SEC = {"passive": "P", "skill 1": "Q", "skill 2": "W", "skill 3": "E", "ultimate": "R", "special skill": "E"}
        picked = []
        for hi, (pos, h) in enumerate(heads):
            if h not in SEC:
                continue
            end = heads[hi + 1][0] if hi + 1 < len(heads) else len(txt)
            seg = txt[pos:end]
            k = seg.lower().find("{{ability")
            if k < 0:
                continue
            body = templates(seg[k:k + 2] + "Ability" + seg[k + 9:], "Ability")
            if body:
                picked.append((SEC[h], body[0]))
        abil = []
        seen = set()
        for slot, b in picked:
            fields = fields_of(b)
            name = fields.get("name") or ""
            if not name or slot in seen:
                continue
            seen.add(slot)
            cd = nums(fields.get("cooldown", "").split("\n")[0]) or None
            cost = nums(fields.get("mana-cost", "").split("\n")[0]) or None
            desc = fields.get("description", "")
            extra = []
            base = re.search(r"base=(\d+)(?:-(\d+))?", desc)
            if base:
                hi = float(base.group(2) or base.group(1))
                extra.append({"k": "dmg", "p": round(min(3.0, max(0.3, hi / (900 if slot == "R" else 420))), 2)})
            rng = re.search(r"(\d+(?:\.\d+)?)\s*(?:units|yards)", strip(desc))
            ab = ability(cid, slot, name, desc, cls, "mlbb", cd=cd, cost=cost, rng=(float(rng.group(1)) * U_MLBB if rng else None),
                         extra_fx=extra, approx=([] if base else ["damage magnitude (normalised)"]))
            abil.append(ab)
        if abil:
            result[cid] = {"src": "Mobile Legends Wiki (Fandom, CC BY-SA) ability data", "basic": {"range": 37 if cls in ("Marksman", "Mage") else 12, "ranged": cls in ("Marksman", "Mage")}, "abilities": abil}

# ------------------------------------------------------------------------------------ Honor of Kings
for f in glob.glob(os.path.join(RAW, "hok/hero/*.html")):
    s = open(f, encoding="utf-8", errors="ignore").read()
    slug = os.path.basename(f)[:-5]
    c = next((c for c in catalogue["hok"]["characters"] if norm(c["name"]) == norm(slug) or norm(c["character_id"].split("-", 1)[1]) == norm(slug)), None)
    if not c:
        continue
    parts = [(k, html.unescape(n), html.unescape(re.sub(r"<[^>]+>", " ", d))) for k, n, d in re.findall(r'font-mono[^"]*">(passive|skill1|skill2|skill3|ultimate|active)</span><span[^>]*>([^<]+)</span></div><p[^>]*>(.*?)</p>', s, re.S)]
    cid = c["character_id"]
    cls = (c.get("classes") or ["Fighter"])[0]
    abil = []
    seen = set()
    nact = 0
    for key, name, desc in parts:
        if key == "passive" and "P" in seen:
            break  # second form of a two-form hero
        if key == "active":
            if nact >= 3:
                continue
            key = ["skill1", "skill2", "skill3"][nact]
            nact += 1
        slot = {"passive": "P", "skill1": "Q", "skill2": "W", "skill3": "E", "ultimate": "R"}[key]
        if slot in seen:
            continue
        seen.add(slot)
        abil.append(ability(cid, slot, name.strip(), desc, cls, "hok", approx=["damage magnitude (normalised)", "effect durations unless stated"]))
    if abil:
        abil.sort(key=lambda a: "PQWER".index(a["slot"]))
        result[cid] = {"src": "hokstats.gg hero skills (short descriptions); timings approximate", "basic": {"range": 37 if cls in ("Marksman", "Mage") else 12, "ranged": cls in ("Marksman", "Mage")}, "abilities": abil}

# ---------------------------------------------------------------------------------------- Valorant
VAL_SLOT = {"Grenade": "C", "Ability1": "Q", "Ability2": "E", "Ultimate": "X", "Passive": "P"}


def val_kind(desc):
    t = re.sub(r"<[^>]+>", "", desc).lower()
    kinds = []
    for k, rx in [("smoke", r"smoke|vision[- ]block|blocks vision|cloud|veil|screen|sphere that blocks|cover|obscur"), ("flash", r"blind|flash|flare"),
                  ("wall", r"\bwall|barrier"), ("recon", r"reveal|recon|scan|marks? (?:enemies|their location)|detect|drone|sensor|spot"),
                  ("heal", r"\bheal"), ("revive", r"revive|resurrect"), ("mobility", r"dash|teleport|updraft|propel|leap|launch (?:yourself|upward)|blast pack|fly|jump"),
                  ("molly", r"molly|incendiar|burn|damag\w* (?:over time|zone|area)|explod|blast|detonat|damaging"), ("trap", r"trap|tripwire|alarm|turret|cage|mine"),
                  ("concuss", r"concuss|stun|daze"), ("slow", r"\bslow"), ("suppress", r"suppress"), ("decoy", r"decoy|clone|echo"), ("nearsight", r"nearsight|deafen"),
                  ("weapon", r"(?:equip|access|summon)[^.]{0,50}(?:rifle|sniper|weapon|knives|blades|shotgun|bow|pistol|gun|operator)|kill with|on kills?, refresh|kills? (?:reset|refund)")]:
        if re.search(rx, t):
            kinds.append(k)
    return kinds or ["utility"]


VAL_COST = {"C": (200, 1), "Q": (250, 1), "E": (0, 1), "X": (8, 1), "P": (0, 0)}
agents = json.load(open(os.path.join(RAW, "valorant/agents.json")))["data"]
for a in agents:
    c = next((c for c in catalogue["valorant"]["characters"] if norm(c["name"]) == norm(a["displayName"])), None)
    if not c:
        continue
    abil = []
    for ab in a["abilities"]:
        slot = VAL_SLOT.get(ab["slot"])
        if not slot:
            continue
        kinds = val_kind(ab["description"])
        cost, charges = VAL_COST[slot]
        abil.append({"id": f"{c['character_id']}-{slot.lower()}", "slot": slot, "name": ab["displayName"], "type": "ultimate" if slot == "X" else "passive" if slot == "P" else ("signature" if slot == "E" else "basic"),
                     "kinds": kinds, "cost": cost if slot != "X" else 0, "ultPoints": cost if slot == "X" else 0, "charges": charges,
                     "approx": ["credit cost / charges / ultimate points (typical values)"]})
    result[c["character_id"]] = {"src": "valorant-api.com (Riot game data)", "abilities": abil}

for t in ["lol", "wildrift", "dota2", "mlbb", "hok", "valorant"]:
    ids = [c["character_id"] for c in catalogue[t]["characters"]]
    have = [i for i in ids if i in result and len(result[i]["abilities"]) >= 3]
    report[t] = {"characters": len(ids), "with_kit": len(have), "missing": [i for i in ids if i not in have]}
    print(t, len(have), "/", len(ids), "missing:", report[t]["missing"][:12], file=sys.stderr)

json.dump({"version": 1, "generated": "2026-11-21", "units": "map units (map is 0..1000); cooldowns in seconds; slots P passive, Q/W/E basic, R ultimate (Valorant C/Q/E/X)",
           "sources": {"lol": "Riot Data Dragon 16.23.1 (spell names, descriptions, cooldowns, costs, ranges)", "wildrift": "wildrift.leagueoflegends.com champion pages (ability names, descriptions); cooldowns and ranges approximated from the PC champion",
                       "dota2": "OpenDota constants from the Dota 2 game files (behaviour, damage type, cooldown, mana, cast range, damage, durations)",
                       "mlbb": "Mobile Legends Wiki (Fandom, CC BY-SA 3.0) ability templates (cooldown, mana cost, base damage, descriptions)",
                       "hok": "hokstats.gg hero pages (skill names and short descriptions); numbers approximate", "valorant": "valorant-api.com (abilities, descriptions); costs approximate"},
           "method": "Descriptions are classified into reusable effect types (damage, damage over time, stun, knock-up, root, slow, silence, heal, shield, dash, stealth, immunity, buffs, summons, reveal, execute, terrain, global, revive) with targeting and an AI usage tag. Damage magnitude is a multiplier on the engine's per-title standard for the slot; it comes from the source where it lists damage (Dota 2, MLBB), otherwise 1.0. Fields listed in 'approx' are approximations.",
           "report": {k: {"characters": v["characters"], "with_kit": v["with_kit"]} for k, v in report.items()},
           "chars": result}, open(OUT, "w"), separators=(",", ":"))
print("wrote", OUT, os.path.getsize(OUT), file=sys.stderr)
