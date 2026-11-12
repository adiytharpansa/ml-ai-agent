package id.rancangan.mlai.touch

/**
 * Timing rules for the accessibility fallback path.
 *
 * Accessibility strokes are planned in advance and cannot be steered
 * mid-gesture, so a held joystick is simulated with chained segments of
 * 200–500 ms (the range the feasibility research found workable).
 * Pure logic — unit-tested.
 */
object GesturePlan {
    const val SEGMENT_MIN_MS = 200L
    const val SEGMENT_MAX_MS = 500L
    const val DEFAULT_SEGMENT_MS = 300L
    const val TAP_DURATION_MS = 70L
    const val RELEASE_DURATION_MS = 50L

    /** Clamp a requested segment duration into the workable window. */
    fun clampSegmentMs(requestedMs: Long): Long =
        requestedMs.coerceIn(SEGMENT_MIN_MS, SEGMENT_MAX_MS)
}
