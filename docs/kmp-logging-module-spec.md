# Tài liệu kỹ thuật — KMP Logging Module (AIView B02 Box)

**Tài liệu số:** 01 · **Đơn vị:** AI View · **Ngày viết:** 26/05/2026  
**Soạn bởi:** Bkav AI — Tầng 2 toà nhà HH1, Phường Yên Hoà, Quận Cầu Giấy, Hà Nội

---

## Lịch sử sửa đổi

| Bản sửa đổi | Ngày | Mô tả |
|---|---|---|
| B02_KT_V01 | 26/05/2026 | Bản đầu tiên |

---

## 1. Giới thiệu

### 1.1 Mục đích

Tài liệu này mô tả thiết kế kỹ thuật và cách triển khai Module Logging cho dự án AIView B02 Box — một hệ thống giám sát camera AI chạy trên Android và Linux desktop (KMP).

Module logging cung cấp:

- Ghi log theo cấu trúc (group/tag) vào nhiều file riêng biệt trên đĩa
- Phân tuyến tự động (routing): lỗi nghiêm trọng ghi thêm vào file tổng hợp `errors-<pid>.log`
- Xoay vòng file (rotation) khi đạt 50 MB và nén Gzip sang thư mục `archive/`
- Giám sát tần suất log (RateMonitor) và cảnh báo khi vượt ngưỡng
- Gửi log lỗi qua HTTP về server từ xa (RemoteSink) với retry exponential backoff
- Giám sát sức khỏe phần cứng (HealthMonitorService): CPU, GPU, RAM, nhiệt độ

### 1.2 Phạm vi

Module áp dụng cho toàn bộ code business logic trong `shared/commonMain`. Không phụ thuộc vào Android SDK hay Linux-specific API — nền tảng inject `PlatformLogger` (expect/actual).

---

## 2. Công nghệ sử dụng

| Thư viện | Phiên bản | Mục đích |
|---|---|---|
| Kotlin Coroutines (`kotlinx-coroutines-core`) | 1.7.x | Queue bất đồng bộ, single-writer coroutine, retry loop |
| `kotlinx-datetime` | 0.6.x | Timestamp đa nền tảng trong log line và archive |
| Ktor Client (`ktor-client-core`) | 2.3.x | HTTP POST cho RemoteSink gửi log ERROR/FATAL |
| Kotlin Multiplatform (KMP) | 2.0.x | Chia sẻ logic logging giữa Android và Linux desktop |

---

## 3. Kiến trúc tổng thể

### 3.1 Luồng dữ liệu một chiều

```
GroupLogger.log(level, msg)
  ├── RateMonitor.increment(key)         [non-blocking trySend]
  └── LogEngine.enqueue(entry)           [non-blocking trySend]
              │
              ├── criticalChannel (UNLIMITED)   ← ERROR/FATAL — không bao giờ drop
              └── normalChannel (26 000 cap)    ← DEBUG/INFO/WARN — DROP_OLDEST khi đầy
                            │
                      writerJob (1 coroutine duy nhất)
                            │
                ┌───────────┴──────────────┐
            ConsoleSink               FileSink
            (println / Logcat)        ├── <group>-<pid>.log
                                      ├── errors-<pid>.log
                                      ├── report.log
                                      └── healthMonitor_warning.log
                                                │
                                           RemoteSink (tùy chọn)
                                           HTTP POST + retry backoff
```

### 3.2 State Machine của LogEngine

LogEngine có 5 trạng thái:

- `UNINITIALIZED` → khởi tạo ban đầu, chưa có sinks
- `READY` → sinks đã được cấu hình, chưa có writerJob
- `RUNNING` → writerJob đang xử lý, nhận log bình thường
- `FLUSHING` → đang drain queue khi app chuẩn bị tắt (tối đa 2 giây)
- `CLOSED` → đã đóng tất cả sinks, từ chối nhận log mới

### 3.3 Cơ chế Backpressure

- `normalChannel` (DEBUG/INFO/WARN): `DROP_OLDEST` khi vượt 26 000 entries (~8 MB)
- `criticalChannel` (ERROR/FATAL): `UNLIMITED` — không bao giờ drop lỗi nghiêm trọng
- Tất cả callers gọi `trySend()` — không suspend, không block thread

---

## 4. Các thành phần chính

### 4.1 AppLog (Facade)

Singleton khởi tạo một lần khi app start. Cung cấp API duy nhất để tạo logger.

```kotlin
AppLog.init(LogConfig(
    processName = "AiviewBox",
    logDirectory = "$aiviewDir/logs",
    minLevel = LogLevel.INFO,
))

val log = AppLog.forGroup("connectToServer", "mqtt")
log.i("Connecting to MQTT broker")
log.e("Connection failed", exception)
log.d { "Lazy: ${buildReport()}" }  // lambda chỉ evaluate khi DEBUG được bật
```

Logger instance được cache theo key `"group:tag"` — thread-safe, tái sử dụng.

### 4.2 LogEngine (Single-Writer)

Trái tim của hệ thống. Sở hữu hai Channel và một `writerJob` duy nhất:

- Ưu tiên `criticalChannel` qua `select { criticalChannel.onReceive {...}; normalChannel.onReceive {...} }`
- Graceful shutdown: `close()` → `FLUSHING` → drain tối đa 2s → đóng sinks
- JVM shutdown hook đăng ký tự động trong `AppLog.init()`

### 4.3 FileSink (File Router + Rotation)

Định tuyến log vào file theo quy tắc:

- `DEBUG/INFO/WARN/ERROR/FATAL` → `<group>-<pid>.log`
- `ERROR/FATAL` → cũng ghi thêm vào `errors-<pid>.log`
- Khi file vượt 50 MB: đổi tên, nén Gzip, lưu vào `archive/<group>-<pid>-<ts>.log.gz`
- **Retention:** khi tổng archive vượt 25 GB, xóa `.gz` cũ nhất (ưu tiên giữ `errors-*.gz`)
- Symlink `<processName>-current.log` → file group đầu tiên được tạo (để `tail -f` dễ)
- Hai file đặc biệt ghi trực tiếp (bypass LogEngine queue):
  - `report.log` ← cảnh báo RateMonitor (tránh feedback loop)
  - `healthMonitor_warning.log` ← cảnh báo hardware threshold

### 4.4 GroupLogger

Logger nhẹ gắn với một cặp (group, tag). Cung cấp hai dạng API:

- **Eager:** `log.i("message")` — message được tạo ngay
- **Lazy:** `log.d { "message" }` — lambda chỉ chạy khi level được bật, tiết kiệm CPU

Mức log: `VERBOSE (v)` < `DEBUG (d)` < `INFO (i)` < `WARN (w)` < `ERROR (e)` < `FATAL (f)`

### 4.5 RateMonitor

Giám sát tần suất log không chặn luồng chính:

- Mỗi `GroupLogger.log()` gọi `rateMonitor.increment(key)` qua `trySend()` (không suspend)
- Background sweep coroutine chạy mỗi ~10s, đếm logs theo tag
- Nếu tag vượt **500 logs/phút**: ghi cảnh báo vào `report.log` (ghi trực tiếp, không qua queue)
- Thiết kế: 6 sweep/window để tính trung bình chính xác hơn

### 4.6 RemoteSink

Gửi ERROR/FATAL qua HTTP POST đến server từ xa:

- Header: `X-Api-Key: <redacted>`
- Body JSON:
  ```json
  {
    "id": "...",
    "ts": 1234567890,
    "level": "ERROR",
    "process": "AiviewBox",
    "group": "...",
    "tag": "...",
    "message": "..."
  }
  ```
- Nếu gửi thất bại: buffer vào `ArrayDeque` (tối đa 500 entries), retry với exponential backoff (1s, 2s, 4s, 8s, 16s)
- Buffer drop: xóa entry cũ nhất khi đầy (không ảnh hưởng file log trên đĩa)

---

## 5. HealthMonitorService

Service độc lập poll thông số phần cứng mỗi 5 giây.

### 5.1 Nguồn dữ liệu

| Metric | Nguồn (Linux) | Nguồn (Android) |
|---|---|---|
| CPU % | `/proc/stat` | ActivityManager |
| GPU % | `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` | Không có |
| RAM (total/used MB) | `/proc/meminfo` | ActivityManager |
| Nhiệt độ (°C) | `/sys/class/thermal/thermal_zone0/temp` | BatteryManager |

### 5.2 Ngưỡng cảnh báo

| Metric | Ngưỡng |
|---|---|
| CPU | > 90% |
| GPU | > 90% |
| RAM | > 90% |
| Nhiệt độ | > 70°C |

Khi vượt ngưỡng:

1. Ghi `ERROR` vào `healthMonitor-<pid>.log` (đồng thời auto-route vào `errors-<pid>.log`)
2. Ghi trực tiếp vào `healthMonitor_warning.log` với timestamp:
   ```
   [2026-05-26 10:32:01] THRESHOLD EXCEEDED — CPU=91.5% > 90%
   ```

### 5.3 Format log bình thường (INFO mỗi 5s)

```
CPU=12.3%  GPU=0 %  RAM=45.2% (3612/8192MB)  TEMP=52.0°C  NET=↓1024KB ↑256KB  DISK=R128KB W64KB
```

---

## 6. Cấu hình (LogConfig)

| Tham số | Mặc định | Mô tả |
|---|---|---|
| `processName` | (bắt buộc) | Tên tiến trình — dùng trong tên file và symlink |
| `logDirectory` | (bắt buộc) | Thư mục gốc chứa log |
| `minLevel` | `DEBUG` | Mức log tối thiểu được ghi |
| `maxFileSizeMb` | 50 | Ngưỡng rotation theo MB |
| `maxTotalSizeGb` | 25 | Tổng dung lượng archive tối đa |
| `batchSize` | 64 | Số entry xử lý mỗi lô (dự phòng) |
| `batchTimeoutMs` | 500 | Timeout flush lô (ms) |
| `queueMemoryLimitBytes` | 8 MB | Dung lượng normalChannel |
| `enableRateMonitor` | `true` | Bật/tắt RateMonitor |
| `rateMonitorThreshold` | 500 | Ngưỡng logs/phút trước khi cảnh báo |
| `remote` | `null` | Cấu hình RemoteSink (tùy chọn) |

---

## 7. Cấu trúc file log

```
~/.aiview/logs/
├── AiviewBox-current.log          ← symlink → file group đầu tiên (tail -f dễ)
├── healthMonitor-6293.log         ← stats CPU/GPU/RAM/Temp mỗi 5s
├── connectToServer-6293.log       ← log của group "connectToServer"
├── signaling-6293.log             ← log của group "signaling"
├── errors-6293.log                ← tổng hợp tất cả ERROR/FATAL
├── report.log                     ← cảnh báo RateMonitor
├── healthMonitor_warning.log      ← cảnh báo ngưỡng hardware
└── archive/
    ├── connectToServer-6293-2026-05-26T10-32.log.gz
    └── errors-6293-2026-05-25T08-15.log.gz
```

---

## 8. Phi chức năng (NFR)

| Chỉ tiêu | Giá trị |
|---|---|
| Latency tối đa của `log()` | ~1 µs (trySend vào Channel, không suspend) |
| Dung lượng queue tối đa | 8 MB (normalChannel ~26 000 entries) |
| Flush khi tắt | Tối đa 2 giây |
| Số group tối đa | ~30 song song (mỗi group 1 file writer) |
| Rotation | 50 MB / file |
| Retention | 25 GB tổng archive |
| Poll HealthMonitor | 5 giây / lần |

---

## 9. Ghi chú và lưu ý

- **Thread safety:** Thiết kế Single-writer — mọi thread chỉ `trySend()` vào Channel. Chỉ 1 coroutine (`writerJob`) ghi ra đĩa. Không cần lock/synchronized.
- **Feedback loop prevention:** `RateMonitor` và `HealthMonitorService` ghi trực tiếp vào file qua `writeReport()` / `writeHealthWarning()` — bypass LogEngine để tránh gây thêm log entries khi log đang bão.
- **GPU parsing:** `gpu_info` từ thiết bị Snapdragon là String như `"75%"` hoặc `"0 %"`. Phải dùng `.removeSuffix("%").trim().toFloat()`.
- **commonMain float format:** Không dùng `String.format("%.1f", v)` trong `commonMain` (JVM-only). Dùng hàm `fmt1()` với phép tính làm tròn thuần Kotlin.
- **Symlink:** FileSink tạo DUY NHẤT 1 symlink `<processName>-current.log` khi group file đầu tiên được mở. Không có symlink per-group.
- **RemoteSink:** Chỉ kích hoạt khi `LogConfig.remote != null`. Nếu không cấu hình, không có HTTP call nào được thực hiện.
