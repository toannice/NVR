# Gateway Setup — Edge Camera Provisioning (4-6 Plan A)

How a field **gateway** (Raspberry Pi) lets one or more NVRs reach cameras on a *different* network,
fully auto and with the Pi staying a dumb, low-RAM L4 proxy.

> Companion to [`connection_to_server.md`](./connection_to_server.md) (cloud link) and
> [`4-6-to-do-jobs.md`](./legacy/4-6-to-do-jobs.md) (the plan). Agent code: [`edge-agent/`](./edge-agent/).

## Mental model

```
Field LAN (192.168.2.x)            ZeroTier mesh (10.147.20.x)        NVR(s) (any network)
  camera 192.168.2.64  ─┐                                              ┌─ NVR #1
  camera 192.168.2.65  ─┤   Raspberry Pi gateway (passive)            │
                        └─► nvr-edge-agent + nginx stream{}  ◄────────┤  NVR #2 (auto-scans ZT,
                            per camera: ZTip:onvifPort→:80            │   authorizes, adopts)
                                        ZTip:rtspPort →:554          └─ …
```

- **Gateway is passive**: HTTP *server* only. It never connects to an NVR, never stores camera
  credentials, never restreams video. It just forwards TCP. Multiple NVRs can use the same gateway.
- **NVR drives everything (Plan A)**: the NVR talks to the **self-host ZeroTier controller** (HQ box,
  net `efb242bde2f3e856` = `nvr-fleet`, pool `10.147.20.0/24`). When a new Pi powers on and joins, it
  shows up as a **pending member**; the admin clicks **Cấp phép** in the NVR Web UI and the NVR
  authorizes it on the controller via REST API — **no ZeroTier Central / ztncui step**. The NVR then
  auto-scans the overlay subnet, adopts a camera by typing its ONVIF user/pass once, does ONVIF SOAP
  over the proxy, rewrites the RTSP host to the gateway, auto-detects PTZ, and writes Frigate/go2rtc.

## Part A — Raspberry Pi (one-time, per gateway)

1. **Build the agent** (on your dev machine, Go installed):
   ```bash
   cd edge-agent
   ./build.sh                 # linux/arm64 (Pi 3/4 64-bit). ARCH=arm ./build.sh for 32-bit Debian.
   ```
2. **Copy to the Pi and install**:
   ```bash
   scp -r edge-agent pi@<pi-host>:~/
   ssh pi@<pi-host>
   sudo ./edge-agent/install.sh        # installs ZeroTier + nginx-full, wires stream{}, systemd unit
   ```
3. **Configure** `/etc/nvr-edge-agent/agent.env` — set only the fleet token; **leave the network id
   empty** (cách A: one generic image, the region is chosen later from the app via `/enroll`):
   ```ini
   EDGE_AGENT_TOKEN=<paste the fleet token printed during NVR setup — MUST match the NVR>
   EDGE_AGENT_NAME=gateway-nha-A           # optional; default = hostname
   ZT_NETWORK_ID=                          # LEAVE EMPTY — the app enrolls the region per-gateway
   EDGE_AGENT_FIELD_SUBNET=192.168.2.0/24
   ```
   ```bash
   sudo systemctl restart nvr-edge-agent
   ```
4. **That's it on the Pi — no manual ZeroTier authorize, no per-region image.** The agent waits to be
   enrolled into a region. The field worker does that from the app (Part C, step 1): the agent runs
   `zerotier-cli join <id>` (retried in the background until the `zerotier-one` API is ready) and
   persists the id so reboots re-join the right region. The Pi then appears as a **pending member**;
   the admin **Cấp phép** from the NVR Web UI. You never open ZeroTier Central or ztncui.
5. **Verify**:
   ```bash
   systemctl status nvr-edge-agent
   curl -s localhost:9797/health | head        # on the Pi (loopback, no token needed) — networkId is "" until enrolled
   ```

Field workers can also open `http://<pi-zerotier-ip>:9797/` (or on the Pi `http://localhost:9797/`)
to scan + create proxies themselves — no credentials required there; the NVR adopts afterwards.

### Multi-region (cách A: one controller, many networks)

One self-host controller hosts **one ZeroTier network per region** (each id = the controller's
10-hex node id + a 6-hex network number, e.g. `efb242bde2f3e856`, `efb242bde2f3e857`, …). The same
generic Pi image is used everywhere; the app points each gateway at its region's network id. On the
NVR side, each region's NVR uses the **same** `ZT_CONTROLLER_URL` but its own `ZT_NETWORK_ID` +
`ZT_SCAN_SUBNET`. Region isolation is enforced by ZeroTier membership — an NVR only reaches gateways
on its own network. The network id is **not a secret** (a node still needs admin "Cấp phép" before
it can do anything), so handing the field worker the id over chat/SMS is fine.

## Part B — NVR (one-time)

The fleet token lives in `secrets/edge_agent_token` (created automatically; gitignored). It must be
the **same value** on every NVR and every Pi. To see / set it:
```bash
cat secrets/edge_agent_token          # the value to paste into each Pi's agent.env
# to rotate: printf '%s' "<new-token>" > secrets/edge_agent_token   (then update every Pi)
```

`.env` (gitignored) controls the auto-scan **and** the Plan A self-authorize:
```ini
ZT_SCAN_SUBNET=10.147.20.0/24         # the ZeroTier subnet to sweep; empty disables auto-scan
EDGE_AGENT_PORT=9797
GATEWAY_SCAN_INTERVAL_SEC=30
ZT_CONTROLLER_URL=http://192.168.2.106:9993   # self-host controller REST API (HQ box `truong`)
ZT_NETWORK_ID=efb242bde2f3e856        # nvr-fleet network the NVR authorizes members on
```

The controller auth token lives in `secrets/zt_controller_token` (gitignored). It is the controller's
`/var/lib/zerotier-one/authtoken.secret`. **Without `ZT_CONTROLLER_URL` + this secret the Web UI hides
the "Chờ cấp phép" section** (Plan A goes dormant; you'd be back to authorizing manually). One-time on
the controller box: `/var/lib/zerotier-one/local.conf` must allow remote management, otherwise every
call returns 401 regardless of token:
```json
{"settings":{"allowManagementFrom":["127.0.0.1","192.168.2.0/24","10.147.20.0/24"]}}
```
(then `systemctl restart zerotier-one` on the controller).

Deploy:
```bash
docker compose build --no-cache nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx
sqlite3 data/nvr.db '.schema gateways'        # confirm the table exists
```
(`nvr-wrapper` runs `network_mode: host`, so it reaches ZeroTier IPs directly.)

## Part C — Daily flow

**Step 1 — Field worker, on the Android app (same field LAN as the Pi):**
1. Power the Pi + LAN (+ camera). Open the app → **Quét** → the gateway appears (networkId blank).
2. Open it → **Thêm gateway này** → in **Khu vực (ZeroTier)** type the 16-hex **Network ID** the admin
   gave for this region → **Kết nối khu vực**. The agent joins that network (POST `/enroll`, persisted)
   and replies "đã gửi yêu cầu, chờ admin cấp phép". (Optionally also name the gateway below.)

**Step 2 — Admin, on the NVR Web UI → tab "Gateway":**
3. The Pi appears in the **"Chờ cấp phép"** (amber) section. Click **Cấp phép** → the NVR authorizes
   the member on the controller (assigns a `10.147.20.x` IP). This is **one-click**: when the scanner
   then discovers that IP it inserts the gateway as already **approved** (no separate "Phê duyệt" step
   — logged `DISCOVERED_AUTOAPPROVED`). Gateway goes online within ~30s.
4. **Thêm camera** → **Quét LAN hiện trường** (or **Camera đã sẵn sàng** if the field worker already
   created proxies) → for each camera type a **name + ONVIF username/password** → **Thêm**.
5. The camera now shows up in Live View / Playback / PTZ like any other camera.

## How it maps to data

For a gateway camera the NVR stores: `cameras.ip = <gateway ZeroTier IP>`,
`cameras.onvif_port = <assigned ONVIF proxy port>`, `cameras.assigned_rtsp_port`,
`cameras.gateway_id`, `cameras.local_ip`. Because `ip`/`onvif_port` are populated, **all existing
ONVIF/PTZ/live-view code works unchanged** — the gateway layer only computes those values.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Pi never shows in "Chờ cấp phép" | Pi didn't join ZeroTier (check `zerotier-cli listnetworks` on the Pi — should list `efb242bde2f3e856`); or `ZT_CONTROLLER_URL`/`secrets/zt_controller_token` unset on the NVR (section is hidden); or controller `local.conf` doesn't allow the NVR's IP (401). Check `docker logs nvr-wrapper \| grep -i gateway`. |
| Gateway never appears (after Cấp phép) | `ZT_SCAN_SUBNET` empty/wrong; token mismatch (`EDGE_AGENT_TOKEN` ≠ `secrets/edge_agent_token`). Check `docker logs nvr-wrapper \| grep GATEWAY`. |
| `curl ZTip:9797/health` 401 from NVR host | `EDGE_AGENT_TOKEN` on the Pi ≠ `secrets/edge_agent_token` on the NVR. |
| Adopt fails "agent did not allocate ports" | nginx not installed / `nginx -t` failing on the Pi. `nginx -t` on the Pi; ensure `stream{}` include present. |
| Live view black for gateway camera | camera offline on field LAN, or RTSP not TCP. go2rtc uses `#tcp` already; check the camera is reachable from the Pi: `curl <localIp>:554`. |
| PTZ d-pad missing on a real PTZ cam | known false-negative for cams that reject ONVIF `ContinuousMove` (e.g. OSSD2MPVF33XPTZAI). Set **Kiểu PTZ** manually in Edit Camera. See `legacy/4-6-to-do-jobs.md` risks. |
| Proxies gone after Pi reboot | should auto-restore from `/var/lib/nvr-edge-agent/state.json`; check `systemctl status nvr-edge-agent` and `nginx -t`. |
