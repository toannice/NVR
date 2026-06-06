# Gateway Setup — Edge Camera Provisioning (4-6 Plan A)

How a field **gateway** (Raspberry Pi) lets one or more NVRs reach cameras on a *different* network,
fully auto and with the Pi staying a dumb, low-RAM L4 proxy.

> Companion to [`connection_to_server.md`](./connection_to_server.md) (cloud link) and
> [`4-6-to-do-jobs.md`](./4-6-to-do-jobs.md) (the plan). Agent code: [`edge-agent/`](./edge-agent/).

## Mental model

```
Field LAN (192.168.2.x)            ZeroTier mesh (10.34.24.x)         NVR(s) (any network)
  camera 192.168.2.64  ─┐                                              ┌─ NVR #1
  camera 192.168.2.65  ─┤   Raspberry Pi gateway (passive)            │
                        └─► nvr-edge-agent + nginx stream{}  ◄────────┤  NVR #2 (auto-scans ZT,
                            per camera: ZTip:onvifPort→:80            │   approves, adopts)
                                        ZTip:rtspPort →:554          └─ …
```

- **Gateway is passive**: HTTP *server* only. It never connects to an NVR, never stores camera
  credentials, never restreams video. It just forwards TCP. Multiple NVRs can use the same gateway.
- **NVR drives everything**: auto-scans the ZeroTier subnet, you approve the gateway, then adopt a
  camera by typing its ONVIF user/pass once. The NVR does ONVIF SOAP over the proxy, rewrites the
  RTSP host to the gateway, auto-detects PTZ, and writes Frigate/go2rtc config.

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
   sudo ./edge-agent/install.sh        # installs nginx-full, wires stream{} include, systemd unit
   ```
3. **Configure** `/etc/nvr-edge-agent/agent.env`:
   ```ini
   EDGE_AGENT_TOKEN=<paste the fleet token printed during NVR setup — MUST match the NVR>
   EDGE_AGENT_NAME=gateway-nha-A
   ZT_NETWORK_ID=<your ZeroTier network id>
   EDGE_AGENT_FIELD_SUBNET=192.168.2.0/24
   ```
   ```bash
   sudo systemctl restart nvr-edge-agent
   ```
4. **Authorize the Pi in ZeroTier Central** (manual, out-of-band): https://my.zerotier.com → your
   network → tick `Authorized` for the new member. (Deliberately not automated — keeps the gateway
   decoupled from any NVR.)
5. **Verify**:
   ```bash
   systemctl status nvr-edge-agent
   curl -s localhost:9797/health | head        # on the Pi (loopback, no token needed)
   ```

Field workers can also open `http://<pi-zerotier-ip>:9797/` (or on the Pi `http://localhost:9797/`)
to scan + create proxies themselves — no credentials required there; the NVR adopts afterwards.

## Part B — NVR (one-time)

The fleet token lives in `secrets/edge_agent_token` (created automatically; gitignored). It must be
the **same value** on every NVR and every Pi. To see / set it:
```bash
cat secrets/edge_agent_token          # the value to paste into each Pi's agent.env
# to rotate: printf '%s' "<new-token>" > secrets/edge_agent_token   (then update every Pi)
```

`.env` (gitignored) controls the auto-scan:
```ini
ZT_SCAN_SUBNET=10.34.24.0/24          # the ZeroTier subnet to sweep; empty disables auto-scan
EDGE_AGENT_PORT=9797
GATEWAY_SCAN_INTERVAL_SEC=30
```

Deploy:
```bash
docker compose build --no-cache nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx
sqlite3 data/nvr.db '.schema gateways'        # confirm the table exists
```
(`nvr-wrapper` runs `network_mode: host`, so it reaches ZeroTier IPs directly.)

## Part C — Daily flow (Web UI → tab "Gateway")

1. Power the Pi → it joins ZeroTier → you authorize it in ZeroTier Central.
2. In the NVR Web UI open **Gateway** → the Pi appears as **Chờ duyệt** within ~30s → **Phê duyệt**.
3. **Thêm camera** → **Quét LAN hiện trường** (or **Camera đã sẵn sàng** if the field worker already
   created proxies) → for each camera type a **name + ONVIF username/password** → **Thêm**.
4. The camera now shows up in Live View / Playback / PTZ like any other camera.

## How it maps to data

For a gateway camera the NVR stores: `cameras.ip = <gateway ZeroTier IP>`,
`cameras.onvif_port = <assigned ONVIF proxy port>`, `cameras.assigned_rtsp_port`,
`cameras.gateway_id`, `cameras.local_ip`. Because `ip`/`onvif_port` are populated, **all existing
ONVIF/PTZ/live-view code works unchanged** — the gateway layer only computes those values.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Gateway never appears in NVR | `ZT_SCAN_SUBNET` empty/wrong; Pi not authorized in ZeroTier Central; token mismatch. Check `docker logs nvr-wrapper \| grep GATEWAY`. |
| `curl ZTip:9797/health` 401 from NVR host | `EDGE_AGENT_TOKEN` on the Pi ≠ `secrets/edge_agent_token` on the NVR. |
| Adopt fails "agent did not allocate ports" | nginx not installed / `nginx -t` failing on the Pi. `nginx -t` on the Pi; ensure `stream{}` include present. |
| Live view black for gateway camera | camera offline on field LAN, or RTSP not TCP. go2rtc uses `#tcp` already; check the camera is reachable from the Pi: `curl <localIp>:554`. |
| PTZ d-pad missing on a real PTZ cam | known false-negative for cams that reject ONVIF `ContinuousMove` (e.g. OSSD2MPVF33XPTZAI). Set **Kiểu PTZ** manually in Edit Camera. See `4-6-to-do-jobs.md` risks. |
| Proxies gone after Pi reboot | should auto-restore from `/var/lib/nvr-edge-agent/state.json`; check `systemctl status nvr-edge-agent` and `nginx -t`. |
