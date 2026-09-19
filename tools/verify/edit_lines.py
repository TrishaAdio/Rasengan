#!/usr/bin/env python3
"""Mutates the awakening-lines data while the dedicated server is running.

Used by run_summon_test.sh to prove two claims that cannot be shown by re-running a query after a
no-op /reload:

  add      append one line to the file the running server actually reads, so /reload must move the
           count from 64 to 65
  datapack drop a world datapack that (a) OVERRIDES rasengan:awakening with 3 lines and (b) adds an
           unrelated audit:extra file. /reload must then make the spoken pool exactly 3 - proving
           datapack override works, and proving the unselected extra file is correctly not spoken
  restore  put the shipped resource back exactly as it was

The dev server reads mod resources from build/resources/main, so that is the copy to edit. The
source tree is never touched.
"""
import json
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIVE = os.path.join(ROOT, "build", "resources", "main", "data", "rasengan",
                    "summon_lines", "awakening.json")
BACKUP = LIVE + ".orig"
PACK = os.path.join(ROOT, "run", "server", "world", "datapacks", "audit_extra")

# Format 101 is beyond the old pack_format ceiling, so this needs min_format/max_format.
MCMETA = {
    "pack": {
        "description": "summon-audit extra lines",
        "min_format": 0,
        "max_format": 9999,
    }
}


def add(text):
    if not os.path.exists(BACKUP):
        shutil.copy2(LIVE, BACKUP)
    with open(LIVE, encoding="utf-8") as handle:
        data = json.load(handle)
    data["lines"].append(text)
    with open(LIVE, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, ensure_ascii=False)
    print("edit_lines: live file now has %d lines" % len(data["lines"]), file=sys.stderr)


def datapack():
    extra = os.path.join(PACK, "data", "audit", "summon_lines")
    override = os.path.join(PACK, "data", "rasengan", "summon_lines")
    os.makedirs(extra, exist_ok=True)
    os.makedirs(override, exist_ok=True)
    with open(os.path.join(PACK, "pack.mcmeta"), "w", encoding="utf-8") as handle:
        json.dump(MCMETA, handle, indent=2)
    # Not selected by lines_resource: must load but never be spoken.
    with open(os.path.join(extra, "extra.json"), "w", encoding="utf-8") as handle:
        json.dump({"lines": [
            "MARK datapack line one.",
            "MARK datapack line two.",
        ]}, handle, indent=2)
    # Same path as the shipped file: must replace it wholesale.
    with open(os.path.join(override, "awakening.json"), "w", encoding="utf-8") as handle:
        json.dump({"lines": [
            "MARK override one.",
            "MARK override two.",
            "MARK override three.",
        ]}, handle, indent=2)
    print("edit_lines: datapack written to %s" % PACK, file=sys.stderr)


def restore():
    if os.path.exists(BACKUP):
        shutil.move(BACKUP, LIVE)
        print("edit_lines: live file restored", file=sys.stderr)
    shutil.rmtree(PACK, ignore_errors=True)


if __name__ == "__main__":
    action = sys.argv[1]
    if action == "add":
        add(sys.argv[2])
    elif action == "datapack":
        datapack()
    elif action == "restore":
        restore()
    else:
        raise SystemExit("unknown action " + action)
