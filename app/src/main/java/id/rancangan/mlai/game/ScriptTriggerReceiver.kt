package id.rancangan.mlai.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Remote trigger for the Fase 0 Practice script, so the operator (adb)
 * can start it while the GAME is in front:
 *   adb shell am broadcast -a id.rancangan.mlai.RUN_PRACTICE_SCRIPT -p id.rancangan.mlai
 *
 * The script itself re-checks Shizuku readiness and logs honestly to
 * diagnostics.txt; a rejected trigger (already running / not ready)
 * shows a toast when a screen is available.
 */
class ScriptTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return
        val started = PracticeScript.start(context)
        if (!started) {
            Toast.makeText(
                context,
                "Naskah tidak dimulai — sedang berjalan atau Shizuku belum siap",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val ACTION_RUN = "id.rancangan.mlai.RUN_PRACTICE_SCRIPT"
    }
}
