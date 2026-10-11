#!/usr/bin/env bash
# Serve the published APK on the local network: http://<this-machine-ip>:8000/latest.apk
# Pair it with tools/publish-latest.sh and the apk-qr.png QR code.
# Your phone must be on the same Wi-Fi as this machine.
set -euo pipefail

SERVE_DIR="${SERVE_DIR:-/tmp/ashorts-serve}"
mkdir -p "$SERVE_DIR"
exec python3 -m http.server 8000 --bind 0.0.0.0 --directory "$SERVE_DIR"
