#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  set -- "$PWD/issues/issue"
  [[ -f /tmp/android-ui-new.save ]] && set -- "$@" /tmp/android-ui-new.save
fi

./gradlew assembleDebug --no-daemon >/dev/null
# Clear only the test application's private session/history files.  The
# differential runner must be repeatable after an interrupted run; keeping
# hundreds of half-megabyte history snapshots can exhaust the emulator.
adb shell pm clear io.github.tuxkoh.bcsfe >/dev/null
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb shell am force-stop io.github.tuxkoh.bcsfe
adb shell monkey -p io.github.tuxkoh.bcsfe 1 >/dev/null
adb forward tcp:18765 tcp:8765 >/dev/null

for _ in {1..20}; do
  if curl --silent --fail --max-time 2 http://127.0.0.1:18765/status >/dev/null; then
    # Android's cached-process freezer can suspend a loopback server after
    # roughly a minute because the UI is intentionally not interacted with.
    # Keep only this test process unfrozen while the matrix runs.
    (
      while :; do
        pid=$(adb shell pidof io.github.tuxkoh.bcsfe 2>/dev/null | tr -d '\r')
        [[ -n "$pid" ]] && adb shell cmd activity unfreeze "$pid" >/dev/null 2>&1 || true
        sleep 15
      done
    ) &
    keepalive_pid=$!
    trap 'kill "$keepalive_pid" 2>/dev/null || true' EXIT INT TERM
    python3 tools/api_diff.py "$@"
    status=$?
    kill "$keepalive_pid" 2>/dev/null || true
    wait "$keepalive_pid" 2>/dev/null || true
    trap - EXIT INT TERM
    exit "$status"
  fi
  sleep 0.25
done
echo "Android local API did not become ready" >&2
exit 1
