package id.rancangan.mlai

import id.rancangan.mlai.touch.GesturePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class GesturePlanTest {

    @Test
    fun `segment below window is raised to the minimum`() {
        assertEquals(GesturePlan.SEGMENT_MIN_MS, GesturePlan.clampSegmentMs(50))
    }

    @Test
    fun `segment above window is lowered to the maximum`() {
        assertEquals(GesturePlan.SEGMENT_MAX_MS, GesturePlan.clampSegmentMs(5_000))
    }

    @Test
    fun `segment inside window passes through`() {
        assertEquals(350L, GesturePlan.clampSegmentMs(350))
    }
}
