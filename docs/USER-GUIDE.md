# User guide

Everything Flick does, and every setting, on each device. New to Flick? Install it
first: [INSTALL.md](INSTALL.md).

- [The basics](#the-basics)
- [Send from the phone to the computer](#send-from-the-phone-to-the-computer)
- [Connect your phone](#connect-your-phone)
- [Send from the computer to the phone](#send-from-the-computer-to-the-phone)
- [Shared clipboard](#shared-clipboard)
- [The phone app](#the-phone-app)
- [On Windows](#on-windows)
- [On a Mac](#on-a-mac)
- [Where files go](#where-files-go)

---

## The basics

- The phone and the computer must be on the **same Wi-Fi** (or the computer joined to
  the phone's hotspot).
- The computer app is always on in the background once you've opened it once. You
  never need to open it to receive.
- **Sending from the phone** works with any computer running Flick: the computer asks
  before accepting anything.
- **Sending from the computer, and the shared clipboard,** work with phones you've
  **connected** (paired) to that computer: once, with one tap on each side.

## Send from the phone to the computer

1. In any app, select photos, videos, files, a link or some text and tap **Share**.
2. Choose **Flick**. A sheet lists the computers on your Wi-Fi.
3. Tap your computer. On the computer a popup appears:
   *"Pixel 9 wants to send you 3 photos"*, with a thumbnail and the file names.
4. Click **Accept** (or **Decline**). The phone shows progress, then *Sent ✓*.

Good to know:

- **Skip the popup next time:** click **Always Accept** (Mac) or tick **Always accept
  from …** (Windows). That phone's files then go straight through. This also connects
  the phone (see below).
- **Links** open in the computer's browser (the button says **Open**). Other **text**
  goes on the computer's clipboard (the button says **Copy**).
- **Big files** keep sending if you leave the share sheet: progress shows in a
  notification on the phone and, on Windows, in a progress window with **Cancel**.
- **One-tap repeat:** after the first send, your computer appears as its own target in
  the top row of Android's share menu.
- By default the folder with the received files opens when they arrive. Turn this off
  with **Show files when they arrive** in the computer's settings.

## Connect your phone

Connecting lets the computer send files to the phone and share its clipboard.

1. Open **Flick** on the phone. Your computer is listed under **Your computers**.
2. Tap **Connect** next to it.
3. On the computer, click **Connect** in the popup.

The computer now shows **Connected** on the phone. From then on the phone reconnects by
itself whenever both are on the same Wi-Fi, also after a restart.

To undo it: tap the computer in the phone app → **Disconnect**, or on the computer use
**Forget connected phones**.

## Send from the computer to the phone

The phone must be connected (above) and on the same Wi-Fi.

**Windows**, any of:
- Right-click files or a folder → **Send to → Phone (Flick)**.
- Right-click → **Send with Flick** (on Windows 11 it's under **Show more options**).
- Tray icon → **Send files to phone…**

**Mac**, either of:
- Right-click files in Finder → **Quick Actions → Send with Flick**.
- Open Flick → **Send files to a phone…**

If more than one phone is connected, pick one. The computer shows progress (a progress
window on Windows, notifications on a Mac).

On the phone, files are saved automatically (**Accept files automatically** is on by
default). If you turned that off, a notification asks **Accept** or **Decline**.

## Shared clipboard

**Computer → phone.** Copy text or a link on the computer. A moment later it's on the
phone's clipboard, ready to paste. Nothing to tap.

**Phone → computer.** Android only lets the keyboard read the clipboard in the
background, so Flick offers two ways:

1. **Automatic** (one-time setup). In Flick, turn on **Send what I copy on this phone**
   and follow the steps:
   1. Tap **Open settings** → **Flick — send what I copy** (it may be under *Installed
      apps* or *Downloaded apps*) → turn it on → **Allow**.
   2. If it's greyed out ("Restricted setting"): tap **App info** → **⋮** (top right) →
      **Allow restricted settings**, then do step 1 again.

   From then on, anything you copy is on the computer's clipboard a moment later.
   Android shows *"Flick pasted from your clipboard"* each time: that's Flick sending
   it. The helper only notices the moment you tap Copy (or see a "Copied" message); it
   doesn't read, keep or send anything else on screen.
2. **Open Flick.** Whatever you last copied is sent. (Optional: add the **Send
   clipboard** tile to Quick Settings for the same thing in one tap.)

Rules for both directions:

- Text and links only, up to 100 KB.
- **Passwords are never shared.** Text marked private by password managers is skipped.
- Text you copy on one connected phone also reaches your other connected phones.
- Turn it off on the phone (the two clipboard switches) or on the computer (**Share
  clipboard with phones**).

## The phone app

| Part of the screen | What it does |
|---|---|
| **This phone appears as** | The name computers show. Tap to rename. |
| **Your computers** | Computers on this Wi-Fi and ones you've used before, with their status: *Connected*, on this Wi-Fi, or away. **Connect** pairs one. |
| Tap a computer | **Send photos & videos**, **Send files**, **Connect**, **Disconnect**, **Forget this computer**. |
| **Add a computer by IP address** | For Wi-Fi that hides devices from each other. Find the computer's address in its Wi-Fi settings (Mac: **System Settings → Wi-Fi → Details**; Windows: **Settings → Network & internet → Wi-Fi → your network → IPv4 address**). |
| **Let Flick stay connected** card | Appears if Android may pause Flick to save battery. Tap **Allow**. |
| **Stay connected** | Keeps the link to your connected computers open in the background. Off = the computer can't send to the phone. |
| **Hide the "Connected" notification** | Android requires a notification while Flick stays connected. It's silent and has no status-bar icon; this switches it off completely. Flick stays connected either way. |
| **Get what I copy on my computer** | Computer → phone clipboard. |
| **Send what I copy on this phone** | Phone → computer clipboard (see above). |
| **Accept files automatically** | Files from connected computers are saved without asking. |

If Flick ever stops unexpectedly, the next time you open it you'll see **Flick stopped
unexpectedly** with the details. Tap **Copy details** and paste them into your bug
report.

## On Windows

Right-click the Flick icon in the system tray (next to the clock; it may be under the
**^** arrow):

| Menu item | What it does |
|---|---|
| *Flick is on — visible as "…"* | Status: the name phones see. |
| *No phone connected* / *Connected: …* | Status: phones connected right now. |
| **Send files to phone…** | Pick files to send to a connected phone. |
| **Open received files** | Opens the save folder. |
| **Change save folder…** | Choose where files from the phone go. |
| **Show files when they arrive** | Open the folder when a transfer finishes. |
| **Share clipboard with phones** | Shared clipboard on/off for this PC. |
| **Forget connected phones (n)** | Disconnects all phones; they must connect again. |
| **Start with Windows** | Start Flick when you sign in. |
| **Quit Flick** | Stops Flick until the next restart (or until you open it). |

## On a Mac

Open **Flick** (from Applications, Spotlight or Launchpad) while it's running to see
its status and settings:

| Item | What it does |
|---|---|
| (status line) | The name phones see, connected phones, and the save folder. |
| **Send files to a phone…** | Pick files to send to a connected phone. |
| **Open received files** | Opens the save folder in Finder. |
| **Change where files are saved…** | Choose where files from the phone go. |
| **Show files when they arrive: On/Off** | Show the files in Finder when a transfer finishes. |
| **Share clipboard with phones: On/Off** | Shared clipboard on/off for this Mac. |
| **Forget connected phones (n)** | Disconnects all phones; they must connect again. |
| **Turn off Flick** | Stops Flick and keeps it off until you open it again. |

## Where files go

| Direction | Where |
|---|---|
| Phone → Mac / PC | Your **Downloads** folder (change it in the settings above). Existing files are never overwritten: a second `photo.jpg` becomes `photo (1).jpg`. |
| Computer → phone, photos | **Pictures/Flick** (shows in your gallery) |
| Computer → phone, videos | **Movies/Flick** (shows in your gallery) |
| Computer → phone, everything else | **Download/Flick** |
