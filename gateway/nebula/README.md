# gateway/nebula/ — Nebula overlay (ACTIVE / locked production overlay)

The gateway overlay runs on **Nebula (MIT)** — the locked production choice (license + sovereignty).
Design + rationale + runbook: [`../nebula-migration-va-trien-khai.md`](../nebula-migration-va-trien-khai.md).

> The ZeroTier agent in `../edge-agent/` stays compiled as a **dormant fallback** (its HQ-controller
> scripts + docs were archived to `../legacy/`). The NVR-side Kotlin is edited **in place** in
> `backend/` (additive, gated by `NEBULA_CA_URL`): ZeroTier only activates when Nebula env is unset.

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
| Routes + scanner + frontend + Android app wiring | ✅ done & compiles (see below) |
| **Real-Pi end-to-end test** | ⏳ pending hardware |

### Wiring DONE (commit `5922f55`) — overlay-aware, ZeroTier untouched when Nebula env unset
1. **Routes** (`GatewayRouting.kt`): `/pending` + `/authorize` pick `NebulaCaClient` vs `ZtControllerClient`
   by config (unified `PendingItem{id,label,detail}`); `id` = ZT nodeId OR Nebula token.
2. **Scanner** (`GatewayScanner.kt` + `GatewayConfig.activeScanSubnet`): sweeps `NEBULA_OVERLAY_SUBNET`
   when Nebula active. `upsertFromHealth` auto-approves Nebula gateways (overlay presence == admin signed).
   `EdgeHealth` gained `overlayIp`/`nebulaUp`/`certExpiry`; the scanned IP is stored in `Gateways.zerotierIp`
   (same column, overlay-agnostic).
3. **Frontend** (`GatewaysPanel.tsx`): pending list uses `{id,label,detail}`; authorize by `id`.
4. **Android app**: `/enroll` body = `{signingUrl, token, name}` ("mã khu vực"); identity/detail/status
   views are overlay-aware (Nebula `signed`/`enrolled`/`overlayIp`/`lighthouse`). `compileDebugKotlin` ✅.

### Remaining (needs hardware + a running HQ/Pi to verify)
- **Real-Pi end-to-end test** (runbook §5): fresh Pi → install → enroll → Cấp phép → scan → live view;
  measure NAT traversal / decide if a relay is needed. Then rebuild `nvr-wrapper` with `NEBULA_*` env set.

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
