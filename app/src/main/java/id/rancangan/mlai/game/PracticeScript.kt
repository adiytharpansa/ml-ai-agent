package id.rancangan.mlai.game

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import id.rancangan.mlai.diag.EvidenceFiles
import id.rancangan.mlai.touch.ShizukuState
import id.rancangan.mlai.touch.ShizukuTouchBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Fase 0 gate script (design doc §9), run inside Practice mode:
 * a FIXED, non-AI sequence through the Shizuku hand —
 *   1. hold the joystick and walk a full octagon (8 directions, flowing
 *      turns, the finger never lifts),
 *   2. return to centre and release — the hero must stop dead,
 *   3. walk again and fire skills 1, 2, 3 WHILE still walking,
 *   4. release everything.
 *
 * Every injection's real acceptance is logged with its step name and the
 * whole run is appended to diagnostics.txt. Whether the hero visibly
 * obeyed is judged from screenshots/capture frames by the operator —
 * this script never claims movement it cannot see.
 */
object PracticeScript {

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /** Start the script on its own thread. False if already running or Shizuku is not ready. */
    fun start(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val appContext = context.applicationContext
        Thread {
            try {
                run(appContext)
            } finally {
                running.set(false)
            }
        }.start()
        return true
    }

    private fun run(context: Context) {
        val log = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        log.append("\n===== Naskah Latihan Fase 0 — ").append(stamp).append(" =====\n")

        if (ShizukuState.check(context) != ShizukuState.READY) {
            log.append("DIBATALKAN — Shizuku belum siap\n")
            EvidenceFiles.appendBoth(context, EvidenceFiles.DIAGNOSTICS_NAME, log.toString())
            return
        }

        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        log.append("ukuran layar=%dx%d\n".format(w, h))

        val joyX = GameGeometry.JOYSTICK_X * w
        val joyY = GameGeometry.JOYSTICK_Y * h
        val radius = GameGeometry.JOYSTICK_RADIUS_REF * (w / GameGeometry.REFERENCE_WIDTH)
        val backend = ShizukuTouchBackend(context)

        fun step(name: String, ok: Boolean) {
            log.append(if (ok) "OK  " else "GAGAL ").append(name).append('\n')
        }

        try {
            // 1) Walk the octagon: 8 directions, one continuous hold.
            var allOk = true
            val downOk = backend.joystickDown(joyX, joyY, 1f, 0f, radius)
            step("joystick turun (arah kanan)", downOk)
            allOk = allOk && downOk
            for (i in 0 until 8) {
                val angle = Math.toRadians((i * 45).toDouble())
                val dx = cos(angle).toFloat()
                val dy = sin(angle).toFloat()
                // Hold each direction ~650ms, steering in small steps so
                // the turn flows instead of jumping.
                repeat(5) {
                    val ok = backend.joystickMove(dx, dy)
                    if (!ok) allOk = false
                    Thread.sleep(130)
                }
                step("jalan arah ${i * 45}°", allOk)
            }

            // 2) Precise stop: back to centre briefly, then release.
            step("kembali ke tengah", backend.joystickMove(0f, 0f))
            Thread.sleep(250)
            step("joystick lepas (hero harus berhenti)", backend.joystickRelease())
            Thread.sleep(800)

            // 3) Walk right again; skills 1/2/3 fire while walking.
            step("joystick turun lagi (kanan)", backend.joystickDown(joyX, joyY, 1f, 0f, radius))
            Thread.sleep(350)
            step("skill 1 sambil jalan", backend.skill(GameGeometry.SKILL1_X * w, GameGeometry.SKILL1_Y * h))
            Thread.sleep(400)
            step("skill 2 sambil jalan", backend.skill(GameGeometry.SKILL2_X * w, GameGeometry.SKILL2_Y * h))
            Thread.sleep(400)
            step("skill 3 sambil jalan", backend.skill(GameGeometry.SKILL3_X * w, GameGeometry.SKILL3_Y * h))
            Thread.sleep(400)

            // 4) Release everything — the emergency-stop shape.
            step("lepas semua", backend.releaseAll())
        } catch (e: Exception) {
            log.append("BERHENTI DI TENGAH — pengecualian: ").append(e.message).append('\n')
            try {
                backend.releaseAll()
            } catch (_: Exception) {
            }
        } finally {
            backend.close()
        }

        EvidenceFiles.appendBoth(context, EvidenceFiles.DIAGNOSTICS_NAME, log.toString())
    }
}
