#!/usr/bin/env bash
# Cross-compile nvr-edge-agent for the Pi. Run on your dev machine (Go installed).
#   ./build.sh            # Pi 3/4 64-bit Raspberry Pi OS / Debian arm64  (default)
#   ARCH=arm ./build.sh   # 32-bit Debian (armv6/armv7)
set -euo pipefail
cd "$(dirname "$0")"

ARCH="${ARCH:-arm64}"
case "$ARCH" in
  arm64) GOARCH=arm64; GOARM="" ;;
  arm)   GOARCH=arm;   GOARM=7  ;;
  *) echo "ARCH must be arm64 or arm"; exit 1 ;;
esac

echo "Building nvr-edge-agent for linux/$GOARCH ..."
export GOOS=linux GOARCH="$GOARCH" CGO_ENABLED=0
[ -n "$GOARM" ] && export GOARM="$GOARM"
go build -ldflags='-s -w' -o nvr-edge-agent .

ls -lh nvr-edge-agent
echo "OK. Copy the whole edge-agent/ dir to the Pi, then: sudo ./install.sh"
