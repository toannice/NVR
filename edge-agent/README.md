# nvr-edge-agent

Passive L4 provisioning agent for a field **gateway** (Raspberry Pi) on the ZeroTier mesh.
Part of the gateway plan — see [`../4-6-to-do-jobs.md`](../4-6-to-do-jobs.md) (Plan A).

## What it does (and deliberately does NOT)

- **Does:** join ZeroTier on boot → expose a tiny HTTP API → on request, allocate two `nginx stream{}`
  TCP-forward ports per camera (ONVIF→:80, RTSP→:554) and reload nginx → persist state so proxies
  survive reboots.
- **Does NOT:** connect to any NVR, store camera credentials, run ONVIF SOAP, or restream video.
  It is a dumb, shared L4 proxy. **NVRs discover it and drive it** (NVR is the HTTP client). This keeps
  it decoupled from any single NVR (multi-NVR ready) and within the Pi's 256MB / low-CPU budget.

## HTTP API

Auth: `Authorization: Bearer <EDGE_AGENT_TOKEN>` for non-loopback callers; **exempt:** loopback (the
local field page) and the two app endpoints `/identity` + `/label` (the Android onboarding app reaches
them over the field LAN with no fleet token — read-only / cosmetic, Tier 0 design). All camera/proxy
mutation stays behind the fleet token (NVR-only).

| Method | Path | Auth | Body / query | Returns |
|---|---|---|---|---|
| GET | `/health` | token | — | `{status, gatewayId, gatewayName, installer, location, zerotierIp, ztAddress, ztJoined, ztOnline, fieldSubnet, ramMb, uptimeSec, nginxOk, cameras[]}` |
| GET | `/identity` | **none** | — | `{gatewayId, name, installer, location, ztAddress, ztJoined, ztOnline, zerotierIp, fieldSubnet, agentPort, alive, cameraCount}` — app scan + identify |
| POST | `/label` | **none** | `{name?, installer?, location?}` | `{name, installer, location}` — app names the gateway (partial update; persisted) |
| POST | `/discovery` | token | `{}` | `{devices:[{ip,name,hardware,xaddrs}]}` (WS-Discovery on field LAN) |
| POST | `/camera` | token | `{localIp, name}` | `{localIp,name,onvifPort,rtspPort}` (idempotent by localIp; no creds) |
| GET | `/cameras` | token | — | `{cameras:[...]}` |
| DELETE | `/camera` | token | `?localIp=…` | `{deleted:bool}` |
| GET | `/` | loopback | — | field web page (scan + add, no credential input) |

`ztAddress` = this node's 10-hex ZeroTier address (from `zerotier-cli info`) — what the admin approves
on ztncui. `ztJoined` = joined the fleet network with an OK config; `ztOnline` = reached planet roots.
A label set via `POST /label` (app) overrides `EDGE_AGENT_NAME` in both `/identity` and `/health`.

The NVR builds endpoints from the response: ONVIF `http://<zerotierIp>:<onvifPort>/onvif/device_service`,
RTSP `rtsp://<creds typed at NVR>@<zerotierIp>:<rtspPort>/<path>`.

## Build & install

```bash
# on dev machine (Go installed):
./build.sh                 # linux/arm64 (Pi 3/4 64-bit). ARCH=arm ./build.sh for 32-bit Debian.
scp -r ../edge-agent pi@<pi>:~/                 # copy dir incl. the built binary
# on the Pi:
sudo ./install.sh
sudo nano /etc/nvr-edge-agent/agent.env         # set EDGE_AGENT_TOKEN + ZT_NETWORK_ID
sudo systemctl restart nvr-edge-agent
# then authorize the Pi as a member on the self-host controller (ztncui), out-of-band
curl -s localhost:9797/health | head
```

Requires `nginx-full` (ships `ngx_stream`); `install.sh` installs it and wires the
`stream { include /etc/nginx/stream.d/*.conf; }` block into `nginx.conf`.

## Config

All via env (`/etc/nvr-edge-agent/agent.env`) — see [`agent.env.example`](agent.env.example).
Key ones: `EDGE_AGENT_TOKEN` (shared fleet secret, must match every NVR), `ZT_NETWORK_ID`,
`EDGE_AGENT_NAME`, `EDGE_AGENT_FIELD_SUBNET`. Reserved ports 1984/8554/8555 are never allocated.
