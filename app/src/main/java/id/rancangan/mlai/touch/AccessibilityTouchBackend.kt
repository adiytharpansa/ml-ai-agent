package id.rancangan.mlai.touch

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import id.rancangan.mlai.accessibility.MlAccessibilityService

/**
 * Modul 3 — FALLBACK touch backend via AccessibilityService.dispatchGesture().
 *
 * Honest limits of this path (design doc §3/§4, from the feasibility research):
 *  - A stroke's path is fixed the moment it is dispatched. It cannot be
 *    steered mid-gesture in reaction to the game.
 *  - A "held" joystick is therefore simulated as a chain of short
 *    continued-stroke segments ([GesturePlan.DEFAULT_SEGMENT_MS] each);
 *    every joystickMove() dispatches the next segment.
 *  - A skill tap is its OWN gesture. On this backend it is NOT truly
 *    simultaneous with a held joystick — dispatching it interacts with the
 *    in-flight joystick chain. True hold-and-tap needs the Shizuku backend.
 *
 * All methods return dispatchGesture()'s acceptance result; false also when
 * the service is not connected.
 */
class AccessibilityTouchBackend : TouchBackend {

    override val name: String = "Aksesibilitas"

    // Joystick chain state (meaningful only while [joystickHeld]).
    private var joystickHeld = false
    private var centerX = 0f
    private var centerY = 0f
    private var tipX = 0f
    private var tipY = 0f
    private var radiusPx = 0f

    override fun isReady(): Boolean = MlAccessibilityService.instance != null

    private fun dispatch(description: GestureDescription): Boolean {
        val service = MlAccessibilityService.instance ?: return false
        return service.dispatchGesture(description, null, null)
    }

    private fun stroke(path: Path, durationMs: Long, willContinue: Boolean): GestureDescription {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs, willContinue)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    override fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(stroke(path, GesturePlan.TAP_DURATION_MS, willContinue = false))
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(stroke(path, GesturePlan.clampSegmentMs(durationMs), willContinue = false))
    }

    override fun joystickDown(
        centerX: Float, centerY: Float,
        dirX: Float, dirY: Float, radiusPx: Float
    ): Boolean {
        this.centerX = centerX
        this.centerY = centerY
        this.radiusPx = radiusPx
        tipX = centerX + dirX * radiusPx
        tipY = centerY + dirY * radiusPx
        val path = Path().apply {
            moveTo(centerX, centerY)
            lineTo(tipX, tipY)
        }
        // willContinue = true: the pointer stays down; joystickMove() continues it.
        val accepted = dispatch(stroke(path, GesturePlan.DEFAULT_SEGMENT_MS, willContinue = true))
        joystickHeld = accepted
        return accepted
    }

    override fun joystickMove(dirX: Float, dirY: Float): Boolean {
        if (!joystickHeld) return false
        val newTipX = centerX + dirX * radiusPx
        val newTipY = centerY + dirY * radiusPx
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(newTipX, newTipY)
        }
        tipX = newTipX
        tipY = newTipY
        val continued = GestureDescription.StrokeDescription(
            path, 0, GesturePlan.DEFAULT_SEGMENT_MS, /* willContinue = */ true
        )
        // A continued stroke must ride in a new GestureDescription.
        val description = GestureDescription.Builder().addStroke(continued).build()
        return dispatch(description)
    }

    override fun joystickRelease(): Boolean {
        if (!joystickHeld) return false
        joystickHeld = false
        // Final segment: stay at the tip, end the stroke (willContinue = false lifts it).
        val path = Path().apply { moveTo(tipX, tipY) }
        val end = GestureDescription.StrokeDescription(
            path, 0, GesturePlan.RELEASE_DURATION_MS, /* willContinue = */ false
        )
        val description = GestureDescription.Builder().addStroke(end).build()
        return dispatch(description)
    }

    override fun skill(x: Float, y: Float): Boolean {
        // See the class doc: on this fallback this is a separate tap gesture,
        // NOT simultaneous with the joystick chain. Do not claim otherwise.
        return tap(x, y)
    }

    override fun releaseAll(): Boolean {
        var ok = true
        if (joystickHeld) ok = joystickRelease()
        return ok
    }
}
