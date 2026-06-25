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

## Gateway Security — To-Do (PENDING, từ audit 2026-06-08; cập nhật 2026-06-19)

### Nguyên tắc giao tiếp (CHỐT 2026-06-19)
**Luồng 1 chiều: NVR → Pi.** Pi KHÔNG được tự gọi lên NVR, KHÔNG được giao tiếp sang Pi khác trong cùng overlay. Thiết kế thụ động hoàn toàn — Pi chỉ phục vụ khi được hỏi.

Audit code `gateway/edge-agent/` (proxy L4 thụ động). Thiết kế kín ở: creds camera KHÔNG bao giờ trên Pi, video mã hoá trên đường Nebula, proxy port chỉ bind overlay IP, node lạ phải admin cấp phép mới vào overlay. Nhưng còn các lỗ sau (ưu tiên giảm dần):

| # | Mức | Lỗ hổng | Vị trí | Vá đề xuất |
|---|---|---|---|---|
| G1 | ✅ | **`/camera` = port-forward tùy ý vào LAN field (SSRF/pivot).** `localIp` chỉ validate "IP hợp lệ", KHÔNG bắt buộc thuộc field subnet → ai có fleet token + trên overlay forward tới bất kỳ host:80/:554 của LAN khách. | `main.go#handleCamera` | ✅ DONE (Nebula agent): `localIPInField()` chặn `localIp ∉ FieldSubnet` (403); fail-open + WARN nếu chưa cấu hình subnet. TODO mirror sang ZT agent nếu còn dùng. |
| G2 | ✅ | **Fleet token DÙNG CHUNG cả fleet.** Trộm 1 Pi / đọc 1 SD = điều khiển TẤT CẢ gateway; không revoke lẻ được. | `config.go` `EDGE_AGENT_TOKEN` | ✅ DONE (2026-06-23): (1) **G2:C cert-group** đã đóng rủi ro chính của đường overlay (NVR↔gateway xác thực bằng cert group, không cần token chung). (2) **Per-gateway token** — mỗi Pi tự sinh token riêng ở `state.json` lúc boot đầu (`newToken()`), agent chấp nhận token-riêng SONG SONG fleet token → trộm 1 SD chỉ lộ secret field-LAN của đúng Pi đó, không phải cả fleet. EDGE_AGENT_TOKEN nay để TRỐNG được. **KHÔNG** xây control-plane rotate token riêng (cert-group đã thay vai trò đó). Revoke lẻ = **G8**. |
| G3 | ✅ | **`/enroll` no-auth → hijack gateway CHƯA adopt.** Pi chỉ vào được 1 overlay tại 1 thời điểm. Kịch bản: Pi mới boot chưa enroll → kẻ tấn công gọi `/enroll` trước kỹ thuật viên → Pi bị trỏ sang overlay lạ → không bao giờ kết nối vào NVR thật. | `main.go#handleEnroll` | ✅ DONE (Nebula agent, 2026-06-12): **cửa sổ thời gian sau boot** — `/enroll` chỉ nhận region MỚI trong `EDGE_AGENT_ENROLL_WINDOW_MIN` (mặc định 30') sau boot; hết hạn phải reboot. Chặn được kẻ từ xa. Còn hở: kẻ tấn công có mặt vật lý tại field trong 30 phút. **Định hướng tương lai (chưa làm):** nút GPIO gắn thêm vào Pi gate `/enroll` — chỉ nhận khi nút đang được nhấn. |
| G4 | ✅ | **Agent chạy `root` + `ip_nonlocal_bind=1` global.** RCE = root. | `nvr-edge-agent.service`, `install.sh` | ✅ DONE (2026-06-23, **CẦN TEST PI THẬT**): agent chạy user `nvragent` (no shell). Root chỉ cho **2 lệnh** qua sudoers hẹp (`nginx -s reload`, `systemctl restart nebula`) — `nvr-edge-agent.sudoers`, pin nguyên argv. Agent route 2 lệnh đó qua `sudo -n` (`privileged.go`). systemd sandbox: `ProtectSystem=strict` + `ReadWritePaths` đúng 3 thư mục, `ProtectHome/PrivateTmp/ProtectKernel*`, `RestrictAddressFamilies`. `install.sh` tạo user + chown + `visudo -c` validate. (NoNewPrivileges KHÔNG bật vì cần sudo.) |
| G5 | 🟡 | **API bind `0.0.0.0`** → `/identity` rò recon ra cả LAN field. | `config.go#listenAddrs`, `main.go#handleIdentity` | **GĐ A ✅ DONE (2026-06-23):** `handleIdentity` rút gọn — bỏ `overlayIp`/`lighthouse`/`fieldSubnet`/`cameraCount`/`agentPort`/`nebulaUp`, chỉ còn `gatewayId`/`name`/`installer`/`location`/`overlay`/`enrolled`/`signed`/`enrollOpen`/`alive`. NVR đọc full qua `/health` (overlay). Ảnh hưởng app enroll = **chỉ cosmetic** (mọi field Identity có default + `ignoreUnknownKeys`): `agentPort`→DEFAULT_PORT 9797, `overlayIp` hiển thị từ enroll-response, `cameraCount`=0 trên Pi mới. **GĐ B (backlog, cần Pi, lợi ích biên thấp):** tách listener — control plane (`/health`,`/camera`,`/discovery`,`/cameras`) chỉ nghe overlay IP; field LAN chỉ public paths. Chỉ chặn thêm kẻ **đã có token** đứng trên LAN khách (đã bị G2:C/G2/G3 thu hẹp) nhưng risk làm chết control plane nếu listener-động sai. |
| G6 | ✅ | **nginx config injection qua tên camera.** `c.Name` ghi thẳng vào nginx conf, không lọc newline. | `nginx.go#renderStreamConf` | ✅ DONE (Nebula agent): `sanitizeComment()` strip newline/control char + cap 64 ký tự. TODO mirror sang ZT agent nếu còn dùng. |
| G7 | 🟠 | **Gateway nới rộng bề mặt tấn công của camera** (pass mặc định, ONVIF lộ info, CVE firmware) giờ với tới từ overlay. | (thiết kế) | Cứng hoá camera; G1 giảm phần lớn |
| G9 | 🟡 | **Restream NVR phơi ra LAN + overlay không auth.** `go2rtc` RTSP `8554` + API `1984` bind `0.0.0.0` → ai trên field LAN HOẶC trên overlay (gateway bị chiếm) kéo `rtsp://IP_NVR:8554/<cam>` / mở `http://IP_NVR:1984` xem mọi camera, không cần mật khẩu. | `docker-compose.yml` frigate `ports`, `config.yml` go2rtc | ✅ GĐ1 DONE (2026-06-23): `1984`→`127.0.0.1` (chỉ nvr-wrapper dùng); `8554`→`127.0.0.1` + `${FRIGATE_HOST_IP}` (IP LAN) thay vì `0.0.0.0` → **cắt overlay**, giữ Box cùng LAN (URL Box nhận từ API NVR vốn đã là IP LAN). **GĐ2 (TODO):** bật auth go2rtc qua hook `IntegrationService.RTSP_AUTH` để chặn cả máy lạ cùng LAN — cần nhúng creds vào path nội bộ Frigate (`CameraService.writeYaml`), để khi test Box E2E. |
| G8 | ✅ | **Không có cơ chế revoke gateway nhanh khi Pi bị mất/đánh cắp.** Hiện tại revoke cert là manual qua HQ endpoint. | NVR UI + HQ signing service | ✅ DONE (2026-06-23): Nút **"Thu hồi"** (icon `gpp_bad`) trên tab Gateway → `POST /api/gateways/{id}/revoke` → `GatewayService.revokeGateway`: (1) HQ `/revoke` theo **overlayIp** (NVR không giữ enrollment token) → từ chối renew/re-enroll + blocklist fingerprint; (2) cascade xóa camera (proxy+DB+YAML+go2rtc); (3) **tombstone** row = `revoked` để scanner KHÔNG tự rediscover+auto-approve lại. Badge "⊘ Đã thu hồi"; nút thùng rác = xóa hẳn. HQ down vẫn cắt được local (best-effort). |

> Trọng tâm thực dụng (nhà dân/cửa hàng): **G1 (pivot vào LAN khách) + G8 (revoke nhanh khi mất Pi)** là rủi ro đáng tiền nhất — **cả hai nay đã ✅**. Còn mở: **G5** (bind 0.0.0.0 / recon `/identity`) + **G7** (cứng hoá camera) — mức 🟠, để sau.

### LED blink — identify only (2026-06-19)
LED blink (`/blink` endpoint → ACT LED onboard Pi) dùng để **nhận dạng đúng Pi vật lý** (kỹ thuật viên nhìn thấy đúng con nào nháy) — **không phải cơ chế bảo mật**. Không liên quan đến việc chặn enroll hijack hay SD theft.

Điều khiển ACT LED: `echo 1 > /sys/class/leds/ACT/brightness` (bật) / `echo 0 > ...` (tắt). Code endpoint `/blink` đã có. Đây là tính năng UX cho kỹ thuật viên lắp đặt, giữ nguyên cho PoC.

Nút GPIO gate `/enroll` là tính năng bảo mật riêng biệt — định hướng tương lai, chưa implement.

---

### Cert TTL & Auto-renew (2026-06-19)
- **TTL cert ngắn** là cơ chế tự bảo vệ thực tế nhất khi SD bị clone: cert hết hạn tự chết, không cần revoke thủ công.
- **Mục tiêu:** TTL cert ngắn (cụ thể TBD — đề xuất 90 ngày); auto-renew khi còn **30 ngày** (`NEBULA_RENEW_THRESHOLD_DAYS=30`).
- Pi chủ động renew: gọi HQ signing service re-sign cùng public key → không cần admin can thiệp. Đã có code R1 trong Nebula agent.
- Nếu SD bị clone: clone vẫn dùng được đến hết TTL → cần kết hợp G8 (revoke thủ công ngay khi phát hiện).

---

### G8 — Nút "Thu hồi Gateway" trên NVR (✅ DONE 2026-06-23)
**Trigger:** admin phát hiện Pi bị mất / nghi ngờ SD bị clone.

**Luồng đã implement:**
1. Admin nhấn **"Thu hồi"** (icon `gpp_bad`) trên tab Gateway → confirm cảnh báo.
2. `POST /api/gateways/{id}/revoke` → `GatewayService.revokeGateway`.
3. NVR gọi HQ `POST /revoke` với **`overlayIp`** (NVR theo dõi gateway bằng overlay IP, không giữ enrollment token). HQ `markRevokedByOverlayIP` → set `Revoked=true` (từ chối `/renew` + re-enroll) + append fingerprint vào blocklist file (operator nạp vào lighthouse `pki.blocklist`). Best-effort: HQ down KHÔNG chặn cắt local.
4. NVR cascade xóa camera của gateway (proxy agent + DB + YAML + go2rtc + Frigate restart).
5. NVR **tombstone** row = `revoked` (KHÔNG xóa hẳn) → `upsertFromHealth` bỏ qua row `revoked` nên scanner không tự rediscover + auto-approve lại Pi khi cert còn hạn. UI badge "⊘ Đã thu hồi"; nút thùng rác để xóa hẳn (forget).

**Hiệu lực:** Pi bị thu hồi mất kết nối overlay khi cert hết hạn / khi cố renew (HQ từ chối) / khi operator nạp blocklist. Trong thời gian cert còn lại, Pi vẫn có overlay IP nhưng cameras đã xóa khỏi DB → NVR không còn tin các port proxy.

**Đã có sẵn ở HQ từ trước:** endpoint `/revoke` (V3) + blocklist append. Lần này bổ sung **revoke-by-overlayIp** + toàn bộ đường NVR (client/service/route/UI). Code: `NebulaCaClient.revoke`, `GatewayService.revokeGateway`, `GatewayRouting` `POST /{id}/revoke`, `GatewaysPanel.tsx`, `store.markRevokedByOverlayIP`.

---

### G9 — Restream NVR phơi ra LAN/overlay (GĐ1 ✅ 2026-06-23; GĐ2 TODO)
**Bối cảnh:** `go2rtc` của Frigate phục vụ RTSP restream ở `8554` và API/video ở `1984`. Tích hợp AiView Box (v1.2) cho Box **xác thực vào API NVR** (bcrypt + rate-limit) rồi kéo `rtsp://<LAN_IP>:8554/<chId>_main` để chạy AI — đây là luồng hợp lệ trong LAN tin cậy (`IntegrationService.streamUrl`, `lanIp()` đã cố ý bỏ qua interface overlay).

**Lỗ:** compose publish `8554`/`1984` ở `0.0.0.0` → lộ ra **cả interface overlay `nebula1`**, nên không chỉ LAN mà **một gateway bị chiếm trên overlay** cũng kéo được mọi camera, KHÔNG cần mật khẩu (go2rtc `RTSP_AUTH=false`). Nặng hơn G5 (G5 chỉ lộ recon; G9 lộ luôn video).

**GĐ1 (đã làm) — siết bind, không sửa code:** `docker-compose.yml`:
- `1984` → `127.0.0.1:1984:1984` (chỉ nvr-wrapper dùng qua localhost; Box không cần).
- `8554` → `127.0.0.1:8554:8554` + `${FRIGATE_HOST_IP}:8554:8554` (IP LAN), **bỏ** `0.0.0.0`.
→ Box cùng LAN tới được (đúng URL API NVR trả); node overlay (`10.10.20.x`) KHÔNG; Frigate↔go2rtc nội bộ (cùng container, localhost) không đụng; cloud live WebRTC (`127.0.0.1:1984` + `8555/udp`) không đổi. ⚠️ **`FRIGATE_HOST_IP` phải set trong `.env`** — nếu trống, mapping thứ 2 thành `0.0.0.0` (phơi lại).

**GĐ2 (TODO, khi test Box E2E) — chặn cả máy lạ cùng LAN:** dùng hook có sẵn `IntegrationService.RTSP_AUTH`: bật user/pass go2rtc trong `config.yml`, `streamUrl()` nhúng creds (`rtsp://user:pass@IP:8554/...`), `rtsp_auth=true`. Đánh đổi: phải nhúng creds vào path nội bộ `rtsp://127.0.0.1:8554/...` mà Frigate đọc (`CameraService.writeYaml`) nếu không record/detect gãy → invasive, test kỹ. **Bổ sung belt-and-suspenders:** siết inbound firewall Nebula phía NVR để gateway không với tới cổng quản trị/restream NVR qua overlay.

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
