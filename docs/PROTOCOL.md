# Flick protocol v1

Flick moves files and clipboard text between an Android phone and a Mac or Windows
PC on the same Wi-Fi. Every connection is opened by the phone; the computer never
connects to the phone. Sections 1–4 cover sending from the phone to the computer;
section 5 covers pairing and the live link the computer uses to send to the phone.
Nothing leaves the local network: no accounts, no cloud, no internet connection
required.

## 1. Discovery

Computers are found two ways at once, so discovery still works on routers that
filter one of them. Results are merged by device `id`.

**UDP probe (primary).** While the share sheet is open the phone sends, every
1.5 s, a probe to `255.255.255.255:47474`, to each interface's subnet broadcast
address, and to every computer it has sent to before (unicast, last known IP):

```json
{"flick":1,"type":"probe","id":"<phone uuid>","name":"Pixel 8"}
```

Each receiver replies by unicast to the probe's source address and port:

```json
{"flick":1,"type":"here","id":"<pc uuid>","name":"Asha's MacBook Air",
 "os":"mac","port":47475,"fp":"<sha256 of TLS cert, lowercase hex>","v":"1.0.0"}
```

**mDNS / DNS-SD (secondary).** Receivers also advertise `_flick._tcp.local` with
TXT records `v=1`, `id`, `os`, `fp`. The phone browses it with `NsdManager`.

## 2. Transport security

Every receiver creates its own self-signed TLS certificate on first launch and
announces the certificate's SHA-256 fingerprint during discovery. The phone only
completes a TLS handshake if the presented certificate matches that fingerprint
(certificate pinning), so transfers are encrypted end to end and cannot be read
by anyone else on the Wi-Fi. For a computer added by IP address the phone pins
the certificate it sees on first contact (trust on first use).

All endpoints below are HTTPS on the announced `port` (47475, or the next free
port up to 47480).

## 3. API

### `GET /v1/info`
`{"flick":1,"id":"…","name":"…","os":"mac|windows|linux","v":"1.0.0","fp":"…"}`

### `POST /v1/ask`
Asks the person at the computer to accept. The request is held open while the
accept/decline popup is on screen (up to 120 s).

```json
{
  "sender": {"id":"<phone uuid>","name":"Pixel 8","model":"Google Pixel 8",
             "platform":"android","trustKey":"<optional>"},
  "items": [{"name":"IMG_0042.jpg","size":3145728,"mime":"image/jpeg"}],
  "text": "https://example.com (optional, for shared links or text)",
  "thumb": "<optional base64 JPEG, ≤ 200 KB>"
}
```

Replies:

| Status | Body | Meaning |
|---|---|---|
| 200 | `{"accepted":true,"session":"…","token":"…","trustKey":"…?"}` | Upload now. `trustKey` is present when the user ticked "Always accept"; the phone stores it and sends it with later asks. |
| 403 | `{"accepted":false,"reason":"declined"}` | The user said no. |
| 408 | `{"accepted":false,"reason":"timeout"}` | Nobody answered. |
| 429 | `{"accepted":false,"reason":"busy"}` | Too many pending requests. |

If the phone drops the connection while the popup is open, the popup closes.
Text-only asks complete on acceptance (the link opens in the browser or the text
is copied to the clipboard); no session is created.

### `PUT /v1/upload?session=<id>&index=<n>`
Header `X-Flick-Token: <token>`. Body is the raw file bytes, sent chunked
(declared sizes are only used for progress, because some Android content
providers misreport them). The receiver writes to a temporary file and renames
it into the save folder only when the upload completes cleanly. Reply:
`{"ok":true,"name":"IMG_0042 (1).jpg"}`.

### `POST /v1/cancel?session=<id>`
Header `X-Flick-Token`. Stops the transfer and deletes partial files.

## 4. Safety rules on the receiver

* Nothing is written to disk before a person accepts (or the phone holds a valid
  `trustKey` and auto-accept is on).
* File names are reduced to a bare name: path separators, `..`, control and
  reserved characters, and Windows device names are removed, so a sender cannot
  write outside the save folder. Existing files are never overwritten.
* Only `http`/`https` links are ever opened automatically.
* Tokens and trust keys are 256-bit random values compared in constant time.
* A session expires after 2 minutes without data; partial files are deleted.

## 5. Two-way: pairing, the live link, clipboard and computer → phone files

### Pairing — `POST /v1/ask` with `"pair": true`
An ask with no items, no text and `"pair": true` asks the person at the computer to
connect the phone. On **Connect** the reply is `{"accepted":true,"trustKey":"…"}`.
A phone with a trust key is "paired": the computer accepts its files without asking
and may push events to it. (Ticking *Always accept* during a send also pairs.)

Paired phones authenticate later requests with two headers:
`X-Flick-Phone: <phone id>` and `X-Flick-Key: <trust key>`.

### Live link — `GET /v1/link?name=<phone name>&model=<model>`
A paired phone keeps this request open while it's on the same network. The response
is a stream of newline-delimited JSON events:

| Event | Meaning |
|---|---|
| `{"type":"hello","id":…,"name":…,"clipboard":true}` | Connected. |
| `{"type":"ping"}` | Every 25 s, so dead connections are noticed. |
| `{"type":"clip","text":"…"}` | Text copied on the computer: put it on the phone's clipboard. |
| `{"type":"offer","offer":{"id","from","fromId","items":[{name,size,mime}],"total","description"}}` | The computer wants to send files. |
| `{"type":"cancel","offerId":"…"}` | The computer cancelled an offer. |

`403` means the computer no longer trusts the phone (it was forgotten there); the
phone drops its key and must pair again. A newer link from the same phone replaces
an older one. The phone reconnects with backoff, immediately on network changes, and
re-discovers the computer over UDP if its address changed.

Clipboard rules on the computer: only text, at most 100 KB, only when changed, never
content marked private by password managers (Windows `ExcludeClipboardContentFromMonitorProcessing`,
`CanUploadToCloudClipboard=0`, …; macOS `org.nspasteboard.ConcealedType`, …), and never
echoed back to the phone that sent it.

### Clipboard from the phone — `POST /v1/clip`
Body `{"text":"…"}` with the pairing headers. The computer puts the text on its
clipboard (silently) and pushes it to its other connected phones; it doesn't echo it
back to the sender. `409` means clipboard sharing is turned off on that computer.

Android only lets an app read the clipboard while one of its windows has focus, so
the phone sends at moments Flick has focus: when Flick is opened, from the optional
"Send clipboard" Quick Settings tile, or — with the
accessibility helper — right after the person taps Copy (an invisible 1-pixel window
takes focus for an instant). Clips marked `android.content.extra.IS_SENSITIVE`
(passwords) are never sent.

### Files from the computer
After an `offer` event the phone either declines —
`POST /v1/offer/status?offer=<id>` `{"state":"declined"}` — or accepts and downloads
each file with `GET /v1/offer/file?offer=<id>&index=<n>` (raw bytes with
`Content-Length`), then reports `{"state":"done"}` (or `failed` / `cancelled`). All three
calls carry the pairing headers. The computer stops waiting after 2 minutes without an
answer and treats 90 s without progress as a lost connection.
