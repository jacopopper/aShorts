#!/usr/bin/env bash
# Publish the freshly built debug APK to the LAN serving directory behind the QR code.
#
# Usage: tools/publish-latest.sh [path-to-apk]
#
# After running, the QR code (http://<this-machine-ip>:8000/latest.apk) installs the newest
# version. Installs upgrade in place: the debug APK is always signed with the same key and the
# versionCode increases with every build, so Android updates over the old app - no uninstalling.
set -euo pipefail

SERVE_DIR="${SERVE_DIR:-/tmp/ashorts-serve}"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"

mkdir -p "$SERVE_DIR"
cp "$APK" "$SERVE_DIR/latest.apk"
echo "Published $APK -> $SERVE_DIR/latest.apk"
echo "Scan the QR code on your phone to install the update (no uninstall needed)."
