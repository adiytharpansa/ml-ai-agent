package id.rancangan.mlai.game

/**
 * Positions of Mobile Legends' on-screen controls as fractions of the
 * landscape display — FIRST CALIBRATION, from the game's default layout.
 *
 * These are estimates to be corrected against real screenshots from the
 * A15 (the Fase 0 Practice runs): if the hero does not move or a skill
 * misses its button, the fractions — not the injection path — are what
 * gets adjusted. The Test Canvas geometry in ScriptedTouchTest is a
 * different, deliberately separate layout and is NOT reused here.
 */
object GameGeometry {

    // Virtual joystick base, lower-left.
    const val JOYSTICK_X = 0.13f
    const val JOYSTICK_Y = 0.73f

    // Skill cluster, lower-right (default layout).
    const val SKILL1_X = 0.762f
    const val SKILL1_Y = 0.845f
    const val SKILL2_X = 0.802f
    const val SKILL2_Y = 0.635f
    const val SKILL3_X = 0.905f
    const val SKILL3_Y = 0.60f

    /** Joystick push radius in pixels at 2340px-wide landscape; scaled by caller. */
    const val JOYSTICK_RADIUS_REF = 90f
    const val REFERENCE_WIDTH = 2340f
}
