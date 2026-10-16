# Architecture

How the two apps are built. For the exact network messages see
[PROTOCOL.md](PROTOCOL.md).

- [Overview](#overview)
- [Computer app (desktop/)](#computer-app-desktop)
- [Phone app (android/)](#phone-app-android)
- [Clipboard, both ways](#clipboard-both-ways)
- [Testing](#testing)

---

## Overview

```
┌──────────────────────── Android phone ────────────────────────┐        ┌──────────── Mac / Windows ────────────┐
│ ShareActivity ── TransferService (foreground, data sync) ─────┼─HTTPS─▶│ Flick agent (Go, always on)           │
│   share sheet      uploads files, shows progress              │        │  ├ server.go   /v1/ask, /v1/upload …  │
│                                                               │        │  ├ discovery.go  UDP 47474 responder  │
│ Discovery ── UDP probe 47474 + Bonjour (NSD) ─────────────────┼─UDP───▶│  ├ link.go     live links, offers     │
│                                                               │        │  ├ clipboard watcher (Mac / Windows)  │
│ LinkService (foreground, connected device) ◀══ /v1/link ══════┼═HTTPS══│  └ platform UI: dialogs, tray, notif. │
│   clipboard from computer, file offers → Saver (MediaStore)   │        │                                       │
│ ClipWatchService (Accessibility, optional) ── /v1/clip ───────┼─HTTPS─▶│  settings, certificate, log on disk   │
└───────────────────────────────────────────────────────────────┘        └───────────────────────────────────────┘
```

The phone always opens the connections. The computer only answers, and talks back to
connected phones through the long-lived `/v1/link` request each phone keeps open. That
way the phone never needs to accept incoming connections, and no extra firewall rules
are needed on the phone's side.

## Computer app (desktop/)

One Go program for macOS and Windows (`CGO_ENABLED=0`, no browser engine). The same
binary does different jobs depending on how it's started (`main.go`):

| Started as | Job |
|---|---|
| `Flick` (double-click) | **Launcher.** First time: installs itself and starts the agent. Later: asks the running agent to show its status and settings. |
| `Flick --agent` | **Agent.** The always-on background receiver. Started at login. |
| `Flick --send <files>` | Right-click *Send with Flick* / *Send to → Phone (Flick)*: hands the files to the agent, which asks which phone. |
| `Flick --uninstall` | Removes Flick (Windows uninstaller, or by hand on a Mac). |
| `Flick --test` | Scripted receiver used by the automated tests. |

### The agent

- **Small:** the garbage collector runs early (`GCPercent 50`) with a soft 40 MB cap.
  Measured: about 7.5 MB idle, about 11 MB with a phone connected, no CPU while idle.
- **One at a time:** a lock file stops a second agent; `agent.json` holds its port and
  a random token so the launcher can talk to it (`/v1/local/*`).
- **Server (`server.go`):** HTTPS on the first free port from 47475 to 47480, HTTP/1.1.
  `/v1/ask` holds the request open while the accept popup is on screen; `/v1/upload`
  streams straight to a temporary file that's renamed into place when complete.
- **Discovery (`discovery.go`):** answers UDP probes on 47474 with name, port and
  certificate fingerprint. Macs also advertise `_flick._tcp` with `dns-sd`.
- **Live link (`link.go`):** each connected phone keeps `GET /v1/link` open; the agent
  writes newline-delimited JSON events to it (`hello`, `ping` every 25 s, `clip`,
  `offer`, `cancel`). File offers are downloaded by the phone with
  `/v1/offer/file`; several right-click launches within 0.7 s are batched into one
  offer.

### Mac (`platform_darwin.go`, `clip_darwin.go`, `quickaction_darwin.go`)

- **Install:** copies `Flick.app` into `/Applications` (or `~/Applications`) and
  registers a per-user **LaunchAgent** `in.piqit.flick.agent` (`RunAtLoad`, restarted
  automatically if it ever crashes).
- **UI:** native dialogs and notifications through AppleScript (`osascript`), with all
  text passed as arguments, never pasted into the script. No Dock or menu-bar icon
  (`LSUIElement`).
- **Clipboard watcher:** reads `NSPasteboard.changeCount` through the Objective-C
  runtime (purego, no cgo) every half second, which costs almost nothing.
- **Finder Quick Action** *Send with Flick*: an Automator workflow in
  `~/Library/Services` that runs `Flick --send`.
- Built as one **universal** binary (Apple Silicon + Intel), ad-hoc signed.

### Windows (`platform_windows.go`, `win32_windows.go`)

- **Install:** copies itself to `%LOCALAPPDATA%\Programs\Flick`, adds a start-up
  entry (`HKCU\…\Run`), an Apps & features uninstall entry, a Start menu shortcut,
  *Send to → Phone (Flick)* and *Send with Flick* right-click menus, and (with one
  administrator prompt) the **Flick** firewall rule.
- **UI:** pure Win32 through system calls: a tray icon with menu, Task Dialogs for
  accept/connect/progress, the standard file and folder pickers. Common Controls v6
  and per-monitor DPI awareness come from the embedded manifest (`winres/`).
- **Clipboard watcher:** `AddClipboardFormatListener`, so Windows tells Flick when the
  clipboard changes (no polling).

## Phone app (android/)

Two Gradle modules:

- **`core/`** (pure Kotlin, runs on the JVM): protocol types, UDP discovery, the HTTPS
  client with certificate pinning (OkHttp), and the transfer engine. Tested against the
  real desktop app.
- **`app/`**: everything Android.

| Component | Job |
|---|---|
| `ShareActivity` | The **Flick** entry in the share sheet: lists computers, sends. |
| `Shortcuts` | Direct Share targets, so recent computers appear in the share sheet's top row. |
| `TransferService` | Foreground service (*data sync*) that uploads files and shows progress, so big transfers survive leaving the share sheet. |
| `Discovery` | UDP probes on every network interface plus Bonjour (NSD); merges results by computer ID. |
| `LinkService` | Foreground service (*connected device*) holding `/v1/link` to each connected computer. Reconnects with back-off, at once on network changes, and re-discovers a computer whose address changed. Receives clipboard text and file offers. |
| `Saver` | Saves received files with MediaStore: Pictures/Flick, Movies/Flick, Download/Flick. |
| `BootReceiver` | Restarts `LinkService` after a reboot or an app update. |
| `ClipShare`, `ClipReadActivity` | Phone → computer clipboard: read the clipboard while Flick has focus, send to `/v1/clip`. |
| `ClipWatchService` | Optional Accessibility service for automatic copy (below). |
| `ClipTileService` | Optional *Send clipboard* Quick Settings tile. |
| `Store` | Small settings store (SharedPreferences): phone ID and name, known computers, trust keys, switches. |
| `CrashLog` | Records why Flick last stopped unexpectedly, shown next time Flick opens. |
| `MainActivity` | The app screen (Jetpack Compose). |

## Clipboard, both ways

**Computer → phone**

1. The watcher sees the clipboard change and checks it's text (≤ 100 KB) and not
   marked private by a password manager.
2. The agent pushes `{"type":"clip"}` down every connected phone's link (except the
   phone the text came from).
3. `LinkService` remembers the text (so it isn't sent back) and puts it on the phone's
   clipboard.

**Phone → computer**

Android only lets an app read the clipboard while one of its windows has focus. Flick
reads it at moments it legitimately has focus:

- **Automatic:** `ClipWatchService` watches only for the moment you copy something (a
  click on a view whose text, description or ID says *copy*, a *Copied* toast, or
  Android's clipboard preview). It checks a computer is connected, then adds an
  invisible 1×1 accessibility window that takes focus for an instant, reads the
  clipboard, and removes the window again on the next frame. If that window can't get
  focus on a phone, it falls back to the invisible `ClipReadActivity`.
- **When Flick opens:** `MainActivity` sends the clipboard when it gains focus.
- **Quick Settings tile:** opens `ClipReadActivity`.

The phone sends the text to every connected computer with `POST /v1/clip`. The computer
puts it on its clipboard silently and passes it on to its other connected phones.

## Testing

- **`scripts/e2e.sh`** builds the desktop app, starts it in `--test` mode (a scripted
  person at the computer: names containing *decline* are declined, *trust* are always
  accepted, *ignore* never answered) and runs the phone's real networking code
  (`android/core`) against it: **19 tests** covering discovery and pinning,
  accept/decline/timeouts, *Always accept*, unsafe file names, wrong sizes, duplicate
  names, cancelling, pairing, the live link, clipboard both ways, and computer → phone
  files.
- **`desktop/names_test.go`**: Go unit tests for file-name sanitising.
- CI runs both on every release tag; see [BUILDING.md](BUILDING.md).
