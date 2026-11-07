# Shade

Watches the notifications on Alan's phone (Samsung Galaxy S23 Ultra), records how each one leaves the
shade, and once a week has Claude suggest changes. Nothing changes without an Approve.

## How it works

1. **Android app** (`app/`, `studio.alans.shade`, Kotlin/Compose). A `NotificationListenerService`
   (no root) logs every notification: app, channel, importance, title (text only for Alan's own
   systems: Home Assistant companion, `studio.alans.*`, `com.ahaverty.*`). Android tells a listener why
   each one was removed, which becomes the outcome:

   | Android reason | outcome |
   |---|---|
   | REASON_CLICK | opened |
   | REASON_CANCEL, REASON_GROUP_SUMMARY_CANCELED | swiped |
   | REASON_CANCEL_ALL | cleared (Clear all) |
   | REASON_APP_CANCEL(_ALL) | app_removed (handled in the app / elsewhere) |
   | REASON_TIMEOUT / REASON_SNOOZED | timeout / snoozed |
   | REASON_LISTENER_CANCEL | ruled (Shade's own rule) |
   | REASON_CHANNEL_BANNED / PACKAGE_BANNED | blocked |
   | still showing | NULL (in the shade; "ignored" after 6 h) |

   Ongoing, foreground-service and group-summary notifications are skipped; a repost of one still
   showing counts as an update.
2. **Sync**: every 30 min (and on open) the app posts changed events and Alan's decisions to the server
   on the LAN, `http://192.168.0.100:8795/api/sync`, bearer token `~/.shade/app-token`. Away from home
   without WireGuard it just retries later; events queue on the phone.
3. **Review**: `server/review.py` (scheduler job `shade-review`, Sundays 10:00, or "Review now" in the
   app) gives `claude -p` two weeks of per-channel stats and asks for up to 5 suggestions:
   - `channel_settings`: Approve opens that app's channel settings page on the phone (Alan flips it).
   - `phone_rule`: Shade dismisses, or snoozes during a time window, matching notifications as they
     arrive (`Rules.kt`). Removable in the app.
   - `own_system`: a change at the source (HA automation, Friday app, his own apps). Approve starts a
     visible Claude Code session through the Ask Claude launcher with the change text; it records the
     result with `server.py --result <id> done|failed "note"`.
4. **Approval**: each suggestion arrives as a phone notification with **Approve** / **Hold** (and in the
   app). Hold = not suggested again for 30 days unless it gets worse.

Safety: glucose/pump/alarm/call apps (CamAPS, Dexcom, FX Watcher, Ypsomed, dialer, clock) are never
suggested and the phone refuses rules for them (`Rules.SAFETY`, `store.SAFETY_PREFIXES`).

## Running

- Server: always-on service `shade` (`C:\Python314\pythonw.exe server\server.py`), data in `~/.shade/`
  (shade.db, server.log, last-review.txt). LAN page: http://192.168.0.100:8795/
- Build: `JAVA_HOME=<JDK 21> ./gradlew assembleRelease -Pkotlin.compiler.execution.strategy=in-process`
  (the Kotlin daemon dies when the PC is short of RAM). Signing key `C:\projects\shade-signing`
  (`keystore.properties`, git-ignored; losing it means uninstall + reinstall). `secrets.properties`
  bakes in the server URL and token.
