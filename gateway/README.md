# gateway/ — Gateway-side artifacts

Everything that runs **on the gateway hardware** (the Raspberry Pi at a remote
site) or that is used to **onboard/manage** a gateway lives here, so it is no
longer mixed in with the NVR application code.

**Overlay is Nebula (MIT)** — the locked production overlay (`nebula/`). ZeroTier code/agent stay
compiled as a dormant fallback; its docs are archived under `legacy/`.

```
gateway/
├── nebula/            ACTIVE overlay — HQ lighthouse + signing service + Nebula edge-agent
│   └── README.md      Nebula control-plane + agent status
├── nebula-migration-va-trien-khai.md   ACTIVE: overlay design (Nebula) + runbook Pi mới (VI)
├── edge-agent/        Go agent on the Pi (passive L4 proxy + overlay join + field web page)
├── android-app/       Phone app used in the field to enroll a gateway
├── gateway_setup.md   How to bring up a Pi gateway end-to-end
├── commercialization-security-notes.md   License/sovereignty + G1–G7 security backlog
└── legacy/            📦 ZeroTier-era docs (4-6-to-do-jobs, controller_hq_setup, arch overview)
```

## What is NOT here (and why)

The **NVR-side** integration is intentionally left inside the NVR application,
because it is compiled into the `nvr-wrapper` image and runs inside the NVR, not
on the gateway:

| Stays in | What |
|---|---|
| `backend/.../gateway/*`, `backend/.../services/Gateway{Scanner,Service}.kt`, `backend/.../routes/GatewayRouting.kt` | Kotlin: scan the overlay subnet (Nebula, or ZeroTier fallback), approve, adopt camera, talk to the agent + signing service. Part of the `vn.aiview.nvr` Gradle module. |
| `frontend/src/components/GatewaysPanel.tsx` | React: the "Gateway" tab in the Web UI. Part of the frontend bundle. |

Moving those out of `backend/`/`frontend/` would break the build — they are not
gateway hardware artifacts, they are the NVR's view of the gateways.

## Data path (recap)

`Camera → Gateway (Pi, nginx stream{} L4 proxy) → overlay (Nebula) → NVR`.
The Pi never decodes video and never calls the NVR; the NVR scans the overlay
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
Nebula**. Any Linux SBC or mini-PC (Orange Pi, Radxa, x86 mini-PC, …)
works — only the OS image and the binary's `GOARCH` differ. A true bare-metal
microcontroller (ESP32 / STM32 / Arduino — no Linux) does **not** work as-is;
see the note in `gateway_setup.md`.
