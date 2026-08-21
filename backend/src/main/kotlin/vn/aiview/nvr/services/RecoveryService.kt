package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.LockConfig
import vn.aiview.nvr.models.SecurityQuestions
import vn.aiview.nvr.plugins.hashPattern
import vn.aiview.nvr.plugins.verifyPattern
import java.security.SecureRandom
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline password recovery — the device never sends email (decision 2026-07-29).
 * Two independent paths, both usable with no internet:
 *   1. Recovery code  — GUID-style single-use code shown once at setup.
 *   2. Security questions — 3 answers from a fixed catalog.
 * Both hand out a short-lived reset ticket that /api/lock/reset-complete consumes.
 */
object RecoveryService {

    // ─── Recovery code ────────────────────────────────────────────────────
    // Crockford-ish Base32 without I, L, O, U, 0, 1 — nothing that gets mis-copied by hand.
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUPS = 4
    private const val GROUP_LEN = 5
    private val rng = SecureRandom()

    /** 4 groups x 5 chars ≈ 98 bits. Formatted with dashes for reading off paper. */
    fun generateCode(): String = (0 until GROUPS).joinToString("-") {
        (0 until GROUP_LEN).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.joinToString("")
    }

    /** Accept the code however the user typed it: spaces, lowercase, missing dashes. */
    fun normalizeCode(input: String): String =
        input.uppercase().filter { it in ALPHABET }.chunked(GROUP_LEN).joinToString("-")

    /** Store a freshly generated code (bcrypt) and clear the used marker. */
    fun persistCode(code: String) {
        val h = hashPattern(code)
        val now = System.currentTimeMillis()
        transaction {
            LockConfig.update {
                it[recoveryHash] = h
                it[recoveryCreatedAt] = now
                it[recoveryUsedAt] = null
            }
        }
    }

    fun status(): Pair<Boolean, Long?> = transaction {
        val row = LockConfig.selectAll().firstOrNull()
        val hash = row?.get(LockConfig.recoveryHash)
        Pair(hash != null && row.get(LockConfig.recoveryUsedAt) == null, row?.get(LockConfig.recoveryCreatedAt))
    }

    enum class CodeResult { OK, WRONG, ALREADY_USED, NOT_CONFIGURED }

    fun verifyCode(input: String): CodeResult {
        val row = transaction { LockConfig.selectAll().firstOrNull() } ?: return CodeResult.NOT_CONFIGURED
        val stored = row[LockConfig.recoveryHash] ?: return CodeResult.NOT_CONFIGURED
        if (row[LockConfig.recoveryUsedAt] != null) return CodeResult.ALREADY_USED
        val (matched, _) = verifyPattern(normalizeCode(input), stored)
        return if (matched) CodeResult.OK else CodeResult.WRONG
    }

    fun markCodeUsed() {
        val now = System.currentTimeMillis()
        transaction { LockConfig.update { it[recoveryUsedAt] = now } }
    }

    // ─── Security questions ───────────────────────────────────────────────

    data class Question(val id: String, val text: String)

    val CATALOG = listOf(
        Question("primary_school", "Tên trường tiểu học của bạn là gì?"),
        Question("first_plate", "Biển số xe đầu tiên của bạn là gì?"),
        Question("first_pet", "Tên vật nuôi đầu tiên của bạn là gì?"),
        Question("mother_maiden", "Họ và tên thời con gái của mẹ bạn là gì?"),
        Question("birth_town", "Bạn sinh ra ở xã/phường nào?"),
        Question("first_job", "Nơi làm việc đầu tiên của bạn tên là gì?"),
        Question("childhood_friend", "Tên người bạn thân thời thơ ấu của bạn là gì?"),
        Question("favorite_dish", "Món ăn ưa thích nhất của bạn là gì?"),
        Question("first_phone", "Chiếc điện thoại đầu tiên của bạn hiệu gì?"),
        Question("install_site", "Thiết bị này được lắp đặt tại địa điểm nào?"),
    )

    private val CATALOG_IDS = CATALOG.map { it.id }.toSet()
    const val QUESTION_COUNT = 3

    /**
     * Fold away everything a user will not reproduce identically months later:
     * case, surrounding/duplicate spaces and Vietnamese diacritics. "Hà Nội" == "ha noi".
     */
    fun normalizeAnswer(raw: String): String {
        val noMarks = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
        return noMarks.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    /** Returns the configured question ids in display order, empty when not set up. */
    fun configuredQuestions(): List<Question> = transaction {
        SecurityQuestions.selectAll().orderBy(SecurityQuestions.idx).mapNotNull { row ->
            CATALOG.firstOrNull { it.id == row[SecurityQuestions.questionId] }
        }
    }

    /** @return null on success, or an error message. */
    fun setQuestions(items: List<Pair<String, String>>): String? {
        if (items.size != QUESTION_COUNT) return "Phải đặt đủ $QUESTION_COUNT câu hỏi"
        if (items.map { it.first }.toSet().size != QUESTION_COUNT) return "Không được chọn trùng câu hỏi"
        if (items.any { it.first !in CATALOG_IDS }) return "Câu hỏi không hợp lệ"
        val normalized = items.map { normalizeAnswer(it.second) }
        if (normalized.any { it.length < 2 }) return "Câu trả lời quá ngắn (tối thiểu 2 ký tự)"

        val hashed = items.mapIndexed { i, (qid, _) -> Triple(i, qid, hashPattern(normalized[i])) }
        transaction {
            SecurityQuestions.deleteAll()
            hashed.forEach { (i, qid, h) ->
                SecurityQuestions.insert {
                    it[idx] = i
                    it[questionId] = qid
                    it[answerHash] = h
                }
            }
        }
        return null
    }

    fun clearQuestions() = transaction { SecurityQuestions.deleteAll() }

    /** All three must match. We never reveal which one failed. */
    fun verifyAnswers(answers: List<String>): Boolean {
        val rows = transaction {
            SecurityQuestions.selectAll().orderBy(SecurityQuestions.idx).map {
                it[SecurityQuestions.idx] to it[SecurityQuestions.answerHash]
            }
        }
        if (rows.size != QUESTION_COUNT || answers.size != QUESTION_COUNT) return false
        return rows.all { (i, hash) ->
            val (ok, _) = verifyPattern(normalizeAnswer(answers[i]), hash)
            ok
        }
    }

    // ─── Reset tickets ────────────────────────────────────────────────────
    // Proving identity and drawing the new pattern are two requests. The ticket bridges them
    // so /reset-complete can never be called cold.

    private const val TICKET_TTL_MS = 5 * 60 * 1000L

    data class Ticket(val method: String, val expiresAt: Long)

    private val tickets = ConcurrentHashMap<String, Ticket>()

    fun issueTicket(method: String): String {
        val bytes = ByteArray(24).also { rng.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        tickets.entries.removeIf { it.value.expiresAt < System.currentTimeMillis() }
        tickets[token] = Ticket(method, System.currentTimeMillis() + TICKET_TTL_MS)
        return token
    }

    /** Consumes the ticket; returns the method it was issued for, or null when invalid/expired. */
    fun consumeTicket(token: String): String? {
        val t = tickets.remove(token) ?: return null
        return if (t.expiresAt < System.currentTimeMillis()) null else t.method
    }
}

/**
 * Per-IP escalating lockout, shared by the pattern screen and both recovery paths.
 * Buckets are independent: hammering the recovery code must not lock out the owner's pattern.
 * In-memory on purpose — a restart clearing the counters is acceptable, and it keeps the
 * lock screen working when the DB is busy.
 */
object Throttle {

    data class State(var failures: Int = 0, var lockedUntil: Long = 0, var windowStart: Long = 0)

    private val buckets = ConcurrentHashMap<String, State>()

    private fun key(bucket: String, ip: String) = "$bucket|$ip"

    /** Remaining lock in seconds, 0 when the caller may proceed. */
    fun remainingLock(bucket: String, ip: String): Long {
        val s = buckets[key(bucket, ip)] ?: return 0
        val left = s.lockedUntil - System.currentTimeMillis()
        return if (left > 0) (left + 999) / 1000 else 0
    }

    /**
     * @param maxFailures failures tolerated before the first lock
     * @param steps escalating lock durations in seconds; the last one repeats
     * @return attempts left before the next lock (0 means it just locked)
     */
    fun recordFailure(bucket: String, ip: String, maxFailures: Int, steps: List<Long>): Int {
        val s = buckets.getOrPut(key(bucket, ip)) { State() }
        synchronized(s) {
            s.failures++
            if (s.failures >= maxFailures) {
                val stepIdx = minOf((s.failures / maxFailures) - 1, steps.size - 1).coerceAtLeast(0)
                s.lockedUntil = System.currentTimeMillis() + steps[stepIdx] * 1000
                return 0
            }
            return maxFailures - s.failures
        }
    }

    fun reset(bucket: String, ip: String) {
        buckets.remove(key(bucket, ip))
    }

    /**
     * Sliding-window quota (used to stop a single IP from spamming expensive endpoints).
     * @return true when the call is allowed.
     */
    fun allowRate(bucket: String, ip: String, maxCalls: Int, windowSeconds: Long): Boolean {
        val s = buckets.getOrPut(key("rate:$bucket", ip)) { State() }
        synchronized(s) {
            val now = System.currentTimeMillis()
            if (now - s.windowStart > windowSeconds * 1000) {
                s.windowStart = now
                s.failures = 0
            }
            s.failures++
            return s.failures <= maxCalls
        }
    }
}
