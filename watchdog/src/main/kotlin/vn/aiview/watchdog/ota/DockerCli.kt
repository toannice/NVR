package vn.aiview.watchdog.ota

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Gọi `docker` CLI qua socket đã mount.
 *
 * Riêng cho OTA thay vì dùng `DockerApi` sẵn có, vì hai bên cần khác nhau: `DockerApi` lo
 * trạng thái container (inspect/restart cho self-heal), còn ở đây là vòng đời IMAGE (load,
 * tag, pull theo digest) — và quan trọng nhất là [pullAnonymous] cần một biến môi trường
 * riêng mà không nên áp vào mọi lệnh docker khác của watchdog.
 */
class DockerCli {
    private val log = LoggerFactory.getLogger("DockerCli")

    private data class Exec(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    private fun exec(vararg cmd: String, timeoutSec: Long = 900, env: Map<String, String> = emptyMap()): Exec {
        return try {
            val pb = ProcessBuilder(*cmd)
            if (env.isNotEmpty()) pb.environment().putAll(env)
            val p = pb.start()
            // Đọc stdout/stderr trong thread riêng: pipe của process có bộ đệm hữu hạn, `docker
            // pull` in rất nhiều dòng tiến trình — không đọc song song là nó block mãi ở giữa
            // và ta timeout oan sau 15 phút.
            val outT = Thread { }
            val outSb = StringBuilder()
            val errSb = StringBuilder()
            val t1 = Thread { p.inputStream.bufferedReader().forEachLine { outSb.appendLine(it) } }
            val t2 = Thread { p.errorStream.bufferedReader().forEachLine { errSb.appendLine(it) } }
            t1.start(); t2.start()
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                t1.join(2000); t2.join(2000)
                return Exec(-1, outSb.toString(), "timeout sau ${timeoutSec}s")
            }
            t1.join(5000); t2.join(5000)
            outT.run()
            Exec(p.exitValue(), outSb.toString().trim(), errSb.toString().trim())
        } catch (e: Exception) {
            Exec(-1, "", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Image id đầy đủ của một tag, null nếu tag không tồn tại trên máy. */
    fun imageId(tag: String): String? {
        val r = exec("docker", "image", "inspect", "-f", "{{.Id}}", tag, timeoutSec = 30)
        return if (r.ok) r.out.trim().takeIf { it.isNotEmpty() } else null
    }

    fun tag(source: String, target: String): Boolean {
        val r = exec("docker", "tag", source, target, timeoutSec = 60)
        if (!r.ok) log.error("TAG_FAIL $source → $target: ${r.err}")
        return r.ok
    }

    /**
     * `docker load` từ file. Trả về tag mà tarball mang, hoặc null nếu lỗi.
     *
     * Không cần giải nén trước: daemon tự nhận gzip/zstd/xz/bzip2. Đó là lý do gói dùng zstd
     * được mà không phải thêm binary zstd vào image watchdog (CLI chỉ đẩy byte qua socket).
     */
    fun load(file: File): String? {
        if (!file.isFile) {
            log.error("LOAD_FAIL không thấy ${file.absolutePath}")
            return null
        }
        val mb = file.length() / 1_048_576
        val t0 = System.currentTimeMillis()
        val r = exec("docker", "load", "-i", file.absolutePath, timeoutSec = 1800)
        val ms = System.currentTimeMillis() - t0
        if (!r.ok) {
            log.error("LOAD_FAIL ${file.name} (${mb}MB) sau ${ms}ms: ${r.err.take(500)}")
            return null
        }
        // stdout: "Loaded image: nvr-wrapper:latest" (hoặc "Loaded image ID: sha256:…")
        val tag = r.out.lineSequence()
            .mapNotNull { l ->
                when {
                    l.startsWith("Loaded image: ") -> l.removePrefix("Loaded image: ").trim()
                    l.startsWith("Loaded image ID: ") -> l.removePrefix("Loaded image ID: ").trim()
                    else -> null
                }
            }.lastOrNull()
        if (tag == null) {
            log.error("LOAD_NO_TAG docker load xong nhưng không đọc được tên image từ output: ${r.out.take(300)}")
            return null
        }
        log.info("LOAD_OK ${file.name} (${mb}MB) → $tag ttl=${ms}ms")
        return tag
    }

    /**
     * Pull một image công khai, ĐẢM BẢO không gửi credential nào.
     *
     * `DOCKER_CONFIG` trỏ vào thư mục tạm rỗng ⇒ docker không tìm thấy file auth nào nên đi
     * anonymous, kể cả khi máy từng `docker login`. Đây là chốt an toàn cho đúng yêu cầu
     * "không đưa thông tin tài khoản nào ra máy field": image Frigate là công khai, pull nó
     * không cần token, và biến này bảo đảm không thể lỡ gửi.
     */
    fun pullAnonymous(ref: String): Boolean {
        val emptyCfg = java.nio.file.Files.createTempDirectory("docker-anon").toFile()
        try {
            val t0 = System.currentTimeMillis()
            val r = exec("docker", "pull", ref, timeoutSec = 3600,
                env = mapOf("DOCKER_CONFIG" to emptyCfg.absolutePath))
            val ms = System.currentTimeMillis() - t0
            if (!r.ok) {
                log.error("PULL_FAIL $ref sau ${ms}ms: ${r.err.take(500)}")
                return false
            }
            log.info("PULL_OK $ref ttl=${ms}ms (anonymous, DOCKER_CONFIG rỗng)")
            return true
        } finally {
            emptyCfg.deleteRecursively()
        }
    }

    /**
     * Đọc file /app/VERSION nướng trong một image, để biết image đó là bản nào.
     *
     * Cần vì `docker image inspect` không đọc được nội dung file, và ta phải trả lời câu
     * "image đang gắn tag này có đúng là bản máy đang chạy không?" — nếu không thì tag ghim
     * để lùi sẽ trỏ vào chính image mới và rollback thành lệnh vô nghĩa.
     *
     * Chạy một container ngắn (~0.3s) mỗi lần apply. Trả null nếu image không có file đó
     * (watchdog, frigate) — người gọi phải xử lý null chứ không coi là "khớp".
     */
    fun bakedAppVersion(tag: String): String? {
        val r = exec("docker", "run", "--rm", "--entrypoint", "sh", tag,
            "-c", "cat /app/VERSION 2>/dev/null || cat VERSION 2>/dev/null", timeoutSec = 60)
        if (!r.ok) {
            log.warn("BAKED_VERSION_FAIL không đọc được VERSION trong $tag: ${r.err.take(200)}")
            return null
        }
        return r.out.trim().takeIf { it.isNotEmpty() && it.length < 32 && it.none { c -> c == '\n' } }
    }

    fun removeImage(idOrTag: String): Boolean =
        exec("docker", "rmi", "-f", idOrTag, timeoutSec = 120).ok
}
