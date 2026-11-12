package id.rancangan.mlai.touch

/**
 * Modul 3 (Tangan) — the tool interface the brain will call.
 *
 * Coordinates are raw screen pixels of the current display. Every method
 * returns true only when the underlying mechanism accepted the gesture;
 * false means "did not happen" — callers must not pretend otherwise.
 *
 * Two implementations exist in Fase 0:
 *  - [ShizukuTouchBackend] — the planned primary path (streaming pointers).
 *  - [AccessibilityTouchBackend] — the fallback path (pre-planned strokes).
 */
interface TouchBackend {
    /** Short human-readable name shown in the UI, e.g. "Aksesibilitas". */
    val name: String

    /** Whether this backend can dispatch right now. */
    fun isReady(): Boolean

    fun tap(x: Float, y: Float): Boolean

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean

    /**
     * Press the virtual joystick at (centerX, centerY) and hold it pushed
     * toward (dirX, dirY) — a unit-ish direction multiplied by [radiusPx].
     */
    fun joystickDown(centerX: Float, centerY: Float, dirX: Float, dirY: Float, radiusPx: Float): Boolean

    /** Steer an already-held joystick toward a new direction. */
    fun joystickMove(dirX: Float, dirY: Float): Boolean

    /** Release the joystick. */
    fun joystickRelease(): Boolean

    /**
     * Tap a skill button at (x, y) WITHOUT lifting the joystick pointer.
     * This simultaneity is the design's acid test (design doc §4, Modul 3).
     */
    fun skill(x: Float, y: Float): Boolean

    /** Lift every pointer immediately — the emergency stop for touches. */
    fun releaseAll(): Boolean
}
