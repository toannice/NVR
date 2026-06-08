# gateway/ — Gateway-side artifacts

Everything that runs **on the gateway hardware** (the Raspberry Pi at a remote
site) or that is used to **onboard/manage** a gateway lives here, so it is no
longer mixed in with the NVR application code.

```
gateway/
├── edge-agent/        Go agent that runs ON the Pi (passive L4 proxy + ZeroTier join + field web page)
├── android-app/       Phone app used in the field to enroll a gateway into a ZeroTier network
├── hq-controller/     Self-hosted ZeroTier controller (ztncui) install/systemd/backup scripts for the HQ box
├── gateway_setup.md   How to bring up a Pi gateway end-to-end
├── controller_hq_setup.md   How to stand up the HQ ZeroTier controller
├── 4-6-to-do-jobs.md  Plan A design + verify steps
└── tổng-quan-kiến-trúc-gateway-8-6-26.md   Architecture overview (VI)
```

## What is NOT here (and why)

The **NVR-side** integration is intentionally left inside the NVR application,
because it is compiled into the `nvr-wrapper` image and runs inside the NVR, not
on the gateway:

| Stays in | What |
|---|---|
| `backend/.../gateway/*`, `backend/.../services/Gateway{Scanner,Service}.kt`, `backend/.../routes/GatewayRouting.kt` | Kotlin: scan ZeroTier subnet, approve, adopt camera, talk to the agent + controller. Part of the `vn.aiview.nvr` Gradle module. |
| `frontend/src/components/GatewaysPanel.tsx` | React: the "Gateway" tab in the Web UI. Part of the frontend bundle. |

Moving those out of `backend/`/`frontend/` would break the build — they are not
gateway hardware artifacts, they are the NVR's view of the gateways.

## Data path (recap)

`Camera → Gateway (Pi, nginx stream{} L4 proxy) → ZeroTier overlay → NVR`.
The Pi never decodes video and never calls the NVR; the NVR scans the ZeroTier
subnet, discovers the agent, and pulls. See `gateway_setup.md`.

## Portability / target hardware

The agent is a single static Go binary. To target a different board, rebuild for
its CPU architecture — the logic, the install flow, and the whole NVR-side
integration are unchanged:

```bash
cd edge-agent
./build.sh              # linux/arm64 (Pi 3/4 64-bit) — default
ARCH=arm ./build.sh     # linux/arm (32-bit armv7)
# any other Linux board: GOOS=linux GOARCH=<arch> CGO_ENABLED=0 go build ...
```

Requirements on the target: **Linux + systemd + nginx-full (stream module) +
ZeroTier One**. Any Linux SBC or mini-PC (Orange Pi, Radxa, x86 mini-PC, …)
works — only the OS image and the binary's `GOARCH` differ. A true bare-metal
microcontroller (ESP32 / STM32 / Arduino — no Linux) does **not** work as-is;
see the note in `gateway_setup.md`.
