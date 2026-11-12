package id.rancangan.mlai.game

/**
 * Positions of Mobile Legends' on-screen controls as fractions of the
 * landscape display.
 *
 * SECOND CALIBRATION (2026-11-13, Training Camp on the A15): the skill
 * fractions below are MEASURED button centres (visual grounding on a
 * real 2340x1080 game frame, confirmed by casts: S2 hit, S1 kill,
 * ult cooldown 27 s). The first calibration's skill points sat
 * ~100-130 px up-right of the true centres and missed or rim-hit the
 * buttons — that, not the injection path, was why scripted skill taps
 * did not cast. The joystick press point is unchanged: it has grabbed
 * the wheel reliably in every run (the wheel zone is generous; its
 * idle centre renders near (0.19, 0.79)).
 *
 * The Test Canvas geometry in ScriptedTouchTest is a different,
 * deliberately separate layout and is NOT reused here.
 */
object GameGeometry {

    // Virtual joystick base, lower-left.
    const val JOYSTICK_X = 0.13f
    const val JOYSTICK_Y = 0.73f

    // Skill cluster, lower-right — measured centres, 2340x1080 frame.
    const val SKILL1_X = 0.720f
    const val SKILL1_Y = 0.877f
    const val SKILL2_X = 0.766f
    const val SKILL2_Y = 0.698f
    const val SKILL3_X = 0.851f
    const val SKILL3_Y = 0.592f

    /** Joystick push radius in pixels at 2340px-wide landscape; scaled by caller. */
    const val JOYSTICK_RADIUS_REF = 90f
    const val REFERENCE_WIDTH = 2340f
}
