#!/usr/bin/env python3
"""Fetch public ability data (cached in raw/). Polite: batched APIs, ~1 request/second for page crawls."""
import json, os, re, sys, time, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
RAW = os.environ.get("ABIL_RAW", os.path.join(ROOT, "abilities-raw"))  # downloaded pages (not committed)
CHARS = os.path.join(ROOT, "..", "src", "data", "real", "characters.json")
UA = "EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)"
os.makedirs(RAW, exist_ok=True)


def get(url, path, delay=1.0):
    full = os.path.join(RAW, path)
    if os.path.exists(full) and os.path.getsize(full) > 0:
        return open(full, encoding="utf-8", errors="ignore").read()
    os.makedirs(os.path.dirname(full), exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                body = r.read().decode("utf-8", errors="ignore")
            open(full, "w", encoding="utf-8").write(body)
            time.sleep(delay)
            return body
        except urllib.error.HTTPError as e:
            print("HTTP", e.code, url, file=sys.stderr)
            if e.code in (403, 404, 410):
                return None
            if e.code == 429:
                print("rate limited: stopping this source", file=sys.stderr)
                return None
            time.sleep(5 * (attempt + 1))
        except Exception as e:
            print("ERR", e, url, file=sys.stderr)
            time.sleep(5 * (attempt + 1))
    return None


chars = json.load(open(CHARS))["titles"]
names = {t: [(c["character_id"], c["name"]) for c in chars[t]["characters"]] for t in ["mlbb", "wildrift", "hok", "lol", "dota2", "valorant"]}

# LoL: one file.
get("https://ddragon.leagueoflegends.com/cdn/16.23.1/data/en_US/championFull.json", "lol/championFull.json", 0.2)
# Dota 2: OpenDota constants.
for k in ["abilities", "hero_abilities", "heroes"]:
    get(f"https://api.opendota.com/api/constants/{k}", f"dota/{k}.json", 1.0)
# Valorant.
get("https://valorant-api.com/v1/agents?isPlayableCharacter=true", "valorant/agents.json", 0.2)

# MLBB: MediaWiki API, 40 titles per request.
ml = [n for _, n in names["mlbb"]]
for i in range(0, len(ml), 40):
    batch = ml[i:i + 40]
    q = urllib.parse.urlencode({"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main", "redirects": "1", "format": "json", "titles": "|".join(batch)})
    get("https://mobile-legends.fandom.com/api.php?" + q, f"mlbb/batch{i // 40}.json", 2.0)

# Wild Rift: official champion pages (slugs from the sitemap).
idx = get("https://wildrift.leagueoflegends.com/sitemap_index.xml", "wr/sitemap_index.xml", 1.0) or ""
slugs = set()
for sm in re.findall(r"<loc>([^<]+)</loc>", idx):
    body = get(sm, "wr/" + re.sub(r"[^A-Za-z0-9]+", "_", sm)[-80:] + ".xml", 1.0) or ""
    for u in re.findall(r"<loc>(https://wildrift\.leagueoflegends\.com/en-us/champions/[^<]+?)/?</loc>", body):
        slugs.add(u.rstrip("/").split("/")[-1])
json.dump(sorted(slugs), open(os.path.join(RAW, "wr/slugs.json"), "w"))
print("WR slugs", len(slugs))
for s in sorted(slugs):
    get(f"https://wildrift.leagueoflegends.com/en-us/champions/{s}/", f"wr/champ/{s}.html", 1.0)

# Honor of Kings: hokstats.gg hero pages (slugs from the hero list page).
lst = get("https://hokstats.gg/heroes", "hok/heroes.html", 1.0) or ""
hs = sorted(set(re.findall(r'href="/heroes/([a-z0-9\-]+)/?"', lst)))
print("HoK slugs", len(hs))
json.dump(hs, open(os.path.join(RAW, "hok/slugs.json"), "w"))
for s in hs:
    get(f"https://hokstats.gg/heroes/{s}", f"hok/hero/{s}.html", 1.0)
print("done")
