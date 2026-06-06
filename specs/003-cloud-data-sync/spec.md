# Feature Specification: Cloud ↔ Local Data Sync (Device Info, Camera CRUD, Live View)

**Feature Branch**: `003-cloud-data-sync`
**Created**: 2026-05-29
**Status**: Draft
**Input**: Build on 002-cloud-connection. Today only the device serial is pushed to the AIView cloud server and the server replies with maxChannel. The cloud needs more identity/network info (MAC, ethernet, model, firmware, storage). Camera CRUD operations issued from the cloud dashboard and from the local Web UI currently write to two different stores and drift apart — they must share a single source of truth. Live camera view via cloud must work reliably end-to-end.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Cloud Sees Real Device Identity & Network (Priority: P1)

When the AIView cloud dashboard opens the device detail panel, it sees the device's true MAC address and LAN IPv4 settings (address, subnet mask, gateway) as reported by the host operating system. Model name, firmware version, storage capacity, and similar non-network metadata may show placeholder values (the device is a software NVR appliance with no fixed firmware), but they must be stable and consistent across requests.

**Why this priority**: The cloud server uses MAC and IP to identify and locate the device on the network. Today these are mocked or missing, so the dashboard cannot reliably distinguish or reach devices.

**Independent Test**: Open the device detail panel on the AIView cloud dashboard and confirm that the MAC and IPv4 address match what `ip addr` shows on the host LAN interface.

**Acceptance Scenarios**:

1. **Given** the NVR is connected to cloud, **When** the cloud requests device ethernet info, **Then** the response contains the real MAC address and IPv4 address of the host's primary LAN interface (not `00:00:00:00:00:00` and not `0.0.0.0`)
2. **Given** the NVR is connected to cloud, **When** the cloud requests device base info, **Then** the response contains the same serial number the admin configured locally plus stable placeholder values for model, firmware, and product number
3. **Given** the cloud requests device info repeatedly within one minute, **When** responses arrive, **Then** MAC, IP, and serial values are identical across requests
4. **Given** the host has multiple network interfaces (eth0 + docker0 + virtual bridges), **When** ethernet info is requested, **Then** the response picks the physical LAN interface and ignores docker/bridge/virtual interfaces
5. **Given** storage info is requested, **When** the response is built, **Then** it contains placeholder values (e.g., totalSpace = 1 GB stub) sufficient for the dashboard to render without crashing

---

### User Story 2 — Single Source of Truth for Cameras (Priority: P1)

An admin adds a camera from the local Web UI; within seconds the same camera appears on the AIView cloud dashboard channel list. The reverse also holds: a camera added from the cloud dashboard appears in the local Web UI camera grid, with the same name, IP, and credentials. Editing or deleting from either side updates both sides.

**Why this priority**: Today the cloud dashboard and the Web UI maintain separate camera lists that drift apart. Operators cannot trust either view, and the same camera can be added twice (once from each side) producing duplicate streams.

**Independent Test**: Add Camera A from the local Web UI → verify it appears in the cloud channel list within 10 seconds. Add Camera B from the cloud dashboard → verify it appears in the local Web UI within 10 seconds. Delete Camera A from cloud → verify it disappears from the Web UI.

**Acceptance Scenarios**:

1. **Given** the local Web UI camera list is empty, **When** admin adds a camera locally and then the cloud requests `get_channels`, **Then** the cloud response contains that camera with the same IP, port, credentials, name, and tokens used locally
2. **Given** the cloud sends `post_channels` for a new camera, **When** processing completes, **Then** the camera appears in the local Web UI list, the same row backs both the cloud response and the local UI, and Frigate's `config.yml` contains the new camera entry
3. **Given** the cloud sends `del_channels`, **When** processing completes, **Then** the camera is removed from the local Web UI list, removed from `config.yml`, and the go2rtc stream is unregistered
4. **Given** a camera is edited from the local Web UI (e.g., name change), **When** the cloud next requests `get_channels`, **Then** the updated name is returned
5. **Given** a camera exists in the local Web UI, **When** the cloud sends `put_channels` with new credentials, **Then** the local Web UI shows the updated credentials and Frigate uses the new RTSP URL

---

### User Story 3 — Reliable Live View From the Cloud (Priority: P2)

A remote operator clicks a camera tile on the AIView cloud dashboard. Within a few seconds, live video begins playing in the dashboard. If the operator switches to another camera, the stream switches without leaving the previous one consuming resources. Cameras added in either UI become viewable from the cloud immediately, without restarting the NVR.

**Why this priority**: Live streaming is the headline feature of the cloud connection. It already works in 002 for cameras that were present at startup, but newly added cameras often fail to play because the WebRTC stream was never registered or because go2rtc lost state after a restart.

**Independent Test**: Add a new camera from the cloud dashboard, click "Live" on that camera within 30 seconds — video must play. Restart only the `nvr-wrapper` container, then click Live on any pre-existing camera — video must play within 10 seconds.

**Acceptance Scenarios**:

1. **Given** a camera is added via either UI, **When** a LIVE WebRTC offer arrives for that camera within the same minute, **Then** the stream plays in the cloud dashboard
2. **Given** the `nvr-wrapper` container is restarted, **When** the cloud requests a LIVE stream for an existing camera, **Then** the stream plays within 10 seconds (streams are re-registered before the first offer can arrive)
3. **Given** a camera is deleted, **When** the cloud sends a LIVE offer for that camera's token, **Then** the system returns a clear error response (not a hang or a silent drop) and the cloud dashboard shows a "not available" state
4. **Given** two operators open LIVE streams for two different cameras simultaneously, **When** both sessions are active, **Then** both streams play without interfering and the device remains marked online by the heartbeat

---

### Edge Cases

- What happens when the host has no physical LAN interface up (only loopback or Wi-Fi)? → Return the best available non-loopback, non-virtual IPv4 interface; MAC is whatever that interface reports
- What happens when both the cloud and the Web UI add the same camera (same IP) at the same moment? → A file/db mutex serializes the writes; the second one becomes an update (idempotent upsert), not a duplicate
- What happens when the cloud `del_channels` references a camera that the Web UI just deleted? → Return success (idempotent) with no error
- What happens when the local Web UI uses a slug as the camera ID and the cloud uses a numeric channelID? → The system maintains a deterministic mapping between slug and channelID so both UIs reference the same row
- What happens when MAC detection on the host fails entirely (uncommon container/network setup)? → Return an empty string for MAC rather than `00:00:00:00:00:00`, and log a warning
- What happens when a LIVE offer arrives for a token that does not match any camera? → Return error code `E0007` ("Camera not found") in the WebRTC answer envelope so the dashboard can show a clear failure
- What happens when go2rtc has not registered a newly added camera yet and a LIVE offer arrives immediately? → Camera-add path registers the stream synchronously and only completes after go2rtc confirms registration; LIVE offers after that always find the stream

---

## Requirements *(mandatory)*

### Functional Requirements

**Device Identity & Network Info**

- **FR-001**: When the cloud requests device ethernet info, the system MUST return the real MAC address of the host's primary LAN interface
- **FR-002**: When the cloud requests device ethernet info, the system MUST return the real IPv4 address, subnet mask, and gateway of the host's primary LAN interface
- **FR-003**: Primary LAN interface selection MUST exclude loopback, docker/bridge (`docker*`, `br-*`, `veth*`), and virtual interfaces, and MUST prefer interfaces whose names start with `eth`, `en`, or `wl` and that have a private LAN IPv4 address
- **FR-004**: When the cloud requests device base info, the system MUST return the locally configured serial number plus stable placeholder values for `model`, `firmware_version`, `product_number`, `manufacturer`, `web_version`, and `number_of_channel` (e.g., model `E120-FCWP`, firmware `1.0.3.2`, channel count 16)
- **FR-005**: When the cloud requests storage info, the system MUST return at least one storage entry with placeholder capacity values and a memory block with placeholder values, in the field shape the cloud dashboard expects (`totalSpace`, `freeSpace`, `usedSpace`, `diskState`, `systemPartition`, `type`)
- **FR-006**: When the cloud requests Wi-Fi info, the system MUST return a stub response indicating Wi-Fi is not enabled (the device is wired-only)
- **FR-007**: When the cloud requests time, the system MUST return the host's current UTC time in ISO-8601 format with second precision
- **FR-008**: Across repeated requests within the same connection, device identity and network values MUST be stable (no random or per-request variation in MAC, IP, serial, or placeholder fields)

**Camera Source of Truth**

- **FR-010**: The system MUST store cameras in a single shared table that both the local Web UI and the cloud command handlers read from and write to (the separate `CloudCameras` table MUST be eliminated and its contents migrated)
- **FR-011**: Camera records MUST carry all fields required by both the local Web UI (slug, display name, IP, port, ONVIF port, manufacturer, model, PTZ flag, credentials) and the cloud protocol (channelID, mainToken, subToken, RTSP URL, MAC)
- **FR-012**: A deterministic mapping between the local slug and the cloud `channelID` MUST exist so that the same camera is identifiable from either side; new cameras MUST receive both at creation time
- **FR-013**: Every camera create/update/delete (whether originating from the Web UI or from cloud `post_channels`/`put_channels`/`del_channels`) MUST update the shared table, update Frigate's `config/config.yml`, and update go2rtc stream registration in a single critical section guarded by the existing config mutex
- **FR-014**: After any successful create/update, the system MUST request Frigate to reload its configuration and MUST report success or failure of the reload back to the originator (Web UI toast or cloud response)
- **FR-015**: Cloud `get_channels` MUST read the same shared table and MUST return every camera the local Web UI shows, with credentials, name, IP, port, and tokens consistent with the local view
- **FR-016**: `post_channels` and `put_channels` MUST be idempotent — repeating the same call for the same channelID MUST NOT create duplicate rows or duplicate Frigate entries
- **FR-017**: `del_channels` MUST be idempotent — deleting an already-deleted camera MUST return success without error
- **FR-018**: When the cloud or Web UI deletes a camera, the corresponding go2rtc stream registration MUST be removed

**Live Streaming Reliability**

- **FR-020**: When a camera is added (via either UI), the go2rtc main stream MUST be registered synchronously as part of the add operation, so the camera is immediately available for cloud LIVE offers
- **FR-021**: On startup of the `nvr-wrapper` container, the system MUST register every camera's go2rtc stream before connecting to MQTT, so the first incoming LIVE offer always finds its stream
- **FR-022**: When a LIVE offer arrives for a token that does not resolve to any camera, the system MUST publish a WebRTC answer envelope carrying error code `E0007` and a short reason, instead of dropping the offer silently
- **FR-023**: Live streaming MUST continue to use go2rtc WebRTC SDP exchange (`POST http://127.0.0.1:1984/api/webrtc?src=<streamId>`) as in 002 — no new transport is introduced in this feature
- **FR-024**: At least two concurrent LIVE sessions for two different cameras MUST be servable without one degrading the other

**Heartbeat**

- **FR-030**: The 5-second `notify_channels_state` heartbeat MUST continue reporting only camera connection statuses (no device info), as established in 002

### Key Entities

- **Camera (unified)**: One row per camera, holding everything both the local Web UI and the cloud need — local slug, cloud channelID, display name, IP, ONVIF port, RTSP port, credentials, manufacturer, model, MAC, PTZ flag, mainToken, subToken, RTSP URL
- **DeviceIdentity**: Set of values returned to the cloud for the device itself — real MAC and IPv4 detected from the host; placeholder model, firmware, product number, web version, channel count; serial from cloud config
- **NetworkInterfaceSnapshot**: Transient — for one cloud request, the chosen LAN interface's name, MAC, IPv4 address, subnet mask, derived gateway
- **StorageSnapshot**: Stub — one disk entry plus one memory block with placeholder values in the field shape the cloud dashboard expects

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On the AIView cloud dashboard, the device's MAC address matches `ip link show` on the host, and the IPv4 address matches `ip -4 addr show` for the primary LAN interface, in 100% of observations
- **SC-002**: A camera added from the local Web UI appears in the cloud dashboard's channel list within 10 seconds, and vice versa, in 100% of observations
- **SC-003**: After deleting a camera from either UI, the camera is gone from both views and from `config/config.yml` within 10 seconds
- **SC-004**: A camera added from either UI is viewable as a LIVE stream from the cloud dashboard within 30 seconds of being added (no NVR restart required), in at least 95% of attempts
- **SC-005**: After restarting only the `nvr-wrapper` container, any existing camera's LIVE stream plays from the cloud dashboard within 10 seconds of the operator clicking it
- **SC-006**: Two operators viewing two different cameras simultaneously through the cloud see both feeds play without one disconnecting the other, for sessions lasting at least 5 minutes
- **SC-007**: The device remains continuously marked "online" on the cloud dashboard while `nvr-wrapper` is running (no spurious offline transitions caused by malformed heartbeat or device-info responses)

---

## Assumptions

- The host runs Ubuntu with a single primary wired LAN interface (eth0 / enp*); Wi-Fi is treated as non-existent for cloud reporting
- Model name, firmware version, product number, and storage values are stable placeholders chosen to satisfy the cloud dashboard's parser — the my-nvr appliance has no fixed firmware version and the cloud is not expected to drive firmware upgrades from this data
- Local Web UI camera slugs and cloud channelIDs are both small strings; a one-to-one mapping (e.g., slug = `cam_<channelID>` or channelID derived from slug) is acceptable as long as it is stable per row
- The existing `Cameras` table from the local Web UI is the surviving table; `CloudCameras` is migrated and dropped. Existing rows in `CloudCameras` at upgrade time are merged into `Cameras` (matched by IP+port; new ones inserted)
- go2rtc continues to run inside the Frigate container on `127.0.0.1:1984` and is reachable from `nvr-wrapper` (Frigate exposes it on host loopback in `network_mode: host`)
- ONVIF probing on add/verify continues to use the existing local ONVIF client (`OnvifSoapClient`) with a 5–10 s timeout
- The shared config mutex defined in `CameraRouting.kt` (002) is reused to serialize all `config.yml` and go2rtc updates regardless of origin (Web UI or cloud)
- Frigate reload via `POST http://localhost:5000/api/restart` after a camera change is in scope for this feature, but optimizing reload time is not
- WebRTC DATA channel and dc-bridge already work as built in 002 — no changes required here
- The AIView cloud dashboard is the only consumer of these endpoints; no other clients depend on the response shapes
