# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**my-nvr** is a NVR (Network Video Recorder) orchestration platform built on top of [Frigate](https://frigate.video/). It adds fleet management, self-healing, ONVIF camera control, and real-time monitoring on top of Frigate's AI video analysis engine.

**Core services:**
- **nvr-wrapper** (backend/) — Kotlin/Ktor REST API + WebSocket server; orchestrates cameras, proxies Frigate API, manages schedules/config
- **nvr-watchdog** (watchdog/) — Kotlin daemon; health monitoring, self-healing (container restarts), OTA updates with rollback
- **frontend/** — React + TypeScript SPA; live WebRTC streams, HLS playback, real-time MQTT events
- **nginx** — HTTPS reverse proxy/TLS termination, routes all traffic to backend on port 3000
- **mosquitto** — MQTT broker; Frigate publishes detection events, backend bridges them to WebSocket for browser clients
- **frigate** — AI video analysis engine (v0.16.3) with go2rtc for WebRTC streaming

## Build Commands

### Backend (Kotlin/Ktor)
```bash
cd backend
./gradlew build          # compile + test
./gradlew test           # run tests only
./gradlew installDist    # produce distribution (used by Docker)
./gradlew run            # run locally
```

### Watchdog (Kotlin daemon)
```bash
cd watchdog
./gradlew jar            # build fat JAR at build/libs/nvr-watchdog.jar
```

### Frontend (React + Vite)
```bash
cd frontend
npm install
npm run dev              # dev server with HMR
npm run build            # production build to dist/
npm run lint             # ESLint
npm run preview          # preview production build
```

### Docker
```bash
# Full stack (multi-stage: frontend build → backend JAR → JRE image)
docker build -t ghcr.io/toannice/nvr:latest .

# Watchdog only
docker build -t ghcr.io/toannice/nvr-watchdog:latest ./watchdog

# Run the full stack
docker compose up
```

## Architecture

### Service Communication

| Service | Port | Notes |
|---------|------|-------|
| nvr-wrapper | 3000 | Internal only; nginx proxies public traffic here |
| Frigate API | 5000 | Proxied via `/api/frigate/*` on the wrapper |
| go2rtc | 1984 | WebRTC/RTSP; also proxied via `/api/frigate/*` |
| RTSP re-stream | 8554 | |
| Mosquitto MQTT | 1883 | Internal; 9001 for WebSocket |
| nginx | 80/443 | Only public entry point |

Both `nvr-wrapper` and `nvr-watchdog` run in **host network mode** — required for ONVIF multicast discovery.

### Backend Route Structure (`backend/src/main/kotlin/vn/aiview/nvr/routes/`)

| Route prefix | File | Purpose |
|---|---|---|
| `/api/cameras` | CameraRouting.kt | Camera CRUD, discovery, ONVIF credentials |
| `/api/frigate/*` | FrigateProxy.kt | Reverse proxy to Frigate + go2rtc; sanitizes RTSP credentials |
| `/api/onvif/*` | OnvifRouting.kt | ONVIF PTZ, stream profiles |
| `/api/schedule` | ScheduleRouting.kt | Cron-based recording schedules |
| `/api/config` | ConfigRouting.kt | frigate.yml / system config |
| `/api/lock` | LockRouting.kt | Pattern lock security |
| `/api/system/*` | SystemRouting.kt | Health, storage, version info |
| `WS /ws/mqtt` | Sockets.kt | Streams MQTT events to browser in real time |

### Data Flow
```
Cameras (RTSP/ONVIF)
  └─► Frigate (AI detection) ──MQTT──► Mosquitto
                              └─HLS/WebRTC─► go2rtc

nvr-wrapper:
  - bridges Mosquitto → WebSocket (/ws/mqtt) → Frontend
  - proxies Frigate/go2rtc API
  - manages camera metadata in SQLite (data/nvr.db)

Frontend: WebRTC live view, HLS playback, real-time events via WebSocket
```

### Watchdog Modules (`watchdog/src/main/kotlin/vn/aiview/watchdog/modules/`)
- **SystemHealthMonitor** — CPU/memory/disk + container health every 30s
- **SelfHealingEngine** — auto-restarts unhealthy containers
- **UpdateManager** — checks GHCR for new image versions, performs OTA with automatic rollback on failure
- **AlertManager** — Telegram notifications
- **NetworkMonitor** — connectivity checks to Frigate, MQTT
- **EventLogger** — audit trail in SQLite (`/data/events.db`)

## Database

SQLite (`data/nvr.db`, WAL mode). Schema in `backend/src/main/kotlin/vn/aiview/nvr/models/Schema.kt`.

Tables: `cameras`, `schedules`, `lock_config`, `ai_events`

Watchdog uses a separate DB at `/data/events.db` (tables: `events`, `reports`).

## Key Configuration

- **`.env`** — runtime secrets and versions (`FRIGATE_VERSION`, `NVR_WRAPPER_VERSION`, `TELEGRAM_BOT_TOKEN`, `FRIGATE_HOST_IP`, GitHub credentials for private image pulls)
- **`config/config.yml`** — Frigate configuration (cameras, MQTT, go2rtc streams, detection zones)
- **`config/watchdog.yml`** — monitoring intervals, alert thresholds, OTA settings
- **`nginx/nginx.conf`** — TLS, reverse proxy rules, security headers; blocks direct access to `.env`, `*.db`, `*.yml`

`FRIGATE_HOST_IP` must be set to the host's LAN IP for WebRTC ICE candidates to work correctly.

## CI/CD Pipeline

`.github/workflows/docker-ci.yml`:
1. **test** job — runs `backend/gradlew test` on every push to main/staging or version tag `v*.*.*`
2. **build-push** job — builds and pushes to GHCR (`ghcr.io/toannice/nvr` + `ghcr.io/toannice/nvr-watchdog`) with tags: `latest` (main), `staging` (staging branch), version tag

OTA updates on edge devices are handled by `watchdog/UpdateManager` pulling from GHCR, or manually via `deploy/edge_ota_update.sh v1.2.0`.

## Deployment

`run.sh` — one-shot setup script for a new host: installs Docker/mergerfs, auto-detects + formats drives, creates MergerFS pool at `/media`, updates `.env` with host IP.

`nvr-stack.service` — systemd unit that runs `docker compose up` after Docker starts.

## Session Protocol (NON-NEGOTIABLE)

These rules apply to every Claude Code session in this repository:

1. **Start of session** — Review and update this file (`CLAUDE.md`) first before any other work. Commit with: `update : CLAUDE.md — session refresh`
2. **Git commits** — After every meaningful change, commit using format: `edit|update|new|delete : <description>`
3. **Language** — All assistant responses MUST be in Vietnamese. Code, identifiers, and commands stay in English.
4. **Frontend changes** — ANY change to `frontend/` requires announcing the plan (files + impact) to the user and waiting for explicit confirmation before touching any file.
5. **Code style** — Keep code clean and simple; no premature abstractions, no unused code.
