"""Shade's weekly review: Claude reads two weeks of notification stats and suggests up to 5 changes.

Run by the scheduler (job 'shade-review', Sundays) or the app's "Review now" button (server.py).
New suggestions land in the store as 'pending'; the phone shows each one as a notification with
Approve / Hold on its next sync. Nothing is changed here.

    python review.py            # review and save
    python review.py --dry-run  # print what Claude would suggest, save nothing
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import store  # noqa: E402

MODEL = "opus"
LOG = store.HOME / "last-review.txt"
MIN_DAYS = 3
# Read-only lookups so an own_system suggestion can name the automation or file that sends it.
TOOLS = ["Read", "Grep", "Glob",
         "mcp__claude_ai_Home_Assistant__ha_search",
         "mcp__claude_ai_Home_Assistant__ha_config_get_automation",
         "mcp__claude_ai_Home_Assistant__ha_config_get_script",
         "mcp__scheduler__schedule_list", "mcp__scheduler__jobs_list"]

SYSTEM = r"""You review the notifications on Alan's Samsung Galaxy S23 Ultra and suggest changes so that his
phone only interrupts him for things he acts on. You get, per app + notification channel, two weeks of
counts and how each notification left the shade:

- opened: he tapped it. swiped: he swiped it away. cleared: gone with Clear all.
- app_removed: the app took it back (usually he dealt with it in the app or elsewhere, so it was useful).
- in_shade: still showing. in_shade_over_6h: ignored for 6+ hours. ruled: hidden by a rule he approved.
- median_minutes_shown, night_23_07 (posted 23:00-07:00), importance (Android: 4 = makes sound and pops up,
  3 = sound, 2 = silent in the shade, 1 = minimised), titles with digits replaced by #.

Signals: mostly swiped/cleared within minutes, or ignored for hours, means noise. Opened or app_removed
means useful. A busy channel that's useful is fine. Small numbers (under ~5 in two weeks) are not
enough evidence, skip them.

Suggestion kinds (pick the lightest one that fixes it):
1. channel_settings: a change he makes himself in Android's settings for that app's channel (make it
   silent, turn it off, or turn off pop-ups/sound). Approve opens that exact settings page on his phone.
   Best for third-party apps (shops, news, social, banking promos).
2. phone_rule: Shade itself handles matching notifications on the phone. rule = {"pkg": ..., "channel_id":
   ... or null, "title_regex": a Java regex on the title or null, "action": "dismiss" | "snooze",
   "from": "HH:MM" or null, "until": "HH:MM" or null}. dismiss = removed as soon as it arrives (still
   counted). snooze = during from..until it is held back and comes back at `until` (good for night-time
   noise he does want in the morning). Use this when only some of a channel is noise (title_regex) or
   for time windows.
3. own_system: the notification comes from something Alan built and owns: Home Assistant automations
   and scripts (package io.homeassistant.companion.android, most of his household notifications: the
   scheduler, Friday apps, plants, cats, leave times, power cuts, litter trays...), or his own apps
   (studio.alans.*: FX Watcher, Meal Log; com.ahaverty.*). Describe the change to make at the source
   (fewer, merged, different time, quieter channel, drop it) in `change`, naming the automation, script
   or C:\projects folder if you can find it (you may look things up with the read-only tools; HA
   notifications usually come from automations calling notify.mobile_app_alan_samsung, and C:\projects
   holds his code). If you approve, a Claude Code session is started with your `change` text to do it,
   so make it specific and self-contained. `project` = the C:\projects folder name or null.

Hard rules:
- Never suggest quieting anything marked safety=true (glucose/pump alarms, CamAPS, Dexcom, FX Watcher's
  alerts, calls, alarm clock), or anything about his insulin, glucose or medical reminders, even if he
  swipes them. Direct messages and calls from people are not noise either: leave messaging apps alone
  unless it's clearly a group or promo channel.
- Don't repeat a suggestion he held or that was done in the history, unless it was held over 30 days ago
  and the numbers got worse.
- At most 5 suggestions, best first. None is a fine answer when nothing stands out.
- Write for a phone: title under 70 characters, why = one or two short sentences with the numbers
  ("Swiped 41 of 44 in 2 weeks, mostly within a minute"). Plain words, no dashes as punctuation.

End your reply with ONE line of JSON and nothing after it:
{"suggestions": [{"kind": ..., "title": ..., "why": ..., "pkg": ..., "app": ..., "channel_id": ...,
  "channel_name": ..., "rule": {...} or null, "change": "..." or null, "project": ... or null}]}
"""


def build_prompt() -> str | None:
    with store.db() as c:
        first = c.execute("SELECT MIN(posted_at) FROM events").fetchone()[0]
    if not first or time.time() * 1000 - first < MIN_DAYS * 86_400_000:
        return None
    return ("Today is " + time.strftime("%A %d %B %Y") + ".\n\n"
            "## Stats, last 14 days\n" + json.dumps(store.stats(14), ensure_ascii=False) +
            "\n\n## Rules Shade runs on the phone now\n" + json.dumps(store.device_rules(), ensure_ascii=False) +
            "\n\n## Earlier suggestions (90 days) and what he did\n" +
            json.dumps(store.history(90), ensure_ascii=False))


def ask_claude(prompt: str) -> str:
    fd, sp = tempfile.mkstemp(prefix="shade-review-", suffix=".md")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(SYSTEM)
    try:
        proc = subprocess.run(["claude", "-p", "--model", MODEL, "--output-format", "json",
                               "--allowedTools", ",".join(TOOLS), "--append-system-prompt-file", sp],
                              input=prompt, text=True, encoding="utf-8", capture_output=True, timeout=900,
                              cwd=r"C:\projects")
    finally:
        os.unlink(sp)
    store.HOME.mkdir(parents=True, exist_ok=True)
    LOG.write_text(proc.stdout + "\n--- stderr ---\n" + proc.stderr, encoding="utf-8")
    if proc.returncode != 0:
        raise RuntimeError(f"claude exit {proc.returncode}: {proc.stderr.strip()[:300]}")
    j = json.loads(proc.stdout)
    if j.get("is_error"):
        raise RuntimeError(str(j.get("result"))[:300])
    return j.get("result") or ""


def parse(result: str) -> list[dict]:
    for line in reversed(result.strip().splitlines()):
        line = line.strip().strip("`")
        if line.startswith("{") and '"suggestions"' in line:
            return json.loads(line).get("suggestions") or []
    m = re.search(r'\{\s*"suggestions".*\}', result, re.S)
    if m:
        return json.loads(m.group(0)).get("suggestions") or []
    raise ValueError("no suggestions JSON in Claude's reply")


def review(dry_run: bool = False) -> dict:
    prompt = build_prompt()
    if prompt is None:
        return {"ok": True, "added": 0, "note": f"Less than {MIN_DAYS} days of notifications yet, nothing to review."}
    found = parse(ask_claude(prompt))[:5]
    if dry_run:
        return {"ok": True, "would_add": found}
    added = [sid for sg in found if (sid := store.add_suggestion(sg))]
    return {"ok": True, "added": len(added), "dropped": len(found) - len(added), "ids": added}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    try:
        r = review(a.dry_run)
    except Exception as ex:  # noqa: BLE001 - report to the scheduler
        print(f"Shade review failed: {ex}")
        sys.exit(1)
    if a.dry_run:
        print(json.dumps(r, indent=2, ensure_ascii=False))
    elif r.get("added"):
        print(f"Shade: {r['added']} new notification suggestion(s), they'll show on the phone at the next sync.")
    else:
        print(r.get("note") or "Shade: nothing worth changing this week.")
