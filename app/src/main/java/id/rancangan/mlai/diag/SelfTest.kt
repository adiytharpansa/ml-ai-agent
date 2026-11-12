package id.rancangan.mlai.diag

import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.WindowManager
import id.rancangan.mlai.accessibility.MlAccessibilityService
import id.rancangan.mlai.canvas.TestCanvasActivity
import id.rancangan.mlai.canvas.TouchLog
import id.rancangan.mlai.capture.CaptureStats
import id.rancangan.mlai.touch.AccessibilityTouchBackend
import id.rancangan.mlai.touch.ScriptedTouchTest
import id.rancangan.mlai.touch.ShizukuState
import id.rancangan.mlai.touch.ShizukuTouchBackend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Verdict of one Fase 0 experiment. BELUM = not actually measured yet — never dressed up. */
enum class Vonis { LULUS, GAGAL, BELUM }

data class HasilUji(
    val nama: String,
    val vonis: Vonis,
    val detail: String
) {
    fun line(): String = "$nama: ${vonis.name} — $detail"
}

/**
 * Uji Mandiri Fase 0 — one button, the app judges itself (design doc §9).
 *
 * Runs the Fase 0 experiments in order on the CALLER's background thread and
 * reports only what was actually measured:
 *  - capture verdicts come from CaptureStats counters,
 *  - the touch verdict comes from MotionEvents the canvas really received,
 *  - Shizuku verdict comes from the binder/permission check,
 *  - anything not implemented or not set up yet is reported BELUM.
 */
object SelfTest {

    fun run(context: Context): List<HasilUji> {
        val results = mutableListOf<HasilUji>()
        results += ujiAksesibilitas()
        results += ujiRekamLayar()
        results += ujiSentuhKanvas(context)
        results += ujiShizuku(context)
        results += ujiSimultan(context)
        results += ujiSuhuBaterai(context)
        return results
    }

    private fun ujiAksesibilitas(): HasilUji {
        return if (MlAccessibilityService.instance != null) {
            HasilUji(
                "Aksesibilitas terhubung", Vonis.LULUS,
                "layanan terhubung dan siap menerima perintah gestur"
            )
        } else {
            HasilUji(
                "Aksesibilitas terhubung", Vonis.BELUM,
                "layanan belum dihidupkan di Pengaturan Aksesibilitas"
            )
        }
    }

    private fun ujiRekamLayar(): HasilUji {
        if (!CaptureStats.serviceRunning) {
            return HasilUji(
                "Rekam layar (MediaProjection)", Vonis.BELUM,
                "layanan tangkap belum berjalan — beri izin lalu tekan Mulai Layanan"
            )
        }
        val frames = CaptureStats.totalFrames
        val blackPct = CaptureStats.blackRatio * 100.0
        val detail = "frame=%d, fps=%.1f, frame hitam=%.1f%%, ukuran=%dx%d, png contoh %s".format(
            frames, CaptureStats.fps, blackPct,
            CaptureStats.frameWidth, CaptureStats.frameHeight,
            if (CaptureStats.samplePngSaved) "tersimpan" else "belum tersimpan"
        )
        return when {
            frames < 30 -> HasilUji("Rekam layar (MediaProjection)", Vonis.BELUM, "frame belum cukup terkumpul. $detail")
            CaptureStats.blackRatio >= 0.99 -> HasilUji(
                "Rekam layar (MediaProjection)", Vonis.GAGAL,
                "hampir semua frame hitam bahkan pada layar aplikasi sendiri — jalur rekam gagal/terhalang. $detail"
            )
            else -> HasilUji("Rekam layar (MediaProjection)", Vonis.LULUS, detail)
        }
    }

    private fun ujiSentuhKanvas(context: Context): HasilUji {
        val nama = "Urutan sentuh mendarat di Kanvas Uji"
        if (MlAccessibilityService.instance == null) {
            return HasilUji(nama, Vonis.BELUM, "layanan aksesibilitas belum terhubung")
        }
        val (width, height) = displaySize(context)
        val before = TouchLog.totalEvents

        val intent = Intent(context, TestCanvasActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Thread.sleep(1500)

        val script = ScriptedTouchTest.run(AccessibilityTouchBackend(), width, height)
        Thread.sleep(600)
        val newEvents = TouchLog.totalEvents - before
        TestCanvasActivity.instance?.finish()

        val detail = "gestur diterima sistem: ketuk=%s geser=%s skill=%s; event baru di kanvas=%d".format(
            script.tapAccepted, script.swipeAccepted, script.skillAccepted, newEvents
        )
        return when {
            !script.allAccepted() -> HasilUji(nama, Vonis.GAGAL, "sebagian gestur ditolak sistem. $detail")
            newEvents >= 6 -> HasilUji(nama, Vonis.LULUS, detail)
            else -> HasilUji(
                nama, Vonis.GAGAL,
                "gestur diterima tetapi event tidak sampai ke kanvas. $detail"
            )
        }
    }

    private fun ujiShizuku(context: Context): HasilUji {
        val state = ShizukuState.check(context)
        return when (state) {
            ShizukuState.READY -> HasilUji(
                "Shizuku", Vonis.LULUS,
                "binder aktif dan izin diberikan; apakah injeksinya benar-benar bekerja dibuktikan percobaan simultan di bawah"
            )
            else -> HasilUji("Shizuku", Vonis.BELUM, state.label())
        }
    }

    /**
     * The design's acid test, run for real when Shizuku is READY:
     * hold the joystick down on the canvas's joystick circle, tap skill 1
     * with the second pointer while it is held, then release. The verdict
     * does NOT come from injectInputEvent accepting the events — it comes
     * from the canvas's own log: only a genuine two-finger overlap makes
     * [TouchLog.maxPointerCount] reach 2.
     */
    private fun ujiSimultan(context: Context): HasilUji {
        val nama = "Joystick ditahan + skill bersamaan (Shizuku)"
        val state = ShizukuState.check(context)
        if (state != ShizukuState.READY) {
            return HasilUji(nama, Vonis.BELUM, "Shizuku belum siap: ${state.label()}")
        }
        val (width, height) = displaySize(context)
        val backend = ShizukuTouchBackend(context)

        val intent = Intent(context, TestCanvasActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Thread.sleep(1500)

        TouchLog.resetMaxPointerCount()
        val eventsBefore = TouchLog.totalEvents

        val joyX = ScriptedTouchTest.JOYSTICK_X * width
        val joyY = ScriptedTouchTest.JOYSTICK_Y * height
        val skillX = ScriptedTouchTest.SKILL1_X * width
        val skillY = ScriptedTouchTest.SKILL1_Y * height

        // Push the joystick to the right and HOLD it; skill lands mid-hold.
        // Keep the FIRST failure's reason: later calls overwrite lastError
        // with follow-on complaints ("not held") that hide the root cause.
        val downOk = backend.joystickDown(joyX, joyY, dirX = 1f, dirY = 0f, radiusPx = 70f)
        var firstError = backend.lastError
        Thread.sleep(400)
        val skillOk = if (downOk) backend.skill(skillX, skillY) else false
        if (!skillOk && firstError == null) firstError = backend.lastError
        Thread.sleep(300)
        val releaseOk = backend.joystickRelease()
        Thread.sleep(400)

        val newEvents = TouchLog.totalEvents - eventsBefore
        val maxPointers = TouchLog.maxPointerCount
        TestCanvasActivity.instance?.finish()
        backend.close()

        val detail = "injeksi: joystickDown=%s skill=%s joystickRelease=%s; " +
            "event baru di kanvas=%d; penunjuk terbanyak bersamaan=%d; alasan backend=%s".format(
                downOk, skillOk, releaseOk, newEvents, maxPointers,
                firstError ?: backend.lastError ?: "(tidak ada)"
            )
        return when {
            !downOk || !skillOk || !releaseOk -> HasilUji(
                nama, Vonis.GAGAL,
                "salah satu injeksi ditolak sistem. $detail"
            )
            maxPointers >= 2 -> HasilUji(nama, Vonis.LULUS, detail)
            else -> HasilUji(
                nama, Vonis.GAGAL,
                "injeksi diterima sistem tetapi kanvas tidak pernah melihat 2 penunjuk bersamaan — simultan tidak terbukti di perangkat ini. $detail"
            )
        }
    }

    private fun ujiSuhuBaterai(context: Context): HasilUji {
        val health = DeviceHealthReader.read(context)
        val temp = health.temperatureCelsius
        return if (temp != null) {
            HasilUji(
                "Suhu & baterai (pengukuran sesaat)", Vonis.LULUS,
                "suhu=%.1f°C, baterai=%s%%, mengisi=%s — catatan: ini bacaan sesaat; uji sesi 15 menit Fase 0 belum dijalankan".format(
                    temp,
                    health.batteryLevelPercent?.toString() ?: "?",
                    health.charging
                )
            )
        } else {
            HasilUji(
                "Suhu & baterai (pengukuran sesaat)", Vonis.BELUM,
                "suhu baterai tidak terbaca dari BatteryManager di perangkat ini"
            )
        }
    }

    private fun displaySize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
}

/** Writes self-test results to diagnostics.txt — both copies (see [EvidenceFiles]). */
object DiagnosticsWriter {

    fun write(context: Context, results: List<HasilUji>): List<java.io.File> {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("\n===== Uji Mandiri Fase 0 — ").append(stamp).append(" =====\n")
        for (r in results) sb.append(r.line()).append('\n')
        EvidenceFiles.appendBoth(context, EvidenceFiles.DIAGNOSTICS_NAME, sb.toString())
        return EvidenceFiles.targets(context, EvidenceFiles.DIAGNOSTICS_NAME)
    }
}
