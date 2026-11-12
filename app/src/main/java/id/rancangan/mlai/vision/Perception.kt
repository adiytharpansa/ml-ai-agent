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
 *  - `layar`: a skill button counts as PRESENT when its centre is
 *    blue-dominant or its rim is — the rim keeps glowing blue even under
 *    the dark cooldown shade and on not-yet-learned icons. Two or more
 *    present buttons = the match HUD. Conservative on purpose: the
 *    Fase 1 gate forbids ever confusing a match with the lobby.
 *  - `skill_siap`: a ready skill icon is bright; on cooldown (or not yet
 *    learned) it sits dark under a shade. Mean centre luminance
 *    separates the states by a wide measured margin.
 *  - `darah_persen`: the hero stands near screen centre (the camera
 *    follows), HP bar floating just above. We scan a centre window for
 *    the longest horizontal run of HP-green and express it against the
 *    bar's full length (a fixed fraction of frame width).
 *
 * Every threshold below was measured on real frames (Fase 1 calibration,
 * 2026-11-13): match frames from Training Camp and a live Vs A.I. match,
 * plus splash/panel/chat frames as negatives — the v2 rule set reads all
 * of them correctly.
 *
 * v3 adds positive signatures for the screens around a match, checked in
 * priority order BEFORE the disc rule — the mode-select screen's blue
 * card art otherwise fakes two skill discs (caught live by the gate
 * evaluation, 2026-11-13): mode select has a flat dark-navy header band;
 * results screens (victory splash, scoreboard) are deep blue across the
 * middle of the frame; the main lobby has a pale navigation strip along
 * the bottom. Measured on 13 labelled frames, all read correctly. Known
 * gap: the brief DEFEAT splash is not yet captured, so it reads OTHER
 * until the (deep-blue) scoreboard appears.
 */
object Perception {

    /** Full overhead HP bar length as a fraction of frame width (measured: 72–73px at 960). */
    const val HP_BAR_FULL_FRAC = 0.076

    /** A green run shorter than this fraction of the full bar is not trusted as the HP bar. */
    const val HP_MIN_RUN_FRAC = 0.30

    /** Disc luminance at/above this = skill ready; below = cooldown shade (ready ≥140, dark ≤82 measured). */
    const val SKILL_READY_LUMINANCE = 100.0

    /** Centre blue dominance (b − mean(r,g)) at/above this marks a skill disc as present. */
    const val DISC_CENTRE_DOMINANCE = 14.0

    /** Rim blue dominance at/above this also marks a skill disc as present (dark discs keep a blue rim). */
    const val DISC_RIM_DOMINANCE = 30.0

    /** Results screens: fraction of the frame's middle that must be deep blue (b − r ≥ 25). */
    const val HASIL_BLUE_FRAC = 0.60

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
        val skillRimDom = ArrayList<Double>(3)
        val skillSiap = ArrayList<Boolean?>(3)
        for ((fx, fy) in centres) {
            val (lum, centreDom, rimDom) = discStats(frame, fx, fy)
            skillLum.add(lum)
            skillRimDom.add(rimDom)
            if (centreDom >= DISC_CENTRE_DOMINANCE || rimDom >= DISC_RIM_DOMINANCE) {
                discsPresent++
                skillSiap.add(lum >= SKILL_READY_LUMINANCE)
            } else {
                skillSiap.add(null)
            }
        }
        // Fixed screens around a match are identified positively, first:
        // their art can fake skill discs, so they outrank the disc rule.
        val special = detectSpecialScreen(frame)
        val layar = special ?: if (discsPresent >= 2) ScreenKind.MATCH else ScreenKind.OTHER

        val hpRunFrac = longestHpRunFrac(frame)
        val darah = if (layar == ScreenKind.MATCH && hpRunFrac >= HP_MIN_RUN_FRAC) {
            min(100, (hpRunFrac * 100).toInt())
        } else {
            null
        }
        // Outside a match the disc numbers are raw diagnostics only; the
        // brain must never see phantom skills from menu art.
        val skills: List<Boolean?> =
            if (layar == ScreenKind.MATCH) skillSiap else listOf(null, null, null)
        return GameState(nowMs, layar, darah, skills, skillLum, skillRimDom, hpRunFrac)
    }

    private fun luminance(packedRgb: Int): Double {
        val r = (packedRgb shr 16) and 0xFF
        val g = (packedRgb shr 8) and 0xFF
        val b = packedRgb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    private fun detectSpecialScreen(frame: FrameView): ScreenKind? {
        if (isPilihMode(frame)) return ScreenKind.PILIH_MODE
        if (midBlueFraction(frame) >= HASIL_BLUE_FRAC) return ScreenKind.HASIL
        if (isLobi(frame)) return ScreenKind.LOBI
        return null
    }

    /** Mode select: the header band is one flat dark navy (measured lum 42 at every sample). */
    private fun isPilihMode(frame: FrameView): Boolean {
        val y = (0.055 * frame.height).toInt()
        for (fx in listOf(0.40f, 0.52f, 0.64f, 0.76f, 0.88f, 0.92f)) {
            val c = frame.rgb((fx * frame.width).toInt(), y)
            val r = (c shr 16) and 0xFF
            val b = c and 0xFF
            val lum = luminance(c)
            if (lum < 20.0 || lum > 75.0 || b < r || b - r > 45) return false
        }
        return true
    }

    /** Fraction of the frame's middle band whose blue clearly beats red (results screens ≈ 0.64–1.00). */
    private fun midBlueFraction(frame: FrameView): Double {
        val x0 = (0.05 * frame.width).toInt()
        val x1 = (0.95 * frame.width).toInt()
        val y0 = (0.30 * frame.height).toInt()
        val y1 = (0.80 * frame.height).toInt()
        var blue = 0
        var total = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = frame.rgb(x, y)
                val r = (c shr 16) and 0xFF
                val b = c and 0xFF
                if (b - r >= 25) blue++
                total++
                x += 3
            }
            y += 3
        }
        return if (total == 0) 0.0 else blue.toDouble() / total
    }

    /** Main lobby: pale navigation strip along the bottom edge (landscape only; measured 5/6 points lit). */
    private fun isLobi(frame: FrameView): Boolean {
        if (frame.width <= frame.height) return false
        val y = (0.955 * frame.height).toInt()
        var lit = 0
        for (fx in listOf(0.20f, 0.30f, 0.40f, 0.50f, 0.60f, 0.65f)) {
            if (luminance(frame.rgb((fx * frame.width).toInt(), y)) >= 170.0) lit++
        }
        return lit >= 4
    }

    private data class DiscStats(val centreLum: Double, val centreDom: Double, val rimDom: Double)

    /**
     * Mean luminance + blue dominance over a small disc at fraction
     * (fx, fy), plus mean blue dominance on a thin ring at the button's
     * rim — the rim stays blue when the icon itself goes dark.
     */
    private fun discStats(frame: FrameView, fx: Float, fy: Float): DiscStats {
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
        // Rim: 60 samples around the circle at 0.021 * width from centre.
        val rimRadius = 0.021 * frame.width
        var rimN = 0
        var rimDomSum = 0.0
        var step = 0
        while (step < 60) {
            val angle = Math.toRadians(step * 6.0)
            val x = cx + (rimRadius * Math.cos(angle)).toInt()
            val y = cy + (rimRadius * Math.sin(angle)).toInt()
            if (x in 0 until frame.width && y in 0 until frame.height) {
                val c = frame.rgb(x.toInt(), y.toInt())
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                rimDomSum += b - (r + g) / 2.0
                rimN++
            }
            step++
        }
        val centreLum = if (n == 0) 0.0 else lumSum / n
        val centreDom = if (n == 0) 0.0 else domSum / n
        val rimDom = if (rimN == 0) 0.0 else rimDomSum / rimN
        return DiscStats(centreLum, centreDom, rimDom)
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
