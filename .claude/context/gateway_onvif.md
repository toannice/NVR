# Gateway ONVIF Control via socat Proxy — Work Log

**Date:** 2026-06-03
**Branch:** `003-cloud-data-sync` (base commit `f1a1ee9`)
**Goal:** Control & query the field PTZ camera from the central Kotlin/Ktor backend through a socat unicast proxy on the Edge Gateway — without any heavy Java/ONVIF framework, using only the Ktor HTTP client and raw SOAP.

---

## 1. Topology

```
Central Server (Kotlin/Ktor backend, :3000)
        │  HTTP POST  application/soap+xml
        ▼
socat unicast proxy @ Edge Gateway   http://10.34.24.12:8080
        │  raw TCP forward (path preserved)
        ▼
Field camera (Bkav)                  http://192.168.2.65:80
```

- The backend never talks to `192.168.2.65` directly. Every ONVIF SOAP request is POSTed to the proxy endpoint `http://10.34.24.12:8080/onvif/device_service`.
- **The socat proxy is path-transparent** — it forwards the raw TCP stream, so the HTTP request line (path) reaches the camera unchanged. This is the key insight that makes per-service routing work (see §4).

---

## 2. What was built

### `backend/src/main/kotlin/vn/aiview/nvr/services/OnvifCameraClient.kt` (new)
Lightweight ONVIF client on the Ktor `HttpClient` (CIO engine):

- **WS-Security UsernameToken (PasswordDigest)**
  `PasswordDigest = Base64( SHA-1( nonceBytes + createdBytes + passwordBytes ) )`
  - `nonce` = 16 random bytes from `SecureRandom`; the `<Nonce>` element carries the **Base64** of those bytes, while the digest hashes the **raw** bytes.
  - `Created` = `Instant.now().truncatedTo(SECONDS)` → ISO-8601 like `2026-06-03T07:28:31Z`.
- **SOAP 1.2 string templates** (`object SoapTemplates`): `GetSystemDateAndTime` (no auth), `GetDeviceInformation`, `GetCapabilities`, `GetProfiles`, `ContinuousMove`, `Stop`.
- **Public API** (all `suspend`):
  - `getDeviceInformation(): OnvifDeviceDetails`
  - `getSystemDateAndTime(): String`
  - `getProfiles(): List<String>`
  - `moveCamera(profileToken, pan, tilt, zoom = 0.0)` — velocities clamped to [-1.0, 1.0]
  - `stopCamera(profileToken)`
- **Service discovery** (`ensureServices()`): calls `GetCapabilities` once, extracts the Media & PTZ `<XAddr>`, rewrites scheme+authority to the proxy's, caches. Media/PTZ calls go to the resolved endpoints; device calls stay on device_service.
- `Closeable` — reuses one HttpClient; `HttpTimeout` 8 s; custom `OnvifException`.
- Default endpoint constant `OnvifCameraClient.DEFAULT_ENDPOINT = "http://10.34.24.12:8080/onvif/device_service"`.

### `backend/src/main/kotlin/vn/aiview/nvr/routes/OnvifRouting.kt` (wired stubs)
Two previously-stubbed endpoints now use the client (both inside `authenticate("auth-session")` → require the `NVR_SESSION` cookie):

- `POST /api/onvif/test-connection`
  Body `{ "username", "password" }` → returns manufacturer/model/firmware/serial + `profileTokens`.
- `POST /api/onvif/ptz`
  Body `{ "username", "password", "profileToken"?, "action": "move"|"stop", "pan", "tilt", "zoom" }`.
  If `profileToken` omitted → uses first discovered profile. `action:"stop"` → Stop; otherwise ContinuousMove.

Responses use `@Serializable` data classes (`OnvifTestConnectionResponse`, `OnvifPtzResponse`, `OnvifErrorResponse`), **not** `mapOf` — see bug #5 below.

> These endpoints are independent of the existing Web-UI PTZ path (`/api/cameras/{id}/ptz/*` in `OnvifSoapClient.kt`, which targets the camera IP directly via `HttpURLConnection`). Nothing in the old path was changed.

---

## 3. Bugs found & fixed (chronological)

| # | Symptom | Root cause | Resolution |
|---|---------|-----------|------------|
| 1 | `{"error":"Unauthorized"}` | Routes are under `authenticate("auth-session")`; curl had no session cookie | Login via `POST /api/lock/verify` with the pattern, reuse cookie jar |
| 2 | `Sender not Authorized` (HTTP 400) | Wrong ONVIF password (`test`) | Use the real camera ONVIF password |
| 3 | `{"success":false,"error":"Xác thực thất bại"}` | Two curl commands pasted on one line → shell concatenated them → malformed `lock/verify` JSON body → 500 catch branch | Run commands separately |
| 4 | `profileTokens: []` empty | `GetProfiles` was POSTed to `device_service`, which answers media ops with SOAP fault **"Action Not Implemented"** | Added `GetCapabilities`-based service discovery; route media/PTZ calls to `…/onvif/media_service` & `…/onvif/ptz_service` via the proxy |
| 5 | 500 empty body on error path | `call.respond(mapOf("success" to false, "error" to "…"))` is `Map<String, Any>` (Boolean + String) — kotlinx-serialization cannot serialize collections of mixed element types | Replaced mixed maps with `@Serializable` data classes |
| 6 | `The requested speed is out of bounds` on any PanTilt | **Hardware**: the field camera is zoom-only | Not a bug — send `zoom` only, omit `pan`/`tilt` |

---

## 4. ONVIF service map of the field camera (from GetCapabilities)

Advertised XAddr (camera-local host, reachable via proxy by swapping authority to `10.34.24.12:8080`):

| Service | Path |
|---------|------|
| Device  | `/onvif/device_service` |
| Media   | `/onvif/media_service` |
| PTZ     | `/onvif/ptz_service` |
| Imaging | `/onvif/image_service` |
| Events  | `/onvif/event_service` |
| Recording | `/onvif/recording_service` |

**Lesson:** media/PTZ operations must hit their own service path. Only `device_service` ops (GetSystemDateAndTime, GetDeviceInformation, GetCapabilities) work on the device path.

---

## 5. Field camera facts (Bkav P200-0802N)

- Manufacturer **Bkav**, Model **P200-0802N**, Firmware **1.6.9.3**, HardwareId 1.0.
- ONVIF server banner: `AIViewonvif server V8.6`.
- Clock: `DateTimeType=Manual`, TimeZone `(GMT+07:00) Bangkok, Hanoi, Jakarta`. UTCDateTime matched host UTC exactly — **no clock skew**, so WS-Security digest validated fine.
- Two media profiles: `PROFILE_1774593074314`, `PROFILE_1774593074315`.
- **PTZ = zoom-only.** `GetNodes` advertises only:
  - `ContinuousZoomVelocitySpace` → XRange [-1.0, 1.0]
  - `ZoomSpeedSpace` → XRange [0.0, 1.0]
  - **No PanTilt velocity space.** Any `<PanTilt>` in ContinuousMove (even x=0.05) → fault "The requested speed is out of bounds".
- `MaximumNumberOfPresets = 100`.

---

## 6. Verified working commands

```bash
# 1. Login → save session cookie
curl -s -c /tmp/nvr.cookies -X POST http://localhost:3000/api/lock/verify \
  -H "Content-Type: application/json" -d '{"pattern":"0-1-2"}'

# 2. Probe device (returns model + profile tokens)
curl -s -b /tmp/nvr.cookies -X POST http://localhost:3000/api/onvif/test-connection \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<ONVIF_PASS>"}'

# 3. Zoom in (this camera: use zoom, NOT pan/tilt)
curl -s -b /tmp/nvr.cookies -X POST http://localhost:3000/api/onvif/ptz \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<ONVIF_PASS>","zoom":0.5,"action":"move"}'

# 4. Stop
curl -s -b /tmp/nvr.cookies -X POST http://localhost:3000/api/onvif/ptz \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"<ONVIF_PASS>","action":"stop"}'
```

Pattern string format (login): hyphen-joined, 0-based, 3×3 grid `0 1 2 / 3 4 5 / 6 7 8` → top row = `0-1-2` (see `frontend/src/components/PatternLock.tsx:224`).

### Standalone diagnostic scripts (kept in /tmp, not committed)
- `/tmp/onvif_probe.sh '<user>' '<pass>'` — curl + openssl reimplementation of the WS-Security digest; isolates code vs camera vs proxy issues. Was the decisive test that proved the digest + credentials were correct.

---

## 7. Build & deploy used

```bash
docker compose build nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx
```

---

## 8. Open follow-ups (not done)

- Frontend PTZ UI for the proxy path (current UI PTZ uses the direct-IP `OnvifSoapClient`, not this proxy client).
- Optionally hide/disable PanTilt buttons for zoom-only cameras based on `GetNodes` capability.
- Credentials are passed per-request in the body; consider sourcing ONVIF creds from the camera record / a secret instead of the caller.
- Mirror this finding into `connection_to_server.md` if the proxy ONVIF path becomes a supported feature.
