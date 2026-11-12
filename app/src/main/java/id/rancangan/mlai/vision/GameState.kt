package id.rancangan.mlai.vision

/**
 * Modul 2 (Paham Layar) — the written state of the game, Fase 1 version.
 *
 * Produced by [Perception] from a captured frame, 4 times per second.
 * Field names follow the design document (RANCANGAN §6/§9): the brain of
 * a later phase reads ONLY this structure, never raw pixels.
 */
enum class ScreenKind {
    /** The in-match HUD is on screen (skill cluster detected). */
    MATCH,

    /** Anything else: lobby, menus, our own panel, a popup storm. */
    OTHER
}

data class GameState(
    val timestampMs: Long,
    val layar: ScreenKind,

    /** Own hero HP, 0–100. Null when the HP bar was not found in this frame. */
    val darahPersen: Int?,

    /** Skill readiness for S1, S2, ULT (true = ready to cast). Null entries = unreadable. */
    val skillSiap: List<Boolean?>,

    /**
     * Raw calibration values, kept so Fase 1's gate evaluation can tune
     * thresholds against human-checked frames instead of guessing:
     * mean luminance of each skill disc, and the longest HP-green run as
     * a fraction of the expected full bar length.
     */
    val skillLum: List<Double>,
    val hpRunFrac: Double
) {
    /** One journal line (Modul 7): compact, stable format, easy to diff against a frame. */
    fun line(): String {
        val darah = darahPersen?.toString() ?: "?"
        val skills = skillSiap.joinToString(",") { if (it == null) "?" else if (it) "siap" else "CD" }
        val lum = skillLum.joinToString(",") { "%.0f".format(it) }
        return "t=%d layar=%s darah=%s%% skill=[%s] lum=[%s] hpRun=%.2f".format(
            timestampMs, layar.name, darah, skills, lum, hpRunFrac
        )
    }
}

/** Latest published state, written by the capture thread, read by the panel. */
object GameStateStore {
    @Volatile
    var latest: GameState? = null
}
