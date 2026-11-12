package id.rancangan.mlai

import id.rancangan.mlai.touch.PointerTracker
import id.rancangan.mlai.touch.PointerTracker.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-touch bookkeeping the Shizuku backend lives or dies by:
 * stable pointer ids, shifting indices, and UP events that still
 * contain the lifting pointer.
 */
class PointerTrackerTest {

    @Test
    fun `first down is ACTION_DOWN at index 0`() {
        val tracker = PointerTracker()
        val event = tracker.down(0, 100f, 200f)!!
        assertEquals(Action.DOWN, event.action)
        assertEquals(0, event.actionIndex)
        assertEquals(listOf(PointerTracker.Pointer(0, 100f, 200f)), event.pointers)
        assertEquals(1, tracker.activeCount)
    }

    @Test
    fun `second down is POINTER_DOWN appended at last index`() {
        val tracker = PointerTracker()
        tracker.down(0, 100f, 200f)
        val event = tracker.down(1, 800f, 600f)!!
        assertEquals(Action.POINTER_DOWN, event.action)
        assertEquals(1, event.actionIndex)
        assertEquals(listOf(0, 1), event.pointers.map { it.id })
    }

    @Test
    fun `duplicate down of an active id is rejected`() {
        val tracker = PointerTracker()
        tracker.down(0, 0f, 0f)
        assertNull(tracker.down(0, 5f, 5f))
        assertEquals(1, tracker.activeCount)
    }

    @Test
    fun `move carries every pointer and changes only the named one`() {
        val tracker = PointerTracker()
        tracker.down(0, 100f, 200f)
        tracker.down(1, 800f, 600f)
        val event = tracker.move(0, 130f, 210f)!!
        assertEquals(Action.MOVE, event.action)
        assertEquals(
            listOf(
                PointerTracker.Pointer(0, 130f, 210f),
                PointerTracker.Pointer(1, 800f, 600f)
            ),
            event.pointers
        )
    }

    @Test
    fun `move with nothing down or unknown id is rejected`() {
        val tracker = PointerTracker()
        assertNull(tracker.move(0, 1f, 1f))
        tracker.down(0, 0f, 0f)
        assertNull(tracker.move(7, 1f, 1f))
    }

    @Test
    fun `lifting the first of two is POINTER_UP and the second keeps its id at index 0`() {
        val tracker = PointerTracker()
        tracker.down(0, 100f, 200f)
        tracker.down(1, 800f, 600f)
        val event = tracker.up(0)!!
        assertEquals(Action.POINTER_UP, event.action)
        assertEquals(0, event.actionIndex)
        // The event itself still contains BOTH pointers (the lifter included)…
        assertEquals(listOf(0, 1), event.pointers.map { it.id })
        // …but the surviving state has only id 1, now at index 0.
        assertEquals(listOf(PointerTracker.Pointer(1, 800f, 600f)), tracker.pointers)
        assertEquals(0, tracker.indexOf(1))
    }

    @Test
    fun `lifting the last pointer is ACTION_UP`() {
        val tracker = PointerTracker()
        tracker.down(0, 100f, 200f)
        tracker.down(1, 800f, 600f)
        tracker.up(1)
        val event = tracker.up(0)!!
        assertEquals(Action.UP, event.action)
        assertEquals(0, event.actionIndex)
        assertEquals(0, tracker.activeCount)
    }

    @Test
    fun `up of an unknown id is rejected`() {
        val tracker = PointerTracker()
        tracker.down(0, 0f, 0f)
        assertNull(tracker.up(9))
        assertEquals(1, tracker.activeCount)
    }

    @Test
    fun `draining newest-first yields POINTER_UP then UP (the releaseAll order)`() {
        val tracker = PointerTracker()
        tracker.down(0, 100f, 200f)
        tracker.down(1, 800f, 600f)
        val actions = mutableListOf<Action>()
        while (tracker.activeCount > 0) {
            val event = tracker.up(tracker.pointers.last().id)!!
            actions.add(event.action)
        }
        assertEquals(listOf(Action.POINTER_UP, Action.UP), actions)
    }

    @Test
    fun `a fresh down after a full release starts a new stream as ACTION_DOWN`() {
        val tracker = PointerTracker()
        tracker.down(0, 0f, 0f)
        tracker.up(0)
        val event = tracker.down(0, 10f, 10f)!!
        assertEquals(Action.DOWN, event.action)
        assertEquals(0, event.actionIndex)
    }

    @Test
    fun `cancelAll plans one CANCEL with every pointer and clears state`() {
        val tracker = PointerTracker()
        tracker.down(0, 100f, 200f)
        tracker.down(1, 800f, 600f)
        val event = tracker.cancelAll()!!
        assertEquals(Action.CANCEL, event.action)
        assertEquals(listOf(0, 1), event.pointers.map { it.id })
        assertEquals(0, tracker.activeCount)
        assertNull(tracker.cancelAll())
        assertTrue(tracker.pointers.isEmpty())
    }

    @Test
    fun `ids need not be sequential - a lone secondary pointer is a valid stream`() {
        val tracker = PointerTracker()
        val event = tracker.down(1, 50f, 50f)!!
        assertEquals(Action.DOWN, event.action)
        assertEquals(1, event.pointers[0].id)
        assertEquals(Action.UP, tracker.up(1)!!.action)
    }
}
