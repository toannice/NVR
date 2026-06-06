package vn.aiview.nvr.utils

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private const val KEY_FILE = "data/.device_key"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128

    private var deviceKey: ByteArray? = null

    fun initDeviceKey() {
        val keyFile = File(KEY_FILE)
        deviceKey = if (keyFile.exists()) {
            keyFile.readText().trim().hexToBytes()
        } else {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            keyFile.parentFile?.mkdirs()
            keyFile.writeText(key.toHex())
            key
        }
    }

    fun encrypt(plaintext: String): String {
        val key = requireKey()
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(encoded: String): String {
        val key = requireKey()
        val data = Base64.getDecoder().decode(encoded)
        val iv = data.copyOfRange(0, IV_LENGTH)
        val ciphertext = data.copyOfRange(IV_LENGTH, data.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun requireKey(): ByteArray =
        deviceKey ?: error("CryptoUtils not initialized — call initDeviceKey() first")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
