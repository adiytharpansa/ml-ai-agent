package id.rancangan.mlai.vision

import id.rancangan.mlai.game.GameGeometry
import kotlin.math.max
import kotlin.math.min

/**
 * A read-only view of one captured frame, in packed RGB
 * ((r shl 16) or (g shl 8) or b), so [Perception] stays free of Android
 * types and unit-testable on the JVM — same pattern as [FrameLuminance].
 */
interface FrameView {
    val width: Int
    val height: Int
    fun rgb(x: Int, y: Int): Int
}

/**
 * Modul 2 (Paham Layar), Fase 1 — colour + pattern reading of the parts
 * of the HUD that never move (design §4, Modul 2, step 1):
 *
 *  - `layar`: the three skill buttons are saturated blue discs in fixed
 *    spots; if at least two of the three spots are strongly blue, the
 *    match HUD is on screen. Conservative on purpose: the Fase 1 gate
 *    forbids ever confusing a match with the lobby.
 *  - `skill_siap`: a ready skill icon is bright; on cooldown the game
 *    lays a dark shade over it (and draws a pale countdown digit). Mean
 *    disc luminance separates the two states by a wide margin.
 *  - `darah_persen`: the hero stands near screen centre (the camera
 *    follows), HP bar floating just above. We scan a centre window for
 *    the longest horizontal run of HP-green and express it against the
 *    bar's known full length (a fixed fraction of frame width).
 *
 * All thresholds are v1 values, exposed raw in [GameState] so the
 * calibration pass tunes numbers, not code structure.
 */
object Perception {

    /** Full overhead HP bar length as a fraction of frame width (measured: ~118px at 2340). */
    const val HP_BAR_FULL_FRAC = 0.050

    /** A green run shorter than this fraction of the full bar is not trusted as the HP bar. */
    const val HP_MIN_RUN_FRAC = 0.30

    /** Disc luminance at/above this = skill ready; below = cooldown shade. */
    const val SKILL_READY_LUMINANCE = 100.0

    /** Blue dominance (b − mean(r,g)) at/above this marks a skill disc as present. */
    const val DISC_BLUE_DOMINANCE = 20.0

    /** HP bar green: strong green, clearly above red and blue. */
    fun isHpGreen(packedRgb: Int): Boolean {
        val r = (packedRgb shr 16) and 0xFF
        val g = (packedRgb shr 8) and 0xFF
        val b = packedRgb and 0xFF
        return g >= 140 && g - r >= 60 && g - b >= 40
    }

    fun read(frame: FrameView, nowMs: Long): GameState {
        val centres = listOf(
            GameGeometry.SKILL1_X to GameGeometry.SKILL1_Y,
            GameGeometry.SKILL2_X to GameGeometry.SKILL2_Y,
            GameGeometry.SKILL3_X to GameGeometry.SKILL3_Y
        )
        var discsPresent = 0
        val skillLum = ArrayList<Double>(3)
        val skillSiap = ArrayList<Boolean?>(3)
        for ((fx, fy) in centres) {
            val (lum, dominance) = discStats(frame, fx, fy)
            skillLum.add(lum)
            if (dominance >= DISC_BLUE_DOMINANCE) {
                discsPresent++
                skillSiap.add(lum >= SKILL_READY_LUMINANCE)
            } else {
                skillSiap.add(null)
            }
        }
        val layar = if (discsPresent >= 2) ScreenKind.MATCH else ScreenKind.OTHER

        val hpRunFrac = longestHpRunFrac(frame)
        val darah = if (layar == ScreenKind.MATCH && hpRunFrac >= HP_MIN_RUN_FRAC) {
            min(100, (hpRunFrac * 100).toInt())
        } else {
            null
        }
        return GameState(nowMs, layar, darah, skillSiap, skillLum, hpRunFrac)
    }

    /** Mean luminance and blue dominance over a small disc at fraction (fx, fy). */
    private fun discStats(frame: FrameView, fx: Float, fy: Float): Pair<Double, Double> {
        val cx = (fx * frame.width).toInt()
        val cy = (fy * frame.height).toInt()
        val radius = max(3, (0.012 * frame.width).toInt())
        var n = 0
        var lumSum = 0.0
        var domSum = 0.0
        var dy = -radius
        while (dy <= radius) {
            var dx = -radius
            while (dx <= radius) {
                if (dx * dx + dy * dy <= radius * radius) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until frame.width && y in 0 until frame.height) {
                        val c = frame.rgb(x, y)
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        lumSum += 0.299 * r + 0.587 * g + 0.114 * b
                        domSum += b - (r + g) / 2.0
                        n++
                    }
                }
                dx += 2
            }
            dy += 2
        }
        if (n == 0) return 0.0 to 0.0
        return (lumSum / n) to (domSum / n)
    }

    /**
     * Longest horizontal HP-green run inside the centre window, as a
     * fraction of the expected full bar length. The hero's bar floats
     * above the hero, who rides near the centre of the frame.
     */
    private fun longestHpRunFrac(frame: FrameView): Double {
        val x0 = (0.36 * frame.width).toInt()
        val x1 = (0.64 * frame.width).toInt()
        val y0 = (0.34 * frame.height).toInt()
        val y1 = (0.54 * frame.height).toInt()
        val fullPx = HP_BAR_FULL_FRAC * frame.width
        var best = 0
        var y = y0
        while (y < y1) {
            var run = 0
            var x = x0
            while (x < x1) {
                if (isHpGreen(frame.rgb(x, y))) {
                    run++
                    if (run > best) best = run
                } else {
                    run = 0
                }
                x++
            }
            y += 2 // the bar is several px tall even at capture scale; skip a row for speed
        }
        return best / fullPx
    }
}
