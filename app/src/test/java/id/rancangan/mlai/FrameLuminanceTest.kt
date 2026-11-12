package id.rancangan.mlai

import id.rancangan.mlai.vision.FrameLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLuminanceTest {

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    @Test
    fun `all black samples average zero and count as black frame`() {
        val samples = IntArray(100) { rgb(0, 0, 0) }
        assertEquals(0.0, FrameLuminance.averageLuminance(samples), 0.001)
        assertEquals(1.0, FrameLuminance.blackSampleRatio(samples), 0.001)
        assertTrue(FrameLuminance.isBlackFrame(samples))
    }

    @Test
    fun `all white samples average 255 and are not black`() {
        val samples = IntArray(100) { rgb(255, 255, 255) }
        assertEquals(255.0, FrameLuminance.averageLuminance(samples), 0.001)
        assertEquals(0.0, FrameLuminance.blackSampleRatio(samples), 0.001)
        assertFalse(FrameLuminance.isBlackFrame(samples))
    }

    @Test
    fun `pure green is brighter than pure red is brighter than pure blue`() {
        // Sanity check on the Rec.601 weights we rely on for HUD colour work later.
        val green = FrameLuminance.luminanceOf(rgb(0, 255, 0))
        val red = FrameLuminance.luminanceOf(rgb(255, 0, 0))
        val blue = FrameLuminance.luminanceOf(rgb(0, 0, 255))
        assertTrue(green > red && red > blue)
    }

    @Test
    fun `empty sample set is safe`() {
        assertEquals(0.0, FrameLuminance.averageLuminance(IntArray(0)), 0.001)
        assertEquals(0.0, FrameLuminance.blackSampleRatio(IntArray(0)), 0.001)
        assertFalse(FrameLuminance.isBlackFrame(IntArray(0)))
    }

    @Test
    fun `half black half white ratio is one half`() {
        val samples = IntArray(10) { i -> if (i < 5) rgb(0, 0, 0) else rgb(255, 255, 255) }
        assertEquals(0.5, FrameLuminance.blackSampleRatio(samples), 0.001)
    }
}
