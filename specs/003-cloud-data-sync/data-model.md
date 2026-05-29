# Data Model: Cloud ↔ Local Data Sync

**Feature**: 003-cloud-data-sync
**Date**: 2026-05-29

---

## 1. Unified `Cameras` Table

Single source of truth for both the local Web UI and the cloud command handlers.

| Column                | Type           | Default     | Notes                                                          |
| --------------------- | -------------- | ----------- | -------------------------------------------------------------- |
| `slug`                | varchar(255)   | —           | PK. Human ID used by Web UI and Frigate `config.yml`.          |
| `display_name`        | varchar(255)   | —           | Shown in both UIs.                                             |
| `ip`                  | varchar(50)    | NULL        | Camera LAN IP.                                                 |
| `onvif_port`          | int            | 80          | ONVIF service port.                                            |
| `manufacturer`        | varchar(100)   | `"Unknown"` |                                                                |
| `model`               | varchar(100)   | `"Unknown"` |                                                                |
| `ptz_enabled`         | bool           | false       | UI flag.                                                       |
| `created_at`          | datetime       | now         |                                                                |
| `updated_at`          | datetime       | now         |                                                                |
| **NEW**               |                |             |                                                                |
| `channel_id`          | varchar(50)    | NULL        | Cloud protocol ID (numeric string). Unique when not null.      |
| `username`            | varchar(255)   | NULL        | Camera ONVIF/RTSP user.                                        |
| `password_encrypted`  | text           | NULL        | AES-GCM, key from `data/.device_key` (002 helper).             |
| `main_token`          | varchar(255)   | `""`        | Real ONVIF main-profile token from probe.                      |
| `sub_token`           | varchar(255)   | `""`        | Sub token (derived if not from ONVIF).                         |
| `rtsp_url`            | text           | `""`        | Resolved main-stream RTSP URL.                                 |
| `sub_rtsp_url`        | text           | NULL        |                                                                |
| `mac_address`         | varchar(50)    | NULL        | Camera MAC if known.                                           |
| `has_ptz`             | bool           | false       | Cloud-side PTZ capability (kept in sync with `ptz_enabled`).   |

**Invariants**:

- `slug` is immutable after insert.
- `channel_id`, once assigned, is immutable; it is unique across all non-null rows.
- A row may exist with `channel_id = NULL` (camera created by Web UI before cloud ever asked for it). On the first cloud `get_channels`, all NULL channel_ids are assigned the next free integer in a single transaction.

---

## 2. Mapping rule (slug ↔ channelID)

| Origin of camera         | `slug` assignment            | `channel_id` assignment                                                |
| ------------------------ | ---------------------------- | ---------------------------------------------------------------------- |
| Local Web UI add         | Provided by user (validated) | NULL initially; assigned on first cloud `get_channels` (lowest free)   |
| Cloud `post_channels`    | `"cam_${channel_id}"`        | From the message body (e.g., `"1"`)                                    |
| Cloud `put_channels`     | Lookup by `channel_id`       | unchanged                                                              |

---

## 3. Migration from `cloud_cameras`

Executed once during `Databases.createMissingTablesAndColumns()` after the new columns are added:

```text
for row in cloud_cameras:
    match = cameras WHERE ip = row.ip AND onvif_port = row.port
    if match exists:
        update match: channel_id, username, password_encrypted (re-encrypt from plaintext),
                      main_token, sub_token, rtsp_url, sub_rtsp_url, mac_address, has_ptz
    else:
        insert new row:
            slug = "cam_${row.channel_id}",
            display_name = row.name,
            ip = row.ip, onvif_port = row.port,
            channel_id = row.channel_id,
            username = row.username,
            password_encrypted = encrypt(row.password),
            main_token = row.main_token, sub_token = row.sub_token,
            rtsp_url = row.rtsp_url, sub_rtsp_url = row.sub_rtsp_url,
            mac_address = row.mac_address,
            has_ptz = row.has_ptz, ptz_enabled = row.has_ptz
```

`cloud_cameras` is **not** dropped in this feature — left for one release to allow rollback. A follow-up task removes it.

---

## 4. Other entities (unchanged, listed for context)

- `CloudConfig` (002) — domain, port, username, encrypted password, serial, enabled flag. Used unchanged.
- `LockConfig`, `OtpTokens`, `Schedules`, `AIEvents` — untouched.
- `MqttConnectionInfo`, `Session`, `IceServer` (transient runtime objects from 002) — untouched.

---

## 5. Derived runtime values (not persisted)

| Name                          | Source                                                          | Used by                                  |
| ----------------------------- | --------------------------------------------------------------- | ---------------------------------------- |
| `NetworkInterfaceSnapshot`    | `NetworkInterface.getNetworkInterfaces()` filtered per R1       | `DeviceCommandHandler.getEthernet()`     |
| `StorageSnapshot` (placeholder) | Fixed values (totalSpace=1024 MB, freeSpace=512 MB, etc.)     | `DeviceCommandHandler.getStorages()`     |
| Device base info placeholders | Constants (model `E120-FCWP`, firmware `1.0.3.2`, channels=16) | `DeviceCommandHandler.getInformation()`  |
| `go2rtcStreamName`            | `"${channel_id}_main"` for cloud, also for Web UI               | `Go2RtcClient.registerStreamSync`        |
