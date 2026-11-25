#!/usr/bin/env python3
"""
honours.json clean-up: a few stage records (groups, a regular season) merged several teams' players into
one champion or runner-up roster (e.g. Bin and Knight listed with three LPL teams in one year). A roster of
more than nine names is not one team's: it is emptied, the team names stay. Idempotent.

Usage: python3 scripts/data/honours_rosters_fix.py
"""
import json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HON = os.path.join(ROOT, "src", "data", "real", "honours.json")
h = json.load(open(HON))
n = 0
for e in h["events"]:
    for side in ("wp", "rp"):
        if len(e.get(side) or []) > 9:
            e[side] = []
            n += 1
h["sources"]["rosters_fix"] = "Champion/runner-up rosters of more than nine names (merged stage records) removed; the teams are kept"
json.dump(h, open(HON, "w"), ensure_ascii=False, separators=(",", ":"))
print("rosters emptied: %d" % n, file=sys.stderr)
