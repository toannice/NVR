# Implementation Plan: Cloud ↔ Local Data Sync

**Branch**: `003-cloud-data-sync` | **Date**: 2026-05-29 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-cloud-data-sync/spec.md`

## Summary

Build on the cloud connection delivered in 002. Three problems to solve:

1. **Device info to cloud** — today only `serial` round-trips with `maxChannel`. Extend the existing `DeviceCommandHandler` so `get_ethernet` returns the real host MAC + IPv4 (subnet, gateway), and `get_information`/`get_storages`/`get_wifi`/`get_time` return stable shapes the cloud dashboard's C# parser accepts (model/firmware/storage are placeholders by design — this is a software NVR).
2. **Camera single source of truth** — eliminate the parallel `CloudCameras` table. Both `CameraRouting.kt` (local Web UI) and `CameraCommandHandler.kt` (cloud) read/write the same `Cameras` table. Add cloud-only columns (`channel_id`, `main_token`, `sub_token`, `rtsp_url`, `mac_address`, `password_encrypted`, `has_ptz`) to `Cameras`. Serialize all writes — local or cloud — through the existing `configMutex`. Provide a one-shot migration from `cloud_cameras` rows into the unified table on startup.
3. **Live-view reliability** — register the go2rtc stream synchronously inside the camera-add code paths (both Web UI add and cloud `post/put_channels`), re-register every camera on `nvr-wrapper` startup before MQTT connects, and return an `E0007` WebRTC answer envelope when a LIVE offer arrives for an unknown token instead of dropping silently.

No new external dependencies. No new transports. All work in `backend/src/main/kotlin/vn/aiview/nvr/`.

## Technical Context

**Language/Version**: Kotlin 2.0 (JVM target 21)
**Primary Dependencies**: Ktor 2.x, Exposed (SQLite), kotlinx.serialization, SnakeYAML, Eclipse Paho MQTT v3, Ktor HttpClient (CIO). All already in `backend/build.gradle.kts`.
**Storage**: SQLite via Exposed — file at `data/nvr.db`. Frigate `config/config.yml` is the source of truth for stream URLs.
**Testing**: Manual integration test against the AIView cloud staging server + local curl scripts for the camera CRUD path. No new unit-test framework introduced (project has none today).
**Target Platform**: `nvr-wrapper` Docker container (eclipse-temurin:21-jre-alpine) running on Ubuntu host with `network_mode: host`.
**Project Type**: Web application — Kotlin/Ktor backend + React frontend, deployed via docker-compose.
**Performance Goals**: Camera add operation (local or cloud) completes in <2 s including ONVIF probe + go2rtc registration + Frigate reload trigger. Cloud `get_channels` responds in <500 ms for ≤16 cameras. LIVE first-frame latency <5 s.
**Constraints**: Must not break the existing Web UI camera CRUD flow during the schema migration. Must keep working with the unmodified Frigate 0.16.3 image. Must not introduce new long-running processes — extends `CloudService` startup hook only.
**Scale/Scope**: Single device, ≤16 cameras, 1–2 concurrent LIVE viewers, 1 admin user.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

No constitution file exists at `.specify/memory/constitution.md`. Default project gates (from existing code conventions):

- **Single source of truth for cameras** — explicitly upheld (eliminating `CloudCameras` is the whole point of US 2).
- **Reuse existing infrastructure** — no new deps, no new processes, reuse `configMutex`, `OnvifSoapClient`, `SignalingManager`, `DeviceCommandHandler`.
- **Idempotent CRUD** — required (FR-016, FR-017).
- **Backwards-compat on schema** — handled by `createMissingTablesAndColumns` + a one-shot copy of `cloud_cameras` → `cameras` extra columns on first boot after upgrade.
- **No silent failures** — LIVE offers for unknown tokens must surface an answer with `E0007` (FR-022).

**Result**: PASS (no constitution violations; no Complexity Tracking entries needed).

## Project Structure

### Documentation (this feature)

```text
specs/003-cloud-data-sync/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (cloud MQTT command JSON shapes)
│   ├── get_ethernet.json
│   ├── get_information.json
│   ├── get_channels.json
│   ├── post_channels.json
│   ├── del_channels.json
│   └── live_offer_error.json
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

Touched paths only — feature does not create new top-level modules.

```text
backend/src/main/kotlin/vn/aiview/nvr/
├── models/
│   └── Schema.kt                          # MODIFY: extend Cameras table; mark CloudCameras deprecated
├── plugins/
│   └── Databases.kt                       # MODIFY: createMissingColumns + cloud_cameras → cameras migration
├── routes/
│   └── CameraRouting.kt                   # MODIFY: write extended Camera columns on add/edit; register go2rtc sync
├── cloud/
│   ├── SignalingManager.kt                # MODIFY: pre-registration loop reads unified Cameras table
│   ├── handlers/
│   │   ├── CameraCommandHandler.kt        # MODIFY: read/write Cameras (not CloudCameras); idempotent CRUD
│   │   └── DeviceCommandHandler.kt        # MODIFY: harden LAN-interface picker; confirm stable placeholders
│   └── SignalingManager.kt (handleLiveOffer) # MODIFY: send E0007 answer when token unresolved
└── services/
    └── CloudService.kt                    # MODIFY: ordering — migrate → register all streams → connect MQTT

config/config.yml                          # WRITE: same cameras section regardless of add origin

frontend/                                  # NO CHANGES — Web UI already calls /api/add-camera etc.
```

**Structure Decision**: Continue the existing single-backend layout. No new packages.

## Phase 0 — Research

See `research.md` for resolved decisions on:

- LAN interface selection algorithm (R1)
- Cameras schema migration strategy (R2)
- Slug ↔ channelID mapping rule (R3)
- Synchronous go2rtc registration inside camera-add (R4)
- LIVE offer error response envelope (R5)
- Frigate reload trigger consolidation (R6)

All Technical Context items are concrete — no NEEDS CLARIFICATION remain.

## Phase 1 — Design

- `data-model.md` — unified `Cameras` table schema (new columns), migration plan from `cloud_cameras`, mapping rule between `slug` and `channel_id`.
- `contracts/` — JSON examples for each affected cloud MQTT response shape and the new LIVE error envelope.
- `quickstart.md` — manual verification steps mapped to the spec's Success Criteria (SC-001 through SC-007).
- Agent context: update CLAUDE.md `Active feature plan:` line to point to this plan.

## Phase 2 — Tasks (out of scope here)

Generated by `/speckit-tasks` into `tasks.md`. Expected breakdown:

1. Schema + migration (Schema.kt, Databases.kt)
2. Local CameraRouting writes new columns + sync go2rtc registration
3. CameraCommandHandler reads/writes Cameras (delete CloudCameras references)
4. DeviceCommandHandler harden LAN picker; verify stable placeholders
5. SignalingManager pre-registration loop sourced from Cameras
6. SignalingManager `handleLiveOffer` E0007 path
7. CloudService startup ordering
8. Manual integration test pass against staging cloud (quickstart.md)

## Complexity Tracking

No violations — section intentionally empty.
