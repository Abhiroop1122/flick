#!/usr/bin/env bash
# Runs the Android app's networking code (android/core) against the real desktop receiver.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="$(mktemp -d)/flick"
(cd "$ROOT/desktop" && CGO_ENABLED=0 go build -o "$BIN" .)
LOG="$(mktemp)"
FLICK_ASK_TIMEOUT=4000 "$BIN" --test > "$LOG" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true; echo "--- receiver log ---"; cat "$LOG"' EXIT
for _ in $(seq 1 50); do grep -q '"ev":"ready"' "$LOG" && break; sleep 0.2; done
SAVE=$(grep -o '"saveDir":"[^"]*' "$LOG" | cut -d'"' -f4)
PORT=$(grep -o '"port":[0-9]*' "$LOG" | head -1 | cut -d: -f2)
cd "$ROOT/android"
${GRADLE:-./gradlew} :core:test --rerun-tasks -Dflick.e2e.host="127.0.0.1:$PORT" -Dflick.e2e.dir="$SAVE" "$@"
