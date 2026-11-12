package id.rancangan.mlai

import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import id.rancangan.mlai.accessibility.MlAccessibilityService
import id.rancangan.mlai.canvas.TestCanvasActivity
import id.rancangan.mlai.capture.CaptureService
import id.rancangan.mlai.capture.CaptureStats
import id.rancangan.mlai.diag.DiagnosticsWriter
import id.rancangan.mlai.diag.SelfTest
import id.rancangan.mlai.touch.AccessibilityTouchBackend
import id.rancangan.mlai.touch.ScriptedTouchTest
import id.rancangan.mlai.touch.ShizukuState
import rikka.shizuku.Shizuku

/**
 * Fase 0 control panel. One screen that shows, honestly and live:
 * MediaProjection consent, accessibility state, Shizuku state, and capture
 * statistics — plus the buttons to drive the Fase 0 experiments.
 *
 * Nothing on this screen claims success from a return value alone; the
 * diagnostics section is written by [SelfTest] from measured evidence.
 */
class MainActivity : ComponentActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var tvConsent: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var tvShizuku: TextView
    private lateinit var tvCapture: TextView
    private lateinit var tvDiagnostics: TextView
    private lateinit var btnSelfTest: Button

    // MediaProjection consent is per-session: these live only in memory.
    private var consentResultCode: Int = 0
    private var consentData: Intent? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 500)
        }
    }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            consentResultCode = result.resultCode
            consentData = result.data
            toast("Izin rekam layar diberikan untuk sesi ini")
        } else {
            consentData = null
            toast("Izin rekam layar ditolak")
        }
        refreshStatus()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification visibility is a courtesy here, not a Fase 0 verdict. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConsent = findViewById(R.id.tvConsent)
        tvAccessibility = findViewById(R.id.tvAccessibility)
        tvShizuku = findViewById(R.id.tvShizuku)
        tvCapture = findViewById(R.id.tvCapture)
        tvDiagnostics = findViewById(R.id.tvDiagnostics)
        btnSelfTest = findViewById(R.id.btnSelfTest)

        findViewById<Button>(R.id.btnConsent).setOnClickListener {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            consentLauncher.launch(mpm.createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startCapture() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(CaptureService.stopIntent(this))
            toast("Perintah berhenti dikirim")
        }
        findViewById<Button>(R.id.btnAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnShizukuPermission).setOnClickListener {
            if (ShizukuState.check(this) == ShizukuState.RUNNING_NO_PERMISSION) {
                ShizukuState.requestPermission(REQUEST_SHIZUKU_PERMISSION)
            } else {
                toast("Shizuku: ${ShizukuState.check(this).label()}")
            }
        }
        findViewById<Button>(R.id.btnCanvas).setOnClickListener {
            startActivity(Intent(this, TestCanvasActivity::class.java))
        }
        findViewById<Button>(R.id.btnTouchTest).setOnClickListener { runTouchTest() }
        btnSelfTest.setOnClickListener { runSelfTest() }

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        Shizuku.addRequestPermissionResultListener(this)
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(statusTicker)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(statusTicker)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this)
        super.onDestroy()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
        toast("Izin Shizuku: ${if (granted) "diberikan" else "ditolak"}")
        refreshStatus()
    }

    private fun startCapture() {
        val data = consentData
        if (data == null) {
            toast("Beri izin rekam layar dulu")
            return
        }
        ContextCompat.startForegroundService(
            this, CaptureService.startIntent(this, consentResultCode, data)
        )
        toast("Layanan tangkap dimulai")
    }

    /** Scripted tap+swipe+skill on the canvas via the accessibility backend. */
    private fun runTouchTest() {
        if (MlAccessibilityService.instance == null) {
            toast("Layanan aksesibilitas belum terhubung — hidupkan dulu di Pengaturan")
            return
        }
        startActivity(Intent(this, TestCanvasActivity::class.java))
        Thread {
            Thread.sleep(1200)
            val (w, h) = displaySize()
            val result = ScriptedTouchTest.run(AccessibilityTouchBackend(), w, h)
            runOnUiThread {
                tvDiagnostics.text =
                    "Uji Sentuh Aksesibilitas: ketuk=${result.tapAccepted}, " +
                    "geser=${result.swipeAccepted}, skill=${result.skillAccepted}. " +
                    "Bukti mendaratnya ada di log Kanvas Uji dan dihitung resmi oleh Uji Mandiri."
            }
        }.start()
    }

    private fun runSelfTest() {
        btnSelfTest.isEnabled = false
        tvDiagnostics.text = "Uji Mandiri berjalan…"
        Thread {
            val results = SelfTest.run(this)
            val files = DiagnosticsWriter.write(this, results)
            runOnUiThread {
                btnSelfTest.isEnabled = true
                tvDiagnostics.text = results.joinToString("\n") { it.line() } +
                    "\n\nTertulis ke: " + files.joinToString(" dan ") { it.absolutePath }
            }
        }.start()
    }

    private fun refreshStatus() {
        tvConsent.text = "Izin rekam layar: " +
            if (consentData != null) "sudah diberikan (sesi ini)" else "belum diberikan"

        val enabledInSettings = isAccessibilityEnabledInSettings()
        val connected = MlAccessibilityService.instance != null
        tvAccessibility.text = "Aksesibilitas: " +
            "di Pengaturan ${if (enabledInSettings) "YA" else "tidak"}, " +
            "layanan terhubung ${if (connected) "YA" else "tidak"}"

        tvShizuku.text = "Shizuku: ${ShizukuState.check(this).label()}"

        tvCapture.text = if (CaptureStats.serviceRunning) {
            "Tangkapan: berjalan — frame=%d, fps=%.1f, frame hitam=%.1f%%, ukuran=%dx%d, PNG contoh %s".format(
                CaptureStats.totalFrames, CaptureStats.fps,
                CaptureStats.blackRatio * 100.0,
                CaptureStats.frameWidth, CaptureStats.frameHeight,
                if (CaptureStats.samplePngSaved) "tersimpan" else "belum"
            )
        } else {
            "Tangkapan: tidak berjalan"
        }
    }

    private fun isAccessibilityEnabledInSettings(): Boolean {
        val setting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = ComponentName(this, MlAccessibilityService::class.java)
            .flattenToString()
        return setting.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun displaySize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_SHIZUKU_PERMISSION = 1001
    }
}
