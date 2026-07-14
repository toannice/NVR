#!/usr/bin/env bash
# Build dc-bridge → data/dc-bridge (linux/amd64, static). Restart nvr-wrapper afterwards.
set -euo pipefail
cd "$(dirname "$0")"
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o ../data/dc-bridge -ldflags='-s -w' .
chmod +x ../data/dc-bridge
echo "OK → $(realpath ../data/dc-bridge)"
