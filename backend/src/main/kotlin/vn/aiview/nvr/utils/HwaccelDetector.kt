package vn.aiview.nvr.utils

/**
 * Detects hardware acceleration support by reading the GPU vendor from the DRM subsystem.
 * Returns the appropriate Frigate ffmpeg hwaccel_args value:
 *   - Intel GPU (vendor 0x8086) → "preset-intel-qsv-h264"
 *   - Anything else (NVIDIA, AMD, no GPU) → empty list (software decode)
 *
 * Requires /sys/class/drm to be mounted read-only into the container.
 */
object HwaccelDetector {
    private const val INTEL_VENDOR_ID = "0x8086"

    fun detectHwaccelArgs(): Any {
        val vendorFile = java.io.File("/sys/class/drm/renderD128/device/vendor")
        return if (vendorFile.exists() && vendorFile.readText().trim() == INTEL_VENDOR_ID) {
            "preset-intel-qsv-h264"
        } else {
            emptyList<String>()
        }
    }
}
