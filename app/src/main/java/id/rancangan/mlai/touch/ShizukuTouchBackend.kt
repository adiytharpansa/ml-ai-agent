package id.rancangan.mlai.touch

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Modul 3 — PRIMARY touch backend: real streaming multi-touch injection
 * through Shizuku + the system InputManager (see [InputManagerLink]).
 *
 * This is the path the whole design depends on (design doc §3/§4):
 *  - Pointer id [JOYSTICK_POINTER_ID] is the joystick finger. It goes down
 *    once and STAYS down; steering is a stream of MOVE events.
 *  - Pointer id [SECONDARY_POINTER_ID] is the skill/tap finger. It can go
 *    down and up *while the joystick contact is held*, via
 *    ACTION_POINTER_DOWN / ACTION_POINTER_UP — the simultaneity the
 *    accessibility fallback cannot do.
 *  - All pointer bookkeeping lives in [PointerTracker] (pure, unit-tested);
 *    this class only translates its planned events into MotionEvents and
 *    injects them, and reports injectInputEvent's real boolean back.
 *
 * Threading: every tracker mutation and every injection happens on one
 * dedicated HandlerThread, so event order can never interleave. Public
 * methods block the caller until their event has actually been injected
 * (bounded by [CALL_TIMEOUT_MS]) — a returned true means the system
 * accepted that event, not that it was queued. The single exception is
 * [joystickMove]: steering updates are coalesced — if a move is still
 * waiting to run when a newer one arrives, the pending move's target is
 * replaced (latest position wins) and both callers receive the result of
 * the one move that does run. Down / up / skill / tap are never coalesced,
 * dropped, or reordered.
 *
 * Failure handling: if any injection in a stream fails, the stream is no
 * longer trustworthy (our pointer state may differ from the screen's), so
 * the backend makes one best-effort CANCEL of everything it holds, resets
 * its bookkeeping, records the reason in [lastError], and returns false.
 *
 * Callers must be background threads: these methods block. Never call
 * from the injector thread itself.
 *
 * Status: written and unit-covered for the bookkeeping; the injection
 * chain itself is proven only by code reading so far — the Fase 0 device
 * experiments (Uji Mandiri's simultaneity test on the Test Canvas, then
 * Practice mode) are what will actually prove it on the A15.
 */
class ShizukuTouchBackend(private val context: Context) : TouchBackend {

    override val name: String = "Shizuku"

    /** Why the last operation failed, in plain words; null after a success. */
    @Volatile
    var lastError: String? = null
        private set

    private val link = InputManagerLink()
    private val tracker = PointerTracker()

    private val injectorThread = HandlerThread("mlai-shizuku-injector").apply { start() }
    private val injector = Handler(injectorThread.looper)

    // --- Stream state. Mutated only on the injector thread, except the
    // joystick geometry which joystickMove() reads on the caller thread
    // (hence @Volatile) to compute the coalesced target position. ---
    private var streamDownTimeMs = 0L

    @Volatile
    private var joyCenterX = 0f

    @Volatile
    private var joyCenterY = 0f

    @Volatile
    private var joyRadiusPx = 0f

    // --- Coalesced joystick move slot (guarded by moveLock). ---
    private class MoveSlot(var tipX: Float, var tipY: Float) {
        val waiters = ArrayList<CompletableFuture<Boolean>>()
    }

    private val moveLock = Any()
    private var pendingMove: MoveSlot? = null

    fun state(): ShizukuState = ShizukuState.check(context)

    override fun isReady(): Boolean = state() == ShizukuState.READY

    /**
     * Stop the injector thread. The backend is unusable afterwards.
     * Owners that create a backend for one job (e.g. Uji Mandiri) should
     * call this when done instead of leaking a thread per run.
     */
    fun close() {
        injectorThread.quitSafely()
    }

    // ------------------------------------------------------------------
    // TouchBackend
    // ------------------------------------------------------------------

    override fun tap(x: Float, y: Float): Boolean {
        if (!gateReady()) return false
        return submitSync {
            // A tap while the joystick is held is a second-finger tap;
            // standing alone it reuses the joystick slot (id 0).
            val id = when {
                !tracker.isActive(JOYSTICK_POINTER_ID) -> JOYSTICK_POINTER_ID
                !tracker.isActive(SECONDARY_POINTER_ID) -> SECONDARY_POINTER_ID
                else -> {
                    lastError = "kedua penunjuk sedang terpakai; ketuk dibatalkan"
                    return@submitSync false
                }
            }
            pressAndLift(id, x, y)
        }
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (!gateReady()) return false
        return submitSync {
            val id = when {
                !tracker.isActive(SECONDARY_POINTER_ID) && tracker.isActive(JOYSTICK_POINTER_ID) ->
                    SECONDARY_POINTER_ID
                !tracker.isActive(JOYSTICK_POINTER_ID) -> JOYSTICK_POINTER_ID
                else -> {
                    lastError = "penunjuk untuk geser sedang terpakai; geser dibatalkan"
                    return@submitSync false
                }
            }
            val down = tracker.down(id, x1, y1) ?: return@submitSync false
            if (!injectPlanned(down)) return@submitSync abortStream()
            val steps = (durationMs / SWIPE_STEP_MS).toInt().coerceIn(2, 40)
            var ok = true
            for (i in 1..steps) {
                SystemClock.sleep(durationMs / steps)
                val move = tracker.move(id, x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
                if (move == null || !injectPlanned(move)) {
                    ok = false
                    break
                }
            }
            if (!ok) return@submitSync abortStream()
            val up = tracker.up(id) ?: return@submitSync false
            if (!injectPlanned(up)) return@submitSync abortStream()
            true
        }
    }

    override fun joystickDown(
        centerX: Float, centerY: Float,
        dirX: Float, dirY: Float, radiusPx: Float
    ): Boolean {
        if (!gateReady()) return false
        return submitSync {
            if (tracker.isActive(JOYSTICK_POINTER_ID)) {
                lastError = "joystick sudah tertahan; joystickDown ditolak"
                return@submitSync false
            }
            joyCenterX = centerX
            joyCenterY = centerY
            joyRadiusPx = radiusPx
            // The finger lands on the joystick centre, then pushes out to
            // the rim — two events, one task, so nothing can slip between.
            val down = tracker.down(JOYSTICK_POINTER_ID, centerX, centerY)
                ?: return@submitSync false
            if (!injectPlanned(down)) return@submitSync abortStream()
            val tipX = centerX + dirX * radiusPx
            val tipY = centerY + dirY * radiusPx
            val move = tracker.move(JOYSTICK_POINTER_ID, tipX, tipY)
                ?: return@submitSync abortStream()
            if (!injectPlanned(move)) return@submitSync abortStream()
            true
        }
    }

    override fun joystickMove(dirX: Float, dirY: Float): Boolean {
        if (!gateReady()) return false
        val tipX = joyCenterX + dirX * joyRadiusPx
        val tipY = joyCenterY + dirY * joyRadiusPx
        val future = CompletableFuture<Boolean>()
        synchronized(moveLock) {
            val slot = pendingMove ?: MoveSlot(tipX, tipY).also { created ->
                pendingMove = created
                injector.post { executeMove(created) }
            }
            // Latest position wins: an already-waiting move is retargeted,
            // and this caller joins its waiter list for the shared result.
            slot.tipX = tipX
            slot.tipY = tipY
            slot.waiters.add(future)
        }
        return awaitResult(future)
    }

    override fun joystickRelease(): Boolean {
        if (!gateReady()) return false
        return submitSync {
            if (!tracker.isActive(JOYSTICK_POINTER_ID)) {
                lastError = "joystick tidak sedang tertahan; tidak ada yang dilepas"
                return@submitSync false
            }
            val up = tracker.up(JOYSTICK_POINTER_ID) ?: return@submitSync false
            if (!injectPlanned(up)) return@submitSync abortStream()
            true
        }
    }

    override fun skill(x: Float, y: Float): Boolean {
        if (!gateReady()) return false
        return submitSync {
            if (tracker.isActive(SECONDARY_POINTER_ID)) {
                lastError = "penunjuk kedua sedang terpakai; skill dibatalkan"
                return@submitSync false
            }
            // Works whether or not the joystick is held: PointerTracker
            // plans DOWN for a lone contact and POINTER_DOWN alongside the
            // held joystick — that difference is the whole point.
            pressAndLift(SECONDARY_POINTER_ID, x, y)
        }
    }

    override fun releaseAll(): Boolean {
        if (!gateReady()) return false
        return submitSync {
            if (tracker.activeCount == 0) {
                // Nothing of ours is down; the requested end state already
                // holds, so this is an honest success, not a skipped call.
                return@submitSync true
            }
            // Lift newest-first so indices unwind cleanly: POINTER_UP for
            // every contact except the last remaining one, which is UP.
            while (tracker.activeCount > 0) {
                val lastId = tracker.pointers.last().id
                val up = tracker.up(lastId) ?: return@submitSync abortStream()
                if (!injectPlanned(up)) return@submitSync abortStream()
            }
            true
        }
    }

    // ------------------------------------------------------------------
    // Injector-thread internals
    // ------------------------------------------------------------------

    /** One short press: down, brief hold, up — indivisibly, on this thread. */
    private fun pressAndLift(id: Int, x: Float, y: Float): Boolean {
        val down = tracker.down(id, x, y) ?: run {
            lastError = "penunjuk $id sudah turun; ketuk dibatalkan"
            return false
        }
        if (!injectPlanned(down)) return abortStream()
        SystemClock.sleep(TAP_HOLD_MS)
        val up = tracker.up(id) ?: return abortStream()
        if (!injectPlanned(up)) return abortStream()
        return true
    }

    /** Runs the (possibly retargeted) coalesced joystick move. */
    private fun executeMove(slot: MoveSlot) {
        val waiters: List<CompletableFuture<Boolean>>
        val tipX: Float
        val tipY: Float
        synchronized(moveLock) {
            if (pendingMove === slot) pendingMove = null
            tipX = slot.tipX
            tipY = slot.tipY
            waiters = slot.waiters.toList()
        }
        val result = try {
            when {
                !tracker.isActive(JOYSTICK_POINTER_ID) -> {
                    lastError = "joystickMove tanpa joystick yang tertahan"
                    false
                }
                else -> {
                    val move = tracker.move(JOYSTICK_POINTER_ID, tipX, tipY)
                    if (move == null) false
                    else if (!injectPlanned(move)) abortStream()
                    else true
                }
            }
        } catch (e: Exception) {
            lastError = "joystickMove gagal: ${e.javaClass.simpleName}: ${e.message}"
            false
        }
        waiters.forEach { it.complete(result) }
    }

    /**
     * Translate one planned event into a MotionEvent and inject it.
     * Injector thread only. False propagates [InputManagerLink.lastError].
     */
    private fun injectPlanned(planned: PointerTracker.PlannedEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        if (planned.action == PointerTracker.Action.DOWN) {
            // A new stream starts: every event until the final UP shares
            // this downTime, matching Android's MotionEvent semantics.
            streamDownTimeMs = now
        }
        val event = buildMotionEvent(planned, streamDownTimeMs, now)
        val accepted = try {
            link.inject(event)
        } finally {
            event.recycle()
        }
        if (!accepted) {
            lastError = link.lastError ?: "injeksi ditolak tanpa alasan tercatat"
        }
        return accepted
    }

    private fun buildMotionEvent(
        planned: PointerTracker.PlannedEvent,
        downTimeMs: Long,
        eventTimeMs: Long
    ): MotionEvent {
        val count = planned.pointers.size
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = planned.pointers[i].id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply {
                x = planned.pointers[i].x
                y = planned.pointers[i].y
                pressure = 1f
                size = 1f
            }
        }
        val masked = when (planned.action) {
            PointerTracker.Action.DOWN -> MotionEvent.ACTION_DOWN
            PointerTracker.Action.POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN
            PointerTracker.Action.MOVE -> MotionEvent.ACTION_MOVE
            PointerTracker.Action.POINTER_UP -> MotionEvent.ACTION_POINTER_UP
            PointerTracker.Action.UP -> MotionEvent.ACTION_UP
            PointerTracker.Action.CANCEL -> MotionEvent.ACTION_CANCEL
        }
        val action = if (
            planned.action == PointerTracker.Action.POINTER_DOWN ||
            planned.action == PointerTracker.Action.POINTER_UP
        ) {
            masked or (planned.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            masked
        }
        val event = MotionEvent.obtain(
            downTimeMs, eventTimeMs, action, count, properties, coords,
            /* metaState = */ 0, /* buttonState = */ 0,
            /* xPrecision = */ 1f, /* yPrecision = */ 1f,
            /* deviceId = */ 0, /* edgeFlags = */ 0,
            /* source = */ InputDevice.SOURCE_TOUCHSCREEN, /* flags = */ 0
        )
        return event
    }

    /**
     * A stream member failed to inject: try once to CANCEL everything we
     * hold (best effort — its own result is ignored), drop all bookkeeping,
     * and report failure. The caller's false is the verdict that matters.
     */
    private fun abortStream(): Boolean {
        val cancel = tracker.cancelAll()
        if (cancel != null) {
            val event = buildMotionEvent(cancel, streamDownTimeMs, SystemClock.uptimeMillis())
            try {
                link.inject(event)
            } catch (e: Exception) {
                // Recovery is best-effort; the original failure stands.
            } finally {
                event.recycle()
            }
        }
        tracker.reset()
        return false
    }

    // ------------------------------------------------------------------
    // Caller-thread plumbing
    // ------------------------------------------------------------------

    /** Fast honest gate: without Shizuku READY, no injection can succeed. */
    private fun gateReady(): Boolean {
        val current = state()
        if (current != ShizukuState.READY) {
            lastError = "Shizuku belum siap: ${current.label()}"
            return false
        }
        lastError = null
        return true
    }

    /**
     * Run [block] on the injector thread and wait (bounded) for its real
     * result, preserving submission order against every other operation.
     */
    private fun submitSync(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == injectorThread.looper) {
            // Defensive: blocking on our own thread would deadlock. No
            // internal caller does this; if one ever does, run inline.
            return try {
                block()
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                false
            }
        }
        val future = CompletableFuture<Boolean>()
        injector.post {
            val result = try {
                block()
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                false
            }
            future.complete(result)
        }
        return awaitResult(future)
    }

    private fun awaitResult(future: CompletableFuture<Boolean>): Boolean {
        return try {
            future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            lastError = "batas waktu injeksi terlampaui (${CALL_TIMEOUT_MS} mdtk) — " +
                "rantai Shizuku/InputManager tidak menjawab"
            false
        }
    }

    companion object {
        /** Pointer id reserved for the joystick finger. */
        const val JOYSTICK_POINTER_ID = 0

        /** Pointer id reserved for skill / second-finger taps. */
        const val SECONDARY_POINTER_ID = 1

        /** How long a tap/skill contact stays down before lifting. */
        const val TAP_HOLD_MS = 60L

        /** Rough cadence of intermediate MOVE events inside one swipe. */
        const val SWIPE_STEP_MS = 32L

        /** Upper bound a caller waits for an injection verdict. */
        const val CALL_TIMEOUT_MS = 1500L
    }
}
