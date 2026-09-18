package vn.aiview.watchdog.ota

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import vn.aiview.watchdog.model.OtaConfig
import vn.aiview.watchdog.model.WatchdogConfig
import vn.aiview.watchdog.modules.AlertEvent
import vn.aiview.watchdog.modules.AlertManager
import vn.aiview.watchdog.modules.AlertType
import vn.aiview.watchdog.modules.EventLogger
import vn.aiview.watchdog.modules.EventType
import vn.aiview.watchdog.modules.Severity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Điều phối một lần cập nhật OTA. Thay ruột `UpdateManager` (registry pull) đã bị park.
 *
 * ─── Phân vai, và vì sao nó phải như vậy ────────────────────────────────────────────────
 *
 * | Ai | Làm gì | Vì sao đúng nó |
 * |---|---|---|
 * | `nvr-wrapper` | chỉ nhận file + đọc status | con phơi ra LAN, giữ quyền nhỏ nhất |
 * | **watchdog (file này)** | tải, verify, gate, snapshot, `docker load` | có docker.sock + PROJECT_DIR + Telegram + EventLogger |
 * | `scripts/ota-apply.sh` (host) | swap container, health-check, rollback | thứ DUY NHẤT sống sót khi cả wrapper lẫn watchdog bị recreate |
 *
 * Watchdog KHÔNG tự chạy `docker compose up -d nvr-watchdog` được — lệnh đó giết chính tiến
 * trình đang chạy nó, giữa lệnh. Nên nó làm hết phần nặng (tải + crypto + chuẩn bị đường lùi)
 * rồi **ghi một file trigger**; systemd path unit trên host thấy file đó và chạy host script.
 * Container không gọi được `systemctl`, và cách này không cần dbus, không cần docker-in-docker,
 * không nới thêm quyền nào — nó chỉ cần ghi một file, việc nó vốn đã làm được.
 */
/**
 * Cảnh báo khi `base_url` ghép với `DEVICE_MODEL` ra một URL KHÁC `manifest_url`.
 *
 * Lệch này là chuyện THẬT trong kho: [OtaConfig.DEFAULT_MANIFEST_URL] trỏ
 * `.../download/VMS/OTA/manifest.json`, còn `.env.template` khai
 * `OTA_BASE_URL=https://aiview.bkav.com/download` cộng
 * `DEVICE_MODEL=AIVIEW-NVR-X1`. Ghép hai thứ sau ra
 * `.../download/AIVIEW-NVR-X1/OTA/manifest.json` — một URL khác, và chỉ MỘT
 * trong hai là đúng (kênh phát hành của dòng này là `VMS`; xem README của
 * ota-server).
 *
 * KHÔNG sửa giá trị nào — cả hai đang chạy thật. Chỉ NÓI RA, vì im lặng ở đây
 * nghĩa là một máy cấu hình sai sẽ **không bao giờ thấy bản cập nhật**, mà
 * "không có bản mới" trông y hệt lúc chạy đúng: hỏng kiểu không ai phát hiện.
 *
 * Để ngoài class để test gọi được trực tiếp.
 */
internal fun canhBaoLechUrl(ota: OtaConfig, model: String): String? {
    if (model.isBlank()) return null
    val suyRa = ota.base_url.trimEnd('/') + "/" + model + "/OTA/manifest.json"
    val dangDung = ota.manifest_url.trim()
    if (dangDung.isEmpty() || suyRa == dangDung) return null
    return "OTA_URL_LECH đang dùng manifest_url=\"$dangDung\" nhưng ghép " +
        "base_url + DEVICE_MODEL ra \"$suyRa\" — hai URL khác nhau, chỉ một cái đúng. " +
        "Kênh phát hành của dòng này KHÔNG bằng mã model; kiểm lại .env và " +
        "OtaConfig.DEFAULT_MANIFEST_URL trước khi tin rằng \"không có bản mới\"."
}

class OtaOrchestrator(
    private val config: WatchdogConfig,
    private val alerts: AlertManager,
    private val db: EventLogger,
    private val layout: OtaLayout = OtaLayout.fromEnv(),
) {
    private val log = LoggerFactory.getLogger("OtaOrchestrator")
    private val json = Json { ignoreUnknownKeys = true }

    private val stateStore = OtaStateStore(layout.stateFile)
    private val status = OtaStatusWriter(layout.statusFile)
    private val docker = DockerCli()
    private val hostname = System.getenv("HOSTNAME") ?: "nvr-edge-node"

    private val deviceModel = System.getenv("DEVICE_MODEL")?.takeIf { it.isNotBlank() } ?: ""
    /** Version của chính watchdog — đối chiếu với manifest.minApplier. */
    private val applierVersion = System.getenv("WATCHDOG_VERSION")?.takeIf { it.isNotBlank() } ?: "1.0.0"

    private val keyringDir = System.getenv("OTA_KEYRING_DIR")?.takeIf { it.isNotBlank() }
        ?.let { File(it) } ?: OtaLayout.DEFAULT_KEYRING_DIR

    private var fetcher: OtaFetcher? = null

    /**
     * Định danh máy cho check-in. KHÔNG dùng `hostname` trần: .env.template ghi
     * thẳng "blank = nvr-edge-node for every machine", nên cả đội sẽ gộp thành
     * một dòng trên trang theo dõi. Xem OtaDeviceId.
     */
    private val deviceId: String by lazy {
        OtaDeviceId.resolve(File(layout.root, "device-id"), hostname, File("/etc/machine-id"))
    }

    /**
     * Kênh báo cáo lên máy chủ OTA, BÊN CẠNH Telegram — hai kênh phục vụ hai
     * người khác nhau (xem chú thích OtaCheckin).
     *
     * Dựng lười: không đụng mạng lúc khởi tạo orchestrator.
     */
    private val checkin: OtaCheckin by lazy {
        OtaCheckin(
            baseUrl = config.ota.base_url.trimEnd('/').removeSuffix("/download"),
            model = deviceModel,
            enrollKey = System.getenv("OTA_ENROLL_KEY")?.trim() ?: "",
            tokenFile = File(layout.root, "checkin-token"),
            deviceId = deviceId,
        )
    }

    /**
     * Gửi một báo cáo quan sát. BỌC KÍN mọi lỗi.
     *
     * Bất biến: check-in hỏng KHÔNG được cản cập nhật. OtaCheckin.bao() đã tự
     * bọc, nhưng bọc thêm một lớp ở đây để một lỗi lúc DỰNG lazy (đọc
     * /etc/machine-id, tạo thư mục) cũng không lọt vào vòng OTA.
     */
    private fun baoQuanSat(state: OtaState) {
        runCatching {
            val duoi = File(layout.root.absolutePath)
            checkin.bao(
                state = state,
                fw = state.installedVersion,
                freeMB = duoi.usableSpace / (1024 * 1024),
                uptimeSec = runCatching {
                    File("/proc/uptime").readText().trim().split(" ")[0].toDouble().toLong()
                }.getOrElse { 0L },
            )
        }.onFailure { log.warn("CHECKIN_BO_QUA ${it.javaClass.simpleName}: ${it.message}") }
    }

    // ── Vòng lặp chính ───────────────────────────────────────────────────────────────────

    suspend fun run() {
        layout.ensureDirs()
        status.load()

        if (!config.ota.enabled) {
            log.info("OTA_DISABLED ota.enabled=false trong watchdog.yml — chỉ nhận yêu cầu upload tay")
        }
        if (deviceModel.isBlank()) {
            // Không có model thì gate MODEL_MISMATCH sẽ chặn mọi gói. Nói ngay lúc khởi động
            // thay vì để người ta phát hiện lúc 2h sáng qua một tin Telegram từ chối.
            log.error("OTA_NO_MODEL thiếu DEVICE_MODEL — MỌI gói OTA sẽ bị từ chối. Đặt DEVICE_MODEL trong .env")
        }
        canhBaoLechUrl(config.ota, deviceModel)?.let { log.warn(it) }

        val keyring = OtaVerifier.loadKeyring(keyringDir)
        if (keyring.isEmpty()) {
            log.error("OTA_NO_KEYS không có khoá tin cậy nào ở ${keyringDir.absolutePath} — " +
                "mọi gói sẽ bị từ chối. Chạy: sudo ./scripts/install-ota.sh")
        }
        fetcher = OtaFetcher(config.ota, deviceModel, layout, status)

        // Đồng bộ version đang cài với thực tế: máy vừa cài từ bundle thì state.json còn trắng,
        // và gate minFrom cần biết máy đang ở version nào.
        syncInstalledVersion()

        // Một báo cáo lúc khởi động: máy vừa reboot (kể cả reboot do OTA) phải
        // hiện ngay trên trang, không đợi tới chu kỳ 2 giờ sáng kế tiếp.
        baoQuanSat(stateStore.load())

        status.idle("Sẵn sàng")

        while (true) {
            try {
                // Yêu cầu từ Web UI được ưu tiên: có người đang đứng đợi trước màn hình.
                val req = readRequest()
                if (req != null) {
                    consumeRequest()
                    handleRequest(req, keyring)
                } else if (config.ota.enabled && isInWindow()) {
                    handleScheduled(keyring)
                }
            } catch (e: Exception) {
                log.error("OTA_LOOP_ERROR ${e.javaClass.simpleName}: ${e.message}", e)
            }
            delay(config.ota.poll_seconds * 1000L)
        }
    }

    // ── Yêu cầu từ Web UI ────────────────────────────────────────────────────────────────

    private fun readRequest(): OtaRequest? {
        if (!layout.requestFile.isFile) return null
        return runCatching { json.decodeFromString(OtaRequest.serializer(), layout.requestFile.readText()) }
            .onFailure { log.warn("REQUEST_MALFORMED bỏ qua request.json không đọc được: ${it.message}") }
            .getOrNull()
    }

    /** Xoá request NGAY khi đã đọc — nếu không, apply thất bại sẽ lặp vô hạn mỗi vòng lặp. */
    private fun consumeRequest() {
        if (!layout.requestFile.delete()) {
            log.error("REQUEST_STUCK không xoá được ${layout.requestFile} — có nguy cơ lặp lại yêu cầu")
        }
    }

    private suspend fun handleRequest(req: OtaRequest, keyring: List<OtaVerifier.TrustedKey>) {
        when (req.action) {
            "apply" -> {
                val pkg = File(layout.incoming, req.file.substringAfterLast('/'))
                if (!pkg.isFile) {
                    status.begin(req.requestId, req.trigger, "", "Không thấy gói ${req.file}")
                    status.phase(OtaPhase.REJECTED, "Không thấy gói ${req.file} trong incoming/", "PKG_MISSING")
                    return
                }
                applyPackage(pkg, keyring, OtaGates.Trigger.MANUAL_UPLOAD, req.requestId, req.trigger,
                    remoteManifest = null)
            }
            "check" -> {
                status.begin(req.requestId, req.trigger, "", "Đang hỏi server xem có bản mới…")
                runCheck(keyring, req.requestId, req.trigger, applyIfFound = config.ota.auto_apply)
            }
            else -> log.warn("REQUEST_UNKNOWN_ACTION '${req.action}' — bỏ qua")
        }
    }

    // ── OTA theo lịch (2h sáng) ──────────────────────────────────────────────────────────

    /** Đã chạy trong cửa sổ giờ nào rồi — chống chạy lại nhiều lần trong cùng một giờ. */
    private var lastWindowRun: String = ""

    private fun isInWindow(): Boolean {
        val zone = runCatching { ZoneId.of(config.schedule.timezone) }.getOrElse { ZoneId.systemDefault() }
        val now = ZonedDateTime.now(zone)
        if (now.hour != config.ota.hour) return false

        // Jitter: cả fleet gọi cùng lúc 02:00:00 sẽ tự DDoS domain của mình. Lệch theo HOSTNAME
        // (ổn định qua các lần restart — dùng random thì máy restart lúc 2h05 lại trúng cửa sổ
        // mới và tải lại), rải đều trong khoảng jitter_minutes.
        val jitter = config.ota.jitter_minutes
        if (jitter > 0) {
            val offset = (hostname.hashCode().toLong().let { if (it < 0) -it else it } % jitter).toInt()
            if (now.minute < offset) return false
        }

        val key = "${now.toLocalDate()}-${now.hour}"
        if (lastWindowRun == key) return false
        lastWindowRun = key
        return true
    }

    private suspend fun handleScheduled(keyring: List<OtaVerifier.TrustedKey>) {
        val id = "sched-${Instant.now().toEpochMilli()}"
        status.begin(id, "scheduled", "", "Cửa sổ OTA ${config.ota.hour}h — đang hỏi server")
        runCheck(keyring, id, "scheduled", applyIfFound = config.ota.auto_apply)
    }

    /**
     * Hỏi manifest, verify, chạy gate — RỒI MỚI quyết định có tải gói không.
     *
     * Thứ tự này là điểm thiết kế quan trọng: manifest ~2KB, gói 200MB–3GB. Đêm nào không có
     * bản mới thì tốn ~0 byte (ETag), và một gói bị gate từ chối cũng không tốn byte nào.
     */
    private suspend fun runCheck(
        keyring: List<OtaVerifier.TrustedKey>,
        requestId: String,
        trigger: String,
        applyIfFound: Boolean,
    ) {
        val f = fetcher ?: return
        val state = stateStore.load()

        val head = when (val r = f.fetchManifest(keyring)) {
            is OtaFetcher.ManifestResult.NotModified -> {
                status.phase(OtaPhase.IDLE, "Không có bản mới (manifest không đổi)")
                stateStore.save(state.copy(lastCheck = Instant.now().toString()))
                baoQuanSat(stateStore.load())
                return
            }
            is OtaFetcher.ManifestResult.Unavailable -> {
                status.phase(OtaPhase.REJECTED, "Không lấy được manifest: ${r.reason}", "MANIFEST_UNAVAILABLE")
                stateStore.save(state.copy(lastCheck = Instant.now().toString()))
                baoQuanSat(stateStore.load())
                return
            }
            is OtaFetcher.ManifestResult.Rejected -> {
                // Chữ ký sai trên manifest remote là chuyện ĐÁNG BÁO NGAY: hoặc có ai đó đang
                // can thiệp đường mạng, hoặc mình vừa deploy sai file lên domain.
                status.phase(OtaPhase.REJECTED, "Manifest từ server không hợp lệ: ${r.reason}", r.code)
                db.log(EventType.UPDATE_FAILED, "Manifest OTA bị từ chối (${r.code})", severity = Severity.ERROR)
                alerts.sendAlert(AlertEvent(AlertType.UPDATE_FAILED,
                    "🖥️ <b>[$hostname]</b>\n⚠️ Manifest OTA <b>không hợp lệ</b>\n🔍 <code>${r.code}</code>",
                    r.reason))
                stateStore.save(state.copy(lastCheck = Instant.now().toString()))
                baoQuanSat(stateStore.load())
                return
            }
            is OtaFetcher.ManifestResult.Ok -> r
        }

        val m = head.verified.manifest

        // Cảnh báo quy ước đường dẫn gói. CẢNH BÁO, KHÔNG CHẶN: máy này vẫn tải
        // được, và một máy field đang chờ bản vá không nên bị khoá vì một đường
        // dẫn sai quy ước. Phần chặn nằm ở otasrv lúc publish, nơi sửa rẻ.
        m.canhBaoDuongGoi()?.let { log.warn(it) }
        stateStore.save(state.copy(lastCheck = Instant.now().toString(), lastEtag = head.etag))
        baoQuanSat(stateStore.load())

        // Gate TRƯỚC khi tải
        status.phase(OtaPhase.GATING, "Có bản ${m.version} — đang kiểm điều kiện áp dụng")
        val gateCtx = OtaGates.Context(
            deviceModel = deviceModel,
            applierVersion = applierVersion,
            trigger = OtaGates.Trigger.SCHEDULED,
            freeBytes = layout.root.usableSpace,
            packageBytes = 0,       // chưa biết, sẽ kiểm lại sau khi có Content-Length
        )
        when (val g = OtaGates.evaluate(m, stateStore.load(), gateCtx)) {
            is OtaGates.Result.Reject -> {
                onGateReject(m, g, requestId)
                return
            }
            OtaGates.Result.Pass -> Unit
        }

        if (!applyIfFound) {
            status.phase(OtaPhase.IDLE,
                "Có bản ${m.version} đủ điều kiện — chờ admin bấm Áp dụng (auto_apply=false)")
            alerts.sendAlert(AlertEvent(AlertType.UPDATE_SUCCESS,
                "🖥️ <b>[$hostname]</b>\n📦 Có bản mới <b>${m.version}</b> (đang chạy ${stateStore.load().installedVersion})" +
                    "\nℹ️ Chờ admin bấm Áp dụng trong Web UI"))
            return
        }

        // Tải gói
        status.phase(OtaPhase.ACQUIRING, "Đang tải gói ${m.version}…")
        val pkg = when (val d = f.downloadPackage(m, layout.incoming)) {
            is OtaFetcher.DownloadResult.Failed -> {
                status.phase(OtaPhase.REJECTED, "Tải gói thất bại: ${d.reason}", "DOWNLOAD_FAIL")
                db.log(EventType.UPDATE_FAILED, "Tải gói OTA ${m.version} thất bại: ${d.reason}",
                    severity = Severity.ERROR)
                return
            }
            is OtaFetcher.DownloadResult.Ok -> d.file
        }

        applyPackage(pkg, keyring, OtaGates.Trigger.SCHEDULED, requestId, trigger,
            remoteManifest = head.verified.rawBytes)
    }

    private fun onGateReject(m: OtaManifest, g: OtaGates.Result.Reject, requestId: String) {
        // ALREADY_CURRENT là kết cục BÌNH THƯỜNG của hầu hết các đêm — không phải lỗi, không
        // báo Telegram (nếu báo thì mỗi máy một tin mỗi đêm, và người ta sẽ tắt thông báo).
        if (g.code == "ALREADY_CURRENT") {
            status.phase(OtaPhase.IDLE, "Đang chạy đúng bản mới nhất (${m.version})")
            return
        }
        status.phase(OtaPhase.REJECTED, g.reason, g.code)
        db.log(EventType.UPDATE_FAILED, "Từ chối gói ${m.version}: ${g.code} — ${g.reason}",
            severity = Severity.WARN)

        val icon = when (g.code) {
            "MANIFEST_EXPIRED" -> "🕒"
            "BELOW_FLOOR" -> "🛡️"
            "MINFROM_NOT_MET" -> "🪜"
            "DISK_INSUFFICIENT" -> "💾"
            else -> "⚠️"
        }
        alerts.sendAlert(AlertEvent(AlertType.UPDATE_FAILED,
            "🖥️ <b>[$hostname]</b>\n$icon Bỏ qua bản <b>${m.version}</b>\n🔍 <code>${g.code}</code>",
            g.reason))
    }

    // ── Áp dụng một gói ──────────────────────────────────────────────────────────────────

    private suspend fun applyPackage(
        pkg: File,
        keyring: List<OtaVerifier.TrustedKey>,
        trigger: OtaGates.Trigger,
        requestId: String,
        triggerLabel: String,
        remoteManifest: ByteArray?,
    ) {
        val pkgMb = pkg.length() / 1_048_576
        status.begin(requestId, triggerLabel, "", "Đã nhận gói ${pkg.name} (${pkgMb}MB)")

        // 1. Giải nén + verify chữ ký + sha256 + whitelist
        status.phase(OtaPhase.VERIFYING, "Đang xác minh chữ ký và sha256 của ${pkg.name}")
        val unpacked = OtaPackage.unpackAndVerify(
            pkg = pkg,
            dest = layout.staged,
            keyring = keyring,
            projectDir = layout.projectDir,
            expectedManifest = remoteManifest,
        )
        val verified = when (unpacked) {
            is OtaPackage.Result.Failed -> {
                status.phase(OtaPhase.REJECTED, unpacked.reason, unpacked.code)
                db.log(EventType.UPDATE_FAILED, "Gói ${pkg.name} bị từ chối: ${unpacked.code} — ${unpacked.reason}",
                    severity = Severity.ERROR)
                alerts.sendAlert(AlertEvent(AlertType.UPDATE_FAILED,
                    "🖥️ <b>[$hostname]</b>\n❌ Gói <b>${pkg.name}</b> bị từ chối\n🔍 <code>${unpacked.code}</code>",
                    unpacked.reason))
                layout.staged.deleteRecursively()
                return
            }
            is OtaPackage.Result.Ok -> unpacked.verified
        }
        val m = verified.manifest
        // Version chỉ biết được SAU khi verify (trước đó chưa được phép tin nội dung gói).
        // Không set ở đây thì status.json giữ version rỗng và UI hiện trạng thái không có
        // số phiên bản — đúng lúc người ta cần biết đang cài bản nào nhất.
        status.setVersion(m.version)
        status.note("Chữ ký hợp lệ (khoá ${verified.keyId}), payload khớp sha256 — bản ${m.version}")

        // 2. Gate chính sách
        status.phase(OtaPhase.GATING, "Kiểm điều kiện áp dụng bản ${m.version}")
        val st = stateStore.load()
        val gateCtx = OtaGates.Context(
            deviceModel = deviceModel,
            applierVersion = applierVersion,
            trigger = trigger,
            freeBytes = layout.root.usableSpace,
            packageBytes = pkg.length(),
        )
        when (val g = OtaGates.evaluate(m, st, gateCtx)) {
            is OtaGates.Result.Reject -> {
                onGateReject(m, g, requestId)
                layout.staged.deleteRecursively()
                return
            }
            OtaGates.Result.Pass -> Unit
        }

        // Không áp lên máy đang hỏng: nếu wrapper đang chết thì health-check sau khi swap
        // không phân biệt được "bản mới lỗi" với "máy vốn đã lỗi", và ta sẽ rollback oan.
        if (!isWrapperHealthy()) {
            status.phase(OtaPhase.REJECTED,
                "nvr-wrapper hiện không khoẻ — sửa máy trước rồi hãy cập nhật " +
                    "(nếu áp bây giờ thì không phân biệt được bản mới lỗi hay máy vốn đã lỗi)",
                "PRECONDITION_UNHEALTHY")
            layout.staged.deleteRecursively()
            return
        }

        alerts.sendAlert(AlertEvent(AlertType.UPDATE_SUCCESS,
            "🖥️ <b>[$hostname]</b>\n🔄 Bắt đầu cập nhật <b>${st.installedVersion.ifBlank { "?" }} → ${m.version}</b>" +
                "\n📦 ${pkgMb}MB · ${triggerLabel}" +
                if (m.risk == "high") "\n⚠️ risk=high (có thay đổi lớn, vd Frigate)" else ""))
        db.log(EventType.UPDATE_STARTED, "Bắt đầu áp bản ${m.version} (từ ${st.installedVersion})")

        // 3. Snapshot đường lùi — LÀM TRƯỚC khi chạm bất cứ thứ gì
        status.phase(OtaPhase.SNAPSHOT, "Chuẩn bị đường lùi (ghim image cũ + lưu file cũ)")
        val plan = try {
            buildRollbackPlan(m)
        } catch (e: Exception) {
            status.phase(OtaPhase.REJECTED, "Không chuẩn bị được đường lùi: ${e.message}", "SNAPSHOT_FAIL")
            db.log(EventType.UPDATE_FAILED, "Snapshot rollback thất bại: ${e.message}", severity = Severity.ERROR)
            layout.staged.deleteRecursively()
            return
        }
        status.note("Đường lùi sẵn sàng: ${plan.images.size} image, ${plan.files.size} file")

        // 4. Nạp image mới (chưa đổi gì đang chạy — bước này an toàn, hỏng thì chỉ tốn đĩa)
        status.phase(OtaPhase.LOADING, "Đang nạp image (${m.images.size} image)")
        for (img in m.images) {
            val okLoad = if (img.isRegistry) pullByDigest(img) else loadTarball(img)
            if (!okLoad) {
                status.phase(OtaPhase.REJECTED,
                    "Nạp image '${img.name}' thất bại — chưa đổi gì đang chạy, máy vẫn nguyên trạng",
                    "IMAGE_LOAD_FAIL")
                db.log(EventType.UPDATE_FAILED, "Nạp image ${img.name} thất bại", severity = Severity.ERROR)
                alerts.sendAlert(AlertEvent(AlertType.UPDATE_FAILED,
                    "🖥️ <b>[$hostname]</b>\n❌ Nạp image <b>${img.name}</b> thất bại\nℹ️ Máy vẫn chạy bản cũ, không cần can thiệp"))
                layout.staged.deleteRecursively()
                return
            }
            status.note("đã nạp ${img.name} → ${img.tag}")
        }

        // 5. Bàn giao cho host script. Từ đây trở đi watchdog KHÔNG còn kiểm soát: nó sắp bị
        //    recreate. Mọi việc còn lại (swap, health-check, rollback, Telegram kết cục) do
        //    scripts/ota-apply.sh làm, vì nó là thứ duy nhất sống sót qua bước recreate.
        status.phase(OtaPhase.SWAPPING,
            "Bàn giao cho systemd để đổi container (watchdog sắp được khởi động lại)")
        writeApplyPlan(m, plan, requestId, triggerLabel)
        triggerHostApply()
    }

    // ── Đường lùi ────────────────────────────────────────────────────────────────────────

    @Serializable
    data class RollbackImage(val tag: String, val oldImageId: String, val parkedTag: String)

    @Serializable
    data class RollbackPlan(
        val fromVersion: String = "",
        val toVersion: String = "",
        val images: List<RollbackImage> = emptyList(),
        /** Đường dẫn tương đối đã được lưu bản cũ vào rollback/files/. */
        val files: List<String> = emptyList(),
        val createdAt: String = "",
    )

    /**
     * Ghim lại mọi thứ cần để lùi được.
     *
     * Điểm dễ bỏ sót: `docker tag <id> nvr-ota-rollback/<name>:<ver>`. Không có cái tag đó thì
     * sau khi tag chuẩn (vd `nvr-wrapper:latest`) bị trỏ sang image mới, image cũ thành
     * dangling và **Docker GC xoá mất** — mà SelfHealingEngine của chính watchdog này có dọn
     * đĩa bằng `docker system prune`. Lúc cần lùi thì không còn gì để lùi về.
     */
    private fun buildRollbackPlan(m: OtaManifest): RollbackPlan {
        val filesDir = File(layout.rollback, "files")
        filesDir.deleteRecursively(); filesDir.mkdirs()

        val committed = stateStore.load().committedImages

        val images = m.images.mapNotNull { img ->
            // ─── Ghim theo ID ĐÃ GHI LÚC COMMIT, không phải theo tag hiện tại ───────────
            // Tag `nvr-wrapper:latest` có thể bị đổi NGOÀI đường OTA: `docker compose build`
            // gán thẳng vào nó. Đọc tag lúc này sẽ ghim chính image MỚI, và rollback thành
            // lệnh không làm gì trong khi vẫn báo "đã lùi thành công" — kiểu thất bại tệ
            // nhất. Đã gặp thật khi test 21-08-2026.
            val tagNow = docker.imageId(img.tag)
            val recorded = committed[img.tag]?.takeIf { docker.imageId(it) != null }

            val oldId = recorded ?: tagNow
            if (oldId == null) {
                // Bình thường khi thêm một image chưa từng có trên máy — không có gì để lùi.
                log.info("SNAPSHOT_NO_OLD_IMAGE ${img.tag} chưa có trên máy, không cần ghim")
                return@mapNotNull null
            }
            if (recorded != null && tagNow != null && recorded != tagNow) {
                log.warn("SNAPSHOT_TAG_DRIFT ${img.tag} hiện trỏ ${tagNow.take(19)} nhưng bản đã " +
                    "commit là ${recorded.take(19)} — image bị đổi ngoài đường OTA. Ghim theo bản " +
                    "đã commit để rollback vẫn đúng.")
                status.note("lưu ý: ${img.tag} đã bị đổi ngoài OTA — ghim đường lùi theo bản đã commit")
            }
            val parked = "nvr-ota-rollback/${img.name}:${m.version}-prev"
            if (!docker.tag(oldId, parked)) {
                throw IllegalStateException("không ghim được image cũ của ${img.tag} (id=$oldId) — " +
                    "không có đường lùi thì không được phép áp")
            }
            RollbackImage(tag = img.tag, oldImageId = oldId, parkedTag = parked)
        }

        val savedFiles = m.files.mapNotNull { fl ->
            val live = File(layout.projectDir, fl.path)
            if (!live.isFile) return@mapNotNull null   // file mới, lùi = xoá đi (host script lo)
            val dst = File(filesDir, fl.path)
            dst.parentFile?.mkdirs()
            live.copyTo(dst, overwrite = true)
            fl.path
        }

        val plan = RollbackPlan(
            fromVersion = stateStore.load().installedVersion,
            toVersion = m.version,
            images = images,
            files = savedFiles,
            createdAt = Instant.now().toString(),
        )
        File(layout.rollback, "plan.json").writeText(
            Json { prettyPrint = true }.encodeToString(RollbackPlan.serializer(), plan))
        return plan
    }

    // ── Nạp image ────────────────────────────────────────────────────────────────────────

    private fun loadTarball(img: OtaImage): Boolean {
        val f = File(layout.staged, img.path)
        // `docker load` tự nhận gzip/zstd/xz — daemon trên host giải nén, không phải CLI trong
        // container. Nên không cần zstd binary trong image watchdog.
        val loaded = docker.load(f) ?: return false
        // Tên tag nằm trong tarball có thể khác tag manifest khai (vd build với tên khác).
        // Gán lại theo manifest để docker-compose.yml chắc chắn tìm thấy.
        if (loaded != img.tag) {
            log.info("IMAGE_RETAG tarball mang tag '$loaded', gán thêm '${img.tag}' theo manifest")
            if (!docker.tag(loaded, img.tag)) return false
        }
        return true
    }

    /**
     * Pull image công khai theo DIGEST.
     *
     * `DOCKER_CONFIG` trỏ vào thư mục rỗng: kể cả khi máy có sẵn credential nào (lịch sử
     * `docker login`, file config sót lại) thì lệnh này vẫn đi ANONYMOUS — không thể lỡ gửi
     * token của mình ra registry bên ngoài. Đã kiểm: token anonymous cho image công khai được
     * cấp tự động, không cần tài khoản.
     *
     * Ghim theo digest nên Docker tự từ chối layer sai hash ⇒ toàn vẹn miễn phí, và pull là
     * TĂNG DẦN THEO LAYER nên nâng Frigate chỉ tải phần thay đổi.
     */
    private fun pullByDigest(img: OtaImage): Boolean {
        if (!img.ref.contains("@sha256:")) {
            log.error("PULL_REFUSED ${img.name} ref không ghim digest: ${img.ref}")
            return false
        }
        if (!config.ota.allow_registry_pull) {
            log.error("PULL_DISABLED ota.allow_registry_pull=false nhưng gói yêu cầu pull ${img.name}")
            return false
        }
        status.note("pull ${img.name} theo digest (anonymous)…")
        if (docker.pullAnonymous(img.ref)) {
            return docker.tag(img.ref, img.tag)
        }

        val fb = img.fallback ?: run {
            log.error("PULL_FAIL ${img.name} và không có fallback tarball")
            return false
        }
        status.note("pull thất bại, thử tải tarball dự phòng cho ${img.name}…")
        val f = fetcher?.downloadUrl(fb.url, File(layout.incoming, "${img.name}-fallback.tar"), fb.sha256)
            ?: return false
        val loaded = docker.load(f) ?: return false
        return loaded == img.tag || docker.tag(loaded, img.tag)
    }

    // ── Bàn giao cho host ────────────────────────────────────────────────────────────────

    @Serializable
    data class ApplyPlan(
        val requestId: String = "",
        val trigger: String = "",
        val fromVersion: String = "",
        val toVersion: String = "",
        val risk: String = "normal",
        /** Container cần recreate. LUÔN có nginx — xem chú thích ở dưới. */
        val recreate: List<String> = emptyList(),
        /** path (tương đối projectDir) → nguồn trong staged/files/ */
        val overlayFiles: List<String> = emptyList(),
        /** Tag image gói này đặt. ota-apply.sh ghi ID thật của chúng vào state khi COMMIT. */
        val imageTags: List<String> = emptyList(),
        val healthChecks: List<OtaHealthCheck> = emptyList(),
        val preApplyHook: String = "",
        val postApplyHook: String = "",
        val createdAt: String = "",
    )

    private fun writeApplyPlan(m: OtaManifest, rb: RollbackPlan, requestId: String, trigger: String) {
        // nginx PHẢI có trong danh sách recreate. CLAUDE.md ghi rõ: nginx KHÔNG tự start khi
        // recreate nvr-wrapper, quên là site unreachable. Áp OTA lúc 2h sáng rồi để site chết
        // tới sáng là kịch bản tệ nhất của cả cơ chế này.
        val containers = buildSet {
            m.images.forEach { img ->
                when {
                    img.name.contains("wrapper") -> add("nvr-wrapper")
                    img.name.contains("watchdog") -> add("nvr-watchdog")
                    img.name.contains("frigate") -> add("frigate")
                }
            }
            if (m.files.any { it.path == "docker-compose.yml" || it.path.startsWith("nginx/") }) {
                add("nvr-wrapper")
            }
            add("nginx")
        }

        val plan = ApplyPlan(
            requestId = requestId,
            trigger = trigger,
            fromVersion = rb.fromVersion,
            toVersion = m.version,
            risk = m.risk,
            recreate = containers.toList(),
            overlayFiles = m.files.map { it.path },
            imageTags = m.images.map { it.tag },
            healthChecks = m.healthChecks,
            preApplyHook = m.hooks.preApply,
            postApplyHook = m.hooks.postApply,
            createdAt = Instant.now().toString(),
        )
        // encodeDefaults = true LÀ BẮT BUỘC ở đây: kotlinx-serialization mặc định BỎ field
        // trùng giá trị default, nên expectStatus=200 và timeoutSec=180 biến mất khỏi file.
        // ota-apply.sh vẫn chạy đúng nhờ `// 200` và `// 180` ở phía bash — nhưng đó là hai
        // bản default nằm ở hai ngôn ngữ, và đổi một bên mà quên bên kia thì health-check
        // lặng lẽ dùng ngưỡng sai. Ghi tường minh để file tự mô tả đủ.
        File(layout.root, "apply-plan.json").writeText(
            Json { prettyPrint = true; encodeDefaults = true }
                .encodeToString(ApplyPlan.serializer(), plan))
        log.info("APPLY_PLAN to=${m.version} recreate=${containers.joinToString(",")} " +
            "overlay=${plan.overlayFiles.size} health=${plan.healthChecks.size}")
    }

    /**
     * Chạm file trigger để systemd path unit khởi động `nvr-ota-apply.service`.
     *
     * Container KHÔNG gọi được `systemctl` trên host. Path unit là cách sạch nhất: watchdog chỉ
     * cần ghi một file — việc nó vốn đã làm được — không cần dbus, không docker-in-docker,
     * không nới thêm quyền nào.
     */
    private fun triggerHostApply() {
        layout.triggerFile.writeText("${Instant.now()}\n")
        log.info("HOST_APPLY_TRIGGERED đã ghi ${layout.triggerFile.absolutePath} " +
            "— systemd nvr-ota-apply.path sẽ chạy scripts/ota-apply.sh")
        status.note("Đã bàn giao cho systemd. Tiến trình còn lại xem tại status.json (host script ghi tiếp).")
    }

    // ── Tiện ích ─────────────────────────────────────────────────────────────────────────

    private fun isWrapperHealthy(): Boolean =
        runCatching {
            val conn = (java.net.URL("http://127.0.0.1:3000/api/health").openConnection()
                as java.net.HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
            }
            conn.responseCode in 200..299
        }.getOrDefault(false)

    /**
     * Đồng bộ `installedVersion` với version wrapper thật đang báo.
     *
     * Cần vì: máy vừa cài từ bundle có state.json trắng, và gate minFrom phải biết máy đang ở
     * đâu. Cũng bắt được trường hợp ai đó cập nhật bằng tay (chạy install.sh với source mới)
     * mà không đi qua OTA — state.json sẽ lệch với thực tế nếu không đồng bộ.
     */
    private fun syncInstalledVersion() {
        val real = runCatching {
            val conn = (java.net.URL("http://127.0.0.1:3000/api/version").openConnection()
                as java.net.HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 3000
            }
            if (conn.responseCode !in 200..299) return@runCatching null
            Json { ignoreUnknownKeys = true }
                .parseToJsonElement(conn.inputStream.bufferedReader().readText())
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("version")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()?.takeIf { OtaVersion.parseOrNull(it) != null } ?: return

        val st = stateStore.load()
        if (st.installedVersion == real) return

        log.info("STATE_SYNC installedVersion '${st.installedVersion}' → '$real' (theo /api/version)")
        // Sàn cũng nâng theo: bản đang chạy thật thì mặc nhiên đã "chạy thành công", nên không
        // có lý do gì cho phép hạ xuống dưới nó.
        val newFloor = listOfNotNull(st.floorParsed, OtaVersion.parseOrNull(real)).maxOrNull()?.raw ?: real
        stateStore.save(st.copy(installedVersion = real, floorVersion = newFloor))
    }
}
