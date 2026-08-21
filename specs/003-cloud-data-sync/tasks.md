---
description: "Task list for 003-cloud-data-sync"
---

# Tasks: Cloud ↔ Local Data Sync

**Input**: Design documents from `/specs/003-cloud-data-sync/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md
**Tests**: No automated test tasks — project has no test framework today; verification is manual via `quickstart.md`.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel — different files, no dependency on incomplete tasks
- **[Story]**: User story label (US1 = device info, US2 = camera SoT, US3 = live reliability)
- All paths are absolute or repo-relative from `/home/toan/toanDoc/Documents/my-nvr/`.

---

## Phase 1: Setup

- [X] T001 Confirm branch `003-cloud-data-sync` is checked out and rebased on `main`; back up `data/nvr.db` to `data/nvr.db.bak.20260529` before any schema work
- [X] T002 [P] Add a top-level constants file `backend/src/main/kotlin/vn/aiview/nvr/cloud/DeviceConstants.kt` holding placeholder model (`E120-FCWP`), firmware (`1.0.3.2`), product number (`E120-FCWP-001`), web version (`1.0.0`), manufacturer (`Bkav`), and max channel count (16); both `DeviceCommandHandler` and `CameraCommandHandler` import from here
- [X] T003 [P] Add a small `Go2RtcClient` object at `backend/src/main/kotlin/vn/aiview/nvr/cloud/Go2RtcClient.kt` exposing `registerStreamSync(name, src, timeoutMs=5000)` (PUT) and `deleteStream(name)` (DELETE); reuses the existing CIO `HttpClient`

---

## Phase 2: Foundational (blocking)

These must complete before any user story phase starts. They touch the schema and shared helpers that every story relies on.

- [X] T004 Extend `Cameras` table in `backend/src/main/kotlin/vn/aiview/nvr/models/Schema.kt` with the new columns from `data-model.md` §1: `channel_id` varchar(50) nullable, `username` varchar(255) nullable, `password_encrypted` text nullable, `main_token` varchar(255) default "", `sub_token` varchar(255) default "", `rtsp_url` text default "", `sub_rtsp_url` text nullable, `mac_address` varchar(50) nullable, `has_ptz` bool default false. Keep `slug` as PK. Add a unique index on `channel_id` for non-null values
- [X] T005 Update `backend/src/main/kotlin/vn/aiview/nvr/plugins/Databases.kt` `createMissingTablesAndColumns()` to add each new column with raw `ALTER TABLE cameras ADD COLUMN …` guarded by an `information_schema`/`pragma_table_info('cameras')` check (SQLite); idempotent across restarts
- [X] T006 In `backend/src/main/kotlin/vn/aiview/nvr/plugins/Databases.kt`, add a one-shot migration `migrateCloudCamerasIntoCameras()` per `data-model.md` §3 — runs once after T005, copies/merges rows from `cloud_cameras` into `cameras` (match by `ip`+`onvif_port`; insert with `slug = "cam_${channel_id}"` if no match); re-encrypts plaintext password through the 002 AES-GCM helper; logs row count migrated. Guard with a `migration_flag` row in a new `migrations` table or via SELECT count check
- [X] T007 Extract the existing `configMutex` from `backend/src/main/kotlin/vn/aiview/nvr/routes/CameraRouting.kt` into a shared singleton `backend/src/main/kotlin/vn/aiview/nvr/services/CameraConfigMutex.kt` so the cloud handler can `withLock` the same mutex; update `CameraRouting.kt` to import it
- [X] T008 Create `backend/src/main/kotlin/vn/aiview/nvr/services/CameraService.kt` exposing `suspend fun applyAndPersist(slug, channelId?, host, port, user, pass, name, manufacturer, model): Result<AppliedCamera>` — single code path that: (a) ONVIF probes via `OnvifSoapClient`, (b) upserts `Cameras` row including new columns, (c) writes/updates `config/config.yml` under `CameraConfigMutex`, (d) calls `Go2RtcClient.registerStreamSync("${channelId}_main", "${rtspUrl}#backchannel=0")`, (e) triggers Frigate restart via `POST http://localhost:5000/api/restart`, (f) returns `AppliedCamera(slug, channelId, rtspUrl, mainToken, subToken, frigateRestarted)`
- [X] T009 Add `suspend fun removeAndPersist(slugOrChannelId): Result<Unit>` to the same `CameraService` — deletes row from `Cameras` (match by slug or channel_id), removes camera block from `config.yml` under mutex, calls `Go2RtcClient.deleteStream("${channelId}_main")`, triggers Frigate restart. Idempotent (no error if row already missing)
- [X] T010 Add `fun assignNextChannelId(): String` to `CameraService` — inside a transaction, find rows with `channel_id IS NULL`, assign them lowest free integer strings starting at `"1"` (skipping already-used values), return the next free string for new inserts. Used by `get_channels` lazy assignment

---

## Phase 3: User Story 1 — Real Device Identity & Network (P1)

**Goal**: Cloud dashboard shows true MAC and IPv4 of the host's primary LAN interface; model/firmware/storage stay as stable placeholders.

**Independent test**: Run `quickstart.md` SC-001 — open device detail panel on AIView cloud dashboard, MAC and IP match `ip link` / `ip -4 addr` on the host.

- [X] T011 [US1] In `backend/src/main/kotlin/vn/aiview/nvr/cloud/handlers/DeviceCommandHandler.kt` `getLanInfo()`, extend the excluded-interface name prefix list with `tailscale`, `tun`, `tap` (per research.md R1)
- [X] T012 [US1] In the same `getLanInfo()`, when no candidate interface is found, return MAC `""` (empty string) instead of `"00:00:00:00:00:00"`; log a single `AppLogger.warn` with module SYSTEM, key `LAN_IFACE_NOT_FOUND`
- [X] T013 [P] [US1] Replace the hard-coded model/firmware/manufacturer/product_number/web_version/number_of_channel strings in `getInformation()` with reads from `DeviceConstants` (T002)
- [X] T014 [P] [US1] In `getStorages()`, replace the real `File("/").totalSpace` reads with the stable placeholder values from `data-model.md` §5 (totalSpace 1024 MB, freeSpace 512 MB, usedSpace 512 MB, memory total/free/used 512/256/256) so the cloud dashboard parser always sees a populated, consistent shape regardless of host disk size
- [X] T015 [P] [US1] Verify `getEthernet()` JSON shape matches `contracts/get_ethernet.json` exactly (field order does not matter, but field names and types must match); confirm `ipv6.use=false`, `mode_list=["DHCP","Static"]`, `hostname` literal `"nvr-aiview"`
- [X] T016 [P] [US1] Verify `getInformation()` JSON shape matches `contracts/get_information.json` exactly (placeholder values from `DeviceConstants`, `serial_number` from `SignalingManager.deviceSerial`)

---

## Phase 4: User Story 2 — Single Source of Truth for Cameras (P1)

**Goal**: Both Web UI and cloud read/write the unified `Cameras` table; CRUD is idempotent; deletes propagate to Frigate and go2rtc.

**Independent test**: Run `quickstart.md` SC-002 + SC-003 — add from Web UI, see in cloud within 10 s; add from cloud, see in Web UI within 10 s; delete from either side, both sides update.

**Depends on**: Phase 2 (T004–T010).

- [X] T020 [US2] Refactor `backend/src/main/kotlin/vn/aiview/nvr/routes/CameraRouting.kt#addCamera` to delegate to `CameraService.applyAndPersist(...)`; remove inline YAML mutation, ONVIF probe duplication, and Frigate restart trigger from this route. Keep only request parsing + response shaping. Pass through `frigate_restarted` flag in the response (already in 002)
- [X] T021 [US2] Refactor `CameraRouting.kt#deleteCamera` to delegate to `CameraService.removeAndPersist(slug)`; identical cleanup
- [X] T022 [US2] Refactor `CameraRouting.kt` PTZ enable/disable to write both `ptz_enabled` AND `has_ptz` columns so the cloud `get_channels` PTZ flag stays in sync
- [X] T023 [US2] In `backend/src/main/kotlin/vn/aiview/nvr/cloud/handlers/CameraCommandHandler.kt`, change `getChannels()` to read from the unified `Cameras` table (not `CloudCameras`). Before SELECT, call `CameraService.assignNextChannelId()`-driven backfill so every returned row has a non-null `channel_id`. Map all rows into the JSON shape from `contracts/get_channels.json`
- [X] T024 [US2] In `CameraCommandHandler#postOrPutChannels`, replace the open-coded ONVIF + go2rtc + YAML write block with a single call to `CameraService.applyAndPersist(slug = "cam_${channelId}", channelId = channelId, …)`; include `frigate_restarted` in the response `data` block per `contracts/post_channels.json`. Idempotent: repeated call for same channel_id is a no-op upsert
- [X] T025 [US2] In `CameraCommandHandler#delChannels`, replace the open-coded delete with `CameraService.removeAndPersist(channelId)`; return success even when row is already gone (`contracts/del_channels.json`)
- [X] T026 [US2] Delete the `CloudCameras` import and any remaining direct references from `CameraCommandHandler.kt`; leave the `CloudCameras` table definition in `Schema.kt` with a `// DEPRECATED: migrated to Cameras in 003; remove in next release` comment but do not drop it (rollback safety per research.md R2)
- [X] T027 [US2] Update `getVideoSources()` and `getProfilesChannel()` in `CameraCommandHandler.kt` to read from the unified `Cameras` table; preserve the token convention (`V_SRC_0${chId.padStart(3,'0')}1`, `MediaProfile${chId.padStart(3,'0')}02`)

---

## Phase 5: User Story 3 — Reliable Live View From the Cloud (P2)

**Goal**: Newly-added cameras viewable from cloud within 30 s with no restart; restart recovers within 10 s; unknown tokens return E0007 fast.

**Independent test**: Run `quickstart.md` SC-004, SC-005, and the negative test (unknown token).

**Depends on**: Phase 2 (T008 ensures synchronous registration on add) and Phase 4 (T023–T025 unify CRUD path).

- [X] T030 [US3] In `backend/src/main/kotlin/vn/aiview/nvr/services/CloudService.kt`, change startup ordering to: (1) run DB migrations, (2) read all cameras from unified `Cameras`, (3) for each call `Go2RtcClient.registerStreamSync("${channelId}_main", "${rtspUrl}#backchannel=0")` with a 30 s overall budget, (4) `DcBridgeManager.start()`, (5) `SignalingHttpClient.fetchMqttConnectionInfo(...)`, (6) `connectMQTT(...)`. Pre-registration is blocking — MQTT does not connect until it completes or budget elapses
- [X] T031 [US3] In `backend/src/main/kotlin/vn/aiview/nvr/cloud/SignalingManager.kt#handleLiveOffer`, when `resolveStreamId(token)` returns null, build a base64-encoded JSON `{"type":"answer","sdp":"","error_code":"E0007","message":"Camera not found"}` and publish a `signal.webrtc/answer` envelope per `contracts/live_offer_error.json` to `sessionPubBySubTopic[subTopic]`. Add an `AppLogger.warn` with key `LIVE_TOKEN_UNRESOLVED` and the token value
- [X] T032 [US3] In the same `handleLiveOffer`, change `resolveStreamId(token)` to query the unified `Cameras` table (not `CloudCameras`): match `main_token == token` for `_main`, else match by `buildSubToken(channel_id)` for `_sub`
- [X] T033 [US3] Verify `ChannelStateNotifier` heartbeat (002) reads camera list from unified `Cameras` for `channels[*]` array (channelID + status). No code change expected if 002 already used Cameras; otherwise update the SELECT in `ChannelStateNotifier.kt`

---

## Phase 6: Polish & Cross-Cutting

- [X] T040 [P] Add structured `AppLogger.info` entries with module CLOUD at key points: `DEVICE_GET_ETHERNET` (mac, ip), `CAMERA_APPLY` (slug, channel_id, origin=ui|cloud), `CAMERA_REMOVE` (slug, channel_id), `GO2RTC_REGISTER` (name, ok?), `MIGRATION_CLOUD_CAMERAS` (row count). One log line per event, no PII in payload
- [ ] T041 Run full manual verification per `quickstart.md` (SC-001 through SC-007 plus the negative test) on the dev box; capture any failures and reopen tasks
- [X] T042 Update `CLAUDE.md` Codebase table to mention `CameraService.kt`, `Go2RtcClient.kt`, `CameraConfigMutex.kt`, `DeviceConstants.kt`; update the "Thêm Camera: Luồng dữ liệu" section to reflect the unified path (Web UI and cloud both go through `CameraService.applyAndPersist`)
- [X] T043 Update `CLAUDE.md` Security Status table to add a row for "Unified Cameras source of truth (drop CloudCameras drift)" ✅; bump the "Active feature branch" / "Active feature plan" if not already set (already done by /speckit-plan)

---

## Dependencies

```text
Phase 1 (Setup: T001–T003)
        │
        ▼
Phase 2 (Foundational: T004–T010)   ← BLOCKS everything below
        │
        ├──────────────┬──────────────┐
        ▼              ▼              ▼
   Phase 3 (US1)   Phase 4 (US2)   (US3 waits for US2)
   T011–T016       T020–T027
        │              │
        │              ▼
        │         Phase 5 (US3: T030–T033)
        │              │
        └──────┬───────┘
               ▼
        Phase 6 (Polish: T040–T043)
```

- **US1 ↔ US2 independence**: US1 (device info) touches only `DeviceCommandHandler` + `DeviceConstants`; US2 (camera SoT) touches schema + `CameraService` + `CameraRouting` + `CameraCommandHandler`. After Phase 2 they can proceed in parallel.
- **US3 depends on US2** because `resolveStreamId` and the pre-registration loop read from the unified `Cameras` table introduced in Phase 4. (US3 also relies on T008's synchronous `Go2RtcClient.registerStreamSync` from Phase 2.)

---

## Parallel Execution Opportunities

**Within Phase 1**: T002 ∥ T003 (different new files).

**Within Phase 3 (US1)**: T013 ∥ T014 ∥ T015 ∥ T016 (all touch different `DeviceCommandHandler` methods; T011+T012 modify the same `getLanInfo()` and must run sequentially before the rest).

**Within Phase 4 (US2)**: T020, T021, T022 all modify `CameraRouting.kt` — keep sequential. T023, T024, T025, T026, T027 all modify `CameraCommandHandler.kt` — keep sequential. But the `CameraRouting` group can run in parallel with the `CameraCommandHandler` group (different files).

**Within Phase 5 (US3)**: T031 ∥ T032 both touch `SignalingManager.kt` and share `handleLiveOffer` — keep sequential. T030 (CloudService) and T033 (ChannelStateNotifier) are in different files and can run in parallel.

---

## Implementation Strategy

**MVP (User Story 1 + 2 only)** delivers the bulk of the user-visible value: the cloud dashboard sees real MAC/IP and a unified camera list. US3 is a follow-on that makes live view reliable; without it, live view still works for cameras present at startup (same as today).

Suggested cut order:

1. Phase 1 + Phase 2 (one PR — schema + helpers + migration; merge cautiously, validate `data/nvr.db` migration on a copy first per T001)
2. Phase 3 (US1) — small, isolated, low-risk
3. Phase 4 (US2) — bigger change; verify both UIs after each task using a single SQLite query
4. Phase 5 (US3) — small but touches startup ordering; run SC-005 right after
5. Phase 6 — docs + final verification pass

Each phase is independently testable per the spec's Success Criteria. If Phase 4 reveals data corruption, T001's backup restores `data/nvr.db` and the work can be reattempted.
