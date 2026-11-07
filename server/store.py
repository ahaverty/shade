"""Shade's store: what the phone saw (events), what Claude suggested (suggestions) and the phone's rules.

SQLite in ~/.shade/shade.db. stdlib only.

Outcomes (how a notification left the shade), set by the phone from Android's removal reason:
  opened      tapped (REASON_CLICK)
  swiped      swiped away by hand, alone or as part of its group
  cleared     gone with "Clear all"
  app_removed the app took it back itself (usually: read or handled somewhere else)
  timeout     the app's own timeout ran out
  snoozed     snoozed by the user
  ruled       removed or snoozed by one of Shade's approved rules
  blocked     its channel or app was turned off while it showed
  other       anything else (app updated, phone restarted...)
  NULL        still in the shade
"""

from __future__ import annotations

import json
import re
import sqlite3
import statistics
import time
from collections import Counter, defaultdict
from pathlib import Path

HOME = Path.home() / ".shade"
DB = HOME / "shade.db"
TOKEN_FILE = HOME / "app-token"

# Never suggest quieting these, and the phone refuses rules for them too (glucose alarms, pump, calls, alarms).
SAFETY_PREFIXES = ("com.camdiab.", "com.dexcom.", "studio.alans.fxwatcher", "com.ypsomed.",
                   "com.samsung.android.dialer", "com.google.android.dialer", "com.android.phone",
                   "com.sec.android.app.clockpackage", "com.google.android.deskclock",
                   "com.samsung.android.incallui", "com.android.server.telecom")

SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
  device TEXT NOT NULL, eid INTEGER NOT NULL,
  pkg TEXT NOT NULL, app TEXT, channel_id TEXT, channel_name TEXT, importance INTEGER,
  category TEXT, title TEXT, text TEXT,
  posted_at INTEGER NOT NULL, updates INTEGER DEFAULT 0, removed_at INTEGER, outcome TEXT,
  PRIMARY KEY (device, eid));
CREATE INDEX IF NOT EXISTS events_posted ON events(posted_at);
CREATE TABLE IF NOT EXISTS suggestions (
  id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,
  kind TEXT NOT NULL,           -- phone_rule | channel_settings | own_system
  title TEXT NOT NULL, why TEXT, pkg TEXT, app TEXT, channel_id TEXT, channel_name TEXT,
  rule TEXT,                    -- phone_rule: JSON {pkg, channel_id, title_regex, action, until, from}
  change TEXT,                  -- channel_settings / own_system: what to change, in plain words
  project TEXT,                 -- own_system: the C:\\projects folder it lives in, if any
  status TEXT NOT NULL DEFAULT 'pending',  -- pending | approved | held | done | failed
  decided_at INTEGER, result TEXT);
CREATE TABLE IF NOT EXISTS devices (
  device TEXT PRIMARY KEY, rules TEXT, app_version TEXT, seen_at INTEGER);
"""


def db() -> sqlite3.Connection:
    HOME.mkdir(parents=True, exist_ok=True)
    c = sqlite3.connect(DB, timeout=10)
    c.row_factory = sqlite3.Row
    c.executescript(SCHEMA)
    return c


def safety(pkg: str | None) -> bool:
    return bool(pkg) and pkg.startswith(SAFETY_PREFIXES)


def save_events(device: str, events: list[dict], rules: list | None, version: str | None) -> int:
    with db() as c:
        n = 0
        for e in events:
            try:
                eid, pkg, posted = int(e["eid"]), str(e["pkg"])[:120], int(e["posted_at"])
            except (KeyError, TypeError, ValueError):
                continue
            c.execute("""INSERT INTO events (device, eid, pkg, app, channel_id, channel_name, importance, category,
                           title, text, posted_at, updates, removed_at, outcome)
                         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                         ON CONFLICT(device, eid) DO UPDATE SET updates=excluded.updates,
                           removed_at=excluded.removed_at, outcome=excluded.outcome, title=excluded.title,
                           text=excluded.text""",
                      (device, eid, pkg, s(e.get("app"), 80), s(e.get("channel_id"), 120),
                       s(e.get("channel_name"), 120), e.get("importance"), s(e.get("category"), 40),
                       s(e.get("title"), 120), s(e.get("text"), 200), posted, int(e.get("updates") or 0),
                       e.get("removed_at"), s(e.get("outcome"), 20)))
            n += 1
        c.execute("""INSERT INTO devices (device, rules, app_version, seen_at) VALUES (?,?,?,?)
                     ON CONFLICT(device) DO UPDATE SET rules=excluded.rules, app_version=excluded.app_version,
                     seen_at=excluded.seen_at""",
                  (device, json.dumps(rules or []), s(version, 20), int(time.time() * 1000)))
    return n


def s(v, n: int) -> str | None:
    return None if v is None else str(v)[:n]


def shape(title: str | None) -> str:
    """Titles with the numbers taken out, so '3 new emails' and '5 new emails' count as one kind."""
    return re.sub(r"\d+([.,:]\d+)*", "#", (title or "").strip())[:60]


def stats(days: int = 14, now_ms: int | None = None) -> list[dict]:
    """Per app + channel over the last `days`: how many came and how each one left."""
    now_ms = now_ms or int(time.time() * 1000)
    since = now_ms - days * 86_400_000
    groups: dict[tuple, list[sqlite3.Row]] = defaultdict(list)
    with db() as c:
        for r in c.execute("SELECT * FROM events WHERE posted_at >= ?", (since,)):
            groups[(r["pkg"], r["channel_id"])].append(r)
    out = []
    for (pkg, ch), rows in groups.items():
        oc = Counter(r["outcome"] or "in_shade" for r in rows)
        dwell = [(r["removed_at"] - r["posted_at"]) / 60000 for r in rows if r["removed_at"]]
        hours = Counter(time.localtime(r["posted_at"] / 1000).tm_hour for r in rows)
        stale = sum(1 for r in rows if not r["outcome"] and now_ms - r["posted_at"] > 6 * 3_600_000)
        out.append({
            "pkg": pkg, "app": rows[-1]["app"] or pkg, "channel_id": ch, "channel_name": rows[-1]["channel_name"],
            "importance": rows[-1]["importance"], "count": len(rows), "per_day": round(len(rows) / days, 1),
            "outcomes": dict(oc), "opened_pct": round(100 * oc["opened"] / len(rows)),
            "in_shade_over_6h": stale,
            "median_minutes_shown": round(statistics.median(dwell), 1) if dwell else None,
            "night_23_07": sum(v for h, v in hours.items() if h >= 23 or h < 7),
            "titles": Counter(shape(r["title"]) for r in rows).most_common(6),
            "safety": safety(pkg),
        })
    out.sort(key=lambda g: -g["count"])
    return out


def suggestions(status: tuple[str, ...] = ("pending",)) -> list[dict]:
    q = f"SELECT * FROM suggestions WHERE status IN ({','.join('?' * len(status))}) ORDER BY id DESC"
    with db() as c:
        return [{**dict(r), "rule": json.loads(r["rule"]) if r["rule"] else None} for r in c.execute(q, status)]


def history(days: int = 90) -> list[dict]:
    since = int(time.time() * 1000) - days * 86_400_000
    with db() as c:
        return [dict(r) for r in c.execute(
            "SELECT id, created_at, kind, title, pkg, channel_id, status, result FROM suggestions "
            "WHERE created_at >= ? ORDER BY id DESC", (since,))]


def add_suggestion(sg: dict) -> int | None:
    kind = sg.get("kind")
    if kind not in ("phone_rule", "channel_settings", "own_system") or not sg.get("title"):
        return None
    if safety(sg.get("pkg")) and kind != "own_system":
        return None
    rule = sg.get("rule") if kind == "phone_rule" else None
    if kind == "phone_rule":
        if not isinstance(rule, dict) or rule.get("action") not in ("dismiss", "snooze") or not rule.get("pkg"):
            return None
        if safety(rule["pkg"]):
            return None
    with db() as c:
        cur = c.execute("""INSERT INTO suggestions (created_at, kind, title, why, pkg, app, channel_id, channel_name,
                             rule, change, project) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                        (int(time.time() * 1000), kind, s(sg["title"], 140), s(sg.get("why"), 600),
                         s(sg.get("pkg"), 120), s(sg.get("app"), 80), s(sg.get("channel_id"), 120),
                         s(sg.get("channel_name"), 120), json.dumps(rule) if rule else None,
                         s(sg.get("change"), 1500), s(sg.get("project"), 60)))
        return cur.lastrowid


def get(sid: int) -> dict | None:
    with db() as c:
        r = c.execute("SELECT * FROM suggestions WHERE id = ?", (sid,)).fetchone()
    return {**dict(r), "rule": json.loads(r["rule"]) if r["rule"] else None} if r else None


def set_status(sid: int, status: str, result: str | None = None) -> None:
    with db() as c:
        c.execute("UPDATE suggestions SET status = ?, decided_at = ?, result = COALESCE(?, result) WHERE id = ?",
                  (status, int(time.time() * 1000), result, sid))


def device_rules() -> list:
    with db() as c:
        rows = c.execute("SELECT rules FROM devices ORDER BY seen_at DESC").fetchall()
    return json.loads(rows[0]["rules"]) if rows and rows[0]["rules"] else []
