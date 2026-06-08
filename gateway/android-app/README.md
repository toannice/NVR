# NVR Gateway Onboard (Android)

Field-worker app for **Feature 004, Phase 2**. One job only: **onboard a gateway into the fleet**
(name it, see its ZeroTier address, tell the admin to approve it). It does **not** manage cameras and
**never** talks to the NVR (Tier 0 — D4). It only speaks to the gateway's edge agent over the field LAN
using the agent's two **no-auth** endpoints (`GET /identity`, `POST /label`); it holds no fleet token.

## Flow (3 screens)
1. **Quét mạng** — sweeps the phone's local /24 on agent port `9797`, lists gateways that answer `/identity`.
2. **Chi tiết** — alive / ZT joined / ZT online, ZeroTier address, IP, proxied camera count (`/identity`).
3. **Thêm** — name / installer / location → `POST /label`; then shows the gateway's **ztAddress** (with copy)
   and the instruction to have the admin approve that member on **ztncui**.

## Build
```bash
cd android-app
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
```
Requires the Android SDK (`local.properties` → `sdk.dir`) and JDK 17. Uses the Gradle 8.10.2 wrapper.

## Stack
Kotlin 2.0.21 · Jetpack Compose (Material 3) · OkHttp · kotlinx.serialization · coroutines.
`minSdk 26`, `compile/target 35`. Cleartext HTTP is enabled (LAN, plaintext agent API).

## Notes / boundaries
- Camera count comes from `/identity.cameraCount` (proxied cameras). `/discovery` is token-gated on the
  agent, so the app does not call it.
- Admin still does two remote clicks (approve on ztncui, adopt camera on NVR) — by design (Tier 0).
- See `../../specs/004-gateway-onboarding/plan.md` (Phase 2) and `../edge-agent/main.go` for the agent contract.
