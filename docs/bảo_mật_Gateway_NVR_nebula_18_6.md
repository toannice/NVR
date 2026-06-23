# Bảo mật Gateway NVR — Nebula Overlay
> Tổng hợp phân tích bảo mật ngày 2026-06-18

---

## 1. Kiến trúc hiện tại

```
[Camera LAN 192.168.2.x]
        ↕  L4 proxy (nginx stream)
[Pi Gateway]  ←— field worker cắm tại hiện trường
        ↕  Nebula overlay (mã hoá Noise/ChaCha20)
[NVR — nvr-wrapper]
        ↕  HTTP REST
[HQ — lighthouse + signing service @ 192.168.2.106:9443]
```

### Thành phần trên Pi (sau khi deploy)
| File / Service | Nội dung |
|---|---|
| `host.key` + `host.crt` | Nebula private key + cert (group `gateways`) |
| `/etc/nvr-edge-agent/agent.env` | `EDGE_AGENT_TOKEN` (fleet token), field subnet |
| `/var/lib/nvr-edge-agent/state.json` | IP camera, cổng proxy |
| `/etc/nginx/stream.d/cameras.conf` | Mapping proxy port → camera IP:port |
| `nebula` daemon | Overlay VPN, cert 90 ngày |
| `nvr-edge-agent` | Agent API (:9797), WS-Discovery, nginx manager |

### Trạng thái bảo mật hiện tại
| # | Hạng mục | Trạng thái |
|---|---|---|
| G1 | `/camera` chỉ nhận localIp trong field subnet | ✅ done |
| G2:C | Cert-based auth thay fleet token (Nebula firewall group) | ✅ code done, cần bật qua `NEBULA_FW_NVR_GROUP=nvr` |
| G3 | `/enroll` chỉ mở trong cửa sổ N phút sau boot | ✅ done |
| G4 | Agent chạy root + `ip_nonlocal_bind=1` global | 🔴 chưa làm |
| G5 | API bind `0.0.0.0` lộ `/identity` ra LAN field | 🔴 chưa làm |
| G6 | nginx config injection qua tên camera | ✅ done |
| Cert lifecycle | 90 ngày, auto-renew khi online | ✅ done |
| HQ watchdog | Alert Telegram lighthouse/signing down | ✅ done |
| Per-Pi watchdog | Alert khi Pi offline | 🟡 thiết kế xong, chưa wire |
| Revoke từ NVR UI | Nút "Thu hồi" trên tab Gateway | 🔴 chưa làm |

---

## 2. Kịch bản tấn công — Pi bị lấy vật lý

### 2.1 Những gì attacker đọc được từ SD card

Tháo SD card, mount trên máy khác → đọc được toàn bộ:
- `host.key` + `host.crt` → identity hợp lệ trên Nebula overlay
- `EDGE_AGENT_TOKEN` → fleet token
- `state.json` + nginx conf → IP tất cả camera tại site đó

**Không đọc được:** credentials camera (chỉ lưu trên NVR).

### 2.2 Kịch bản A — Pi bị tắt vĩnh viễn

```
T+0       Pi bị lấy → cert + token bị đọc offline
T+0~7d    [đề xuất] NVR auto-revoke sau N ngày offline
T+90d     Cert hết hạn tự nhiên (nếu không revoke)

Cửa sổ nguy hiểm: T+0 → T+revoke
Attacker có thể làm: giả danh Pi từ máy khác → probe overlay
Attacker không làm được: reach camera (Pi off, không có proxy)
```

### 2.3 Kịch bản B — Pi bị bật lại trên mạng giống hiện trường

Pi ở mạng `192.168.2.x` khác nhưng không có camera thật:
- Có overlay IP hợp lệ → scan subnet `10.10.20.0/24` → biết IP NVR + tất cả Pi khác
- Probe NVR từ overlay nếu NVR firewall chưa chặn inbound từ group `gateways`
- Credentials camera bị capture từ trước → dùng được cho đến khi rotate

**Không làm được:** decrypt traffic giữa NVR ↔ Pi khác (Noise Protocol point-to-point).

### 2.4 Kịch bản C — Nguy hiểm nhất: Pi bật lại đúng mạng hiện trường thật + camera thật

```
Attacker kiểm soát Pi vật lý + ở cùng LAN field:

1. Capture RTSP credentials
   Camera → [Nebula decrypt tại Pi] → nginx forward → Camera
   Tại Pi: RTSP stream (bao gồm rtsp://user:pass@IP) chạy plaintext
   → tcpdump -i any port 554 → lấy được credentials

2. Truy cập camera trực tiếp từ LAN
   Biết IP từ state.json + creds vừa lấy
   → rtsp://user:pass@192.168.2.x:554 trực tiếp, không qua NVR

3. ONVIF management plane (nguy hiểm hơn chỉ xem stream)
   Với credentials → gọi ONVIF SetUser, đổi config camera, xóa recording
   Không chỉ xem — có thể phá hoại

4. Pi làm backdoor dài hạn (nếu modify rồi trả lại hiện trường)
   Pi trả về vị trí cũ → NVR scanner thấy healthy bình thường
   Nhưng Pi đang duplicate stream về server attacker
   → Không phát hiện được từ NVR trừ khi monitor băng thông

5. WS-Discovery enumeration
   Gửi ONVIF multicast probe → tất cả camera trên LAN tự báo IP + model
   Kể cả camera chưa add vào NVR → biết topology đầy đủ site
   (Lưu ý: bất kỳ thiết bị nào trên LAN đều làm được, không chỉ Pi)
```

---

## 3. Phân tích từng vấn đề bảo mật + cách xử lý

### 3.1 Fleet token dùng chung toàn fleet

**Vấn đề:** token bị đọc từ 1 Pi → dùng được trên tất cả Pi chưa bật G2:C.

**Fix:** Bật G2:C trên toàn bộ fleet qua golden image:
```ini
# /etc/nvr-edge-agent/agent.env
NEBULA_FW_NVR_GROUP=nvr
```
Khi G2:C active, Nebula firewall chặn mọi connection không phải group `nvr` → fleet token trong file trở nên vô nghĩa dù bị đọc.

**Điều kiện:** NVR phải được ký cert với `-groups nvr` khi approve.

**Đánh đổi:** Không có — chỉ cần đồng nhất trong golden image.

---

### 3.2 Signing service URL lộ trong config Pi

**Vấn đề:** Pi biết `NEBULA_CA_URL` → attacker biết địa chỉ HQ → probe/brute-force signing service.

**Fix:**
- Signing service bind vào overlay interface (chỉ NVR group reach được)
- Hoặc nếu public: rate-limit + chỉ nhận từ overlay IP của NVR
- Token auth đã có → thêm rate-limit 10 req/phút/IP + log burst 401/403 → alert

**Đánh đổi:** Không có đáng kể.

---

### 3.3 RTSP credentials bị capture tại Pi (vấn đề kiến trúc)

**Vấn đề:** Pi là điểm cuối giải mã Nebula. Sau giải mã, RTSP (bao gồm `user:pass`) chạy plaintext từ overlay interface → nginx → camera. `tcpdump` tại Pi lấy được.

**Fix không thể hoàn toàn** (giới hạn L4 proxy). Giảm thiểu:

**a) Rotate credentials định kỳ khi Pi online (24h):**
```
Mỗi 24h, khi Pi đang online:
NVR → ONVIF SetUser → đổi password camera → cập nhật config NVR
```
Credentials bị capture luôn cũ tối đa 24h.

*Lưu ý quan trọng:* không thể rotate khi Pi offline — Pi là đường duy nhất NVR reach camera. Rotate chỉ làm được khi Pi đang sống.

**b) Camera user readonly:**
Tạo user `nvr_stream` trên camera, chỉ quyền xem stream, không có quyền SetUser/config. Dù credentials bị lấy → chỉ xem được, không phá hoại được.

**c) RTSPS (nếu camera hỗ trợ):**
RTSP over TLS → bytes trên Pi là ciphertext → tcpdump vô nghĩa. Hầu hết camera nhà dân không hỗ trợ.

---

### 3.4 Không có cơ chế phát hiện Pi bị chuyển đi

**Vấn đề:** Pi bị lấy đi mạng khác → NVR không biết.

**Fix:** Pi báo thêm trong `/health`:
```json
{
  "publicIp": "1.2.3.4",
  "lanGateway": "192.168.2.1",
  "connOutboundCount": 12
}
```
NVR so sánh với baseline → `publicIp` hoặc `lanGateway` thay đổi → alert ngay.

`connOutboundCount` bất thường (Pi đang gửi stream đi 2 nơi) → dấu hiệu backdoor.

---

### 3.5 Không có honeypot để phát hiện credentials bị dùng

**Fix:** Thêm 1 camera entry với credentials fake (`honeypot_user:honeypot_pass`) trỏ đến IP không tồn tại. Camera này không bao giờ kết nối được bình thường. Nếu có auth attempt với `honeypot_user` từ IP lạ → alert ngay.

**Effort:** ~2h. Không ảnh hưởng gì hệ thống thật.

---

### 3.6 Revoke Pi chỉ làm được thủ công trên HQ

**Vấn đề:** khi phát hiện Pi mất, admin phải SSH vào HQ để revoke. Chậm.

**Fix:** Nút "Thu hồi" trên NVR UI tab Gateway:
- Lưu `token → gatewayId` mapping khi approve
- Nút bấm → NVR gọi `POST /revoke` trên HQ → cert vào blocklist → lighthouse reload

**Cần làm:** ~3h code.

---

### 3.7 Auto-revoke sau N ngày offline

**Vấn đề:** cert 90 ngày quá dài nếu không ai biết Pi bị mất.

**Fix:** NVR tự revoke Pi offline > N ngày (mặc định 7 ngày, cấu hình được):
```
Pi offline > 7 ngày → NVR gọi HQ /revoke → cert vào blocklist
→ Pi bật lại cũng không vào overlay được
```

**Downside:** Pi bị mất điện lâu + không ai biết → bị revoke, phải enroll lại. Chấp nhận được.

---

### 3.8 Không có hardware watchdog

**Vấn đề:** kernel freeze → phải rút điện tay.

**Fix:** 3 dòng trong `install.sh`:
```bash
apt-get install -y watchdog
echo "bcm2835_wdt" >> /etc/modules
# /etc/watchdog.conf: watchdog-device = /dev/watchdog, max-load-1 = 24
```
Pi tự reboot sau ~60s nếu kernel hang. Không có downside.

---

### 3.9 NVR firewall Nebula chưa chặn inbound từ gateways group

**Vấn đề:** Pi giả danh (cert bị lấy) có thể probe NVR trên overlay.

**Fix:** NVR chỉ cần scan *ra* Pi (outbound), không cần nhận inbound từ Pi:
```yaml
# Nebula config NVR
firewall:
  inbound:
    - port: any
      proto: any
      group: nvr      # chỉ NVR node khác (HA)
  outbound:
    - port: 9797
      proto: tcp
      group: gateways # NVR scan ra Pi
```

**Không có downside.**

---

## 4. Tác động lên tài nguyên Pi (Pi 3B+)

| Thành phần | RAM | CPU | Network |
|---|---|---|---|
| OS + baseline | ~100MB | thấp | — |
| Nebula daemon | ~30MB | thấp (idle) | +~5% overhead mã hoá |
| nginx L4 proxy | ~5MB | rất thấp | = tổng bitrate camera |
| edge-agent | ~15MB | rất thấp | health poll |
| **Tổng baseline** | **~150MB** | **thấp** | **~3-4 Mbps/camera** |
| Tất cả additions bảo mật | **~0** | **~0** | không đáng kể |

Tất cả additions (health reporting, watchdog, IP monitoring) chạy định kỳ, không ảnh hưởng video proxy.

**Bottleneck thực tế:** số camera × bitrate = băng thông LAN (100Mbps trên Pi 3B+).
- FullHD H.265 main + 720p sub ≈ 3-4 Mbps/camera
- Pi 3B+: ~4-5 camera trước khi đạt giới hạn
- Pi 4 (Gigabit): 10+ camera thoải mái

---

## 5. Danh sách việc cần làm (ưu tiên)

### Blocking trước khi thương mại
| # | Việc | Effort |
|---|---|---|
| 1 | Dời lighthouse ra public (Oracle Always Free, UDP 4242) | ~2h |
| 2 | Cấu hình relay trên box HQ | ~30m |
| 3 | Update `bake-prep.sh` từ ZeroTier → Nebula | ~30m |
| 4 | Bật hw watchdog trong `install.sh` | ~10m |
| 5 | Agent dùng MAC làm tên mặc định (thay hostname) | ~30m |
| 6 | Test Pi thật A→Z cross-network (NAT traversal) | ~1 buổi |

### Nên làm trước giao khách đầu tiên
| # | Việc | Effort |
|---|---|---|
| 7 | Nút "Thu hồi" trên NVR UI + lưu token mapping khi approve | ~3h |
| 8 | Auto-revoke sau N ngày offline | ~2h |
| 9 | Pi báo publicIp + lanGateway trong health check | ~2h |
| 10 | Per-Pi offline alert Telegram | ~2h |
| 11 | Rotate RTSP credentials định kỳ 24h (ONVIF SetUser) | ~1 ngày |
| 12 | NVR firewall Nebula chặn inbound từ `gateways` group | ~30m config |
| 13 | Rate-limit + IP whitelist signing service | ~1h |
| 14 | Honeypot credential | ~2h |
| 15 | G4: hạ agent khỏi root | ~3h |
| 16 | G5: agent bind hẹp thay `0.0.0.0` | ~1h |

### Sau (khi có nhiều khách)
| # | Việc |
|---|---|
| 17 | HA lighthouse ≥2 |
| 18 | CA-con-mỗi-khách (cô lập multi-tenant) |
| 19 | Relay riêng scale được |
| 20 | Camera user readonly (thủ công, từng camera) |

---

## 6. Những gì còn chưa rõ / chưa chắc chắn

| Vấn đề | Tại sao chưa rõ |
|---|---|
| **NAT traversal cross-internet** | Chỉ test same-LAN. Nebula hole-punch tốt nhưng symmetric NAT nhà dân chưa verify. Có thể cần relay — chưa biết tỷ lệ fail |
| **ONVIF SetUser — camera nào hỗ trợ** | Không phải camera nào cũng implement SetUser đúng chuẩn. Cần test trên từng model camera đang dùng |
| **Camera user readonly có đủ quyền stream không** | Một số camera cần quyền admin để mở RTSP port. Cần verify per-model |
| **Pi backdoor detection qua connOutbound** | Nếu attacker kiểm soát Pi → họ có thể fake số liệu trả về health check. Không có cách verify từ NVR phía ngoài |
| **Relay băng thông** | Khi NAT cứng → relay gánh video thật. Oracle Always Free ~10TB egress/tháng — đủ cho bao nhiêu camera đồng thời chưa đo |
| **RTSPS support** | Hầu hết camera nhà dân không hỗ trợ. Cần khảo sát fleet camera đang target |
| **WS-Discovery có chặn được không** | Multicast là tính năng cốt lõi ONVIF, không chặn được mà không break discovery. VLAN là cách duy nhất nhưng ngoài tầm gateway |
| **Auto-revoke N ngày — có false positive không** | Pi ở site mất điện dài hạn (sự cố) bị revoke → FW phải ra enroll lại. Cần cân nhắc ngưỡng N |
