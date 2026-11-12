package id.rancangan.mlai

import id.rancangan.mlai.game.GameGeometry
import id.rancangan.mlai.vision.FrameView
import id.rancangan.mlai.vision.Perception
import id.rancangan.mlai.vision.ScreenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Modul 2 (Fase 1) reader tests on synthetic frames painted the way the
 * real HUD looks at capture scale (960x432): vivid blue skill discs
 * (dark navy under cooldown shade), and the hero's green HP bar floating
 * in the centre window. These stand in for human-checked frames until
 * the Fase 1 gate evaluation runs on real match recordings.
 */
class PerceptionTest {

    /** In-memory frame painted by [painter] over a grass-like background. */
    private class FakeFrame(
        override val width: Int = 960,
        override val height: Int = 432,
        painter: (Int, Int) -> Int?
    ) : FrameView {
        private val pixels = IntArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            painter(x, y) ?: GRASS
        }

        override fun rgb(x: Int, y: Int): Int = pixels[y * width + x]

        companion object {
            val GRASS = (88 shl 16) or (108 shl 8) or 66
        }
    }

    private fun discColour(x: Int, y: Int, fx: Float, fy: Float, colour: Int, w: Int, h: Int): Int? {
        val cx = (fx * w).toInt()
        val cy = (fy * h).toInt()
        val r = 34
        val dx = x - cx
        val dy = y - cy
        return if (dx * dx + dy * dy <= r * r) colour else null
    }

    /**
     * A match frame: HP bar at centre filled to [hpFrac], skill discs in
     * ready colour or cooldown shade per [ready] (S1, S2, ULT order).
     */
    private fun matchFrame(hpFrac: Double?, ready: List<Boolean>): FakeFrame {
        val readyColour = (50 shl 16) or (130 shl 8) or 235
        val cdColour = (28 shl 16) or (38 shl 8) or 84
        val green = (70 shl 16) or (215 shl 8) or 80
        val barDark = (40 shl 16) or (30 shl 8) or 30
        val centres = listOf(
            GameGeometry.SKILL1_X to GameGeometry.SKILL1_Y,
            GameGeometry.SKILL2_X to GameGeometry.SKILL2_Y,
            GameGeometry.SKILL3_X to GameGeometry.SKILL3_Y
        )
        return FakeFrame { x, y ->
            var c: Int? = null
            // HP bar: centre window, full length 5% of width, 4 px tall.
            if (hpFrac != null) {
                val w = 960
                val full = (Perception.HP_BAR_FULL_FRAC * w).toInt()
                val bx = w / 2 - full / 2
                val by = (0.44 * 432).toInt()
                if (x in bx until bx + full && y in by until by + 4) {
                    val filled = (hpFrac * full).roundToInt()
                    c = if (x - bx < filled) green else barDark
                }
            }
            for ((i, centre) in centres.withIndex()) {
                val d = discColour(x, y, centre.first, centre.second, if (ready[i]) readyColour else cdColour, 960, 432)
                if (d != null) c = d
            }
            c
        }
    }

    @Test
    fun fullHpAllSkillsReady() {
        val state = Perception.read(matchFrame(1.0, listOf(true, true, true)), 1000)
        assertEquals(ScreenKind.MATCH, state.layar)
        assertTrue("darah was ${state.darahPersen}", state.darahPersen!! in 95..100)
        assertEquals(listOf(true, true, true), state.skillSiap)
    }

    @Test
    fun halfHpUltOnCooldown() {
        val state = Perception.read(matchFrame(0.5, listOf(true, true, false)), 2000)
        assertEquals(ScreenKind.MATCH, state.layar)
        assertTrue("darah was ${state.darahPersen}", state.darahPersen!! in 42..58)
        assertEquals(listOf(true, true, false), state.skillSiap)
    }

    @Test
    fun lobbyFrameIsOtherAndUnread() {
        val state = Perception.read(FakeFrame { _, _ -> null }, 3000)
        assertEquals(ScreenKind.OTHER, state.layar)
        assertNull(state.darahPersen)
        assertEquals(listOf(null, null, null), state.skillSiap)
    }

    @Test
    fun matchWithoutVisibleHpBarReadsNull() {
        val state = Perception.read(matchFrame(null, listOf(true, false, true)), 4000)
        assertEquals(ScreenKind.MATCH, state.layar)
        assertNull(state.darahPersen)
        assertEquals(listOf(true, false, true), state.skillSiap)
    }
}
