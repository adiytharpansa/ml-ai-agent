package id.rancangan.mlai.capture

/**
 * Accumulates frame statistics over a sliding time window.
 * Pure JVM logic (timestamps are passed in) so it is unit-testable.
 */
class CaptureStatsAccumulator(private val windowMs: Long = 2_000L) {

    data class Snapshot(
        val totalFrames: Long,
        val totalBlackFrames: Long,
        /** Black frames as a fraction of all frames, 0.0–1.0. */
        val blackRatio: Double,
        /** Frames per second measured over the sliding window. */
        val windowFps: Double
    )

    private data class Entry(val atMs: Long, val black: Boolean)

    private val window = ArrayDeque<Entry>()
    private var totalFrames = 0L
    private var totalBlack = 0L

    @Synchronized
    fun addFrame(atMs: Long, black: Boolean) {
        totalFrames++
        if (black) totalBlack++
        window.addLast(Entry(atMs, black))
        prune(atMs)
    }

    @Synchronized
    fun snapshot(nowMs: Long): Snapshot {
        prune(nowMs)
        val fps = if (window.isEmpty()) 0.0
        else window.size.toDouble() / (windowMs / 1000.0)
        val ratio = if (totalFrames == 0L) 0.0
        else totalBlack.toDouble() / totalFrames.toDouble()
        return Snapshot(totalFrames, totalBlack, ratio, fps)
    }

    @Synchronized
    fun reset() {
        window.clear()
        totalFrames = 0L
        totalBlack = 0L
    }

    private fun prune(nowMs: Long) {
        while (window.isNotEmpty() && nowMs - window.first().atMs > windowMs) {
            window.removeFirst()
        }
    }
}

/**
 * Process-wide view of the latest capture state, written by [CaptureService]
 * and read by MainActivity and the Fase 0 self-test. Volatile fields only —
 * the UI polls it, nothing here needs stronger guarantees.
 */
object CaptureStats {
    @Volatile var serviceRunning: Boolean = false
    @Volatile var totalFrames: Long = 0
    @Volatile var totalBlackFrames: Long = 0
    @Volatile var blackRatio: Double = 0.0
    @Volatile var fps: Double = 0.0
    @Volatile var frameWidth: Int = 0
    @Volatile var frameHeight: Int = 0
    @Volatile var samplePngSaved: Boolean = false
    @Volatile var lastFrameAtMs: Long = 0

    fun publish(s: CaptureStatsAccumulator.Snapshot, width: Int, height: Int, atMs: Long) {
        totalFrames = s.totalFrames
        totalBlackFrames = s.totalBlackFrames
        blackRatio = s.blackRatio
        fps = s.windowFps
        frameWidth = width
        frameHeight = height
        lastFrameAtMs = atMs
    }

    fun reset() {
        serviceRunning = false
        totalFrames = 0
        totalBlackFrames = 0
        blackRatio = 0.0
        fps = 0.0
        frameWidth = 0
        frameHeight = 0
        samplePngSaved = false
        lastFrameAtMs = 0
    }
}
