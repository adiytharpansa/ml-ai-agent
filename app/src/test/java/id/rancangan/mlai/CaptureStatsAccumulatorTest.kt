package id.rancangan.mlai

import id.rancangan.mlai.capture.CaptureStatsAccumulator
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStatsAccumulatorTest {

    @Test
    fun `empty accumulator reports zeros`() {
        val acc = CaptureStatsAccumulator(windowMs = 2_000)
        val snap = acc.snapshot(nowMs = 10_000)
        assertEquals(0L, snap.totalFrames)
        assertEquals(0.0, snap.blackRatio, 0.001)
        assertEquals(0.0, snap.windowFps, 0.001)
    }

    @Test
    fun `black ratio counts all frames ever added`() {
        val acc = CaptureStatsAccumulator(windowMs = 2_000)
        acc.addFrame(1_000, black = true)
        acc.addFrame(1_100, black = false)
        acc.addFrame(1_200, black = true)
        acc.addFrame(1_300, black = false)
        val snap = acc.snapshot(nowMs = 1_400)
        assertEquals(4L, snap.totalFrames)
        assertEquals(2L, snap.totalBlackFrames)
        assertEquals(0.5, snap.blackRatio, 0.001)
    }

    @Test
    fun `window fps only counts recent frames`() {
        val acc = CaptureStatsAccumulator(windowMs = 2_000)
        // One ancient frame: must not inflate the window fps (but still counts in totals).
        acc.addFrame(1_000, black = false)
        // 20 frames spread over the last 2 seconds -> about 10 fps.
        for (i in 0 until 20) {
            acc.addFrame(8_100 + i * 100L, black = false)
        }
        val snap = acc.snapshot(nowMs = 10_000)
        assertEquals(21L, snap.totalFrames)
        assertEquals(10.0, snap.windowFps, 0.6)
    }

    @Test
    fun `reset clears everything`() {
        val acc = CaptureStatsAccumulator()
        acc.addFrame(1_000, black = true)
        acc.reset()
        val snap = acc.snapshot(nowMs = 1_100)
        assertEquals(0L, snap.totalFrames)
        assertEquals(0.0, snap.blackRatio, 0.001)
    }
}
