package id.rancangan.mlai.touch

import android.os.IBinder
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Modul 3 — the reflective bridge from our app process to the system's
 * InputManager, with Shizuku lending us its (shell/root) identity.
 *
 * The chain, link by link:
 *  1. [SystemServiceHelper.getSystemService]("input") fetches the raw
 *     InputManager binder via Shizuku's helper.
 *  2. [ShizukuBinderWrapper] wraps that binder so every transaction is
 *     forwarded through the Shizuku server process. That forwarding is the
 *     whole point: InputManagerService checks the INJECT_EVENTS permission
 *     against the *calling* identity, and through the wrapper the call
 *     carries Shizuku's privileged identity instead of our app's.
 *  3. `android.hardware.input.IInputManager` is a hidden (@hide) AIDL
 *     interface — it is not in the public SDK we compile against — so the
 *     Stub's asInterface() and the injectInputEvent() method itself are
 *     reached by reflection, exactly as the task constraints require.
 *     Nothing here links against hidden-API stub jars.
 *  4. injectInputEvent(event, INJECT_INPUT_EVENT_MODE_ASYNC) enqueues the
 *     event. ASYNC (0) is deliberate: the wait-for-result modes can block
 *     until the event has been dispatched to a window, which is slow and
 *     can deadlock against a busy input pipeline; ASYNC's boolean still
 *     truthfully reports whether the system accepted the event.
 *
 * Honesty rules: the resolved objects are cached, but ANY failure —
 * binder absent, class/method not found, SecurityException, a dead
 * Shizuku server surfacing as a RemoteException — drops the cache,
 * records the reason in [lastError], and returns false. A false from
 * injectInputEvent itself is likewise reported, never retried silently.
 *
 * Status: proven by code reading against the public Shizuku API and the
 * AOSP IInputManager shape. Whether hidden-API reflection on this exact
 * member is permitted on the user's A15 (Android 14, targetSdk 34) and
 * whether the game accepts the resulting events is NOT yet proven — that
 * is precisely what the Fase 0 device experiments are for.
 */
internal class InputManagerLink {

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var inputManager: Any? = null

    @Volatile
    private var injectMethod: Method? = null

    /** Inject one fully-built event. True only when the system accepted it. */
    fun inject(event: MotionEvent): Boolean {
        val resolved = resolve() ?: return false
        val (manager, method) = resolved
        return try {
            val accepted = method.invoke(manager, event, INJECT_MODE_ASYNC) as? Boolean ?: false
            if (!accepted) {
                lastError = "injectInputEvent mengembalikan false (sistem menolak event)"
            }
            accepted
        } catch (e: Exception) {
            lastError = "injectInputEvent gagal: ${describe(e)}"
            invalidate()
            false
        }
    }

    /** Forget the cached proxy so the next inject re-resolves from scratch. */
    fun invalidate() {
        synchronized(this) {
            inputManager = null
            injectMethod = null
        }
    }

    private fun resolve(): Pair<Any, Method>? {
        inputManager?.let { manager ->
            injectMethod?.let { method -> return manager to method }
        }
        synchronized(this) {
            inputManager?.let { manager ->
                injectMethod?.let { method -> return manager to method }
            }
            return try {
                val rawBinder: IBinder? = SystemServiceHelper.getSystemService(INPUT_SERVICE_NAME)
                if (rawBinder == null) {
                    lastError = "binder layanan 'input' tidak ditemukan lewat Shizuku"
                    return null
                }
                val wrapped = ShizukuBinderWrapper(rawBinder)
                val stubClass = Class.forName(IINPUTMANAGER_STUB)
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val manager = asInterface.invoke(null, wrapped)
                if (manager == null) {
                    lastError = "IInputManager.Stub.asInterface mengembalikan null"
                    return null
                }
                val ifaceClass = Class.forName(IINPUTMANAGER)
                val method = ifaceClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Integer.TYPE
                )
                inputManager = manager
                injectMethod = method
                manager to method
            } catch (e: Exception) {
                lastError = "rantai InputManager tidak tersambung: ${describe(e)}"
                null
            }
        }
    }

    private fun describe(e: Exception): String {
        val root = if (e is InvocationTargetException) (e.targetException ?: e) else e
        return "${root.javaClass.simpleName}: ${root.message ?: "(tanpa pesan)"}"
    }

    private companion object {
        const val INPUT_SERVICE_NAME = "input"
        const val IINPUTMANAGER = "android.hardware.input.IInputManager"
        const val IINPUTMANAGER_STUB = "android.hardware.input.IInputManager\$Stub"

        /** IInputManager.INJECT_INPUT_EVENT_MODE_ASYNC — enqueue, don't wait. */
        const val INJECT_MODE_ASYNC = 0
    }
}
