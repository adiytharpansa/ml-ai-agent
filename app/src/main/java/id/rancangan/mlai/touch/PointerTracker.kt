package id.rancangan.mlai.touch

/**
 * Pure multi-pointer bookkeeping for the Shizuku backend (Modul 3).
 *
 * Android multi-touch is a bookkeeping problem:
 *  - every contact carries a stable pointer **id** chosen by us,
 *  - but each MotionEvent addresses pointers by **index** — their position
 *    in the event's arrays, in the order the contacts went down,
 *  - when a middle contact lifts, the indices of the later contacts shift,
 *    while their ids stay the same,
 *  - and an UP / POINTER_UP event must still *contain* the lifting pointer
 *    in its arrays (it is removed only from the next event onward).
 *
 * This class owns exactly that state machine and nothing else. It has no
 * Android types: callers translate [PlannedEvent] into a MotionEvent.
 * Every operation returns the event to emit, or null when the operation is
 * not valid for the current state (duplicate down, up of an unknown id,
 * move with nothing down) — the backend turns null into an honest false.
 *
 * Deliberately not thread-safe: the backend confines all calls to its
 * injector thread.
 */
class PointerTracker {

    /** One active contact. [id] is stable for the contact's whole life. */
    data class Pointer(val id: Int, val x: Float, val y: Float)

    /** What the next MotionEvent must say. Mirrors MotionEvent's masked actions. */
    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    /**
     * A fully-planned MotionEvent: the masked [action], the [actionIndex]
     * the action applies to (meaningful for POINTER_DOWN / POINTER_UP),
     * and the pointer array snapshot to send — in index order. For UP /
     * POINTER_UP the snapshot still includes the lifting pointer.
     */
    data class PlannedEvent(
        val action: Action,
        val actionIndex: Int,
        val pointers: List<Pointer>
    )

    // Index order == insertion order == the order contacts went down.
    private val active = ArrayList<Pointer>()

    /** Current pointers in index order (defensive copy). */
    val pointers: List<Pointer>
        get() = active.toList()

    val activeCount: Int
        get() = active.size

    fun isActive(id: Int): Boolean = active.any { it.id == id }

    /** Index of [id] in event arrays, or -1 when not active. */
    fun indexOf(id: Int): Int = active.indexOfFirst { it.id == id }

    /**
     * A new contact goes down. The first contact of a stream is DOWN;
     * every later one is POINTER_DOWN appended at the last index.
     * Null when [id] is already down.
     */
    fun down(id: Int, x: Float, y: Float): PlannedEvent? {
        if (isActive(id)) return null
        val first = active.isEmpty()
        active.add(Pointer(id, x, y))
        return PlannedEvent(
            action = if (first) Action.DOWN else Action.POINTER_DOWN,
            actionIndex = active.size - 1,
            pointers = pointers
        )
    }

    /**
     * Move one contact. The emitted event always carries *all* active
     * pointers at their current positions — Android has no per-pointer move.
     * Null when nothing is down or [id] is not active.
     */
    fun move(id: Int, x: Float, y: Float): PlannedEvent? = moveAll(mapOf(id to (x to y)))

    /** Move several contacts in one event. Null when no listed id is active. */
    fun moveAll(updates: Map<Int, Pair<Float, Float>>): PlannedEvent? {
        if (active.isEmpty()) return null
        var changed = false
        for ((id, pos) in updates) {
            val idx = indexOf(id)
            if (idx >= 0) {
                active[idx] = Pointer(id, pos.first, pos.second)
                changed = true
            }
        }
        if (!changed) return null
        return PlannedEvent(Action.MOVE, actionIndex = 0, pointers = pointers)
    }

    /**
     * A contact lifts. The planned event's snapshot still contains it
     * (at [PlannedEvent.actionIndex]); it disappears from the state —
     * and from later events — immediately after planning. Lifting the
     * last remaining contact is UP, any other is POINTER_UP.
     * Null when [id] is not active.
     */
    fun up(id: Int): PlannedEvent? {
        val idx = indexOf(id)
        if (idx < 0) return null
        val snapshot = pointers
        val lastOne = active.size == 1
        active.removeAt(idx)
        return PlannedEvent(
            action = if (lastOne) Action.UP else Action.POINTER_UP,
            actionIndex = idx,
            pointers = snapshot
        )
    }

    /**
     * Plan one CANCEL carrying every active contact, and clear the state.
     * Used as the recovery move after a failed injection, when our idea of
     * what is down can no longer be trusted. Null when nothing is down.
     */
    fun cancelAll(): PlannedEvent? {
        if (active.isEmpty()) return null
        val snapshot = pointers
        active.clear()
        return PlannedEvent(Action.CANCEL, actionIndex = 0, pointers = snapshot)
    }

    /**
     * Drop all state without planning an event. Last-resort bookkeeping
     * reset for when even the recovery CANCEL could not be injected: our
     * pointers may then still be physically down on screen, and only the
     * system's own timeout / the next successful stream can clear them —
     * callers must surface that failure, not hide it.
     */
    fun reset() {
        active.clear()
    }
}
