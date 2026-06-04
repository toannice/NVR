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

Auth: `Authorization: Bearer <EDGE_AGENT_TOKEN>` for non-loopback callers; loopback (the local field
page) is exempt.

| Method | Path | Body / query | Returns |
|---|---|---|---|
| GET | `/health` | — | `{status, gatewayId, gatewayName, zerotierIp, fieldSubnet, ramMb, uptimeSec, nginxOk, cameras[]}` |
| POST | `/discovery` | `{}` | `{devices:[{ip,name,hardware,xaddrs}]}` (WS-Discovery on field LAN) |
| POST | `/camera` | `{localIp, name}` | `{localIp,name,onvifPort,rtspPort}` (idempotent by localIp; no creds) |
| GET | `/cameras` | — | `{cameras:[...]}` |
| DELETE | `/camera` | `?localIp=…` | `{deleted:bool}` |
| GET | `/` | — | field web page (scan + add, no credential input) |

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
# then authorize the Pi as a member in ZeroTier Central (manual, out-of-band)
curl -s localhost:9797/health | head
```

Requires `nginx-full` (ships `ngx_stream`); `install.sh` installs it and wires the
`stream { include /etc/nginx/stream.d/*.conf; }` block into `nginx.conf`.

## Config

All via env (`/etc/nvr-edge-agent/agent.env`) — see [`agent.env.example`](agent.env.example).
Key ones: `EDGE_AGENT_TOKEN` (shared fleet secret, must match every NVR), `ZT_NETWORK_ID`,
`EDGE_AGENT_NAME`, `EDGE_AGENT_FIELD_SUBNET`. Reserved ports 1984/8554/8555 are never allocated.
