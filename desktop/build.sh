#!/usr/bin/env bash
# Builds Flick for macOS (universal Flick.app, ad-hoc signed) and Windows (Flick.exe).
# Needs Go; for the Mac app also `makefat` and `rcodesign` (or run on a Mac, where
# `lipo` and `codesign` are used instead).
set -euo pipefail
cd "$(dirname "$0")"
OUT=dist
rm -rf "$OUT" && mkdir -p "$OUT"
LDFLAGS="-s -w"

# Windows
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "$LDFLAGS -H=windowsgui" -o "$OUT/Flick.exe" .

# macOS (Apple Silicon + Intel in one app)
GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "$LDFLAGS" -o "$OUT/flick-arm64" .
GOOS=darwin GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "$LDFLAGS" -o "$OUT/flick-amd64" .
APP="$OUT/Flick.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
if command -v lipo >/dev/null; then
  lipo -create -output "$APP/Contents/MacOS/Flick" "$OUT/flick-arm64" "$OUT/flick-amd64"
else
  makefat "$APP/Contents/MacOS/Flick" "$OUT/flick-arm64" "$OUT/flick-amd64"
fi
rm "$OUT/flick-arm64" "$OUT/flick-amd64"
chmod +x "$APP/Contents/MacOS/Flick"
cp macos/Info.plist "$APP/Contents/Info.plist"
cp macos/Flick.icns "$APP/Contents/Resources/Flick.icns"
printf 'APPL????' > "$APP/Contents/PkgInfo"
if command -v codesign >/dev/null; then
  codesign --force --deep --sign - "$APP"
else
  rcodesign sign "$APP"
fi
(cd "$OUT" && rm -f Flick-mac.zip && zip -qry -X Flick-mac.zip Flick.app)
ls -lh "$OUT"
