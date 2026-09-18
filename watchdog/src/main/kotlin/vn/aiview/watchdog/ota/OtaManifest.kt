package vn.aiview.watchdog.ota

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Đặc tả `manifest.json` — trái tim của gói cập nhật `.nvrup`.
 *
 * Chữ ký Ed25519 chỉ phủ ĐÚNG file này (dạng byte thô). Manifest lại chứa sha256 của từng
 * file payload, nên chữ ký phủ toàn bộ gói một cách bắc cầu. Cùng mô hình với file `Release`
 * của apt. Lợi ích quyết định: chỉ ~1KB cần đi qua máy air-gap, image 200MB–3GB không bao
 * giờ chạm máy giữ khoá ký.
 *
 * File manifest ở URL remote là BYTE-IDENTICAL với file trong gói (chỉ thêm khối [pkg]).
 * Nhờ vậy máy tải ~1KB, verify chữ ký, chạy hết gate — RỒI MỚI quyết định có tải 200MB không.
 *
 * Xem đặc tả đầy đủ: docs/ota/dinh-dang-goi.md
 */
@Serializable
data class OtaManifest(
    /** Version của chính định dạng này. Watchdog từ chối schema nó không biết đọc. */
    val schema: Int = 0,

    /** Mã model thiết bị. Gói của model khác bị từ chối — chống cài chéo dòng sản phẩm. */
    val model: String = "",

    /** Version sẽ được cài, dạng "1.1.0.0". */
    val version: String = "",

    val channel: String = "stable",
    val releaseDate: String = "",

    /**
     * Số bản phát hành, đếm riêng theo từng dòng máy, tăng đơn điệu.
     *
     * KHÔNG thay thế [OtaState.floorVersion] — hai thứ đo hai chuyện khác nhau
     * và cả hai đều cần. Sàn chặn một gói CŨ do chính ta ký (rollback attack);
     * sequence cho máy chủ biết máy đang ở bản thứ mấy, để trang /noi-bo/ota
     * đếm được "bao nhiêu máy đã lên bản hiện hành".
     *
     * Mặc định 0 để manifest CŨ (chưa có trường này) vẫn giải mã được — một
     * trường mới không được phép làm cả đội mất đường cập nhật.
     */
    val sequence: Long = 0,

    /** Định danh bản phát hành. Dùng nguyên văn, không phân tích. */
    val releaseId: String = "",

    /**
     * Hạn dùng của manifest (ISO-8601 UTC). Chống freeze attack: kẻ chặn mạng không cần sửa
     * gì, chỉ cần GIỮ NGUYÊN manifest cũ mãi mãi thì máy tưởng "không có bản mới" và không
     * bao giờ nhận bản vá — im lặng hoàn toàn. Quá hạn ⇒ cảnh báo Telegram.
     */
    val expiresAt: String = "",

    val description: String = "",
    val notes: String = "",

    /**
     * Version tối thiểu đang cài mới được áp gói này. Chặn nhảy bậc khi migration không
     * nhảy bậc được — ví dụ thật: migrateCloudCamerasIntoCameras() là migration MỘT CHIỀU,
     * bản nào xoá nó đi thì máy còn ở version trước đó nhảy thẳng lên sẽ mất sạch camera
     * cloud mà /api/health vẫn 200 nên rollback KHÔNG kích hoạt.
     */
    val minFrom: String = "",

    /** Version watchdog tối thiểu để hiểu gói này (hook mới, field mới...). */
    val minApplier: String = "",

    val requiresFreeBytes: Long = 0,

    /** "normal" | "high". Gói có Frigate là "high" vì config.yml sai một dòng = crash-loop. */
    val risk: String = "normal",

    /** Gói risk=high phải khai tường minh true mới được tự áp lúc 2h sáng. */
    val autoApply: Boolean = false,

    /** Nơi tải file .nvrup. Xem [OtaPackageRef] về việc CỐ TÌNH không có sha256 ở đây. */
    @SerialName("package") val pkg: OtaPackageRef? = null,

    val images: List<OtaImage> = emptyList(),
    val files: List<OtaFile> = emptyList(),
    val hooks: OtaHooks = OtaHooks(),
    val healthChecks: List<OtaHealthCheck> = emptyList(),
) {
    val versionParsed: OtaVersion? get() = OtaVersion.parseOrNull(version)

    companion object {
        /** Schema duy nhất bản watchdog này đọc được. Tăng khi đổi cấu trúc không tương thích. */
        const val SUPPORTED_SCHEMA = 1

        /**
         * ignoreUnknownKeys = true để manifest của bản mới hơn (có thêm field) vẫn parse được
         * trên watchdog cũ — tương thích tiến. Chốt an toàn thật nằm ở [schema]: đổi cấu trúc
         * không tương thích thì tăng schema, watchdog cũ từ chối thẳng thay vì đọc sai.
         */
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = false          // không nhận JSON méo (quote thiếu, dấu phẩy thừa)
            allowStructuredMapKeys = false
        }
    }
}

/**
 * Trỏ tới file `.nvrup` để tải.
 *
 * ─── Vì sao KHÔNG có sha256 của chính file .nvrup ───────────────────────────────────────
 * Nhìn thì tưởng thiếu, nhưng đưa nó vào là bất khả thi VÀ dư thừa:
 *
 *  1. Bất khả thi — vòng tròn: `.nvrup` CHỨA manifest.json. Hash của .nvrup phụ thuộc vào
 *     manifest, mà manifest lại phải chứa hash đó. Không có điểm dừng.
 *
 *  2. Dư thừa — toàn vẹn đã được phủ kín theo đường khác, và phủ TỐT HƠN:
 *       · manifest.json được ký Ed25519 ⇒ sửa manifest là sai chữ ký
 *       · manifest chứa sha256 của TỪNG file payload ⇒ sửa payload là sai hash file đó
 *       · sau khi giải nén, manifest trong gói phải BYTE-IDENTICAL với manifest đã verify
 *         ⇒ không ghép được manifest-của-bản-này với gói-của-bản-khác
 *     Kết quả: file .nvrup bị sửa một byte ở đâu cũng bị bắt, và bắt CHÍNH XÁC file nào sai
 *     thay vì chỉ báo "gói hỏng". Hash cả tarball còn kém hơn thế.
 *
 * Cũng KHÔNG có `size`: kích thước gói chỉ dùng để hiện thanh tiến trình, mà con số đó lấy
 * từ `Content-Length` lúc tải là đủ và luôn đúng. Đặt nó vào manifest thì gặp đúng vòng tròn
 * như sha256 (gói chứa manifest), nên nó sẽ luôn rỗng — một trường luôn rỗng chỉ gây nhầm.
 * Gate đĩa dùng `requiresFreeBytes` (nằm trong phần ĐÃ KÝ) cộng Content-Length thật.
 */
@Serializable
data class OtaPackageRef(
    val url: String = "",
)

/**
 * Một image trong gói. Hai đường lấy hàng:
 *  - `source="tarball"`: [path] trỏ file trong gói (image của mình, không nằm trên registry nào)
 *  - `source="registry"`: [ref] là reference GHIM THEO DIGEST (image công khai bên thứ ba).
 *    Pull theo digest thì Docker tự từ chối layer sai hash ⇒ toàn vẹn khỏi cần sha256 riêng,
 *    và pull là tăng dần theo layer nên nâng Frigate không phải tải lại cả 3GB.
 */
@Serializable
data class OtaImage(
    val name: String = "",
    val source: String = "tarball",
    /** source=tarball: đường dẫn tương đối trong gói, vd "images/nvr-wrapper.tar.zst". */
    val path: String = "",
    /** source=tarball: sha256 của file trên. */
    val sha256: String = "",
    /** source=registry: PHẢI ghim theo digest, vd "ghcr.io/x/y@sha256:...". Tag thuần bị từ chối. */
    val ref: String = "",
    /** Tag sẽ được gán sau khi load/pull — chính là tag docker-compose.yml dùng. */
    val tag: String = "",
    /** Đường lùi khi mạng khách chặn registry: tải tarball từ domain của mình. */
    val fallback: OtaImageFallback? = null,
) {
    val isRegistry: Boolean get() = source == "registry"
    val isTarball: Boolean get() = source == "tarball"
}

@Serializable
data class OtaImageFallback(
    val source: String = "tarball",
    val url: String = "",
    val sha256: String = "",
)

/** File overlay lên PROJECT_DIR. [path] bị whitelist ở [OtaLayout.isAllowedOverlayPath]. */
@Serializable
data class OtaFile(
    val path: String = "",
    val sha256: String = "",
    val mode: String = "644",
)

@Serializable
data class OtaHooks(
    val preApply: String = "",
    val postApply: String = "",
)

/**
 * Một phép kiểm sau khi swap container.
 *
 * [expectJson] là phần quan trọng nhất và là lý do không thể chỉ dùng /api/health: health trả
 * 200 KỂ CẢ khi container cũ vẫn đang chạy (compose up thất bại lặng lẽ, hoặc container mới
 * crash rồi restart về bản cũ). Health pass ⇒ máy ghi "cập nhật thành công" trong khi chưa
 * đổi gì. Phải đối chiếu /api/version báo ĐÚNG version mong đợi mới tính là thành công.
 */
@Serializable
data class OtaHealthCheck(
    val url: String = "",
    val expectStatus: Int = 200,
    /** Cặp key→value phải khớp trong JSON trả về, vd {"version":"1.1.0.0"}. */
    val expectJson: Map<String, String> = emptyMap(),
    val timeoutSec: Long = 180,
)
