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
sudo ./install.sh          # creates .env + TLS cert + secrets, logs into GHCR, pulls & starts
```
Open `https://<host-ip>` (self-signed cert → accept the warning).

**Developer loop (build from source):**
```bash
./run.sh --rebuild         # build nvr-wrapper locally, recreate, ensure nginx
```

## OTA channels

The image tag is one variable, `WRAPPER_TAG` in `.env`:

| `WRAPPER_TAG` | Channel | Built from |
|---|---|---|
| `latest` | stable (production default) | git `main` |
| `staging` | beta / test boxes | git `staging` |
| `vX.Y.Z` | pinned release | git tag `v*.*.*` |

The watchdog auto-updates to (and rolls back within) this same tag — no hardcoded tags.

## Documentation

- **[docs/INDEX.md](docs/INDEX.md)** — full doc map
- **[CLAUDE.md](CLAUDE.md)** — operational guide + codebase map + known issues
- **[.specify/memory/constitution.md](.specify/memory/constitution.md)** — project principles & design gates
- **[TONG_QUAN_DU_AN.md](TONG_QUAN_DU_AN.md)** — kiến trúc tổng quan (Vietnamese)
