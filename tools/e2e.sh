#!/usr/bin/env bash
# End-to-end test of aShorts against the fake-Instagram harness on the Android emulator.
#
# Boots the emulator if needed (software rendering on this machine, so the first boot takes
# several minutes), installs both apps, enables aShorts without any UI (appops + secure
# settings), then hands over to tools/e2e.py, which drives the scenarios and prints a
# PASS/FAIL line per assertion.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="$HOME/android-sdk/platform-tools/adb"
EMU="$HOME/android-sdk/emulator/emulator"
AVD_NAME="ashorts"

cd "$ROOT"

STATE="$("$ADB" get-state 2>/dev/null || true)"
if [ "$STATE" != "device" ]; then
  echo "Booting emulator (software rendering - first boot takes a while)..."
  nohup "$EMU" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -memory 3072 > /tmp/emu-boot.log 2>&1 &
  disown || true
fi

echo "Waiting for the emulator to finish booting..."
BOOTED=""
for _ in $(seq 1 240); do
  STATE="$("$ADB" get-state 2>/dev/null || true)"
  BOOT="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$STATE" = "device" ] && [ "$BOOT" = "1" ]; then
    BOOTED="1"
    echo "Emulator is up."
    break
  fi
  sleep 5
done
if [ -z "$BOOTED" ]; then
  echo "ERROR: emulator did not boot; see /tmp/emu-boot.log" >&2
  exit 1
fi

# Skip any first-boot setup wizard so the launcher is reachable.
"$ADB" shell settings put global device_provisioned 1 >/dev/null 2>&1 || true
"$ADB" shell am force-stop com.google.android.setupwizard >/dev/null 2>&1 || true

echo "Building app + harness..."
GRADLE="$HOME/tools/gradle-8.10.2/bin/gradle"
if [ ! -x "$GRADLE" ]; then GRADLE="./gradlew"; fi
"$GRADLE" :app:assembleDebug :harness:assembleDebug --console=plain -q

echo "Installing..."
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" install -r harness/build/outputs/apk/debug/harness-debug.apk

echo "Enabling aShorts (overlay permission + accessibility service)..."
"$ADB" shell appops set com.ashorts.blocker SYSTEM_ALERT_WINDOW allow
"$ADB" shell settings put secure enabled_accessibility_services \
  com.ashorts.blocker/com.ashorts.blocker.service.BlockerAccessibilityService
"$ADB" shell settings put secure accessibility_enabled 1

exec python3 tools/e2e.py
