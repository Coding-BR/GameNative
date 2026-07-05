package app.gamenative.utils

import app.gamenative.PrefManager

class DownloadSpeedConfig(
    private val totalExpectedBytes: Long = 0L,
    private val forceCoolMode: Boolean = false,
) {
    private data class Ratios(val download: Double, val decompress: Double)

    private val isCoolMode: Boolean
        get() = forceCoolMode || totalExpectedBytes >= LARGE_DOWNLOAD_BYTES

    private val ratios: Ratios
        get() = if (isCoolMode) {
            Ratios(download = 0.15, decompress = 0.1)
        } else when (PrefManager.downloadSpeed) {
            8 -> {
                Ratios(download = 0.6, decompress = 0.2)
            }

            16 -> {
                Ratios(download = 1.2, decompress = 0.4)
            }

            24 -> {
                Ratios(download = 1.5, decompress = 0.5)
            }

            32 -> {
                Ratios(download = 2.4, decompress = 0.8)
            }

            else -> {
                Ratios(download = 0.6, decompress = 0.2)
            }
        }

    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors()

    val maxDownloads: Int
        get() = if (isCoolMode) {
            1
        } else {
            (cpuCores * ratios.download).toInt().coerceAtLeast(1)
        }

    val maxDecompress: Int
        get() = if (isCoolMode) {
            1
        } else {
            (cpuCores * ratios.decompress).toInt().coerceAtLeast(1)
        }

    companion object {
        private const val LARGE_DOWNLOAD_BYTES = 50L * 1024L * 1024L * 1024L
    }
}
