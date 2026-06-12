# NVR Documentation Index

Start here. Every doc in the repo, grouped by what you're trying to do. `✅` current ·
`📦` legacy/archived (kept for history, not for new work).

## Start here
- **Deploy on a new machine** → [`../install.sh`](../install.sh) (`sudo ./install.sh`) + [`../.env.template`](../.env.template)
- **Understand the system** → [`../CLAUDE.md`](../CLAUDE.md) (operational map) · [`../TONG_QUAN_DU_AN.md`](../TONG_QUAN_DU_AN.md) (VI overview)
- **The rules / why things are this way** → [`../.specify/memory/constitution.md`](../.specify/memory/constitution.md)

## By topic

### Build, deploy & OTA
- ✅ [`../CLAUDE.md`](../CLAUDE.md) § Build & Deploy, § OTA — the canonical reference
- ✅ [`../install.sh`](../install.sh) — production GHCR-pull bring-up · [`../run.sh`](../run.sh) — dev build-from-source loop
- ✅ [`../DEPLOY_GUIDE.md`](../DEPLOY_GUIDE.md) — fleet OTA flow (dev → GHCR tag → edge)
- ✅ [`../fleet_management_guide.md`](../fleet_management_guide.md) — multi-machine ops

### Gateway (edge device)
- ✅ [`../gateway/README.md`](../gateway/README.md) — gateway artifact index
- ✅ [`../gateway/nebula-migration-va-trien-khai.md`](../gateway/nebula-migration-va-trien-khai.md) — **active overlay design + runbook (Nebula)**
- ✅ [`../gateway/gateway_setup.md`](../gateway/gateway_setup.md) — Pi onboarding steps
- ✅ [`../gateway/nebula/README.md`](../gateway/nebula/README.md) — Nebula control-plane + agent
- 📦 [`../gateway/legacy/`](../gateway/legacy/) — ZeroTier-era design/setup docs

### Cloud connection
- ✅ [`../connection_to_server.md`](../connection_to_server.md) — MQTT / WebRTC LIVE / WebRTC DATA, dc-bridge build
- ✅ [`../CLAUDE.md`](../CLAUDE.md) § Cloud Connection — summary table + live-view root-cause

### AiView Box integration
- ✅ [`aiview-box-integration-impl-spec.md`](aiview-box-integration-impl-spec.md) — protocol v1.1 spec
- ✅ [`aiview-box-integration-test.md`](aiview-box-integration-test.md) + [`aiview-box-contract-test.sh`](aiview-box-contract-test.sh) — contract tests

### Security & commercialization
- ✅ [`../gateway/commercialization-security-notes.md`](../gateway/commercialization-security-notes.md) — license/sovereignty + G1–G7 backlog
- ✅ [`../CLAUDE.md`](../CLAUDE.md) § Security Status

### Troubleshooting
- ✅ [`../CLAUDE.md`](../CLAUDE.md) § Known Issues & Fixes — quick table
- ✅ [`LIVE_VIEW_DIAGNOSIS.md`](LIVE_VIEW_DIAGNOSIS.md) — Web UI live-view (MSE) debugging

### Testing
- ✅ [`../claude_test.md`](../claude_test.md) — E2E (Playwright) setup

### Specs
- ✅ [`../specs/003-cloud-data-sync/`](../specs/003-cloud-data-sync/) — unified cameras + cloud live view
- 📦 [`../specs/legacy/004-gateway-onboarding-zerotier/`](../specs/legacy/004-gateway-onboarding-zerotier/) — ZeroTier onboarding (superseded by Nebula)

## Archived
- 📦 [`legacy/releases/`](legacy/releases/) — old versioned doc bundles (v0.0.0.1)
