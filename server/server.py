"""Shade server: the phone app syncs notification events here, and picks up Claude's suggestions.

Listens on the LAN (192.168.0.100:8795); the phone syncs when it is home or on WireGuard. Always-on
service 'shade' (C:\\projects\\always-on\\services.json). stdlib only.

  POST /api/sync     {device, version, rules, events: [...], decisions: [{id, decision}]}
                     -> {saved, suggestions: [pending...], done: [ids the phone can forget]}
  POST /api/review   start a review now (same as the weekly scheduler job)
  GET  /api/stats    ?days=14
  GET  /             LAN page: stats and suggestions

The app sends a bearer token (~/.shade/app-token). The page is LAN/WireGuard only.

Approving an own_system suggestion starts a visible Claude Code session through the Ask Claude
launcher with the suggestion's change text, so Alan can follow it from the Claude app.
"""

from __future__ import annotations

import hmac
import html
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
import review  # noqa: E402
import store  # noqa: E402

LISTEN = ("192.168.0.100", 8795)
LAUNCHER = "http://127.0.0.1:8932/claude/api/launch"
LAUNCHER_KEY = Path(r"C:\projects\claude-launcher\key.txt")
LOGFILE = store.HOME / "server.log"
review_state = {"state": "idle", "at": None, "result": None}
review_lock = threading.Lock()


def log(msg: str) -> None:
    line = f"{datetime.now():%Y-%m-%d %H:%M:%S} {msg}"
    store.HOME.mkdir(parents=True, exist_ok=True)
    with LOGFILE.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def lan(ip: str) -> bool:
    return ip in ("127.0.0.1", "192.168.0.100") or ip.startswith(("192.168.0.", "192.168.99."))


def token() -> str | None:
    try:
        return store.TOKEN_FILE.read_text(encoding="utf-8").strip() or None
    except OSError:
        return None


def launch_session(sg: dict) -> str:
    """Start a Claude Code session that makes an approved own_system change."""
    prompt = (
        "Alan approved this notification change in his Shade app (it watches which phone notifications he "
        "opens, swipes or ignores, and Claude suggested this in the weekly review).\n\n"
        f"Suggestion #{sg['id']}: {sg['title']}\nWhy: {sg.get('why') or ''}\n"
        f"Source: {sg.get('app') or ''} ({sg.get('pkg') or ''}), channel {sg.get('channel_name') or sg.get('channel_id') or '-'}\n\n"
        f"Change to make:\n{sg.get('change') or ''}\n\n"
        "Make exactly this change at the source (Home Assistant automation/script, or the project's code), "
        "following the usual rules (worktree for repos, deploy as the project normally does). Don't widen it. "
        "If it turns out to be wrong or risky, don't do it, say why. When finished, record the outcome with: "
        f"python C:\\projects\\shade\\server\\server.py --result {sg['id']} done|failed \"one line\"")
    body = json.dumps({"prompt": prompt, "project": sg.get("project") or None,
                       "title": f"Shade: {sg['title']}"[:80], "via": "shade"}).encode()
    req = urllib.request.Request(LAUNCHER, body, {"Content-Type": "application/json",
                                                  "X-Claude-Key": LAUNCHER_KEY.read_text(encoding="utf-8").strip()})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            j = json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as ex:
        if sg.get("project"):  # unknown project name: run it from C:\projects instead
            sg = {**sg, "project": None}
            return launch_session(sg)
        raise RuntimeError(f"launcher said {ex.code}: {ex.read()[:200]!r}") from ex
    return f"Claude Code session started: {j.get('title') or j.get('name') or 'Shade'}"


def decide(sid: int, decision: str) -> str:
    sg = store.get(sid)
    if not sg or sg["status"] not in ("pending", "held"):
        return "gone"
    if decision == "hold":
        store.set_status(sid, "held")
        return "held"
    if decision != "approve":
        return "bad decision"
    if sg["kind"] == "own_system":
        store.set_status(sid, "approved")
        try:
            res = launch_session(sg)
            store.set_status(sid, "approved", res)
            log(f"#{sid} approved, {res}")
        except Exception as ex:  # noqa: BLE001
            store.set_status(sid, "failed", f"couldn't start the session: {ex}")
            log(f"#{sid} launch FAILED {ex}")
        return "approved"
    # phone_rule: the phone already runs it. channel_settings: the phone opened the settings page.
    store.set_status(sid, "done", "applied on the phone" if sg["kind"] == "phone_rule" else "settings opened")
    return "done"


def run_review() -> None:
    if not review_lock.acquire(blocking=False):
        return
    try:
        review_state.update(state="working", at=time.time(), result=None)
        r = review.review()
        review_state.update(state="done", result=r)
        log(f"review: {r}")
    except Exception as ex:  # noqa: BLE001
        review_state.update(state="failed", result={"error": str(ex)[:300]})
        log(f"review FAILED {ex}")
    finally:
        review_lock.release()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):  # quiet
        pass

    def send(self, code: int, body: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, obj, code: int = 200) -> None:
        self.send(code, json.dumps(obj, ensure_ascii=False).encode(), "application/json")

    def app_ok(self) -> bool:
        tok, given = token(), self.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        if tok and given and hmac.compare_digest(tok, given) and lan(self.client_address[0]):
            return True
        log(f"refused {self.client_address[0]} {self.command} {self.path}")
        self.send(403, b"forbidden", "text/plain")
        return False

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/internal/healthz":
            return self.send(200, b"ok", "text/plain")
        if not lan(self.client_address[0]):
            return self.send(403, b"forbidden", "text/plain")
        if u.path == "/api/stats":
            days = int((parse_qs(u.query).get("days") or ["14"])[0])
            return self.send_json({"stats": store.stats(max(1, min(days, 90)))})
        if u.path == "/api/review":
            return self.send_json(review_state)
        if u.path in ("", "/"):
            return self.send(200, page().encode(), "text/html; charset=utf-8")
        self.send(404, b"not found", "text/plain")

    def do_POST(self):
        u = urlparse(self.path)
        if not self.app_ok():
            return
        try:
            body = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length", 0)), 5_000_000)) or b"{}")
        except ValueError:
            return self.send_json({"error": "bad json"}, 400)
        try:
            if u.path == "/api/sync":
                device = str(body.get("device") or "phone")[:40]
                saved = store.save_events(device, body.get("events") or [], body.get("rules"), body.get("version"))
                done = []
                for d in body.get("decisions") or []:
                    try:
                        sid = int(d["id"])
                    except (KeyError, TypeError, ValueError):
                        continue
                    decide(sid, str(d.get("decision")))
                    done.append(sid)
                return self.send_json({"saved": saved, "decided": done,
                                       "suggestions": store.suggestions(("pending",)),
                                       "review": review_state["state"]})
            if u.path == "/api/review":
                threading.Thread(target=run_review, daemon=True).start()
                return self.send_json({"started": True})
        except Exception as ex:  # noqa: BLE001
            log(f"{u.path} FAILED {ex}")
            return self.send_json({"error": str(ex)[:300]}, 500)
        self.send(404, b"not found", "text/plain")


def page() -> str:
    e = html.escape
    rows = "".join(
        f"<tr><td><b>{e(g['app'])}</b><br><span class=m>{e(g['channel_name'] or g['channel_id'] or '')}</span></td>"
        f"<td>{g['count']}</td><td>{g['opened_pct']}%</td>"
        f"<td>{g['outcomes'].get('swiped', 0) + g['outcomes'].get('cleared', 0)}</td>"
        f"<td>{g['in_shade_over_6h']}</td></tr>" for g in store.stats(14)[:40])
    sugs = "".join(f"<li><b>{e(s['title'])}</b> <span class=m>{e(s['kind'])}, {e(s['status'])}</span><br>"
                   f"{e(s.get('why') or '')}</li>" for s in store.suggestions(("pending", "held", "approved")))
    hist = "".join(f"<li>{e(h['title'])} <span class=m>{e(h['status'])}{': ' + e(h['result']) if h['result'] else ''}</span></li>"
                   for h in store.history(90) if h["status"] in ("done", "failed"))
    return f"""<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1"><title>Shade</title>
<style>
:root{{--bg:#fff;--fg:#1b1b1f;--m:#666;--line:#e3e3e8;--acc:#4a5bd4}}
@media (prefers-color-scheme:dark){{:root{{--bg:#121216;--fg:#ececf1;--m:#9a9aa5;--line:#2a2a33;--acc:#8f9cff}}}}
body{{background:var(--bg);color:var(--fg);font:15px/1.4 system-ui,sans-serif;margin:0;padding:16px;max-width:760px}}
h1{{font-size:22px;margin:0 0 4px}} h2{{font-size:17px;margin:22px 0 8px}} .m{{color:var(--m);font-size:13px}}
table{{width:100%;border-collapse:collapse}} td,th{{padding:6px 4px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}}
th{{font-size:12px;color:var(--m);font-weight:600}} td:not(:first-child),th:not(:first-child){{text-align:right;white-space:nowrap}}
li{{margin-bottom:8px}}
</style></head><body>
<h1>Shade</h1><div class=m>Phone notifications, last 14 days. Approve or hold suggestions in the Shade app.</div>
<h2>Waiting for you</h2><ul>{sugs or '<li class=m>Nothing right now.</li>'}</ul>
<h2>By app</h2><table><tr><th>App / channel</th><th>Came</th><th>Opened</th><th>Swiped</th><th>Ignored 6h+</th></tr>{rows}</table>
<h2>Done</h2><ul>{hist or '<li class=m>Nothing yet.</li>'}</ul>
</body></html>"""


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "--result":
        store.set_status(int(sys.argv[2]), "done" if sys.argv[3] == "done" else "failed",
                         " ".join(sys.argv[4:]) or None)
        print("recorded")
        sys.exit(0)
    log(f"shade server on {LISTEN[0]}:{LISTEN[1]}")
    ThreadingHTTPServer(LISTEN, Handler).serve_forever()
