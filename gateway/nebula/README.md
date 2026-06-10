# gateway/nebula/ — Nebula overlay (ZeroTier→Nebula migration, in progress)

Parallel implementation of the gateway overlay on **Nebula (MIT)** instead of ZeroTier, to unblock
commercialization (license + sovereignty). Design + rationale + runbook:
[`../nebula-migration-va-trien-khai.md`](../nebula-migration-va-trien-khai.md).

> The ZeroTier implementation in `../edge-agent/` + `../hq-controller/` is **left untouched and still
> works**. This folder is the Nebula sibling. The NVR-side Kotlin is edited **in place** in `backend/`
> (additive, gated by `NEBULA_CA_URL`), so ZeroTier keeps working when Nebula env is unset.

## What's here

```
gateway/nebula/
├── hq/                     HQ control plane (replaces ztncui + ZeroTier controller)
│   ├── signing-service/    Go REST service: mint token, enroll, approve→`nebula-cert sign -in-pub`, poll
│   ├── install-hq.sh       installs nebula + nebula-cert + CA + lighthouse(+relay) + signing service
│   ├── nebula-lighthouse.service / nvr-nebula-signing.service / signing.env.example
└── edge-agent/             Pi agent — fork of ../edge-agent with nebula.go instead of zerotier.go
    ├── nebula.go           keygen (private key stays on Pi), write config.yml, manage nebula svc, status
    ├── main.go             /enroll = signing URL + one-time token; background loop polls until signed
    ├── install.sh          installs nebula (not zerotier-one) + nginx + systemd
    └── nebula.service / agent.env.example  (nginx.go/discovery.go/state.go/web copied from ZT agent)
```

NVR-side (edited in place under `backend/.../gateway/`):
- `NebulaCaClient.kt` — client to the signing service (list pending, approve, reject, mint token).
- `GatewayConfig.kt` — `NEBULA_CA_URL`, `NEBULA_OVERLAY_SUBNET`, `nebulaSignToken`, `nebulaEnabled`.
- `GatewayService.kt` — `nebulaConfigured()`, `listPendingNebula()`, `approveNebula(token)`.

## Status

| Piece | State |
|---|---|
| HQ signing service | ✅ builds; **smoke-tested** end-to-end (mint→enroll→pending→approve→signed cert, IP alloc, 401 on bad auth) with a stubbed `nebula-cert` |
| Edge-agent Nebula fork | ✅ builds (`go build`), gofmt-clean |
| HQ install scripts + systemd + lighthouse | ✅ written, `bash -n` clean (not yet run on a real box) |
| NVR `NebulaCaClient` + config + service methods | ✅ compiles (`./gradlew compileKotlin`); additive, ZeroTier untouched |
| **Remaining wiring** (below) | ⏳ |

### Remaining to wire (needs nvr-wrapper rebuild + a real Pi/Nebula to verify)
1. **Routes** (`backend/.../routes/GatewayRouting.kt`): when `GatewayConfig.nebulaEnabled`, serve
   `listPendingNebula()` / `approveNebula(token)` instead of the ZeroTier `listPending()`/`authorizeMember()`.
2. **Scanner** (`backend/.../services/GatewayScanner.kt` + `GatewayService.upsertFromScan`): Nebula
   `/health` reports `overlayIp` + `gatewayId` (no `ztAddress`). Auto-approve should key on `gatewayId`
   for Nebula gateways; persist `overlayIp` into `Gateways.zerotierIp` (same column, overlay-agnostic).
3. **Frontend** (`frontend/src/components/GatewaysPanel.tsx`): relabel "Network ID" → "Mã khu vực"
   (signing URL + token); the pending list shows token/name instead of nodeId.
4. **Android app** (`gateway/android-app/.../net/AgentClient.kt`, `Models.kt`, enroll screen): `/enroll`
   body becomes `{signingUrl, token, name}` (already partly modeled; field input + labels to update).
5. **Real-Pi end-to-end test** (runbook §5): fresh Pi → install → enroll → Cấp phép → scan → live view;
   measure NAT traversal / decide if a relay is needed.

## Build / test

```bash
# HQ signing service
cd hq/signing-service && go build -o /tmp/nvr-nebula-signing . && go vet ./...

# Edge-agent (Pi)
cd edge-agent && ./build.sh                 # linux/arm64 (Pi 3/4)

# NVR-side (compiles into nvr-wrapper)
cd ../../backend && ./gradlew compileKotlin
```

Smoke-test the signing flow locally (no real nebula needed — stub `nebula-cert`):
```bash
# 1) put a stub `nebula-cert` on PATH that writes a dummy cert on `sign` and prints JSON on `print`
# 2) run with NEBULA_CERT_BIN/NEBULA_CA_DIR/NEBULA_SIGN_TOKEN/NEBULA_LIGHTHOUSE set, then:
curl -s -X POST localhost:9443/tokens   -H 'Authorization: Bearer <admin>' -d '{"region":"nha-A","ipCidr":"10.10.20.0/24"}'
curl -s -X POST localhost:9443/enroll   -d '{"token":"<token>","name":"gw","pubPem":"..."}'
curl -s -X POST localhost:9443/approve  -H 'Authorization: Bearer <admin>' -d '{"token":"<token>"}'
curl -s        "localhost:9443/enroll/status?token=<token>"     # -> status:signed + certPem
```

## How the daily flow maps (vs ZeroTier)

| ZeroTier | Nebula (this folder) |
|---|---|
| App enters 16-hex network id → agent `zerotier join` | App enters **signing URL + token** → agent keygen + `/enroll` (pubkey) |
| Admin "Cấp phép" → controller authorizes member | Admin "Cấp phép" → signing service **signs cert** (`nebula-cert sign -in-pub`) |
| Pi gets IP via planet roots | Agent polls, gets cert → writes `config.yml` → starts `nebula` → overlay IP |
| Scanner discovers ztIP → adopt → live view | Scanner discovers overlay IP → adopt → live view (**unchanged**) |

See the runbook for the full step-by-step (§5) and the operational caveats (§6, §10–11: endpoint,
free hosting, relay fallback, watchdog).
