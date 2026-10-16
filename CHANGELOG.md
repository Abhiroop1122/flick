# Changelog

The computer app (Mac + Windows) and the phone app (Android) have their own version
numbers. Each release below lists both.

## Android 2.2.2 (2026-10-16)

### Fixed
- The automatic-copy helper could crash Flick right after it noticed a copy, and
  Android then showed *"This service is malfunctioning"* in Accessibility settings.
  The helper's invisible window is now removed safely, and nothing the helper does can
  stop Flick any more.
- An unexpected error while sending the clipboard in the background no longer stops
  the app.

### Added
- **Crash details:** if Flick ever stops unexpectedly, the next time it opens it shows
  what happened, with a **Copy details** button for reporting it.

## Android 2.2.1 (2026-10-16)

### Changed
- The *Send clipboard* button is gone from the *Connected to …* notification.
- New **Hide the "Connected" notification** option in the app. Flick stays connected
  with the notification switched off.
- Install guide: how to get past Google Play Protect's *"App blocked to protect your
  device"* when installing from a file.

## Flick 2.2: clipboard both ways (Desktop 2.2.0, Android 2.1.0, 2026-10-16)

### Added
- **Phone → computer clipboard.** Copy on the phone, paste on the computer:
  - automatically, with the optional **Flick — send what I copy** Accessibility helper;
  - by opening Flick;
  - with the **Send clipboard** Quick Settings tile.
- Computers put text from a phone on their clipboard silently and pass it on to their
  other connected phones.
- Password-manager text on the phone (marked sensitive) is never sent.
- Protocol: `POST /v1/clip`.

## Flick 2.1: two-way (Desktop 2.1.0, Android 2.0.0, 2026-10-16)

### Added
- **Connect (pair) a phone** with one tap on each side. Connected phones stay connected
  in the background and reconnect by themselves.
- **Computer → phone files:** *Send to → Phone (Flick)* and *Send with Flick* on
  Windows, *Quick Actions → Send with Flick* on a Mac, and *Send files to phone…* in
  the computer's menu. Photos go to the gallery, videos to Movies, the rest to
  Download/Flick.
- **Computer → phone clipboard:** copy on the computer, paste on the phone.
- Phone app settings: *Stay connected*, *Get what I copy on my computer*, *Accept files
  automatically*; battery-optimisation prompt.
- Computer settings: *Share clipboard with phones*, *Forget connected phones*.
- Protocol: pairing, the live link (`/v1/link`) and file offers.

## Flick 2.0: always on (Desktop 2.0.0, 2026-10-12)

### Changed
- The computer app was rebuilt as a tiny native background program (Go) in place of
  the earlier Electron app. Open it once: it stays on, starts by itself at login or
  with Windows, and uses about 8 MB of memory.
- Mac: starts through a LaunchAgent, with no Dock icon; native dialogs and
  notifications.
- Windows: start-up entry, tray icon with settings, native Task Dialogs, firewall rule
  added with one administrator prompt, uninstall entry in Settings → Apps.
- Settings, certificate and trusted phones carry over from 1.0.

## Flick 1.0: first release (Desktop 1.0.0, Android 1.0.0, 2026-10-12)

### Added
- **Share → Flick** on Android: pick your computer, accept on the computer, files land
  in Downloads.
- Accept popup on the computer with thumbnail and file names; **Always accept** for
  trusted phones.
- Links open in the computer's browser; text goes on its clipboard.
- Discovery over UDP and Bonjour; HTTPS with certificate pinning; *Add a computer by
  IP address*.
- Direct Share: recent computers in the top row of the share sheet.
