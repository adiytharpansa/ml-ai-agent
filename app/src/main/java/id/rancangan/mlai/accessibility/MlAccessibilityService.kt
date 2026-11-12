package id.rancangan.mlai.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Modul 3 fallback touch path — the gesture-capable accessibility service.
 *
 * Deliberately minimal: it exists so [id.rancangan.mlai.touch.AccessibilityTouchBackend]
 * can call dispatchGesture(). It does not read window content
 * (canRetrieveWindowContent=false in the config XML).
 *
 * Android only lets the user enable this in Settings; the app cannot turn
 * itself on. [instance] is non-null only while the service is connected.
 */
class MlAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling in Fase 0 — gestures are dispatched on demand only.
    }

    override fun onInterrupt() {
        // Nothing to interrupt; gestures are short and fire-and-forget.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** The connected service instance, or null when the user has not enabled it. */
        @Volatile
        var instance: MlAccessibilityService? = null
    }
}
