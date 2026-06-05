# Implementation Plan: Gateway Onboarding (self-hosted ZeroTier + Android app)

**Branch**: `004-gateway-onboarding` | **Date**: 2026-06-04 | **Builds on**: Plan A (`4-6-to-do-jobs.md`, gateway already DONE end-to-end on ZeroTier Central)
**Working dir**: `/home/toan/my-nvr`

## Summary

Plan A đã chạy end-to-end (NVR auto-scan ZeroTier → approve → adopt camera, gateway passive L4). Feature 004 đưa nó tới "tầm nhìn đầy đủ" của owner:

1. **Bỏ ZeroTier Central (cloud), chuyển sang self-host controller** trên một máy Ubuntu riêng ở HQ — $0, **không giới hạn node**, full ability, admin sở hữu luôn trang approve (ztncui). NAT traversal vẫn dùng planet roots công cộng (free). Data plane vẫn là ZeroTier One C++ (đã CHỐT vì RAM 16–20MB + auto NAT hole-punch; xem memory `gateway-overlay-zerotier-locked`).
2. **App Android cho Field Worker (FW)** — onboard một gateway bằng đúng các bước: cắm điện+mạng → mở app → scan LAN → chọn gateway → **Add** → admin nhận thông tin → vào ztncui approve → done. App **không quản lý camera**, **không chạm NVR** (không có đường mạng tới NVR), chỉ làm việc với gateway (LAN) và (tùy chọn) controller (internet).
3. **Gateway image nướng-1-lần** — flash là chạy, không config từng cái; mỗi Pi tự sinh ZT identity lần boot đầu; tự join network của controller HQ.
4. **NVR delta nhỏ** — enrich danh sách gateway (tên/người lắp/ztAddress), thêm `DELETE /api/gateways/{id}` cascade xóa camera.

**Mô hình chốt: "Tier 0".** App chỉ nói chuyện local với gateway; admin approve thủ công trên ztncui. NVR học gateway sau approve qua `GatewayScanner` (đã có). Không thêm enrollment service, không lộ NVR ra internet, không nhét ZT API token vào app.

## Locked decisions (không bàn lại)

| # | Quyết định | Lý do |
|---|-----------|-------|
| D1 | Data plane = **ZeroTier One (C++)** | RAM 16–20MB cố định trên 256MB; auto NAT hole-punch + direct P2P ~1.1ms. Tailscale/WireGuard đã loại. |
| D2 | Control plane = **self-host controller (zerotier-one controller mode + ztncui)** trên **box Ubuntu riêng ở HQ** | $0, không cap node, admin sở hữu trang approve + API. Controller tách khỏi NVR (không chết theo NVR). |
| D3 | Planet roots = **công cộng của ZeroTier** (không chạy moon) | Free, đủ cho mọi site có internet. Moon là tùy chọn tương lai. |
| D4 | Mô hình onboarding = **Tier 0** (app↔gateway local; admin approve tay trên ztncui) | App không tới được NVR (khác mạng, NVR không lộ). Đơn giản nhất, kín nhất. |
| D5 | Gateway = **passive** (chỉ HTTP server trên ZeroTier; không push/self-register tới NVR) | Multi-NVR; không couple 1 NVR. (memory `gateway-architecture-plan-a`) |
| D6 | App = **Android trước** (Flutter/Kotlin — chốt ở Phase 2) | Đa số máy field là Android; iOS để sau. |
| D7 | **Một controller / một ZeroTier network cho toàn fleet** (mọi NVR + mọi gateway join chung) | Tránh phân mảnh; multi-NVR dùng chung mạng ảo. |

## Architecture

```
        ┌──────────────── ZeroTier overlay (1 network, controller HQ) ────────────────┐
        │                                                                              │
[HQ box Ubuntu — CONTROLLER]        [NVR Ubuntu 20+ — mạng A]            [Gateway 256MB — mạng B @ site]
  zerotier-one (controller mode)      zerotier-one (member)                zerotier-one (member, ~16-20MB)
  ztncui (web approve + API)          GatewayScanner (quét subnet ZT)      Edge Agent Go (~4MB)
  always-on, systemd auto-start       go2rtc/Frigate (việc nặng)           nginx-stream L4 (proxy onvif/rtsp)
  back up identity + DB               adopt camera (admin gõ creds)        camera ONVIF (cùng LAN)
        ▲                                                                          ▲
        │ admin approve member trên ztncui                                         │ FW: cắm điện+mạng → app scan → Add
        │                                                                          │
   [Admin @ HQ]  ←—— quản trị qua LAN/ZeroTier (ztncui không cần lộ internet) ——→  [App Android của FW]
                                                                                   (chỉ local LAN tới gateway)
```

**Sự thật bất biến (đã phân tích, không lách được):**
- App **không bao giờ tới NVR** (khác mạng + NVR không lộ internet). App chỉ với tới: (a) **gateway tại LAN**, (b) **controller qua internet** (chỉ Tier ≥1, Tier 0 không dùng).
- **Phải flash thẻ SD 1 lần / gateway** (thiết bị trống không thể bị app provision qua mạng). Nhưng admin **không config từng cái** — chỉ nướng image 1 lần.
- **Approve thủ công** trên ztncui — đúng ý owner.

## Components & responsibilities

### HQ Controller box (mới — D2)
- `zerotier-one` ở **controller mode** (local controller) → sinh **network ID mới** (≠ `3b19...` của Central).
- `ztncui` (Node.js web UI, mã nguồn mở GPL) → admin approve member + đặt tên + xem IP vật lý site.
- **systemd auto-start cả 2 dịch vụ khi máy lên** (yêu cầu rõ của owner) — `systemctl enable zerotier-one ztncui`.
- **Backup**: `/var/lib/zerotier-one/identity.secret` + controller DB (`controller.d`) + ztncui data. Mất identity = mất network ID = re-join cả fleet.
- Không bắt buộc lộ internet ở Tier 0 (admin reach qua LAN/ZeroTier).

### Edge Agent (Go — sửa nhẹ, đã có)
- Thêm `GET /identity` (**no-auth, chỉ bind LAN**): `{gatewayId, name, ztAddress, fieldSubnet, alive, cameraCount, ztJoined}` → để app scan + nhận diện đúng gateway.
- Thêm `ztAddress` vào `/health` (đọc `zerotier-cli info`).
- Thêm `POST /label` (no-auth LAN, low-impact): set `name/installer/location` → lưu `state.json`.
- Giữ `POST/DELETE /camera`, `/cameras`, `/discovery` sau **fleet token** (NVR-only) như cũ.
- (Tùy chọn sau) gộp forward L4 vào agent để bỏ nginx → gateway còn 2 thành phần (agent + ZT client).

### NVR (Kotlin — delta nhỏ, đã có Plan A)
- `GatewayService.listGateways`: enrich `name/installer/location/ztAddress` đọc từ `/health`.
- Trạng thái `discovered → approved` giữ nguyên; (tùy chọn) `pending` nếu sau này thêm Tier 1.
- **Job mới — `DELETE /api/gateways/{id}` (session admin)**: cascade `CameraService.removeAndPersist` mỗi camera của gateway (DB+YAML+go2rtc+restart) + best-effort agent `DELETE /camera` giải phóng cổng nginx. Có xác nhận ở UI (đếm số camera).
- **Đổi `ZT_NETWORK_ID`/subnet** sang network của controller HQ + NVR join controller mới.
- ❌ KHÔNG cần auth app→NVR (app không tới NVR ở Tier 0).

### Android App (mới — D6)
- Quét subnet LAN cổng agent (`/identity`) → liệt kê gateway tại site.
- Hiển thị gateway: `alive`, `ztJoined`, `ztAddress`, số camera (gọi `/discovery`).
- Nút **Add**: gọi `POST /label` (tên/người lắp/vị trí lưu trên gateway); hiện `ztAddress` to + hướng dẫn *"Báo admin approve member này trên ztncui"*.
- Không nhập creds camera (admin gõ lúc adopt). Không gọi NVR. Không giữ ZT token.

### Gateway image (mới — Phase 0)
- Bake: OS + `zerotier-one` + `ZT_NETWORK_ID`(controller HQ) + Edge Agent + nginx-stream + fleet token.
- **Xoá ZT identity** trong image → first-boot tự sinh (mỗi Pi unique, tránh đụng node address).
- Ethernet DHCP (zero cấu hình mạng). Wifi: set lúc flash bằng Raspberry Pi Imager nếu cần.
- First-boot: sinh identity → `zerotier join <network HQ>` → systemd giữ agent + nginx + zerotier sống.
- Giao: file `.img` + hướng dẫn flash (Raspberry Pi Imager). FW hoặc ai cũng flash được, không terminal.

## Phases & tasks

### Phase 0 — HQ self-host controller (nền móng, làm TRƯỚC)
> ✅ Artifact đã sẵn: `hq-controller/` (install + systemd + backup) và hướng dẫn `controller_hq_setup.md`. Việc còn lại là CHẠY trên box HQ + thao tác tay trên ztncui.
- [x] Script `install-controller.sh`: cài `zerotier-one` (controller mode) + Node18 + ztncui + user `ztncui` + `.env`.
- [x] systemd: `zerotier-one` (installer tự enable) + `ztncui.service` (auto-start) — **enable cả 2 tự lên khi boot** (yêu cầu owner).
- [x] Backup: `backup.sh` + `zt-controller-backup.{service,timer}` (dump `identity.secret`+`controller.d`+ztncui etc, 03:00 hằng ngày, giữ 14 bản).
- [x] **CHẠY:** `sudo bash hq-controller/install-controller.sh` trên box HQ (`192.168.2.106`, ZT node `efb242bde2`). 2026-06-05. (Gặp + đã patch script: xung đột Node 12→18, thiếu TLS cert, thiếu `etc/passwd` seed.) ✅ verify reboot 2026-06-05: 3 dịch vụ active, `:3443` listening, `zerotier-cli info` ONLINE, web UI OK.
- [x] **Tay trên ztncui:** đổi mật khẩu admin; Add network `nvr-fleet` + Easy setup pool `10.147.20.0/24` → **NETWORK ID `efb242bde2f3e856`**.
- [x] Copy backup `.tar.gz` off-box → NAS `192.168.2.88:/volume1/backUpGatewayServer/zt-controller/` (2026-06-05, scp `-O`). `backup.sh` đã thêm bước đẩy NAS định kỳ (opt-in qua `/etc/zt-controller-backup.env`, SSH key) — chờ setup key trên HQ box để tự động.
- [x] Migrate NVR: đã đổi `.env` → `ZT_NETWORK_ID=efb242bde2f3e856` + `ZT_SCAN_SUBNET=10.147.20.0/24` (2026-06-05). Scanner `SCAN_DONE subnet=10.147.20.0/24 found=1`. NVR đã `leave` Central `3b19...`.
- [x] **Cutover gateway-nha-A (Pi3)** 2026-06-05: Pi `join efb242bde2f3e856` + `leave 3b19...`; authorize trên ztncui → IP `10.147.20.12`; `agent.env` đã sẵn network ID mới. Sửa `config/config.yml` cam_6 `10.34.24.12→10.147.20.12` (cả main+sub); restart Frigate → go2rtc kéo video OK. **Gotcha:** nginx proxy bind `<ztIP>:8081`, sau đổi IP nginx chết (boot-race: nginx start trước khi ZT có IP). Fix: `systemctl start nginx` ngay; lâu dài đã bake vào `edge-agent/install.sh` (`ip_nonlocal_bind=1` + nginx systemd drop-in `After=zerotier-one`+`Restart=on-failure`).
- [ ] (Quan trọng) Re-bake image gateway với `efb242bde2f3e856` (+ fix nginx boot-race ở `install.sh`) **trước khi deploy hàng loạt** Pi mới. Pi3 hiện tại đã đúng.

### Phase 1 — Edge Agent (Go, sửa nhẹ) — ✅ DONE 2026-06-05
- [x] `GET /identity` (no-auth, allowlist trong middleware) + `ztJoined/ztAddress/ztOnline` (đọc `zerotier-cli info`+`listnetworks`, cache 3s ở `zerotier.go`).
- [x] Thêm `ztAddress/ztJoined/ztOnline` + `installer/location` vào `/health`.
- [x] `POST /label` (no-auth) lưu `name/installer/location` vào `state.json` (partial update, không xoá field cũ); label đè `EDGE_AGENT_NAME`.
- [x] Build arm/arm64 OK (`go vet` sạch); smoke-test native: `/identity`+`/label` no-auth 200, `/camera` vẫn sau fleet token, label persist + 400 khi rỗng.
- [x] **Bind 0.0.0.0 (2026-06-05):** API listener đổi từ `127.0.0.1 + ztIP` sang **một** `0.0.0.0:PORT` → app field tự với tới được qua **field LAN** trên image nướng-sẵn mà KHÔNG cần `EDGE_AGENT_EXTRA_BIND` (IP LAN là DHCP per-site, không bake được). Bỏ luôn `EDGE_AGENT_EXTRA_BIND` (thừa: wildcard đã phủ loopback+ZT+LAN; bind cả `127.0.0.1`+`0.0.0.0` cùng cổng → EADDRINUSE trên Linux). An toàn không đổi: `/identity`+`/label` vốn no-auth/read-only, mọi mutation camera vẫn sau fleet token trên mọi interface. Verify native: `/identity` qua LAN IP (no token) 200, `/cameras` qua LAN IP 401, qua loopback 200.

### Phase 2 — Android App (mới) — 🟡 SCAFFOLD DONE, build OK 2026-06-05 (chưa test trên thiết bị thật)
> Code ở `android-app/` (Kotlin + Jetpack Compose). Build: `cd android-app && ./gradlew :app:assembleDebug` → `app-debug.apk` (~9.9MB). Tái dùng gradle 8.10.2 wrapper của backend (gradle hệ thống 4.4.1 quá cũ). SDK ở `~/Android/Sdk` (compileSdk 35, minSdk 26).
- [x] **Chốt stack: Kotlin + Jetpack Compose** (Material3, OkHttp, kotlinx.serialization, coroutines). SDK đã cài sẵn, không Flutter. (D6)
- [x] Màn 1 `ScanScreen`: quét /24 của LAN (NetworkInterface site-local IPv4) trên cổng agent 9797, gọi `GET /identity` song song (Semaphore 48, timeout 700ms/host), list incremental. Android không cần quyền local-network như iOS (chỉ INTERNET).
- [x] Màn 2 `DetailScreen`: alive/ztJoined/ztOnline, ztAddress, zerotierIp, **cameraCount lấy từ `/identity`** (KHÔNG gọi `/discovery` — endpoint đó token-gated, app không có fleet token theo D4).
- [x] Màn 3 `AddScreen`: form tên/người lắp/vị trí → `POST /label`; thành công → hiện **ztAddress** to + nút **Sao chép** + hướng dẫn "báo admin duyệt member trên ztncui". Cảnh báo nếu gateway chưa join ZeroTier (ztAddress rỗng).
- [x] Copy ztAddress qua clipboard (LocalClipboardManager) để FW nhắn admin out-of-band.
- [ ] **Chưa làm:** test trên điện thoại thật trong cùng LAN với 1 gateway (scan → thấy → label → admin approve trên ztncui). Cần APK cài lên máy field.
- [ ] (nice-to-have) auto-scan khi mở app; xử lý nhiều /24 (hiện chỉ quét /24 của IP site-local đầu tiên); pull-to-refresh; build release ký APK.

> ⚠️ **Lệch nhẹ so với plan gốc:** plan ghi Màn 2 lấy camera count qua `/discovery` — nhưng `/discovery` nằm sau fleet token (chỉ `/identity`+`/label` no-auth), app Tier 0 không có token → dùng `cameraCount` của `/identity`. Nếu muốn "đếm camera dò được trên LAN", phải thêm `/discovery` vào allowlist no-auth của agent (quyết định riêng, chưa làm).

### Phase 3 — NVR (Kotlin, delta nhỏ)
- [ ] `DELETE /api/gateways/{id}` + cascade xóa camera (đã chốt: xóa luôn camera, có xác nhận).
- [ ] `listGateways` enrich `name/installer/location/ztAddress`.
- [ ] UI `GatewaysPanel`: nút "Xóa gateway" + xác nhận; hiển thị tên/người lắp/ztAddress.
- [ ] Verify scan subnet mới của controller HQ.

### Phase 4 — Image & docs
- [ ] Script đóng image từ Pi mẫu (xoá ZT identity, dọn log, set first-boot self-gen).
- [ ] `gateway_setup.md`: bổ sung mục self-host controller + flow FW dùng app + flash image.
- [ ] Cập nhật `CLAUDE.md`: bảng codebase, Known Issues, Security.

## Data model (delta)

`Gateways` (đã có) — thêm cột nullable: `ztAddress varchar(16)`, `installer varchar`, `location varchar`. Không cần bảng mới. Migration qua `createMissingTablesAndColumns` (Exposed, đã có cơ chế).

## Risks & honest trade-offs

| Rủi ro | Ảnh hưởng | Giảm thiểu |
|--------|----------|-----------|
| Controller HQ offline | Không authorize member mới / đổi config; **kết nối cũ vẫn chạy** | always-on + auto-start systemd (đã yêu cầu); cảnh báo nếu controller mất |
| Mất `identity.secret` controller | Mất network ID → re-join cả fleet | backup định kỳ (Phase 0) |
| Phụ thuộc planet roots công cộng | Nếu ZT planet sự cố, discovery/relay gián đoạn | chấp nhận (free); moon là tùy chọn tương lai |
| Đổi network ID khi rời Central | Gateway cũ phải re-join | làm migration **trước** khi deploy đại trà |
| App không tới NVR | Không thể "add to NVR" trực tiếp | Tier 0: admin approve trên ztncui; NVR học sau qua scanner — đã thiết kế |
| Flash SD từng gateway | Cần thao tác 1 lần/thiết bị | image nướng sẵn, Raspberry Pi Imager, không config từng cái |

## Out of scope (ghi rõ để khỏi phình)
- App quản lý/adopt camera (vẫn là việc của admin trên NVR).
- App tự authorize ZeroTier (giữ thủ công trên ztncui — D4).
- Tier 1 (enrollment service / app đẩy tên lên controller / thông báo tự động) — để sau nếu muốn tên đẹp lúc approve.
- Moon (roots riêng) — chỉ khi cần air-gap.
- iOS app.
- Auto-dò onvifPort, tái dùng creds giữa camera cùng gateway, classifier PTZ nâng cao (known limitations Plan A).

## Vận hành: phân việc cho 1 gateway MỚI

**Không thể "chỉ FW 100%"** — luôn còn đúng **2 cú chạm của admin** (đều remote, đều click nhanh, KHÔNG ai sờ vào Pi). Phần vật lý ở site FW làm trọn.

| Khi nào | Ai | Làm gì | Sờ Pi? |
|---|---|---|---|
| **1 lần cho cả fleet** | Admin | Dựng controller HQ (Phase 0) + **nướng 1 image** | — |
| **Mỗi thiết bị mới** | Admin **hoặc** FW | **Flash image vào thẻ SD** (Raspberry Pi Imager, ~5 phút, không terminal) | cắm thẻ, không config |
| **Tại hiện trường** | **FW** | Cắm điện + dây mạng (camera + gateway) → mở app → scan → chọn gateway → **Add** (đặt tên/người lắp) | chỉ cắm dây |
| **Sau khi FW Add** | **Admin ①** | Vào **ztncui** → **approve member** (1 click) | ❌ remote |
| **Sau khi approve** | **Admin ②** | Vào **NVR** → adopt camera (gõ ONVIF creds) | ❌ remote |

**2 mô hình flash** (cả hai đều không ai SSH/config từng Pi):
- **A — admin flash sẵn thẻ (batch):** đưa/gửi FW thẻ đã flash → FW chỉ *cắm thẻ + cắm dây + app*.
- **B — FW tự flash:** đưa FW file image + 3 bước Imager → FW làm luôn cả flash.

**Vì sao 2 cú admin không bỏ (đánh đổi nếu muốn bỏ):**
- *Approve trên ztncui* = rào "thiết bị lạ không tự chui vào mạng ảo". Muốn bỏ → nướng **pre-auth key** trong image (Tier nâng cao) → thiết bị tự duyệt, NHƯNG ai cầm image cũng join được mạng → rủi ro. Tier 0 giữ approve tay.
- *Adopt + gõ creds trên NVR* = vì **gateway không bao giờ giữ mật khẩu camera** (thiết kế Plan A, multi-NVR). Creds chỉ ở NVR.

## "Flash" là gì?

**Flash = ghi toàn bộ OS + phần mềm (image `.img`) vào bộ nhớ của thiết bị** (thẻ SD / eMMC) để nó boot lên là chạy sẵn — giống cài Windows từ USB, nhưng cho thiết bị nhúng.
- Công cụ: **Raspberry Pi Imager** (dễ nhất, có "Use custom" để trỏ tới file `.img` của mình), hoặc **balenaEtcher**, hoặc `dd` (CLI).
- Thao tác: chọn file image → chọn thẻ SD → Write (~5 phút). Không cần terminal, không gõ lệnh trên thiết bị.
- Vì thiết bị trống **không có OS** nên app/qua-mạng không thể "với" tới được → **bước flash 1 lần là bắt buộc**, không lách được. Nhưng nó **không phải "config từng cái"**: mọi cấu hình đã nằm sẵn trong image (network id, fleet token, agent, dịch vụ). Flash 100 thẻ = 100 lần Write giống hệt nhau.

## Chuyển sang DẠNG thiết bị gateway khác (làm sản phẩm khác)

Kiến trúc này **không khoá vào Raspberry Pi**. Bất kỳ thiết bị nào thoả "hợp đồng gateway" dưới đây đều chạy được:

**Hợp đồng gateway (mọi thiết bị phải có):**
1. **Embedded LINUX** (⚠️ KHÔNG phải MCU trần như ESP32/RTOS — xem ranh giới dưới).
2. **Kiến trúc CPU** mà cả 3 thành phần build/chạy được: `zerotier-one` (C++, có sẵn build cho x86_64/arm/arm64/mips...), **Edge Agent** (Go — chỉ cần *cross-compile* lại đúng `GOARCH`/`GOARM`), bộ forward L4 (`nginx-stream`, hoặc gộp vào agent Go).
3. **RAM** thực tế ≥ ~64–128MB (ZT ~16–20MB + agent ~4MB + nginx ~5–8MB + OS). 256MB là thoải mái.
4. **1 init system** để auto-start dịch vụ lúc boot (systemd / OpenWrt procd / buildroot init).
5. **Mạng**: 1 interface tới LAN camera + **đường ra internet** (để ZT chạm planet roots + controller HQ).
6. **Bộ nhớ flashable/imageable** (SD / eMMC / sysupgrade) hoặc cơ chế provision tương đương.

**Cái gì PHẢI làm lại khi đổi dạng thiết bị:**
- **Cross-compile Edge Agent** cho arch mới (`GOOS=linux GOARCH=... go build`). Code Go không đổi.
- **Lấy/biên dịch `zerotier-one`** cho arch đó (thường có sẵn gói).
- **Viết lại unit dịch vụ** theo init của OS mới (systemd unit ↔ procd init ↔ ...).
- **Đóng image mới** theo định dạng nạp của thiết bị (`.img` SD ↔ eMMC ↔ OpenWrt sysupgrade ↔ container).
- `ZT_NETWORK_ID` + fleet token vẫn dùng chung (không phụ thuộc phần cứng).

**Cái gì KHÔNG đổi (tài sản tái dùng):** logic Edge Agent (Go), giao thức với NVR (`/health`,`/identity`,`/camera`,`/discovery`,`/label`), data plane ZeroTier, controller HQ + ztncui, app Android, toàn bộ phía NVR.

**⚠️ Ranh giới phải biết khi chọn phần cứng cho sản phẩm khác:**
Toàn bộ stack giả định **embedded Linux**. Nếu chọn **MCU trần không Linux** (ESP32/STM32/RTOS) thì `zerotier-one` + Go agent + nginx **KHÔNG chạy** → phải viết lại hoàn toàn bằng `libzt` (ZeroTier nhúng dạng thư viện) + forwarder tự code + không có shell/systemd. Đó là **một sản phẩm khác về kỹ thuật**, không phải "đổi thiết bị". → Khi nhắm sản phẩm mới, **chọn thiết bị chạy được Linux** (SBC: Pi, Orange Pi, NanoPi, router OpenWrt có đủ flash/RAM...) để tái dùng toàn bộ công sức này.
```

