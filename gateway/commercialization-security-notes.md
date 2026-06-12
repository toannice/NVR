# Gateway — Commercialization, Overlay/License & Security Notes

> Moved out of `CLAUDE.md` (2026-06-10) to keep the main project doc operational.
> These are decision/discussion artifacts for the gateway PoC, not day-to-day build instructions.

---

## 🚨 GẤP — Chốt kế hoạch thương mại hoá (để BÁN được): mất gì, đổi gì?

**Trạng thái: CẦN QUYẾT (chưa chốt) — quyết định này GATE mọi việc gateway tiếp theo.**
PoC đã chạy ngon (Plan A passive L4 + self-host controller). Muốn **đem bán** thì 3 trục dưới phải chốt, mỗi trục có cái mất/đổi. Inputs chi tiết: § *Gateway Security* (G1–G7) + § *Overlay alternatives & License*.

### Trục 1 — License (chặn pháp lý lớn nhất, QUYẾT TRƯỚC)
ZeroTier = **BSL 1.1** → nhúng-bán cho bên thứ ba = vùng cần **OEM/commercial license**. Hai đường:
- (a) **Mua OEM license ZeroTier** → giữ nguyên code, tốn phí + ràng buộc hợp đồng.
- (b) **Đổi overlay license-sạch** — Nebula (MIT) / OpenZiti (Apache) → viết lại control plane.
→ Phải quyết TRƯỚC vì nó định đoạt overlay, kéo theo trục 2 + 3.

### Trục 2 — Chủ quyền (cắt cáp quốc tế / sự kiện an ninh)
Planet roots = hạ tầng ZeroTier Inc. **NGOÀI VN** → cắt quốc tế làm chết onboarding/khôi phục kết nối (link P2P đang chạy còn sống). Gỡ: **moon nội địa** (dễ) hoặc **custom planet** (air-gap tuyệt đối). Nếu đổi **Nebula** thì hết phụ thuộc root ngoại luôn (lighthouse tự host). → PoC nội bộ: moon đủ. Sản phẩm chủ quyền: Nebula / custom planet.

### Trục 3 — An ninh sản phẩm (fix TRƯỚC khi bán)
G1–G7 (§ Gateway Security). **Bắt buộc trước bán:** G1 (pivot vào LAN khách), G2 (token chung → per-gateway/rotate), G3 (hijack `/enroll`). Cộng productization: bake image cho phần cứng chính thức, multi-tenant, onboarding UX, billing/license enforcement.

### "Mất / đổi / giữ / được" nếu chọn đường license-sạch (Nebula) — tóm tắt
- **MẤT:** "Pi chỉ cần 16-hex id", "network id không bí mật", controller+ztncui có sẵn.
- **ĐỔI:** enroll → cert-based; tự xây dịch vụ ký/duyệt cert; relay tự cấu hình.
- **GIỮ:** toàn bộ data path L4 (nginx stream), camera model unified, GatewayScanner, NVR/Frigate/go2rtc.
- **ĐƯỢC:** license bán vô tư + chủ quyền mặc định (zero foreign-root).

**Việc cần làm gấp:** (1) owner chọn đường license (OEM ZeroTier vs Nebula vs OpenZiti); (2) từ đó chốt overlay + onboarding model; (3) lập backlog fix G1–G3 + productization. **Output: 1 quyết định kiến trúc + lộ trình phát triển tới bản bán được.**

---

## Overlay alternatives & License — cân nhắc khi thương mại hoá (2026-06-08)

Hiện overlay **khoá cứng ZeroTier** (lý do RAM + auto-NAT — xem memory `gateway-overlay-zerotier-locked`). Nhưng nảy 2 vấn đề mới khi tính **bán sản phẩm** + **chủ quyền (cắt cáp quốc tế)**:

### Vấn đề 1 — License BSL khi bán
ZeroTier One = **BSL 1.1** (trước là GPLv3). BSL KHÔNG phải copyleft. Phép thử là **Additional Use Grant** = chống cạnh tranh: cấm cung cấp ZeroTier cho **bên thứ ba trên cơ sở hosted/embedded** để cạnh tranh sản phẩm trả phí của họ. → **Nhúng ZeroTier vào phần mềm bán cho khách = vùng cần OEM/commercial license** (đọc `LICENSE.txt` repo + hỏi `contact@zerotier.com` + luật sư). Dùng cho hạ tầng của CHÍNH mình thì thường OK.

### Vấn đề 2 — Chủ quyền / cắt quốc tế
**Planet roots do ZeroTier Inc. vận hành, đặt NGOÀI VN.** Cắt cáp quốc tế → định vị peer + đục NAT chết (link P2P *đang chạy* còn sống, nhưng reboot/join mới/nối lại = fail). Gỡ bằng:
- **Moon** (root tự host, dễ — `initmoon`/`genmoon`/`orbit`, không compile): chịu được cắt quốc tế, đủ 99% nhu cầu. **Giải quyết kỹ thuật, KHÔNG giải quyết license.**
- **Custom planet** (`mkworld`, phải compile + thay file `planet` mọi node): air-gap tuyệt đối.

### Bảng ứng viên thay thế (nếu thương mại hoá)
| Overlay | License | Bán/nhúng | RAM Pi | NAT P2P | Ghi chú |
|---|---|---|---|---|---|
| **Nebula** (Slack) | **MIT** | ✅ vô tư | ~20–40MB | ✅ hole-punch | **Ứng viên số 1.** Lighthouse self-host (không root ngoại → chủ quyền mặc định) |
| OpenZiti | Apache 2.0 | ✅ | nặng (50–100MB+) | ✅ | Zero-trust, nhiều tính năng, nặng/phức tạp hơn |
| Netbird | BSD-3 | ✅ | nặng (WG+coturn) | ✅ | License sạch nhưng RAM nặng |
| Headscale + TS client | BSD-3 | ✅ | nặng + DERP | ✅ | DERP phải tự host |
| WireGuard thuần | GPLv2 | bán được, copyleft | nhẹ nhất | tự xây | Phải tự code coordination/NAT |

### Đánh đổi nếu đổi sang Nebula — 5 trụ kiến trúc
| Trụ hiện tại (ZeroTier) | Nebula | Đánh đổi |
|---|---|---|
| ① Pi chỉ cần **16-hex network id**, không cần IP server | ❌ MẤT — node cần **địa chỉ lighthouse** trong config | `/enroll` đổi: nạp lighthouse addr + **cert ký** thay vì gõ id |
| ② **Network id không bí mật** | ❌ MẤT — membership = **cert ký bởi CA** (credential nhạy cảm) | `/enroll` Tier-0 no-auth phải thiết kế lại |
| ③ Admin "Cấp phép" qua controller REST/ztncui | ⚠️ ĐỔI — membership = **ký cert**, không có SaaS approve UI | Tự xây dịch vụ ký/duyệt cert; `ZtControllerClient`→cert-signing |
| ④ GatewayScanner **quét subnet** phát hiện gateway | ✅ GIỮ — node Nebula có overlay IP tĩnh từ cert | gần như nguyên |
| ⑤ Relay fallback tự động (qua planet) | ⚠️ ĐỔI — phải tự chỉ định **relay host** | cấu hình tay thêm |

**GIỮ NGUYÊN (overlay-agnostic):** proxy L4 nginx, model camera unified, GatewayScanner subnet-scan, NVR/Frigate/go2rtc, creds-chỉ-ở-NVR.
**VIẾT LẠI:** `zerotier.go`, `ZtControllerClient`, flow `/enroll` + app Android, control plane cấp phép, docs. Ước ~vài tuần.

**ĐƯỢC khi đổi Nebula:** license MIT (bán vô tư) + **chủ quyền mặc định** (không root ngoại). **MẤT:** sự tinh gọn "Pi chỉ cần 16-hex", "id không bí mật", phải tự xây control plane cấp phép.

### Chốt quyết định
| Mục tiêu | Nên |
|---|---|
| Chỉ cần chịu **cắt quốc tế** cho PoC nội bộ | **Giữ ZeroTier + dựng moon** (rẻ, nhanh, không viết lại) |
| **Bán thương mại + chủ quyền tuyệt đối + license sạch** | **Chuyển Nebula (MIT)** hoặc **mua OEM license ZeroTier** |

> Mấu chốt: **moon giải quyết kỹ thuật, KHÔNG giải quyết license.**

---

## Gateway Security — To-Do (PENDING, từ audit 2026-06-08)

Audit code `gateway/edge-agent/` (proxy L4 thụ động). Thiết kế kín ở: creds camera KHÔNG bao giờ trên Pi, video mã hoá trên đường ZeroTier (Salsa20), proxy port chỉ bind ztIP, node lạ phải admin cấp phép mới vào overlay. Nhưng còn các lỗ sau (ưu tiên giảm dần):

| # | Mức | Lỗ hổng | Vị trí | Vá đề xuất |
|---|---|---|---|---|
| G1 | ✅ | **`/camera` = port-forward tùy ý vào LAN field (SSRF/pivot).** `localIp` chỉ validate "IP hợp lệ", KHÔNG bắt buộc thuộc field subnet → ai có fleet token + trên overlay forward tới bất kỳ host:80/:554 của LAN khách. | `main.go#handleCamera` | ✅ DONE (Nebula agent): `localIPInField()` chặn `localIp ∉ FieldSubnet` (403); fail-open + WARN nếu chưa cấu hình subnet. TODO mirror sang ZT agent nếu còn dùng. |
| G2 | ✅ | **Fleet token DÙNG CHUNG cả fleet.** Trộm 1 Pi / đọc 1 SD = điều khiển TẤT CẢ gateway; không revoke lẻ được. | `config.go` `EDGE_AGENT_TOKEN` | ✅ DONE (hướng **C**, Nebula agent): xác thực bằng **cert Nebula + firewall group** thay token chung. Bật `NEBULA_FW_NVR_GROUP=nvr` → agent firewall chỉ nhận group `nvr` inbound + tin request overlay không cần token; gateway (group `gateways`) không chạm được API gateway khác. **Bước vận hành:** ký cert NVR `-groups nvr`, gateway giữ `-groups gateways` (mặc định token). Revoke lẻ = revoke cert (V3). Token để trống = legacy. |
| G3 | ✅ | **`/enroll` no-auth → hijack gateway CHƯA adopt.** Van 409 chỉ chặn khi đã có camera; Pi mới cắm bị trỏ sang network của kẻ tấn công. | `main.go#handleEnroll` | ✅ DONE (Nebula agent, 2026-06-12): **cửa sổ thời gian sau boot** — `/enroll` chỉ nhận region MỚI trong `EDGE_AGENT_ENROLL_WINDOW_MIN` (mặc định 30') sau boot; hết hạn phải reboot. Re-submit cùng token vẫn cho. `/identity.enrollOpen` để app báo. (Lưu ý dư: cùng-LAN trong cửa sổ vẫn claim được — nâng PIN/nút nếu cần mạnh hơn.) |
| G4 | 🟠 | **Agent chạy `root` + `ip_nonlocal_bind=1` global.** RCE = root. | `nvr-edge-agent.service`, `install.sh` | Hạ quyền: cap `NET_BIND_SERVICE` + sudoers hẹp |
| G5 | 🟠 | **API bind `0.0.0.0`** → `/identity` rò recon ra cả LAN field. | `config.go#listenAddrs` | Giới hạn field/định danh; giảm thông tin `/identity` |
| G6 | ✅ | **nginx config injection qua tên camera.** `c.Name` ghi thẳng vào nginx conf, không lọc newline. | `nginx.go#renderStreamConf` | ✅ DONE (Nebula agent): `sanitizeComment()` strip newline/control char + cap 64 ký tự. TODO mirror sang ZT agent nếu còn dùng. |
| G7 | 🟠 | **Gateway nới rộng bề mặt tấn công của camera** (pass mặc định, ONVIF lộ info, CVE firmware) giờ với tới từ overlay. | (thiết kế) | Cứng hoá camera; G1 giảm phần lớn |

> Trọng tâm thực dụng (nhà dân/cửa hàng): **G1 (pivot vào LAN khách) + G2 (token chung)** là rủi ro đáng tiền nhất.

---

## Golden image gateway (A1) — ✅ DONE (PoC, 2026-06-05)

> **DECISION 2026-06-06 (user): KHÔNG cần baked golden image cho PoC này.** PoC chạy trên phần cứng đang có sẵn. Việc bake image hoãn lại đến khi có phần cứng phù hợp/chính thức. Hiện tại đừng đầu tư vào image/bake automation. (Giữ làm tham chiếu cho lần bake sau.)

> **SCOPE: PoC, chỉ 2 Pi** (1 production `gateway-nha-A` @ `10.147.20.12` + 1 master spare). KHÔNG triển khai fleet lớn.

> Thuộc 004 (self-host ZeroTier — fleet `nvr-fleet` ID `efb242bde2f3e856`, pool `10.147.20.0/24`).
> **Đã dựng golden master end-to-end trên Pi spare (2026-06-05):** flash Pi OS Lite 64-bit (Debian 13 trixie, aarch64) → cài `edge-agent` + ZeroTier + nginx → join + authorize ztncui → **NVR scanner tự thấy** (`gw-b34bf17c9e89` @ `10.147.20.72`) → hardened **key-only SSH** → `bake-prep.sh` → poweroff. Image: `/home/toan/gateway-golden.img.gz` (full-card, flash sang thẻ **≥32GB cùng cỡ**).
> **Gotchas khi flash:** (1) rpi-imager OS-customisation đôi khi KHÔNG áp → tạo tay cờ `ssh` + `userconf.txt` trên bootfs. (2) Pi OS trixie tạo user theo username gõ trong Imager (vd `pi3`), KHÔNG phải `pi`. (3) Khung chat Claude không có TTY → `sudo`/`ssh` nhập-pass phải chạy ở Terminal thật / `sudo -S` / SSH key.
> **Dọn sau bake:** xoá member test `15df2d5dc5` khỏi ztncui; entry gateway test trên NVR đã xoá.

**Cách hoàn tất A1 khi có Pi spare (giữ làm tham chiếu):**
1. Trên Pi spare: copy `gateway/edge-agent/`, `sudo ./install.sh` (đã có fix nginx boot-race: `ip_nonlocal_bind=1` + drop-in `After=zerotier-one`).
2. Sửa `/etc/nvr-edge-agent/agent.env`: `EDGE_AGENT_TOKEN` = fleet token (khớp `secrets/edge_agent_token`). `ZT_NETWORK_ID` đã bake = `efb242bde2f3e856`.
3. `sudo ./bake-prep.sh` — xoá ZT identity/peers/state + machine-id + ssh host keys + log; giữ `agent.env`; reset nginx cams rỗng.
4. `sudo poweroff` → tháo thẻ SD → chụp image. Mỗi Pi flash ra: boot đầu tự sinh identity → agent tự `zerotier join efb242bde2f3e856` → member CHỜ trên ztncui → admin authorize → NVR scanner tự thấy.

Code: `gateway/edge-agent/bake-prep.sh`, `gateway/edge-agent/install.sh`.
