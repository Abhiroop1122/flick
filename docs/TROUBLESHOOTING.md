# Troubleshooting

Most problems come down to Wi-Fi (devices that can't see each other) or Android
pausing Flick in the background. Find your symptom below.

- [Installing](#installing)
- [The phone can't find the computer](#the-phone-cant-find-the-computer)
- [Connection and background](#connection-and-background)
- [Clipboard](#clipboard)
- [Transfers](#transfers)
- [Logs and crash details](#logs-and-crash-details)

---

## Installing

| Symptom | Fix |
|---|---|
| Android: **"App blocked to protect your device"** | Google Play Protect blocks apps installed from a file that include an Accessibility service (Flick's automatic copy). Play Store → profile picture → **Play Protect** → ⚙ → turn off **Scan apps with Play Protect**, install, then turn it back on. See [Install → Android](INSTALL.md#android). |
| Android: **"App not installed"** | You're installing an *older* version over a newer one. Install the newest APK, or uninstall Flick first (you'll need to connect your computers again). |
| Android: **"There was a problem parsing the package"** | The file didn't copy completely. Copy it again; compare it with `SHA256SUMS.txt` from the release if unsure. |
| Mac: **"Apple could not verify Flick"** | Click **Done**, then **System Settings → Privacy & Security → Open Anyway**. Once. |
| Windows: **"Windows protected your PC"** | Click **More info → Run anyway**. Once. |
| Mac: Flick doesn't come back after a restart | **System Settings → General → Login Items → Allow in the Background** → turn **Flick** on. Then open Flick once. |
| Windows: Flick doesn't come back after a restart | Tray icon → make sure **Start with Windows** is ticked. If the icon is missing, open Flick from the Start menu. |

## The phone can't find the computer

| Symptom | Fix |
|---|---|
| Nothing appears under **Your computers** | Both must be on the **same** Wi-Fi (not a guest network) and the computer awake. Open Flick on the computer once to make sure it says *Flick is on*. |
| Still nothing (office, hotel, mesh or "smart" routers) | Many of these stop devices from seeing each other ("client isolation"). Tap **Add a computer by IP address** in the phone app. Or turn on the phone's hotspot and join it from the computer; Flick works over that too. |
| Windows: the phone sees nothing | If you skipped the administrator prompt: **Windows Security → Firewall & network protection → Allow an app through firewall** → tick **Flick** for Private and Public. Or open Flick.exe again and approve the prompt. |
| Mac: the phone sees nothing | **System Settings → Privacy & Security → Local Network** → turn **Flick** on. |
| Phone says **"Security error"** | The computer's Flick was reinstalled or reset, so its certificate changed. On the phone tap the computer → **Forget this computer**, then connect again. |
| VPN on the phone or computer | Some VPNs hide the local network. Pause the VPN or allow local network access in its settings. |

## Connection and background

| Symptom | Fix |
|---|---|
| Computer says **"No phone is connected"** when sending | Open Flick on the phone: the computer should say *Connected*. If not, tap **Connect**. Phone and computer must be on the same Wi-Fi. |
| Phone keeps losing the connection | Allow Flick to run in the background: tap **Allow** on the *Let Flick stay connected* card in the app, or **Settings → Apps → Flick → Battery → Unrestricted**. |
| Same, on Xiaomi / Redmi / POCO, Vivo, Oppo, Realme, OnePlus | These phones stop background apps on their own. Also allow **Autostart** (or *Auto-launch* / *Allow background activity*) for Flick in the phone's settings or its Security / Phone Manager app. |
| Same, on Samsung | **Settings → Battery → Background usage limits** → make sure Flick isn't in *Sleeping* or *Deep sleeping apps*. |
| The **"Connected to …"** notification bothers you | In Flick tap **Hide the "Connected" notification**. Flick stays connected. |

## Clipboard

| Symptom | Fix |
|---|---|
| Copy on the computer doesn't reach the phone | The phone must show *Connected*, **Get what I copy on my computer** must be on in the phone app, and **Share clipboard with phones** on the computer. |
| Copy on the phone doesn't reach the computer | Turn on **Send what I copy on this phone** and switch on **Flick — send what I copy** in Accessibility settings. Without that, opening Flick sends what you copied. |
| **Flick — send what I copy** is greyed out | Android restricts this for apps installed from a file. In Flick tap **App info** (or Settings → Apps → Flick) → **⋮** (top right) → **Allow restricted settings**, then switch it on. |
| Accessibility says **"This service is malfunctioning"** | Update to Flick 2.2.2 or newer, then turn **Flick — send what I copy** off and on again. If it comes back, open Flick: if it shows *Flick stopped unexpectedly*, tap **Copy details** and report them. On Xiaomi, Vivo, Oppo, Realme and OnePlus phones, also allow **Autostart** for Flick. |
| A copy wasn't sent | A few apps use their own copy icons that the helper doesn't recognise. Open Flick and it's sent. |
| A password wasn't shared | That's on purpose: text that password managers mark as private is never shared. |
| Images don't come across | The shared clipboard is text and links only. Share images with **Share → Flick**. |

## Transfers

| Symptom | Fix |
|---|---|
| The popup on the computer disappeared | It waits about two minutes for an answer. Send again. |
| A transfer stopped partway | Usually Wi-Fi dropped or a device went to sleep. Partial files are deleted; send again. |
| Can't find the files on the computer | Mac: open Flick → **Open received files**. Windows: tray icon → **Open received files**. |
| Can't find the files on the phone | Photos: gallery (**Pictures/Flick**). Videos: **Movies/Flick**. Everything else: **Download/Flick** in the Files app. |
| Files from the computer wait for a tap | Turn on **Accept files automatically** in the phone app. |

## Logs and crash details

| Device | Where |
|---|---|
| Mac | `~/Library/Application Support/Flick/flick.log` |
| Windows | `%APPDATA%\Flick\flick.log` |
| Android | If Flick stops unexpectedly, the next time you open it shows **Flick stopped unexpectedly** with the details and a **Copy details** button. The details stay on the phone until you copy or dismiss them. |
