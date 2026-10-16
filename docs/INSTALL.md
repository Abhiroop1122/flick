# Install

Flick has two halves: the **computer app** (Mac or Windows), which runs quietly in the
background, and the **phone app** (Android). Install both, and keep them on the same
Wi-Fi.

- [Mac](#mac)
- [Windows](#windows)
- [Android](#android)
- [Updating](#updating)
- [Turning Flick off](#turning-flick-off)
- [Removing Flick](#removing-flick)

---

## Mac

**Needs:** macOS 12 Monterey or newer. One app for Apple Silicon and Intel.

1. Unzip `Flick-mac.zip` and double-click **Flick**.
2. If macOS says *"Apple could not verify Flick"* (it isn't notarized by Apple): click
   **Done**, open **System Settings → Privacy & Security**, scroll down and click
   **Open Anyway** next to Flick. You only do this once.
3. Flick copies itself into **Applications**, turns on and shows *"Flick is on"*.
4. If macOS asks to let Flick *find devices on your local network*, click **Allow**.
   Without it the phone can't find the Mac.

That's all. Flick now starts by itself every time you log in. You'll see it under
**System Settings → General → Login Items → Allow in the Background**. It has no Dock
or menu-bar icon; to see its status or change settings, open Flick again
(see [User guide → On a Mac](USER-GUIDE.md#on-a-mac)).

What gets installed:

| What | Where |
|---|---|
| The app | `/Applications/Flick.app` (or `~/Applications` if you can't write to Applications) |
| Start at login | `~/Library/LaunchAgents/in.piqit.flick.agent.plist` |
| Finder right-click action | `~/Library/Services/Send with Flick.workflow` |
| Settings, certificate, log | `~/Library/Application Support/Flick/` |
| Received files | `~/Downloads` (changeable) |

## Windows

**Needs:** Windows 10 or 11, 64-bit.

1. Double-click **Flick.exe**. If SmartScreen says *"Windows protected your PC"*, click
   **More info → Run anyway** (the app isn't from the Microsoft Store).
2. Approve the one administrator prompt. It adds a Windows Firewall rule named
   **Flick** so your phone can reach the PC on any Wi-Fi.
3. You'll see *"Flick is on"*.

Flick installs itself for your user only, starts with Windows, and sits in the system
tray next to the clock (it may be hidden under the **^** arrow). Right-click the icon
for settings (see [User guide → On Windows](USER-GUIDE.md#on-windows)).

What gets installed:

| What | Where |
|---|---|
| The app | `%LOCALAPPDATA%\Programs\Flick\Flick.exe` |
| Start with Windows | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` → Flick |
| Right-click menus | *Send to → Phone (Flick)* and *Send with Flick* |
| Start menu shortcut | `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Flick.lnk` |
| Firewall rule | Windows Defender Firewall → inbound rule **Flick** |
| Settings, certificate, log | `%APPDATA%\Flick\` |
| Received files | your Downloads folder (changeable) |

## Android

**Needs:** Android 8.0 or newer.

1. Copy `Flick-2.2.2.apk` to the phone: send it to yourself on WhatsApp, upload it to
   Google Drive, or plug in a cable. Tap it to install.
2. If Android asks, allow **Install unknown apps** for the app you opened it from.
3. **If you see "App blocked to protect your device":** this is Google Play Protect.
   It blocks apps installed from a file if they include an Accessibility service, and
   Flick has one for its optional automatic-copy feature. It happens in some
   countries, India included. To install:
   1. Open the **Play Store** → tap your profile picture → **Play Protect** → ⚙ (top right).
   2. Turn off **Scan apps with Play Protect**.
   3. Install `Flick-2.2.2.apk`.
   4. Go back and turn **Scan apps with Play Protect** on again.

   You'll need this each time you install a newer Flick from a file.
4. Open Flick and allow **notifications** (they show transfers and files from your
   computer).
5. When you connect a computer, Flick may ask to **run in the background**. Allow it,
   or Android may pause the connection to save battery.

Optional, for automatic copy: in Flick turn on **Send what I copy on this phone** and
follow the steps (see [User guide → Shared clipboard](USER-GUIDE.md#shared-clipboard)).

## Updating

- **Mac / Windows:** open the new Flick once, the same way as installing. It replaces
  the running version and keeps your settings and connected phones.
- **Android:** install the new APK over the old one. Your computers, connections and
  settings stay. Android only accepts the update if it's signed with the same key as
  the installed app; every Flick release is. Play Protect may need the step above again.

## Turning Flick off

- **Mac:** open Flick → **Turn off Flick**. It stays off (also after restarts) until you
  open Flick again.
- **Windows:** tray icon → **Quit Flick** stops it until the next restart. Untick
  **Start with Windows** in the same menu to keep it off.
- **Android:** in Flick turn off **Stay connected**. Sharing to a computer from the
  share menu still works; the computer just can't reach the phone.

## Removing Flick

- **Windows:** **Settings → Apps → Installed apps → Flick → Uninstall**. This removes
  the start-up entry, right-click menus, Start menu shortcut and firewall rule.
- **Mac:**
  1. Open **Terminal** and run
     `/Applications/Flick.app/Contents/MacOS/Flick --uninstall`
     (stops Flick and removes the start-at-login entry and the Finder action).
  2. Drag **Flick** from Applications to the Bin.
  3. Optional: delete `~/Library/Application Support/Flick` to remove its settings.
- **Android:** long-press Flick → **Uninstall**, like any app.
