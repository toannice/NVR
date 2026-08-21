# NVR — Network Video Recorder

Multi-service NVR for Ubuntu, deployed with Docker Compose. Frigate as the NVR engine,
a Kotlin + React app (`nvr-wrapper`) on top, a self-healing watchdog with OTA, and an
nginx HTTPS gateway. Cameras can be local (RTSP/ONVIF) or reached over a self-hosted
**Nebula** overlay through passive edge gateways.

```
mosquitto (MQTT) → frigate (NVR engine) → nvr-wrapper (Kotlin/React app)
                                                 ↑
                        nvr-watchdog (OTA + self-heal) + nginx (HTTPS)
```

## Quick start

**Fresh machine (production):**
```bash
git clone https://github.com/toannice/NVR.git my-nvr && cd my-nvr
sudo ./install.sh          # creates .env + TLS cert + secrets, builds the image, starts the stack
```
Open `https://<host-ip>` (self-signed cert → accept the warning).

**Developer loop (build from source):**
```bash
./run.sh --rebuild         # build nvr-wrapper locally, recreate, ensure nginx
```

## How updates reach a machine

**No container registry is involved, and no registry credential lives on a delivered
machine** (changed 2026-08-03). `nvr-wrapper` is either built on the spot or loaded from a
bundle:

| Path | Where the image comes from | Credential on the machine |
|---|---|---|
| `install.sh` (machine has the source tree) | `docker compose build` | none |
| `deploy-offline.sh` (bundle zip) | `docker load -i images.tar` | none |

The image name to run is `WRAPPER_IMAGE` in `.env` (defaults to `nvr-wrapper:latest`).
There is no `WRAPPER_TAG` and no `GITHUB_USERNAME`/`GITHUB_TOKEN` — both scripts strip those
keys from an older `.env` and run `docker logout ghcr.io` when upgrading a machine.

Registry OTA in the watchdog is hard-disabled (`DISABLE_OTA=true`, every service `managed:
false` in `config/watchdog.yml`); the watchdog still does self-healing and health alerts.
**Pushing a new build to field machines therefore needs a bundle/deploy step — that mechanism
is not built yet.**

## Documentation

- **[docs/INDEX.md](docs/INDEX.md)** — full doc map
- **[CLAUDE.md](CLAUDE.md)** — operational guide + codebase map + known issues
- **[.specify/memory/constitution.md](.specify/memory/constitution.md)** — project principles & design gates
- **[TONG_QUAN_DU_AN.md](TONG_QUAN_DU_AN.md)** — kiến trúc tổng quan (Vietnamese)
