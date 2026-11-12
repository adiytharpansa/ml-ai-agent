package id.rancangan.mlai.vision

/**
 * Pure luminance math for Modul 1 (Mata) black-frame detection.
 *
 * Kept free of Android types so it can be unit-tested on the JVM.
 * Samples are packed RGB ints: (r shl 16) or (g shl 8) or b.
 */
object FrameLuminance {

    /** A frame whose average luminance is below this is treated as (near-)black. */
    const val BLACK_AVERAGE_THRESHOLD = 10.0

    /** A single sample darker than this counts as a black sample for the ratio. */
    const val BLACK_SAMPLE_THRESHOLD = 16.0

    fun luminanceOf(packedRgb: Int): Double {
        val r = (packedRgb shr 16) and 0xFF
        val g = (packedRgb shr 8) and 0xFF
        val b = packedRgb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** Mean luminance of the samples, 0.0 for an empty sample set. */
    fun averageLuminance(samples: IntArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += luminanceOf(s)
        return sum / samples.size
    }

    /** Fraction (0.0–1.0) of samples that are individually near-black. */
    fun blackSampleRatio(samples: IntArray): Double {
        if (samples.isEmpty()) return 0.0
        var black = 0
        for (s in samples) if (luminanceOf(s) < BLACK_SAMPLE_THRESHOLD) black++
        return black.toDouble() / samples.size
    }

    fun isBlackFrame(samples: IntArray): Boolean =
        samples.isNotEmpty() && averageLuminance(samples) < BLACK_AVERAGE_THRESHOLD
}
