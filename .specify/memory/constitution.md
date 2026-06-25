# NVR Project Constitution

> The durable principles and gates this project is built on. When a design choice
> is unclear, this file decides. It captures *why* things are the way they are so a
> change that violates a principle is a conscious decision, not an accident.
>
> Last ratified: 2026-06-12. Supersedes scattered rules in CLAUDE.md / specs.

---

## Principles

### P1 — Single source of truth for cameras
Every camera mutation — local Web UI **and** cloud dashboard — flows through one path:
`CameraService.applyAndPersist*` / `removeAndPersist`, writing the one `Cameras` table.
No parallel tables, no second write path. (History: the old `CloudCameras` table drifted
out of sync; 003 unified it.)
**Apply:** new camera code calls `CameraService`; it never writes `config.yml`, go2rtc, or
the DB directly. Serialize through `CameraConfigMutex.mutex`.

### P2 — Passive edge gateway
The gateway (Pi) is an **HTTP server only**. It never initiates a connection to the NVR; the
NVR discovers it by sweeping the overlay subnet. One gateway can serve multiple NVRs. The Pi
holds **no camera credentials** — the admin types them at approve time. It is a pure L4 proxy
(nginx `stream{}`): no decode, no buffer.
**Apply:** never add gateway→NVR push. New gateway features keep the agent stateless about NVRs.

### P3 — Security by default
RTSP URL validation (SSRF block: loopback/link-local/metadata) is mandatory on every add path.
Pattern hashing is bcrypt (cost 12) with transparent SHA-256 upgrade on login. The Box
integration credential is independent of the pattern lock. Secrets live in `secrets/*` Docker
secrets, never in `.env` or `docker inspect`.
**Apply:** any new ingest of a URL/credential reuses `validateRtspUrl()` / `hashPattern()` —
don't hand-roll.

### P4 — Overlay is Nebula (MIT) for production
The locked overlay is **Nebula** (self-hosted lighthouse + signing service). It solves both
licensing (ZeroTier is BSL 1.1 — can't embed-and-sell) and sovereignty (no foreign root servers).
ZeroTier code stays compiled as a **dormant fallback** and activates only when Nebula is
unconfigured; it is not the shipping path.
**Apply:** new overlay work targets `gateway/nebula/`. Don't revive ZeroTier as primary. Don't
add a third overlay without revisiting this principle.

### P5 — Idempotent operations
Add/delete camera, approve/adopt gateway, register go2rtc stream, OTA update — all must be safe
to repeat. Same inputs → same end state, no duplicate side effects.
**Apply:** deletes are "ensure absent" (DB + YAML + go2rtc + restart), not "fail if missing".

### P6 — No silent failures
Every operation surfaces its outcome. Frigate restart success/failure is reported to the UI and
cloud (`frigate_restarted`). A live offer with no stream returns an error envelope, not a dropped
frame. OTA logs every transition and alerts on rollback.
**Apply:** don't swallow exceptions into a bare `catch {}`; log via `AppLogger` and propagate a
status the caller can act on.

### P7 — Backwards-compatible schema migrations
Schema evolves via `createMissingTablesAndColumns` (Exposed auto-ALTER) + idempotent seed
functions. No destructive migration without a deprecation window of at least one shipped release.
**Apply:** add columns/tables additively; gate drops behind the deferred-cleanup list below.

### P8 — One OTA tag, one source of truth
The image channel is a single variable, `WRAPPER_TAG` in `.env`:
`latest` = stable (git `main`), `staging` = beta (git `staging`), `vX.Y.Z` = pinned.
Both the forward update and the rollback read the *same* resolved tag — no hardcoded tags
anywhere. Production runs `latest`.
**Apply:** never hardcode a tag in compose, watchdog config, or update code; read `WRAPPER_TAG`.

---

## Deployment model (so it ports cleanly)

- **Production / fresh machine:** `git clone` → `sudo ./install.sh`. Pulls the image from GHCR;
  generates `.env` (from `.env.template`), TLS cert, and `secrets/*` if missing. Never builds
  from source, never creates `docker-compose.override.yml`.
- **Developer machine:** `docker-compose.override.yml` (git-ignored) forces local `build:`;
  iterate with `./run.sh --rebuild`.
- The split is intentional: the override's absence is what makes a clone pull the GHCR image.

---

## Design gates (run before implementing a feature)

- [ ] **P1** Does any camera write go through `CameraService` (not a new path)?
- [ ] **P2** Does the gateway stay passive (no push to NVR, no creds on Pi)?
- [ ] **P3** Are new URLs/credentials validated + hashed with the existing helpers?
- [ ] **P4** Does overlay work target Nebula, leaving ZeroTier dormant?
- [ ] **P5** Is the operation idempotent (re-runnable)?
- [ ] **P6** Does every failure surface a status (no silent catch)?
- [ ] **P7** Is the migration additive / backwards-compatible?
- [ ] **P8** Is the OTA tag read from `WRAPPER_TAG` (never hardcoded)?

---

## Deferred cleanup (tracked debt — do, don't forget)

- **Drop `CloudCameras`** (`Schema.kt` object + `migrateCloudCamerasIntoCameras()` in
  `Databases.kt` + `DROP TABLE cloud_cameras`) — only **after** 003 has run ≥1 shipped release
  in production. Kept now for rollback safety (P7).
- **Retire ZeroTier code** (`ZtControllerClient.kt`, `ZT_*` env, `gateway/edge-agent/` ZeroTier
  path) — only **after** Nebula is validated cross-internet (real NAT traversal on real hardware).
  Until then it is the documented fallback (P4).

---

## Release Packaging (zip mã nguồn)

### Credentials dùng chung — baked vào mọi bản phát hành

| Credential | Giá trị | Ghi chú |
|---|---|---|
| `GITHUB_USERNAME` / `GITHUB_TOKEN` | Tài khoản `toannice` + PAT `read:packages` | Tất cả máy dùng chung 1 token pull GHCR |
| `GMAIL_USER` / `secrets/gmail_app_password` | Tài khoản Gmail AIView chung | Gửi OTP reset pattern lock |

`install.sh` điền sẵn 2 nhóm trên — user không cần tự cung cấp khi cài máy mới.

### Loại ra khỏi zip khi tạo bản phát hành

**Secrets / Credentials — TUYỆT ĐỐI không đưa vào:**
```
.env
secrets/
data/session_secret
config/.jwt_secret
nginx/server.key
nginx/ca/ca.key
```

**Database / Runtime state (máy mới phải bắt đầu trống):**
```
data/nvr.db
data/nvr.db-shm
data/nvr.db-wal
config/frigate.db
config/nvr.db
config/backup.db
backend/data/nvr.db
mosquitto/data/mosquitto.db
```

**Cấu hình cụ thể máy đang chạy (không áp dụng máy mới):**
```
config/config.yml
nginx/server.crt
nginx/ca/ca.crt
docker-compose.override.yml
```

**Build artifacts / Dev:**
```
.git/
node_modules/
frontend/dist/
build/
*.class
```

### Lệnh zip chuẩn

```bash
cd /home/toan/my-nvr
zip -r AIViewNVRV<version>.zip . -P "AIView@2030" \
  --exclude ".git/*" \
  --exclude ".env" \
  --exclude "secrets/*" \
  --exclude "data/nvr.db*" \
  --exclude "data/session_secret" \
  --exclude "config/*.db*" \
  --exclude "config/.jwt_secret" \
  --exclude "config/config.yml" \
  --exclude "nginx/server.key" \
  --exclude "nginx/server.crt" \
  --exclude "nginx/ca/ca.key" \
  --exclude "nginx/ca/ca.crt" \
  --exclude "backend/data/*" \
  --exclude "mosquitto/data/*" \
  --exclude "docker-compose.override.yml" \
  --exclude "node_modules/*" \
  --exclude "frontend/dist/*" \
  --exclude "build/*" \
  --exclude "*.class"
```

Sau khi zip: verify không có credential nào lọt vào:
```bash
mkdir /tmp/nvr-verify && unzip -P "AIView@2030" AIViewNVRV<version>.zip -d /tmp/nvr-verify
grep -r "gmail_app_password\|GITHUB_TOKEN\|nebula_sign_token\|Bkav@" /tmp/nvr-verify/
```

---

## References

- Operational guide & codebase map: [`CLAUDE.md`](../../CLAUDE.md)
- Doc navigation: [`docs/INDEX.md`](../../docs/INDEX.md)
- Overlay design + runbook: [`gateway/nebula-migration-va-trien-khai.md`](../../gateway/nebula-migration-va-trien-khai.md)
- Security backlog (G1–G7): [`gateway/commercialization-security-notes.md`](../../gateway/commercialization-security-notes.md)
