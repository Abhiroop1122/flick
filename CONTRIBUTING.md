# Contributing to Flick

Thanks for wanting to make Flick better! Bug reports, ideas and pull requests are all
welcome.

## Reporting bugs and ideas

- **Bugs:** open an issue with the *Bug report* form. Include the app versions, your
  phone and computer, and what you expected. If Flick on Android showed *Flick stopped
  unexpectedly*, paste the copied details.
- **Ideas:** open an issue with the *Feature request* form and describe the problem
  you'd like solved.
- **Security problems:** please report them privately (see the
  [security policy](.github/SECURITY.md)).

## Development setup

You'll need Go 1.26+, JDK 17+ and the Android SDK (platform 36). The full details are
in [docs/BUILDING.md](docs/BUILDING.md).

```bash
cd desktop && go test ./... && ./build.sh     # computer app
cd android && ./gradlew :app:assembleDebug     # phone app
./scripts/e2e.sh                               # end-to-end tests
```

Start with [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for a tour of the code.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change.
3. Run the tests: `./scripts/e2e.sh` must pass.
4. Format your code: `gofmt -w` for Go; the Kotlin code follows the official Kotlin
   style (Android Studio's default formatter).
5. If you change what goes over the network, update [docs/PROTOCOL.md](docs/PROTOCOL.md)
   and add an end-to-end test. If you change what users see or do, update the guides in
   [docs/](docs/).
6. Add a line to [CHANGELOG.md](CHANGELOG.md).

## Guidelines

- **Stay small.** The computer app must stay light in the background (about 10 MB of
  memory, no CPU while idle) and needs no cgo.
- **Stay local.** Flick never talks to servers on the internet. Please don't add
  analytics, accounts or cloud services.
- **Stay safe.** Nothing may be written to disk before the user accepts; file names from
  the network must go through `safeName`; private clipboard content must never be sent.
- **Never commit signing keys.** `android/keystore/` and `android/keystore.properties`
  are ignored by Git; keep it that way.

By contributing, you agree that your contributions are licensed under the
[MIT License](LICENSE). Please follow our [Code of Conduct](CODE_OF_CONDUCT.md).
