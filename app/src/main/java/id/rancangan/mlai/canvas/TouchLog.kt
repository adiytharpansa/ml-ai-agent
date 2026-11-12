package id.rancangan.mlai.canvas

/**
 * Process-wide log of touch events seen by the Test Canvas.
 *
 * The self-test compares [totalEvents] before/after running the scripted
 * accessibility sequence to prove the gestures actually landed — the verdict
 * comes from events received, not from dispatchGesture() merely accepting.
 */
object TouchLog {

    private const val MAX_LINES = 200

    private val lines = ArrayDeque<String>()

    @Volatile
    var totalEvents: Int = 0
        private set

    @Volatile
    var lastEventAtMs: Long = 0
        private set

    /**
     * Largest pointerCount the canvas has seen since the last
     * [resetMaxPointerCount]. This is the simultaneity evidence: a value
     * of 2+ proves two contacts were genuinely down at the same moment —
     * something a sequence of separate one-finger gestures can never fake.
     */
    @Volatile
    var maxPointerCount: Int = 0
        private set

    /** Called by TestCanvasView for every event it receives. */
    fun notePointerCount(count: Int) {
        if (count > maxPointerCount) maxPointerCount = count
    }

    fun resetMaxPointerCount() {
        maxPointerCount = 0
    }

    /** Set by TestCanvasActivity so its on-screen log can refresh live. */
    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun record(line: String) {
        totalEvents++
        lastEventAtMs = System.currentTimeMillis()
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        listener?.invoke()
    }

    @Synchronized
    fun recentLines(count: Int = 12): List<String> = lines.takeLast(count)

    @Synchronized
    fun clear() {
        lines.clear()
        totalEvents = 0
        lastEventAtMs = 0
    }
}
