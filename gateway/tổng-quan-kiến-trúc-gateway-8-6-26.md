# Tổng quan Kiến trúc Gateway — NVR Fleet

**Ngày:** 08/06/2026
**Phạm vi:** Kiến trúc + công nghệ nền tảng cho thiết bị **Gateway** đưa camera hiện trường về NVR qua mạng ảo tự chủ; cách giao tiếp giữa NVR ⇄ Gateway ⇄ App.
**Trạng thái:** Plan A (passive L4) chạy end-to-end thật. Tự host ZeroTier controller xong. Edge Agent + App Android + NVR Phase 1–3 **đã code & build**. Đang chờ test thực địa cách A (multi-region enroll) trên Pi mới.

> Đây là bản cập nhật của [`Cong-nghe-Gateway-5-6.md`](./Cong-nghe-Gateway-5-6.md). **Hai thay đổi lớn so với 05/06:**
> 1. **Plan A self-authorize:** admin onboard gateway **hoàn toàn từ Web UI NVR** — NVR tự gọi controller API để cấp phép member. **Bỏ bước duyệt tay trên ztncui.**
> 2. **Cách A multi-region:** một image Pi trung lập (không bake network id). App điện thoại `POST /enroll` để gắn mỗi gateway vào mạng ZeroTier của **từng khu vực**. Không còn nướng image riêng mỗi khu vực.

---

## 1. Vấn đề & mục tiêu

Camera IP nằm trong LAN từng hiện trường (nhà dân, cửa hàng…), sau NAT, **không có IP public**. NVR đặt ở trung tâm hoặc mạng khác. Cần đưa luồng camera (ONVIF điều khiển + RTSP video) về NVR mà:

- **Không port forward / không xin IP tĩnh** ở từng site.
- **Không cấu hình tay từng thiết bị** — flash thẻ là chạy.
- **Không nhét mật khẩu camera lên thiết bị hiện trường** (bảo mật multi-site).
- **Một NVR quản nhiều site**; một site dùng được bởi nhiều NVR (multi-NVR).
- **Nhiều khu vực**, mỗi khu vực một mạng ảo riêng, dùng **chung một image Pi**.
- RAM/CPU thấp (chạy được trên Raspberry Pi 256MB).

**Gateway** = thiết bị nhúng Linux đặt tại hiện trường, làm cầu nối **L4 thụ động** giữa camera LAN và NVR qua mạng overlay ảo.

---

## 2. Kiến trúc tổng thể

```
   ┌──────────── ZeroTier overlay (controller tự host ở HQ; 1 network / khu vực) ───────────┐
   │                                                                                        │
[HQ box — CONTROLLER]          [NVR Ubuntu — khu vực X]            [Gateway Pi — site, khu vực X]
 zerotier-one (controller)      zerotier-one (member)               zerotier-one (member, ~16-20MB)
 ztncui (web, chỉ để xem/backup) GatewayScanner (quét subnet)       Edge Agent Go (~4MB)
 REST API :9993 (authtoken)     ZtControllerClient (cấp phép API)   nginx-stream L4 (proxy onvif/rtsp)
 always-on, systemd, backup     go2rtc/Frigate (việc nặng)          camera ONVIF (cùng LAN)
                                adopt camera (admin gõ creds)
   ▲                                   ▲                                    ▲
   │  NVR tự gọi REST API cấp phép     │ admin: "Cấp phép" + adopt          │ FW: cắm điện+mạng → app → enroll
   │  (Plan A — KHÔNG dùng ztncui)     │ (đều remote, KHÔNG sờ Pi)          │
   └───────────────────────────────────┘                          [App Android Field Worker]
                                                                    (chỉ local LAN tới gateway)
```

Ba thành phần công nghệ:

| Thành phần | Công nghệ | Vai trò |
|---|---|---|
| **Data plane** (mạng ảo) | ZeroTier One (C++) | Mạng overlay L2/L3, tự đục NAT (hole-punch), P2P trực tiếp |
| **Control plane** (cấp phép) | zerotier-one *controller mode* + REST API `:9993` | Tự host, sở hữu network ID; **NVR gọi API cấp phép**. ztncui chỉ còn để xem/backup |
| **Edge logic** (gateway) | Edge Agent (Go) + nginx-stream | HTTP API thụ động + proxy L4 cổng ONVIF/RTSP từng camera |

---

## 3. Data plane — vì sao ZeroTier (đã chốt cứng)

| Tiêu chí | ZeroTier One | Vì sao chọn |
|---|---|---|
| RAM | **16–20MB cố định** | Vừa ngân sách Pi 256MB |
| NAT traversal | **Tự hole-punch + P2P trực tiếp** (~1.1ms) | Không cần port forward ở site |
| Fallback | Relay qua planet roots công cộng (free) | Site nào có internet là chạy |
| Mô hình | L2 ảo, mỗi node 1 địa chỉ 10-hex + IP trong pool | NVR quét subnet thấy gateway |

> **Khoá quyết định:** data plane = ZeroTier One. Đã loại WireGuard/Tailscale/Netbird/Headscale (lý do RAM + auto NAT). **Không bàn lại.**

- Planet roots = roots công cộng của ZeroTier (chưa tự dựng "moon" — để sau nếu cần air-gap).
- **Network id KHÔNG phải bí mật:** biết id chỉ để *join*; node vẫn ở trạng thái **unauthorized** cho tới khi admin cấp phép. → đưa id cho FW qua chat/SMS là an toàn.

### Multi-region (cách A — mới)

Một controller host **nhiều network, mỗi khu vực một network** (id = node-id 10-hex của controller + 6-hex số network, ví dụ `efb242bde2f3e856`, `efb242bde2f3e857`, …). **Cùng một image Pi trung lập** dùng cho mọi khu vực; app gắn mỗi gateway vào network của khu vực đó. Mỗi NVR khu vực: **cùng** `ZT_CONTROLLER_URL` nhưng riêng `ZT_NETWORK_ID` + `ZT_SCAN_SUBNET`. Cô lập khu vực do membership ZeroTier đảm bảo — NVR chỉ chạm được gateway trên network của chính nó.

---

## 4. Control plane — tự host controller + **NVR tự cấp phép (Plan A)**

Đã bỏ **ZeroTier Central** (cloud SaaS, cap node, phụ thuộc bên thứ ba). Hiện tại:

- **`zerotier-one` controller mode** trên box Ubuntu riêng ở HQ → tự sinh **network ID**. $0, **không cap node**, admin sở hữu network + REST API.
- **REST API `:9993`**, xác thực header `X-ZT1-Auth: <authtoken.secret>`. Đây là kênh NVR dùng để **liệt kê member + cấp phép**.
- **`ztncui`** (`:3443`, HTTPS self-signed, **chỉ LAN/ZeroTier — KHÔNG lộ internet**) giờ chỉ còn vai trò xem/sửa thủ công + backup; **luồng cấp phép thường ngày KHÔNG dùng tới nó nữa**.
- **systemd auto-start** + **backup định kỳ** `identity.secret` + `controller.d` off-box (mất identity = mất network ID = re-join cả fleet).
- `local.conf` phải `allowManagementFrom` các IP của NVR (LAN + subnet ZeroTier), nếu không mọi call API trả 401.

**Plan A — NVR tự cấp phép (thay bước ztncui tay):**
1. NVR (`ZtControllerClient`) gọi REST API liệt kê member **chưa authorized** trên network của nó.
2. Web UI NVR hiện mục **"Chờ cấp phép"** (amber) các member đó.
3. Admin bấm **"Cấp phép"** → NVR `POST` member với `authorized:true` qua REST API → controller cấp IP `10.147.20.x`.
4. **Một click:** khi `GatewayScanner` sau đó quét thấy IP này, nó chèn gateway như đã **approved** luôn (không có bước "Phê duyệt" riêng — log `DISCOVERED_AUTOAPPROVED`).

> Không có `ZT_CONTROLLER_URL` + secret `zt_controller_token` thì Web UI **ẩn** mục "Chờ cấp phép" (Plan A ngủ; quay lại cấp phép tay trên ztncui).

**Hiện trạng controller:**
- LIVE ở `192.168.2.106`, node `efb242bde2`.
- Network **`nvr-fleet`**, **ID `efb242bde2f3e856`**, pool `10.147.20.0/24`.
- NVR + Pi đã rời Central, chạy hẳn trên fleet tự host. Backup đã copy off-box.

---

## 5. Edge Agent — gateway thụ động (Plan A)

Phần mềm Go (~4MB) trên Pi. **Triết lý cốt lõi: thụ động.**

> **Gateway CHỈ là HTTP server. Nó KHÔNG bao giờ chủ động gọi NVR.** NVR mới là client: NVR tự quét subnet ZeroTier (`GatewayScanner`) để phát hiện gateway rồi điều khiển. → Gateway không couple vào 1 NVR (multi-NVR ready), không tự đăng ký/đẩy dữ liệu.

**Mỗi camera, agent chỉ cấp 2 cổng `nginx stream{}` forward L4:**
- `onvifPort` → `<camera_ip>:80` (điều khiển ONVIF/PTZ)
- `rtspPort` → `<camera_ip>:554` (video RTSP)

**Agent KHÔNG:** giữ mật khẩu camera, chạy ONVIF SOAP, restream/decode video. Mọi việc nặng (probe ONVIF, go2rtc, Frigate) ở NVR. → **Mật khẩu camera chỉ tồn tại trên NVR** (admin gõ lúc adopt). Vì chỉ forward byte L4, **RAM agent ~const (~4MB), không phụ thuộc độ phân giải camera**.

State (camera + cổng + **networkId đã enroll**) lưu `state.json` → proxy + khu vực sống sót qua reboot.

### API của Edge Agent (cập nhật — có `/enroll`)

Auth: `Bearer <fleet token>` cho caller từ xa (ZeroTier). **Miễn token (Tier 0):** loopback, và 3 endpoint app `/identity` + `/label` + `/enroll` (app field tới qua LAN; read-only / cosmetic / chọn-khu-vực). Mọi thao tác đụng camera/proxy vẫn sau fleet token (chỉ NVR).

| Method | Path | Auth | Vai trò |
|---|---|---|---|
| GET | `/health` | token | Trạng thái + RAM + `networkId` + `ztAddress/ztJoined/ztOnline` + camera list (NVR đọc) |
| GET | `/identity` | **không** | App field scan + nhận diện (id, tên, **networkId**, ztAddress, số camera, joined) |
| POST | `/label` | **không** | App đặt tên/người lắp/vị trí → `state.json` (đè `EDGE_AGENT_NAME`) |
| **POST** | **`/enroll`** | **không** | **App gắn gateway vào network khu vực: `{networkId}` (16-hex) → agent leave mạng cũ, join mạng này, persist. Trả `{networkId, ztAddress, ztJoined, ztOnline, zerotierIp, requested}`. 409 nếu đã có camera (van an toàn).** |
| POST | `/discovery` | token | WS-Discovery quét camera trong LAN field |
| POST/DELETE | `/camera` | token | NVR cấp/thu hồi cổng proxy cho 1 camera |
| GET | `/cameras` | token | NVR đọc danh sách camera đã proxy |
| GET | `/` | loopback | Trang web field (scan + add, không nhập creds) |

- `ztAddress` = địa chỉ ZeroTier 10-hex của node — **đúng member NVR cấp phép** qua controller API (Plan A).
- `ztJoined` = đã join network + config OK; `ztOnline` = đã chạm planet roots.

**Cơ chế join (mới — `ztJoinLoop`):** thay vì join 1 lần lúc boot, agent chạy **một goroutine nền** đọc `currentNetworkID()` mỗi nhịp và hội tụ node về đúng network đó. Xử lý gọn cả 3 ca: (a) boot-race (zerotier-one API chưa sẵn), (b) chưa enroll (chưa có network id → đứng chờ), (c) đổi khu vực (enroll id mới → leave cũ, join mới). Log throttle để khỏi spam.

> **Van an toàn `/enroll`:** no-auth (Tier 0) nhưng **từ chối đổi network nếu gateway đã có camera (409)** — chặn hijack + tránh proxy nginx mồ côi bám IP ZeroTier cũ. Cộng với "network id không bí mật + bắt buộc admin cấp phép" → đủ an toàn cho Tier 0.

---

## 6. Phía NVR (Kotlin)

- `GatewayScanner` quét `ZT_SCAN_SUBNET` `/health` → phát hiện gateway (auto-connect; gateway **không** đăng ký).
- `ZtControllerClient` nói REST API controller (`X-ZT1-Auth` từ Docker secret `zt_controller_token`) → list member chưa authorized + cấp phép.
- `GatewayService` cấp phép + adopt: admin gõ ONVIF creds → NVR probe ONVIF **qua proxy**, rewrite RTSP host về `<ztIP>:<rtspPort>`, **auto-detect PTZ**, ghi Frigate/go2rtc.
- `DELETE /api/gateways/{id}` (Phase 3): **cascade** gỡ camera của gateway + xoá entry; list/UI enrich (ztAddress / người lắp / vị trí).
- Tái dùng đúng một code path camera với Web UI (`CameraService.applyAndPersistFromOnvif`) → cloud + Web UI + gateway thấy cùng danh sách.
- Live view lên hình qua go2rtc/Frigate như camera LAN thường.

**Map dữ liệu:** camera qua gateway lưu `cameras.ip = <ztIP gateway>`, `onvif_port = <cổng proxy ONVIF>`, `assigned_rtsp_port`, `gateway_id`, `local_ip`. Vì `ip`/`onvif_port` có giá trị nên **toàn bộ code ONVIF/PTZ/live-view cũ chạy y nguyên** — lớp gateway chỉ tính ra mấy giá trị đó.

---

## 7. Luồng onboarding một gateway mới (Tier 0 — cập nhật)

**Vẫn còn đúng 2 cú click của admin, nhưng cả 2 giờ đều ở ngay trên Web UI NVR** (không mở ztncui nữa, KHÔNG ai sờ Pi).

| Khi nào | Ai | Làm gì | Sờ Pi? |
|---|---|---|---|
| 1 lần / cả fleet | Admin | Dựng controller HQ + chuẩn bị 1 image trung lập | — |
| Mỗi thiết bị | Admin **hoặc** FW | Flash image vào thẻ SD (Pi Imager, ~5 phút) | cắm thẻ |
| Tại hiện trường | **FW** | Cắm điện + mạng → app → scan → chọn gateway → **nhập Network ID khu vực → "Kết nối khu vực"** (POST `/enroll`); tuỳ chọn đặt tên | chỉ cắm dây |
| Sau enroll | **Admin ①** | Web UI NVR → mục "Chờ cấp phép" → **"Cấp phép"** (NVR gọi controller API) | ❌ remote |
| Sau cấp phép | **Admin ②** | Web UI NVR → **adopt camera** (gõ ONVIF creds) | ❌ remote |

**Vì sao 2 cú admin không bỏ:**
- *Cấp phép* = rào "thiết bị lạ không tự chui vào mạng ảo". Bỏ được nếu nướng pre-auth key vào image, nhưng ai cầm image cũng join → rủi ro. Tier 0 giữ admin cấp phép.
- *Adopt + gõ creds* = gateway **không bao giờ** giữ mật khẩu camera (multi-NVR). Creds chỉ ở NVR.

---

## 8. Gateway image — **PoC: KHÔNG bake golden image**

> **Quyết định 06/06/2026 (user):** PoC này (1 prod + 1 spare) chạy trên phần cứng sẵn có. **Hoãn việc bake golden image** đến khi có phần cứng chính thức — lúc đó mới bake cho đúng phần cứng đó. Hiện tại đừng đầu tư image/bake automation.

Tham chiếu cho lần bake sau (cách A):
- Image gồm: OS + `zerotier-one` + **`ZT_NETWORK_ID` để TRỐNG** (app enroll khu vực) + Edge Agent + nginx-stream + fleet token + fix nginx boot-race.
- `edge-agent/bake-prep.sh`: xoá ZeroTier identity/state/log + machine-id + ssh host keys + **để trống network id** (giữ fleet token). Mỗi Pi flash ra tự sinh identity riêng ở boot đầu.
- `install.sh` (đã sửa): cài cả ZeroTier + nginx-full, wire `stream{}`, systemd unit, có sẵn fix boot-race.
- Boot đầu: sinh identity → agent chạy nhưng **chưa join mạng nào** (chờ enroll) → FW enroll qua app → join đúng khu vực → hiện member chờ trên NVR → admin cấp phép.

> ⚠️ **Gotcha nginx boot-race:** proxy nginx bind `<ztIP>:port`; lúc boot nginx có thể start TRƯỚC khi zerotier-one dựng IP → "cannot assign requested address" → proxy chết. Fix đã bake: `sysctl net.ipv4.ip_nonlocal_bind=1` + systemd drop-in `After=zerotier-one` + `Restart=on-failure`.
> ⚠️ **Gotcha ZT join boot-race:** lệnh `zerotier join` chạy trước khi `zerotier-one` mở `.port` API → join fail im lặng. Fix: `ztJoinLoop` retry nền tới khi API sẵn.

---

## 9. App Android onboarding (Phase 2 — đã code, chờ test thật)

- Package `vn.aiview.nvr.gatewayonboard` (Kotlin/Jetpack Compose, OkHttp, kotlinx.serialization).
- Luồng: **Quét** gateway (qua `/identity` trên LAN field) → mở → **Thêm gateway này** → nhập **Network ID 16-hex** khu vực → **"Kết nối khu vực"** (`POST /enroll`) → báo "chờ admin cấp phép". Tuỳ chọn đặt tên (`/label`).
- Màn chi tiết hiện cả **"Khu vực (network)"** = networkId hiện tại của gateway.
- App **không bao giờ** chạm tới NVR (khác mạng, NVR không lộ) và **không** giữ ZeroTier API token.
- APK debug: `android-app/app/build/outputs/apk/debug/app-debug.apk`.

---

## 10. Băng thông & sức chứa (capacity planning)

**Luồng:** `Camera → Gateway(Pi) → [UPLOAD site] → internet → [DOWNLOAD site NVR] → NVR`. Mỗi camera kéo **2 luồng** (main=record, sub=detect) 24/7.

**Nút thắt = DOWNLOAD của NVR**, KHÔNG phải RAM/CPU Pi (gateway chỉ forward L4, không decode → RAM ~const, không phụ thuộc res). Trần là **TỔNG camera toàn hệ thống** (download NVR là 1 ống chung; cắm thêm gateway KHÔNG mở rộng).

Bitrate/camera (H.265, 30fps, ước lượng):
- **Config A (Full):** 4K main + FullHD sub ≈ **12–16 Mbps/cam**
- **Config B (Low):** FullHD main + 720p sub ≈ **4–5.5 Mbps/cam**

Hạ tầng đo 06/06/2026: NVR **download 68 / upload 99 Mbps**. Chừa ~30% headroom (ZeroTier chập chờn + RTSP-over-TCP retransmit) → thực ~48 Mbps:

| Config | Max camera (TỔNG hệ thống) |
|---|---|
| A — Full (4K) | **~3 camera** |
| B — Low (FullHD/720p) | **~9–10 camera** |

→ "Điểm ngọt": **main FullHD + sub 720p**. Muốn nhiều cam 4K hơn → **chỉ có cách nâng gói DOWNLOAD của NVR**. Đổi resolution làm **trên chính camera** (ONVIF `SetVideoEncoderConfiguration`), không phải trên gateway/NVR.

**Triệu chứng quá tải băng thông** (RTSP-over-TCP qua ZT chập chờn → kẹt→đơ→rớt, KHÔNG vỡ hình nhiễu hạt): live lag/freeze/đen-xám; record thủng đoạn; camera flapping online↔offline; log Frigate `Unable to read frames`/go2rtc reconnect loop. Hiệu ứng dây chuyền: 1 luồng nghẽn → TCP retransmit → buffer ffmpeg đầy → Frigate restart stream → thêm tải → càng tải càng sập.

---

## 11. "Hợp đồng gateway" — đổi sang phần cứng khác

Kiến trúc **không khoá vào Raspberry Pi**. Thiết bị nào thoả là chạy:

1. **Embedded Linux** (KHÔNG phải MCU trần ESP32/RTOS).
2. CPU build được cả 3: `zerotier-one` (C++), Edge Agent (Go, cross-compile lại), forward L4 (nginx-stream).
3. RAM ≥ ~64–128MB (256MB thoải mái).
4. 1 init system auto-start (systemd / procd / buildroot init).
5. Mạng: 1 interface tới LAN camera + đường ra internet.
6. Bộ nhớ flashable/imageable.

**Phải làm lại khi đổi thiết bị:** cross-compile Edge Agent cho arch mới, lấy `zerotier-one` cho arch đó, viết lại unit dịch vụ theo init mới, đóng image theo định dạng nạp mới. `ZT_NETWORK_ID` (để trống, app enroll) + fleet token dùng chung.

**KHÔNG đổi (tài sản tái dùng):** logic Edge Agent, giao thức với NVR + app (`/health`,`/identity`,`/label`,`/enroll`,`/camera`,`/discovery`), data plane ZeroTier, controller HQ + REST API, app Android, toàn bộ phía NVR.

> ⚠️ MCU trần không Linux → cả stack KHÔNG chạy → phải viết lại bằng `libzt` + forwarder tự code = **một sản phẩm khác về kỹ thuật**. Khi làm sản phẩm mới, chọn thiết bị chạy Linux (Pi, Orange Pi, NanoPi, router OpenWrt đủ RAM/flash).

---

## 12. Trạng thái & còn lại (08/06/2026)

| Hạng mục | Trạng thái |
|---|---|
| Plan A passive L4 gateway (multi-NVR, no creds on Pi) | ✅ chạy end-to-end thật trên Pi3 |
| Tự host ZeroTier controller + cutover Pi3 + backup off-box | ✅ |
| **Plan A NVR self-authorize qua controller API (bỏ ztncui tay)** | ✅ code & deploy |
| Edge Agent Phase 1 (`/identity`, `/label`, `ztAddress`) | ✅ |
| **App Android onboarding (Phase 2)** | ✅ code & build APK; ⏳ chờ test thật |
| **NVR `DELETE /api/gateways/{id}` cascade + enrich (Phase 3)** | ✅ code & deploy |
| **Cách A multi-region app enroll (`/enroll`, image trung lập)** | ✅ code & build; ⏳ chờ test thật trên Pi mới |
| Golden image bake automation | ⏸️ **hoãn** (quyết định PoC 06/06: chờ phần cứng chính thức) |

**Known limitation:** camera PTZ `OSSD2MPVF33XPTZAI` (`.64`) under-report — PTZ thật nhưng không nhận pan/tilt qua ONVIF `ContinuousMove` (auto-PTZ phân loại nhầm `zoom`); cần tìm phương thức pan thật (AbsoluteMove/RelativeMove/space riêng/API vendor).

**Pending test (cách A):** cắm Pi .71 (đã re-bake) → boot → cài APK → app enroll network `efb242bde2f3e856` → NVR "Cấp phép" → adopt camera → feedback.

---

## 13. Bảo mật — nguyên tắc bất biến

- **Mật khẩu camera chỉ ở NVR**, không bao giờ trên gateway (Plan A).
- **ztncui không lộ internet** — chỉ LAN/ZeroTier. **Controller authtoken** (`secrets/zt_controller_token`) là bí mật, chỉ ở NVR (Docker secret).
- **Mất `identity.secret` controller = mất network ID** → backup off-box bắt buộc.
- Fleet token (`secrets/edge_agent_token`) dùng chung, gác mọi endpoint đụng camera/proxy của agent.
- `/identity` + `/label` + `/enroll` miễn token (Tier 0) nhưng **không đụng camera, không lộ creds**; `/enroll` có van an toàn 409 (không đổi network nếu đã có camera).
- **Network id KHÔNG phải bí mật** (node vẫn cần admin cấp phép) → đưa cho FW thoải mái.
- App field **không bao giờ** tới được NVR và **không** giữ ZeroTier API token.

---

*Tài liệu mô tả công nghệ Gateway của hệ NVR Fleet tới 08/06/2026. Tham chiếu code: `edge-agent/`, `backend/.../gateway/`, `hq-controller/`, `android-app/`; kế hoạch: `specs/004-gateway-onboarding/plan.md`; vận hành: `gateway_setup.md`, `controller_hq_setup.md`. Bản trước: `Cong-nghe-Gateway-5-6.md`.*
