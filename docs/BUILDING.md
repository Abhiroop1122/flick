# Building

How to build Flick from this source, run the tests and make a release.

- [Requirements](#requirements)
- [Computer app](#computer-app)
- [Phone app](#phone-app)
- [Signing key](#signing-key)
- [Tests](#tests)
- [CI](#ci)
- [Making a release](#making-a-release)
- [Publishing on GitHub](#publishing-on-github)
- [Going further](#going-further)

---

## Requirements

| For | You need |
|---|---|
| Computer app | Go 1.26 or newer. On Linux also `makefat` and `rcodesign`; on a Mac, `lipo` and `codesign` (built in) are used instead. |
| Phone app | JDK 17 or newer, Android SDK 36 (`ANDROID_HOME` or `android/local.properties`). Gradle is downloaded by `./gradlew`. |
| Tests | Both of the above. |

```bash
go install github.com/randall77/makefat@latest      # Linux only
cargo install apple-codesign                          # provides rcodesign, Linux only
```

## Computer app

```bash
cd desktop
./build.sh
```

Output in `desktop/dist/`:

| File | What |
|---|---|
| `Flick.exe` | Windows, 64-bit, with icon, version info and manifest |
| `Flick.app` | Mac, universal (Apple Silicon + Intel), ad-hoc signed |
| `Flick-mac.zip` | `Flick.app` zipped for sharing |

Windows icon, version and manifest live in `desktop/winres/` and are compiled into
`rsrc_windows_amd64.syso` with [go-winres](https://github.com/tc-hib/go-winres)
(`go-winres make`). The Mac app's `Info.plist` and icon are in `desktop/macos/`.

Run the Go unit tests with `go test ./...`.

## Phone app

```bash
cd android
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Signing key

Android only installs an update over an existing Flick if both are signed with the
**same key**. Lose the key and every phone has to uninstall Flick (and connect its
computers again) before it can install a newer one.

- The key is `android/keystore/flick-release.jks`; its passwords are in
  `android/keystore.properties`:

  ```properties
  storeFile=keystore/flick-release.jks
  storePassword=…
  keyAlias=flick
  keyPassword=…
  ```

- Both are listed in `android/.gitignore`, so they're never committed. **Back them up
  somewhere private** (a password manager or an encrypted drive).
- Without `keystore.properties` the build falls back to the debug key, which can't
  update a release install.

## Tests

```bash
./scripts/e2e.sh
```

Builds the desktop app, starts it in `--test` mode and runs the phone's networking code
against it: 19 end-to-end tests (see [ARCHITECTURE.md → Testing](ARCHITECTURE.md#testing)).
Results: `android/core/build/test-results/test/`.

## CI

`.github/workflows/build.yml` runs when you push a tag like `v2.2.2` (or by hand from
the Actions tab):

1. **test**: Go unit tests and `scripts/e2e.sh`.
2. **android**: builds the APK. To sign it with the release key, add repository secrets
   `ANDROID_KEYSTORE_BASE64` (the `.jks` file, base64-encoded) and
   `ANDROID_KEYSTORE_PASSWORD` (store and key password).
3. **desktop**: on macOS, runs `build.sh` for `Flick.exe` and `Flick-mac.zip`.

The builds are attached to the workflow run as artifacts.

## Making a release

1. Bump the version everywhere it appears:

   | App | File | Field |
   |---|---|---|
   | Computer | `desktop/agent.go` | `const Version` |
   | Mac | `desktop/macos/Info.plist` | `CFBundleShortVersionString`, `CFBundleVersion` |
   | Windows | `desktop/winres/winres.json` | `version`, `file_version`, `product_version`, `FileVersion`, `ProductVersion`; then `go-winres make` |
   | Android | `android/app/build.gradle.kts` | `versionCode` (+1 every release), `versionName` |

   The computer app replaces a running older agent when its version differs, so always
   bump `Version` when the desktop changes.
2. Add an entry to [CHANGELOG.md](../CHANGELOG.md).
3. Run `./scripts/e2e.sh`.
4. Build both apps (above).
5. Copy the outputs into `releases/` (APK named `Flick-<version>.apk`), then refresh the
   checksums:

   ```bash
   cd releases && shasum -a 256 Flick-*.apk Flick-mac.zip Flick.exe > SHA256SUMS.txt
   ```

6. Update the download table at the top of [README.md](../README.md).

## Publishing on GitHub

`releases/` and `archive/` are in `.gitignore`: app files don't belong in the code
history. To publish a version:

1. Commit the version bump and changelog, then tag it: `git tag v2.2.2 && git push --tags`.
   CI tests and builds it.
2. On GitHub open **Releases → Draft a new release**, pick the tag, paste the changelog
   entry, and attach `Flick-<version>.apk` (signed with the release key),
   `Flick-mac.zip`, `Flick.exe` and `SHA256SUMS.txt`.
3. Update the download links in [README.md](../README.md) if the APK name changed.

## Going further

- **No warnings on first open:** sign `Flick.exe` with a Windows code-signing
  certificate, and sign and notarize the Mac app with an Apple Developer ID
  ($99/year).
- **Play Store:** publishing there removes the Play Protect step on install. It needs a
  developer account, a privacy policy and store listing, and Google's review of the
  background-connection permissions and of the Accessibility service (with a
  prominent in-app explanation of what it does).
