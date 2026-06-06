# Quickstart: Cloud ↔ Local Data Sync — Manual Verification

**Feature**: 003-cloud-data-sync
**Maps to**: spec.md Success Criteria SC-001 … SC-007

Run after `docker compose build nvr-wrapper && docker compose up -d --force-recreate --no-deps nvr-wrapper && docker compose up -d nginx`. Assumes 002 cloud connection is already configured and shows "Connected" in the Web UI Cloud tab.

---

## SC-001 — Real MAC and IPv4 on cloud dashboard

```bash
# On host
ip -4 addr show | awk '/inet 192\.168/ {print $2}' | head -1
ip link show | awk '/link\/ether/ {print $2}' | head -1
```

Open AIView cloud dashboard → device detail panel → confirm the `MAC Address` and `IP Address` fields match the host values. Refresh three times — values must be stable.

## SC-002 — Add from either UI, see in both

**Web UI → Cloud**:

1. Web UI → Cameras → Add Camera (IP `192.168.2.50`, user/pass, ONVIF probe).
2. Within 10 s, refresh the cloud dashboard channel list — camera appears with the same name and IP.

**Cloud → Web UI**:

1. From the cloud dashboard, add a camera (same fields).
2. Within 10 s, refresh the Web UI Cameras page — new camera appears with the same name/IP.

```bash
# Optional: confirm a single row in the unified table
docker exec nvr-wrapper sqlite3 /app/data/nvr.db \
  "SELECT slug, channel_id, ip, display_name FROM cameras ORDER BY rowid;"
```

## SC-003 — Delete from either UI removes everywhere

1. Delete the camera added above from the Web UI.
2. Within 10 s the cloud dashboard channel list no longer shows it.
3. `grep '<camera_slug>:' config/config.yml` returns nothing.
4. `curl http://localhost:1984/api/streams | jq` no longer lists the `<channel_id>_main` stream.

## SC-004 — Newly added camera viewable from cloud within 30 s

1. Add a camera from the cloud dashboard.
2. Without restarting anything, click its Live tile within 30 s.
3. Video plays. (Repeat 20 times to validate the ≥95% threshold.)

## SC-005 — LIVE works after `nvr-wrapper` restart

```bash
docker compose restart nvr-wrapper
docker compose up -d nginx
```

Wait for the Cloud tab to show "Connected". Click Live on any pre-existing camera in the cloud dashboard — first frame within 10 s.

## SC-006 — Two concurrent viewers, two cameras

Open two browsers logged into the cloud dashboard. Each opens a Live stream for a different camera. Both must play continuously for 5 minutes without one dropping.

## SC-007 — Device stays "online" continuously

Watch the cloud dashboard device list for 10 minutes while running through SC-001–SC-006. The device must remain online — no flicker, no offline transitions.

---

## Negative test — unknown LIVE token returns error fast

Manually send a LIVE offer with a fake token via the staging cloud's debug tool (or by editing one in DevTools before send). The dashboard must show "Camera unavailable" within ~2 s instead of spinning until timeout.
