package id.rancangan.mlai.touch

/**
 * The fixed (non-AI) Fase 0 touch script, run against our own Test Canvas:
 * tap the joystick centre, swipe across mid-screen, tap skill 1.
 *
 * Positions are fractions of the display so the same script works on the
 * canvas and — in the real Fase 0 device test — in the game's Practice mode.
 * Sleeps happen on the CALLER's background thread; never call from the UI thread.
 */
object ScriptedTouchTest {

    // Canvas geometry (must match TestCanvasView's drawn layout).
    const val JOYSTICK_X = 0.17f
    const val JOYSTICK_Y = 0.78f
    const val SKILL1_X = 0.80f
    const val SKILL1_Y = 0.80f

    data class Result(
        val tapAccepted: Boolean,
        val swipeAccepted: Boolean,
        val skillAccepted: Boolean
    ) {
        fun allAccepted(): Boolean = tapAccepted && swipeAccepted && skillAccepted
    }

    fun run(backend: TouchBackend, displayWidth: Int, displayHeight: Int): Result {
        val tapOk = backend.tap(JOYSTICK_X * displayWidth, JOYSTICK_Y * displayHeight)
        Thread.sleep(600)
        val swipeOk = backend.swipe(
            0.30f * displayWidth, 0.50f * displayHeight,
            0.70f * displayWidth, 0.50f * displayHeight,
            300L
        )
        Thread.sleep(600)
        val skillOk = backend.skill(SKILL1_X * displayWidth, SKILL1_Y * displayHeight)
        Thread.sleep(400)
        return Result(tapOk, swipeOk, skillOk)
    }
}
