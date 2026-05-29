# Research: Cloud ↔ Local Data Sync

**Feature**: 003-cloud-data-sync
**Date**: 2026-05-29

---

## R1 — Primary LAN interface picker

**Decision**: Pick the first network interface that is `isUp`, not loopback, not virtual, whose name starts with `eth` / `en` / `wl`, and that holds at least one private-LAN IPv4 (`192.168.x.x`, `10.x.x.x`, `172.16–31.x.x`). Exclude any interface whose name starts with `docker`, `br-`, `veth`, `tailscale`, `tun`, `tap`. Return MAC, IPv4, subnet mask (from the matching `InterfaceAddress.networkPrefixLength`), and a derived gateway (`x.y.z.1` of the IPv4). If no candidate found, return MAC `""` (empty string — not `00:00:…`) and IPv4 `"0.0.0.0"`, and log a single warning.

**Rationale**: The existing `getLanInfo()` in `DeviceCommandHandler.kt` already implements most of this; it just needs (a) the `tailscale`/`tun`/`tap` exclusions because the host runs Tailscale, (b) the empty-MAC convention instead of `00:00:00:00:00:00` so the cloud dashboard does not display a fake MAC.

**Alternatives considered**: Read from `/sys/class/net/<iface>/address` directly — rejected because `NetworkInterface` API is already used and works inside `network_mode: host`.

---

## R2 — Cameras schema migration

**Decision**: Extend the existing `Cameras` table with the columns currently only present on `CloudCameras`: `channel_id` (varchar 50, unique, nullable until assigned), `username` (varchar 255 nullable), `password_encrypted` (text nullable, reuses 002's AES-GCM helper), `main_token` / `sub_token` (varchar 255 default ""), `rtsp_url` (text default ""), `mac_address` (varchar 50 nullable), `has_ptz` (bool default false). Keep `slug` as the primary key.

On startup, `Databases.createMissingTablesAndColumns()` (a) adds the new columns to `cameras` if missing, then (b) runs a one-shot copy: for each row in `cloud_cameras` not yet present in `cameras` (matched by IP+ONVIF port), insert a new `cameras` row with `slug = "cam_${channel_id}"`. For each row present in both, copy the cloud-only fields into the existing cameras row. After the copy, leave `cloud_cameras` in place but unused — drop it in a later cleanup.

**Rationale**: Project already auto-migrates via `createMissingTablesAndColumns` (see CLAUDE.md). Keeping the deprecated table around for one release lets us roll back without data loss.

**Alternatives considered**: SQL rename + ALTER — rejected because Exposed handles add-column cleanly and dropping the table forfeits rollback.

---

## R3 — slug ↔ channelID mapping

**Decision**: When a camera is created from the Web UI, it has a `slug` (e.g., `front_door`). On first cloud `get_channels`, generate `channel_id` = next free integer string starting at `"1"`, persist it on the same row, and return it. When a camera is created from the cloud (`post_channels` carries `channel_id`), generate `slug = "cam_${channel_id}"`. The mapping is one-to-one and never changes.

**Rationale**: The cloud protocol requires numeric string channelIDs (`"1"`, `"2"`, …). The Web UI uses human slugs. Storing both on the same row removes any need for a lookup table.

**Alternatives considered**: Use slug as channelID — rejected because the C# cloud parser expects channelID to look numeric (it uses it as `int.Parse` in places).

---

## R4 — Synchronous go2rtc stream registration on add

**Decision**: Both `CameraRouting.kt#addCamera` and `CameraCommandHandler#postOrPutChannels` call a shared helper `Go2RtcClient.registerStreamSync(streamName, rtspUrl)` that issues `PUT http://127.0.0.1:1984/api/streams?name=…&src=…` and blocks until 200 OK or 5 s timeout. The call sits inside the `configMutex.withLock { … }` block, after the SQLite upsert and the YAML write, before the Frigate restart trigger.

**Rationale**: The existing cloud path already does this for `${channelId}_main`. The Web UI path today relies on Frigate's `go2rtc` config block to register on Frigate reload, which races against an immediately-following LIVE offer. Making registration synchronous on the add path eliminates the race.

**Alternatives considered**: Wait-for-Frigate-restart-then-LIVE — rejected because Frigate restart can take 20+ seconds, far worse UX than a direct go2rtc PUT.

---

## R5 — LIVE offer error envelope

**Decision**: When `SignalingManager.handleLiveOffer` cannot resolve the offer's `extend.src` token to any camera, publish a `signal.webrtc/answer` with `data` base64-encoded JSON `{"type":"answer","sdp":"","error_code":"E0007","message":"Camera not found"}` to the same session pub topic. Add an `error_code` field to the answer envelope (unused by the dashboard today, but the C# parser tolerates extra fields).

**Rationale**: The cloud dashboard's existing failure UX is "spinner until timeout". Returning a definite answer lets it render "Camera unavailable" within a second. Adding `error_code` to the answer envelope matches the convention already used in `CommandDispatcher.errorResponse`.

**Alternatives considered**: Skip the answer entirely — current behavior, rejected because it produces a 30 s spinner on the dashboard.

---

## R6 — Frigate reload trigger consolidation

**Decision**: Keep the Frigate restart call (`POST http://localhost:5000/api/restart`) inside the local `CameraRouting.kt` path only. The cloud `post/put/del_channels` handlers do NOT trigger Frigate restart directly — instead they call into the same `CameraService.applyAndPersist(slug, …)` helper that the Web UI uses, which is responsible for: SQLite upsert → YAML write → go2rtc register → Frigate restart trigger → return `frigateRestarted: true|false`. The cloud response then includes a `frigate_restarted` boolean in its `data` block (additive, ignored by current dashboard, useful for debugging).

**Rationale**: One code path = one source of bugs. Today the cloud handler open-codes its own YAML write and skips the Frigate restart, which is why cloud-added cameras don't always appear in the local Web UI's Frigate live view until the next manual restart.

**Alternatives considered**: Keep two paths and let each restart Frigate independently — rejected because concurrent restarts within the mutex window deadlock the Frigate API.
