# Đổi overlay ZeroTier → Nebula + Quy trình triển khai gateway mới

> Tài liệu **thiết kế + runbook** (chưa phải code). Mô tả hệ thống **sau khi đổi tầng overlay từ
> ZeroTier sang Nebula**, và cách triển khai **một Pi3 mới toanh** end-to-end: cài image → ra hiện
> trường → online → xác thực → quét → thêm camera → live view.
>
> Nguồn liên quan: vấn đề license & so sánh overlay ở [`commercialization-security-notes.md`](./commercialization-security-notes.md);
> luồng ZeroTier hiện hành ở [`gateway_setup.md`](./gateway_setup.md); controller HQ hiện tại ở
> [`controller_hq_setup.md`](./controller_hq_setup.md). Tài liệu này **thay tầng overlay**, **giữ
> nguyên** toàn bộ tầng dữ liệu (proxy L4, scan, adopt, live view).

---

## 1. Bối cảnh — vì sao đổi sang Nebula

ZeroTier One = **BSL 1.1**. Nhúng-bán cho bên thứ ba rơi vào vùng cần **OEM/commercial license**
(điều khoản chống cạnh tranh). Dùng cho hạ tầng của chính mình thì OK, nhưng **bán sản phẩm có nhúng
ZeroTier = vướng pháp lý**.

**Nebula (Slack/Defined Networking) = MIT** → bán/nhúng vô tư. Thêm nữa, Nebula dùng **lighthouse tự
host** thay cho "planet roots" toàn cầu của ZeroTier → **chủ quyền mặc định**: không phụ thuộc hạ tầng
đặt ở nước ngoài, chịu được sự kiện cắt cáp quốc tế. Đây chính là 2 thứ ZeroTier không cho khi thương
mại hoá (xem 3 trục quyết định trong `commercialization-security-notes.md`).

**Nguyên tắc của bản đổi này:** chỉ thay **tầng overlay**. Mọi thứ phía trên (camera, proxy, scan,
adopt, live view, NVR, app) **giữ nguyên cách hiện tại nhất có thể** — field worker vẫn "Quét → Thêm
gateway", admin vẫn bấm "Cấp phép" một nút, rồi "Thêm camera".

---

## 2. Thực trạng công nghệ: ZeroTier (nay) ↔ Nebula (sau đổi)

| Hiện tại (ZeroTier) | Sau đổi (Nebula) | Ghi chú |
|---|---|---|
| Controller ztncui @ HQ (network `efb242bde2f3e856`) | **Nebula CA + Lighthouse + Signing service** @ HQ | cùng box HQ, đổi vai trò |
| Network **16-hex id** (không bí mật) | **Mã khu vực** = địa chỉ lighthouse + **enrollment token một lần** | token dùng một lần → an toàn hơn |
| Agent `zerotier-cli join <id>` | Agent `nebula-cert keygen` → gửi pubkey đi ký → ghi config → `systemctl start nebula` | **private key ở lại Pi** |
| Membership = được admin authorize trên controller | Membership = **cert do CA ký** | gốc tin cậy = CA, không phải "số mạng" |
| Admin "Cấp phép" → NVR authorize member (REST) | Admin "Cấp phép" → NVR gọi signing service **ký cert**, cấp IP overlay | UX **một nút** giữ nguyên |
| Planet roots (ngoài VN) lo NAT traversal | **Lighthouse** (của mình) lo discovery + hole-punch; **relay** khi NAT cứng | chủ quyền + cần mở UDP 4242 |
| `.env: ZT_SCAN_SUBNET=10.147.20.0/24` | `.env: NEBULA_OVERLAY_SUBNET=10.10.20.0/24` | scanner quét dải overlay |

### Cái GIỮ NGUYÊN (overlay-agnostic — không đụng tới)
- **edge-agent nginx L4 proxy** (`/camera`, `/cameras`, `stream{}` per camera ZTip→cam:80/:554).
- **GatewayScanner** quét subnet overlay để phát hiện gateway (Nebula node cũng có IP overlay tĩnh).
- **NVR adopt camera / ONVIF / PTZ / Frigate / go2rtc / live view** — đường dữ liệu y hệt.
- **Creds camera CHỈ ở NVR**, không bao giờ trên Pi.
- **UX app** "Quét → chi tiết → Thêm gateway".

### Cái VIẾT LẠI (chỉ tầng overlay + control plane)
- `edge-agent/zerotier.go` → `edge-agent/nebula.go` (keygen, ghi config, quản nebula service).
- `edge-agent/install.sh` (cài **nebula** thay zerotier-one).
- NVR `ZtControllerClient` → `NebulaCaClient` (gọi signing service ký cert thay vì authorize member).
- Flow `POST /enroll` của agent + ô nhập trong app (nhận lighthouse addr + token thay 16-hex).
- **Signing service @ HQ** (mới — REST quanh `nebula-cert sign`).
- Docs.

---

## 3. Kiến trúc mới

```
Field LAN (192.168.2.x)         Nebula overlay (10.10.20.x)            NVR(s)
  camera 192.168.2.64 ─┐                                                ┌─ NVR #1
  camera 192.168.2.65 ─┤  Raspberry Pi (passive)                       │   (nebula node, cert riêng;
                       └─► nebula + nvr-edge-agent + nginx stream{} ◄───┤    quét overlay /24, adopt)
                           per camera: overlayIP:onvifPort→:80          │
                                       overlayIP:rtspPort →:554         └─ …
                                   ▲
                                   │ discovery + hole-punch (UDP 4242)
                          ┌────────┴─────────┐
                          │  HQ box          │   • Nebula CA (ca.key — bí mật, offline-ish)
                          │  (always-on,     │   • Lighthouse (am_lighthouse, public IP:4242)
                          │   IP công khai)  │   • Signing service (REST: ký cert khi admin Cấp phép)
                          └──────────────────┘   • (tuỳ chọn) Relay (am_relay) khi NAT cứng 2 đầu
```

Vai trò từng thành phần:

| Thành phần | Vai trò |
|---|---|
| **HQ box** | Chạy **CA** (ký cert), **Lighthouse** (điều phối discovery, KHÔNG relay video), **Signing service** (REST ký cert theo lệnh NVR). Thay cho controller ztncui hiện tại. |
| **Pi gateway** | Chạy `nebula` (node overlay) + `nvr-edge-agent` (proxy L4 — **giữ nguyên**). Tự sinh keypair, chỉ gửi **public key** đi ký. Không giữ creds camera. |
| **NVR** | Là một **nebula node** (cert riêng). Chạy `GatewayScanner` (giữ nguyên) + `NebulaCaClient` (mới — bảo signing service ký cert cho Pi khi admin Cấp phép). |
| **App điện thoại** | Quét LAN hiện trường, `POST /enroll` đẩy **mã khu vực** (lighthouse addr + token) vào agent. Không giữ fleet token, không gọi NVR. |

**Video đi P2P trực tiếp** NVR ↔ Pi qua overlay Nebula; lighthouse chỉ giúp tìm nhau & đục NAT.

---

## 3b. Hai gói triển khai + đa khu vực / đa khách

Vì Nebula định nghĩa "mạng" bằng **CA (gốc tin cậy) + lighthouse + dải IP**, ta gói thành **2 gói bán**:

| | **Gói A — HQ riêng (chủ quyền / nội bộ)** | **Gói B — HQ chung (mình vận hành)** |
|---|---|---|
| Hạ tầng | Mỗi khách **1 box HQ riêng**: CA + lighthouse + signing service, chạy **trong mạng khách** | **1 HQ chung** của mình: lighthouse + signing service dùng chung |
| Cô lập giữa khách | Tuyệt đối (CA riêng + **air-gap được** + sống cả khi HQ của mình chết) | **CA con riêng mỗi khách** → cô lập **bằng mật mã** ngay trong HQ chung |
| Khu vực/chi nhánh của **cùng 1 khách** | Tách bằng dải /24 + group dưới CA của khách | Tách bằng dải /24 + group dưới CA của khách |
| Chịu cắt cáp quốc tế | ✅ lighthouse nội bộ khách | ✅ lighthouse của mình đặt tại VN (không dính planet roots ngoại) |
| Bán cho | Gói cao cấp / khách cần chủ quyền | Gói phổ thông / nhiều khách nhỏ |

> **Điểm chốt:** dùng Nebula **self-host lighthouse** → **cả hai gói đã có chủ quyền** (thứ ZeroTier
> không cho). Gói A **thêm**: cô lập per-khách bằng mật mã + air-gap (chạy 0 internet) + không phụ thuộc
> uptime HQ của mình.

### Cơ chế CA cho Gói B (CA-con-mỗi-khách + 1 lighthouse chung)

Nebula cho phép `pki.ca` của mỗi node chứa **một bó nhiều CA cert**. Khai thác để cô lập khách mà vẫn
dùng chung 1 lighthouse:

- **Infra-CA** (HQ): chỉ ký cert cho **lighthouse chung**.
- **CA-mỗi-khách** (`ca-khachA`, `ca-khachB`, …): ký cert cho gateway + NVR của riêng khách đó.
- `pki.ca` của **mỗi node khách** = `infra-CA` **+** `CA của chính khách`.
  → Node tới được lighthouse (tin `infra-CA`), **nhưng node khách A KHÔNG bắt tay được node khách B**
    (cert của B chain tới `ca-khachB`, không nằm trong bó của A) → **cô lập bằng mật mã**.
- `pki.ca` của **lighthouse** = `infra-CA` **+ tất cả** CA khách (để bắt tay mọi node — chỉ làm điều phối).

### "Mã khu vực" là gì / lấy ở đâu / tạo thế nào

- **Là gì:** chuỗi field worker gõ vào app (thay "16-hex network id" của ZeroTier). Gồm:
  1. **Địa chỉ lighthouse** `<public-ip>:4242` (Pi cần biết để vào overlay — ZeroTier không cần vì có
     planet roots toàn cầu),
  2. **Enrollment token một lần** (signing service dùng để biết Pi thuộc khu vực/CA/dải IP nào, và để duyệt),
  3. *(tuỳ chọn)* **CA fingerprint** để Pi pin đúng CA.

  > Gói gọn dạng 1 chuỗi (vd base64 của JSON `{lh, token, cafp}`) hoặc QR để field worker dán/scan.

- **Lấy ở đâu:** admin **sinh tại HQ** (signing service / trang quản trị), phát cho field worker qua
  chat/SMS — y như cách đưa 16-hex id hiện nay. Địa chỉ lighthouse cố định theo khu vực.

- **Khu vực khác nhau = dải /24 khác nhau:** region A = `10.10.20.0/24`, region B = `10.10.21.0/24`…
  NVR khu vực nào quét dải đó (`NEBULA_OVERLAY_SUBNET`).

- **Tạo khu vực mới (cùng 1 khách):** cấp 1 dải /24 + 1 group + phát token. **Không cần** tạo CA/lighthouse mới.
- **Tạo khách mới (Gói B):** `nebula-cert ca -name "khach-X"` → thêm `ca-khachX.crt` vào bó của lighthouse → phát token.
- **Khách mới (Gói A):** dựng nguyên **1 box HQ** cho khách (chạy script cài CA + lighthouse + signing).

> **PoC hiện tại** (2 Pi, 1 khu vực) = trường hợp đơn giản nhất: 1 CA, 1 lighthouse, 1 dải /24. Runbook
> mục 5 viết cho trường hợp này; phần đa khu vực/đa khách ở trên áp khi nhân rộng.

---

## 4. Chuẩn bị một lần

### 4.1 HQ box (CA + lighthouse + signing service)

> Chạy trên box HQ always-on có **IP công khai ổn định** (hoặc DDNS) và mở **UDP 4242**.

**a) Tạo CA** (giữ `ca.key` thật kỹ — mất = mất cả mạng):
```bash
nebula-cert ca -name "AIView-HQ"            # → ca.crt (phân phối) + ca.key (bí mật)
```
Gói B đa khách: tạo thêm **infra-CA** cho lighthouse và **CA-mỗi-khách** như mục 3b:
```bash
nebula-cert ca -name "infra"      # ký cert lighthouse
nebula-cert ca -name "khach-A"    # ký cert node khách A
```

**b) Ký cert cho lighthouse + dựng lighthouse** (`/etc/nebula/config.yml` trên HQ):
```bash
nebula-cert sign -name "lighthouse" -ip "10.10.20.1/24"      # ký bằng infra-CA ở Gói B
```
```yaml
pki:
  ca: /etc/nebula/ca.crt            # Gói B: bó infra-CA + tất cả CA khách
  cert: /etc/nebula/lighthouse.crt
  key: /etc/nebula/lighthouse.key
static_host_map: {}                 # lighthouse không cần
lighthouse:
  am_lighthouse: true
listen:
  host: 0.0.0.0
  port: 4242                        # mở UDP 4242 ra internet
firewall:
  outbound: [{port: any, proto: any, host: any}]
  inbound:  [{port: any, proto: any, host: any}]   # siết lại theo group khi lên prod
```
```bash
systemctl enable --now nebula      # lighthouse chạy + auto-start theo box
```
> **Endpoint + hosting:** lighthouse cần **endpoint công khai ổn định** (UDP 4242) — IP tĩnh public hoặc
> domain+DDNS; air-gap 1 site thì chỉ cần IP LAN. Hosting free tốt nhất = **Oracle Always Free** (IP public
> sẵn). Chi tiết + **phương án relay dự phòng** ở **§10**.

**c) Signing service (REST)** — service nhỏ ở HQ, NVR gọi để ký cert khi admin "Cấp phép":
- Nhận: `host.pub` (từ Pi qua NVR), tên gateway, **enrollment token**, khu vực.
- Xác thực token (một lần, có hạn) → chọn CA khách + cấp IP kế tiếp trong dải /24 + group `gateways`.
- Chạy: `nebula-cert sign -in-pub <host.pub> -name <ten> -ip <10.10.20.x/24> -groups gateways`
  → trả về `host.crt` + `ca.crt` (bó CA tương ứng) + thông tin lighthouse.
- Bảo vệ: chỉ cho NVR gọi (token `secrets/nebula_sign_token`), bind nội bộ/qua overlay, không lộ internet.

> Đây là phần **thay cho** việc NVR gọi REST controller ztncui authorize member hiện nay.

### 4.2 NVR (một lần)

NVR là 1 nebula node — keygen + xin signing service ký cert cho chính nó (IP vd `10.10.20.2/24`), ghi
`/etc/nebula/config.yml` (trỏ lighthouse), `systemctl enable --now nebula`.

`.env` (gitignored) — thay các khoá ZeroTier cũ:
```ini
NEBULA_OVERLAY_SUBNET=10.10.20.0/24      # dải overlay để scan (thay ZT_SCAN_SUBNET)
NEBULA_LIGHTHOUSE=<public-ip>:4242       # địa chỉ lighthouse khu vực
NEBULA_CA_URL=http://<hq-ip>:9443        # signing service REST
EDGE_AGENT_PORT=9797
GATEWAY_SCAN_INTERVAL_SEC=30
```
Token gọi signing service: `secrets/nebula_sign_token` (gitignored). Fleet token agent vẫn ở
`secrets/edge_agent_token` (giữ nguyên — agent vẫn cần để bảo vệ `/camera`, `/discovery`).

Deploy (như cũ):
```bash
docker compose build --no-cache nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx
```

---

## 5. Runbook — Pi3 MỚI TOANH ra hiện trường (luồng chính A→Z)

> Đối chiếu 1-1 với luồng ZeroTier ở `gateway_setup.md` (Part A/B/C). **Chỉ tầng overlay đổi**; bước
> quét/adopt/live-view **không đổi**. Theo quyết định **2026-06-06 (no-bake cho PoC)**, đường chính là
> **cài tươi**; golden image để sau khi có phần cứng chính thức.

### B0 — Cài Pi (cài tươi, dev/kỹ thuật viên)
```bash
# 1) Flash Pi OS Lite 64-bit (Debian trixie aarch64) ra thẻ ≥16GB, bật SSH.
# 2) Build agent trên máy dev (Go):
cd gateway/edge-agent && ./build.sh           # linux/arm64 (Pi 3/4 64-bit)
# 3) Copy sang Pi + cài (install.sh phiên bản Nebula: cài nebula + nginx-full + systemd unit):
scp -r edge-agent pi3@<pi-host>:~/
ssh pi3@<pi-host> 'sudo ./edge-agent/install.sh'
```
Sửa `/etc/nvr-edge-agent/agent.env` — chỉ đặt fleet token + field subnet; **chưa có cert, chưa có
lighthouse** (mã khu vực sẽ nạp từ app):
```ini
EDGE_AGENT_TOKEN=<fleet token — khớp secrets/edge_agent_token trên NVR>
EDGE_AGENT_NAME=gateway-nha-A
EDGE_AGENT_FIELD_SUBNET=192.168.2.0/24
# NEBULA_LIGHTHOUSE / cert: để trống — app /enroll sẽ nạp
```
```bash
sudo systemctl restart nvr-edge-agent
```

### B1 — Agent boot: tự sinh khoá (private key ở lại Pi)
Lần đầu chạy, agent `nebula-cert keygen` → `host.key` (**bí mật, KHÔNG rời Pi**) + `host.pub`. Chưa có
cert/lighthouse nên agent ở trạng thái **chờ enroll**.
```bash
curl -s localhost:9797/health        # trên Pi — báo enrolled=false, networkId/cert trống
```

### B2 — Hiện trường, app điện thoại (cùng LAN với Pi)
1. Power Pi + LAN (+ camera). Mở app → **Quét** → gateway hiện ra (`/identity`, chưa enroll).
2. Mở nó → **Thêm gateway này** → dán **Mã khu vực** admin đưa (lighthouse addr + token) → **Kết nối khu vực**.
   - App `POST /enroll` → agent **lưu lighthouse addr** + **gửi `host.pub` + tên gateway** lên signing
     service (qua đường HQ), trạng thái **pending**. App báo "đã gửi, chờ admin cấp phép".

### B3 — Admin, NVR Web UI → tab "Gateway"
3. Pi hiện trong mục **"Chờ cấp phép"** (amber). Bấm **Cấp phép** → NVR gọi **signing service**:
   `nebula-cert sign -in-pub host.pub -ip 10.10.20.x/24 -groups gateways` → trả `host.crt` + `ca.crt`.
   - NVR đẩy cert về agent → agent ghi `/etc/nebula/config.yml` (pki + `static_host_map` trỏ lighthouse) →
     `systemctl start nebula`. Pi **lên overlay sau ~vài giây**.
   - `config.yml` của Pi (rút gọn):
     ```yaml
     pki: {ca: /etc/nebula/ca.crt, cert: /etc/nebula/host.crt, key: /etc/nebula/host.key}
     static_host_map: {'10.10.20.1': ['<public-ip>:4242']}
     lighthouse: {am_lighthouse: false, hosts: ['10.10.20.1']}
     listen: {host: 0.0.0.0, port: 4242}
     # relay: {use_relays: true, relays: ['10.10.20.1']}   # bật khi NAT cứng 2 đầu
     firewall: { ... cho phép NVR group tới onvif/rtsp port ... }
     ```

### B4 — Scanner NVR tự phát hiện
4. `GatewayScanner` quét `NEBULA_OVERLAY_SUBNET=10.10.20.0/24`, thấy agent ở IP mới → chèn gateway đã
   **approved** (giống `DISCOVERED_AUTOAPPROVED` hiện nay). Online trong ~30s.
```bash
docker logs nvr-wrapper | grep -i gateway     # SCAN ... DISCOVERED_AUTOAPPROVED
```

### B5 — Thêm camera (GIỮ NGUYÊN như ZeroTier)
5. **Thêm camera** → **Quét LAN hiện trường** (hoặc **Camera đã sẵn sàng** nếu field worker đã tạo proxy
   ở trang `:9797`) → mỗi camera nhập **tên + ONVIF username/password** → **Thêm**.
   - NVR probe ONVIF qua proxy, rewrite RTSP host về overlayIP của gateway, auto-detect PTZ, ghi
     Frigate/go2rtc — **đúng code path cũ** (`CameraService.applyAndPersistFromOnvif`).

### B6 — Live view
6. Camera hiện ở **Live View / Playback / PTZ** như mọi camera khác. Xong.

**Tóm tắt thời gian:** B0 (cài tươi) ~10–15’ một lần / Pi; B1–B6 ngoài hiện trường ~vài phút, đúng bằng
luồng ZeroTier hiện tại — chỉ khác bước "join id" thành "enroll mã khu vực → admin Cấp phép ký cert".

---

## 6. Khác biệt vận hành phải lưu ý (so với ZeroTier)

| Điểm | Ý nghĩa |
|---|---|
| **Cert có hạn** | Nebula cert hết hạn (mặc định 1 năm). Cần **gia hạn/rotate** (ký lại định kỳ qua signing service). ZeroTier không có khái niệm này → đây là việc mới phải tự động hoá. |
| **Lighthouse cần IP công khai + UDP 4242** | ZeroTier dựa planet roots công cộng nên Pi chỉ cần id. Nebula cần lighthouse **reachable** → mở port + DNS/DDNS ổn định. |
| **NAT cứng 2 đầu → cần relay** | Khi cả gateway lẫn NVR sau NAT đối xứng, hole-punch fail → cấu hình **relay** (`am_relay` ở 1 node công khai). Cần **test thực địa**. |
| **Mất `ca.key` = mất CA** | Toàn bộ node phải cấp cert lại. **Backup `ca.key` nghiêm ngặt** (như mất `identity.secret` controller hiện nay). |
| **Enrollment token một lần** | Vá lỗ **G3** (hijack `/enroll`): token có hạn/dùng một lần → an toàn hơn "network-id không bí mật" của ZeroTier. |
| **Cô lập theo CA, không theo "số mạng"** | Phân vùng khách = CA; phân vùng khu vực = dải IP + group + firewall. Tư duy quản trị khác ztncui. |

---

## 7. Backlog code (impact khi thực thi)

**Viết lại / thêm:**
- `gateway/edge-agent/nebula.go` (thay `zerotier.go`): keygen, nhận cert, ghi `config.yml`, quản
  `nebula` service; `/enroll` nhận `{lighthouse, token, cafp}` thay 16-hex; `/health`+`/identity`
  báo trạng thái cert thay `networkId`.
- `gateway/edge-agent/install.sh`: cài **nebula** thay `zerotier-one` (giữ nginx + boot-race fix).
- NVR `backend/.../gateway/NebulaCaClient.kt` (thay `ZtControllerClient`): gọi signing service ký cert.
  `GatewayService.approve()` chuyển từ "authorize member" → "ký cert + đẩy về agent".
- **Signing service @ HQ** (mới): REST quanh `nebula-cert sign -in-pub` + hàng đợi pending + verify token
  + cấp IP/group theo khu vực + endpoint **`/healthz`**. Thay vai trò ztncui REST.
- App: đổi ô nhập "Network ID 16-hex" → "Mã khu vực" (lighthouse + token); cập nhật README.
- `.env` keys (`NEBULA_*`), `secrets/nebula_sign_token`; docs.
- **Watchdog/giám sát (§11):** `/health` agent báo nebula/cert/overlay; HQ healthcheck (lighthouse/signing/
  relay/UDP 4242/backup) + `Restart=always`; NVR poll + tab Gateway hiện trạng thái HQ + per-gateway; alert
  Telegram (tái dùng `nvr-watchdog`); **tự ký lại cert** trước hạn.
- **Relay (§10.3):** node `am_relay` (Oracle/VPS); `relay.use_relays` ở agent+NVR; cơ chế **failover standby
  relay** + tuỳ chọn **HA ≥2 lighthouse**.

**Giữ nguyên (không đụng):** `GatewayScanner`, `CameraService` adopt/ONVIF/PTZ, `GatewaysPanel.tsx`
(chỉ đổi nhãn UI), Frigate/go2rtc, model `Cameras` unified.

**Ước lượng:** ~vài tuần (khớp đánh giá ở `commercialization-security-notes.md`).

---

## 8. Rủi ro / câu hỏi mở

- **NAT traversal Nebula** chưa chắc "auto" mượt như ZeroTier ở mọi mạng nhà dân → **bắt buộc test thực
  địa**, có thể phải dựng relay. Đây là rủi ro kỹ thuật lớn nhất của việc đổi.
- **Cert lifecycle UX**: gia hạn cert hàng loạt, thu hồi (revoke) khi mất Pi — cần thiết kế (Nebula
  không có CRL online; thường dựa cert hết hạn ngắn + ký lại).
- **Vận hành đa CA (Gói B)**: quản bó CA của lighthouse khi thêm/bớt khách; backup nhiều `ca.key`.
- **HQ là điểm chết đơn**: lighthouse/signing chết → onboard mới + relay fallback ngừng (link P2P đang chạy
  vẫn sống). Giảm thiểu = **HA ≥2 lighthouse** + **watchdog/alert** (§10.3, §11) + backup `ca.key` (§6).

---

## 9. Ai đứng sau Nebula? Rủi ro license & phương án dự phòng

**Ai làm ra / duy trì:**
- Nebula được tạo tại **Slack Technologies** (Nate Brown, Ryan Huber + cộng sự), **open-source 2019**,
  license **MIT**, repo `github.com/slackhq/nebula`. Viết bằng Go, dựa **Noise Protocol Framework**
  (cùng họ mã hoá với WireGuard — được đánh giá tốt về bảo mật).
- Hai tác giả lập công ty **Defined Networking, Inc. (Mỹ, 2020)** để phát triển tiếp; **Slack vẫn là nhà
  tài trợ chính** của dự án OSS. Defined Networking có bán bản **SaaS managed**, nhưng **bản core OSS
  độc lập** — ta tự host lighthouse + CA, **không bắt buộc dùng dịch vụ của họ**.
- Vì self-host: **mình tự vận hành + tự vá** lighthouse/CA; bản vá bảo mật đến từ dự án OSS. Không có
  "đường dây" bắt buộc về máy chủ của họ (khác ZeroTier dựa planet roots ngoài).

**Lighthouse có phải "controller" không?** Nebula **không có daemon controller riêng** như ZeroTier.
"Control plane" = chính **CA (ký cert)** + signing service của mình — chỉ là CLI + REST do mình chạy.
**Lighthouse chỉ điều phối** (báo cho 2 node biết public `ip:port` của nhau để đục NAT), **không relay
video** (P2P trực tiếp, mã hoá Noise). Một box HQ có thể **kiêm cả ba** (CA + lighthouse + signing).
Muốn HA thì chạy **nhiều lighthouse**.

**Rủi ro "Nebula đổi license" — và vì sao KHÔNG phải bỏ thương mại lúc đó:**
- **MIT là không thể thu hồi với code đã phát hành.** Defined Networking có relicense các **bản tương
  lai** thì **mọi bản đã ra dưới MIT vẫn MIT vĩnh viễn** → mình luôn **fork bản MIT cuối** và đi tiếp.
  Đây là khác biệt căn bản với ZeroTier (vốn restrictive từ gốc / chuyển sang BSL).
- Tiền lệ: Terraform→**OpenTofu**, Redis→**Valkey**, Elasticsearch, HashiCorp — cộng đồng fork bản mở
  cuối khi upstream siết license. Nebula có Slack tài trợ + lượng dùng lớn → relicense thù địch sẽ kích
  hoạt fork ngay.
- **Thiết kế của ta đã overlay-agnostic** (data path = nginx L4, scan/adopt/live-view không đổi) → đổi
  overlay lần nữa về sau là **việc có giới hạn (~vài tuần)**, không phải viết lại. Không bị khoá vào Nebula.

**Phương án cộng đồng / dự phòng (nếu muốn ít phụ thuộc 1 công ty hơn):**

| Lựa chọn | License | Mô hình | Ghi chú |
|---|---|---|---|
| **WireGuard thuần** | GPLv2 (trong nhân Linux) | Chuẩn, không 1 công ty kiểm soát | "Không thể bị lấy mất" — nhưng phải tự xây coordination/NAT |
| **innernet** (tonari) | **MIT** | Mesh WireGuard tổ chức theo **CIDR block** + server điều phối self-host | Gần mô hình "cert + dải IP" của Nebula nhất; cộng đồng, nhịp chậm |
| **NetBird** | **Apache 2.0** | Client + control server + relay đều OSS, self-host full | Công ty hậu thuẫn nhưng 100% mở; nặng hơn (WG + coturn) |
| **Headscale** | BSD-3 | Reimpl control server của Tailscale | Data plane vẫn dùng client Tailscale (của Tailscale Inc.) |
| **OpenZiti** (NetFoundry) | Apache 2.0 | Zero-trust nhiều tính năng | Nặng/phức tạp hơn |

> **Khuyến nghị:** chọn **Nebula** cho bản đổi này (RAM nhẹ, NAT tốt, cert-model khớp thiết kế), nhưng
> **mirror source bản MIT đang dùng** + giữ **WireGuard/innernet làm phương án rút lui**. Rủi ro license
> của Nebula **thấp hơn nhiều** ZeroTier vì MIT đã trao là không đòi lại được.

---

## 10. Hạ tầng lighthouse: endpoint, hosting free, relay dự phòng

### 10.1 Endpoint lighthouse (mỗi HQ)
Để Pi (hiện trường) và NVR tìm thấy nhau, **lighthouse phải reachable** từ cả hai. Tuỳ topology:

| Tình huống | Cần gì |
|---|---|
| **Air-gap / 1 site** (camera+gateway+NVR+HQ cùng mạng, không ra internet) | **Không cần domain/public** — lighthouse chỉ cần 1 IP LAN |
| **Gateway ở xa, khác mạng** (ca dùng thực tế) | Lighthouse cần **endpoint công khai ổn định** (UDP 4242) |

"Endpoint công khai ổn định" = **một trong hai** (không bắt buộc domain):
- **IP tĩnh public** → khai thẳng `IP:4242`.
- **IP động** → **domain + DDNS** trỏ về IP (Nebula `static_host_map` nhận cả hostname).

> Nebula chỉ cần **UDP 4242**, không cần web/HTTPS/domain đẹp. Domain chỉ để "đặt tên cho IP hay đổi".

### 10.2 Hosting free chịu được kha khá node
**Lighthouse rất nhẹ** — chỉ điều phối (node gửi "tôi ở đây" ~60s/lần + hỏi "peer X đâu"), **KHÔNG tải
video** (video P2P thẳng NVR↔Pi). Một box free cân **hàng nghìn node**:

| Dịch vụ | Free | Hợp để |
|---|---|---|
| **Oracle Cloud — Always Free** | VM ARM ~4 vCPU/24GB RAM, **IP public sẵn**, ~10TB egress/tháng, vĩnh viễn | **Tốt nhất** — lighthouse nghìn node + relay nhẹ |
| Google Cloud `e2-micro` | 1 VM nhỏ + IP public | Lighthouse coordination |
| AWS `t3.micro` | 12 tháng đầu | Tạm, sau trả phí |
| **DuckDNS / No-IP** | domain động miễn phí | Khi IP nhà hay đổi |

→ Khuyến nghị: lighthouse trên **Oracle Always Free** (IP public sẵn → khỏi mua domain).

### 10.3 Phương án relay dự phòng (khi P2P hỏng)
**Mặc định P2P trực tiếp** (đục NAT qua lighthouse), **không qua server**. Nhưng phải có đường lui:

- **Khi nào cần relay:** (a) NAT cứng/đối xứng **cả hai đầu** → hole-punch fail; (b) mạng chặn UDP P2P.
- **Cơ chế Nebula:** 1 node `relay.am_relay: true` (có public IP); các node khác đặt `relay.use_relays: true`
  + `relay.relays: ['<overlay-ip-relay>']`. Nebula **tự fallback** sang relay khi không bắt tay trực tiếp được.
- ⚠️ **Relay GÁNH VIDEO THẬT** → băng thông + CPU của box relay là **nút thắt** (tính dung lượng như nút
  thắt download NVR — xem CLAUDE.md "Băng thông & sức chứa camera qua gateway").
- **Cấp độ triển khai:**
  1. **Relay = chính box lighthouse** (Oracle free) cho lượng nhỏ — đơn giản, gộp một chỗ.
  2. **Relay riêng có thể scale** khi tải lớn — VPS băng thông cao, hoặc nhiều relay theo vùng.
  3. **Standby relay**: chuẩn bị sẵn 1 relay dự phòng; watchdog phát hiện relay chính/P2P sự cố → bật relay
     dự phòng (đổi `relay.relays` hoặc DNS của relay) → node tự hội tụ lại.
- **HA lighthouse:** chạy **≥2 lighthouse** (mỗi node liệt kê cả 2 trong `static_host_map` + `lighthouse.hosts`)
  → 1 lighthouse chết vẫn điều phối được; cũng giúp chịu cắt cáp/khu vực.

---

## 11. Watchdog & giám sát tình trạng (HQ + gateway)

Mục tiêu: biết **on/off của từng thành phần** (lighthouse, CA/signing, relay, mỗi gateway, mỗi NVR), **tự
phục hồi** và **cảnh báo**. Tận dụng hạ tầng sẵn có, không xây lại từ đầu:

- **`nvr-watchdog`** (Kotlin: DockerApi/SelfHealingEngine/UpdateManager) + **Telegram alert**
  (`TELEGRAM_BOT_TOKEN`/`CHAT_ID`) — **tái dùng kênh cảnh báo**.
- **Edge-agent `/health`** đã có (alive, RSS, camera count) → đổi nội dung từ "ZT joined/online" sang
  "**nebula up + cert còn hạn + overlay online**".
- **NVR `GatewayScanner`** đã quét subnet mỗi 30s → đã biết gateway online/offline; tab Gateway đã hiện trạng thái.

### A) Gateway watchdog (trên Pi)
- Agent tự giám sát `nebula.service`; down → `systemctl restart`. `Restart=always` cho cả `nebula` lẫn
  `nvr-edge-agent`.
- `/health` báo: `nebula_up`, `overlay_online` (ping lighthouse), `cert_expiry`, `nginx_ok`, `rss`.
- Proxy còn nguyên sau reboot (khôi phục từ `state.json`).

### B) HQ watchdog (trên box HQ)
- Giám sát: lighthouse process, **signing service** REST, **relay**, **UDP 4242 reachable từ ngoài**,
  **job backup `ca.key`** chạy đúng. `Restart=always` + healthcheck script + systemd timer.
- Signing service phơi **`/healthz`** công khai-nội-bộ để NVR poll biết HQ sống.

### C) Tổng hợp trạng thái ở NVR (nguồn sự thật cho admin)
- NVR poll: ping lighthouse overlay IP, signing `/healthz`, mỗi gateway `/health`.
- Tab Gateway thêm: **trạng thái HQ** (lighthouse/signing/relay) + **per-gateway** (online/cert-expiry/last-seen).
- **Quy tắc on/off:** gateway **online** nếu scanner thấy IP + `/health` ok trong N chu kỳ; **degraded** nếu
  cert sắp hết hạn; **offline** nếu mất M chu kỳ → **alert Telegram**.

| Thành phần | Healthcheck | Tự phục hồi | Cảnh báo |
|---|---|---|---|
| Gateway nebula | `/health`: overlay online + cert | `systemctl restart` | Telegram khi offline M chu kỳ |
| Gateway agent/nginx | `/health` + `nginx -t` | `Restart=always` | Telegram |
| HQ lighthouse | UDP 4242 reachable + process | `Restart=always` | Telegram + banner ở NVR |
| HQ signing service | `/healthz` | `Restart=always` | Telegram |
| Relay | process + băng thông | **failover standby relay** | Telegram |
| Cert (mỗi node) | `expiry < 30 ngày` | **ký lại tự động** qua signing | cảnh báo sớm |

---

> Tài liệu này mô tả **đích đến**. Khi bắt tay làm, theo thứ tự an toàn: (1) dựng CA + lighthouse +
> signing service ở HQ → (2) đưa NVR thành nebula node → (3) đổi agent + app → (4) test 1 Pi end-to-end
> (B0–B6) → (5) đo NAT/relay thực địa → (6) mới nhân rộng.
