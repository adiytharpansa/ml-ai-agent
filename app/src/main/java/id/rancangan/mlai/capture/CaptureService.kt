package id.rancangan.mlai.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import id.rancangan.mlai.diag.EvidenceFiles
import id.rancangan.mlai.vision.FrameLuminance
import id.rancangan.mlai.vision.GameStateStore
import id.rancangan.mlai.vision.Perception

/**
 * Modul 1 (Mata) capture + the Fase 1 wiring of Modul 2.
 *
 * Captures the screen through MediaProjection at a reduced size, measures
 * fps and the black-frame ratio, keeps the latest numbers in [CaptureStats],
 * and saves one sample PNG as proof. Since Fase 1 it also feeds each frame
 * (throttled to 4 Hz) to [Perception], which publishes a written
 * [id.rancangan.mlai.vision.GameState] and journals it once per second.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var densityDpi = 0

    private val accumulator = CaptureStatsAccumulator()
    private var frameWidth = 0
    private var frameHeight = 0
    private var sampleSaved = false

    // Modul 2 (Fase 1): perception runs at 4 Hz on the capture thread and
    // journals one state line per second — cheap reads, small regions.
    private var lastPerceptionMs = 0L
    private var lastJournalMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification()
                startCapture(resultCode, resultData)
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "uji_jalur_capture"
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(channelId, "Rekam Layar Uji Jalur", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Uji Jalur ML — merekam layar")
            .setContentText("Layanan tangkapan Fase 0 berjalan. Berhenti kapan saja dari sini.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Berhenti", stopIntent)
            .build()
        // minSdk is 29, so the typed startForeground overload is always available.
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        stopCapture()
        accumulator.reset()
        CaptureStats.reset()
        sampleSaved = false

        captureThread = HandlerThread("capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        val size = currentCaptureSize()
        frameWidth = size.first
        frameHeight = size.second

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm?.getMediaProjection(resultCode, resultData)
        // Android 14 requires a registered callback before createVirtualDisplay.
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                stopSelf()
            }
        }, captureHandler)

        imageReader = newReader()

        virtualDisplay = projection?.createVirtualDisplay(
            "uji-jalur-capture",
            frameWidth, frameHeight, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, captureHandler
        )
        registerRotationWatcher()
        CaptureStats.serviceRunning = true
    }

    /** Capture size for the display's CURRENT orientation, capped at [MAX_WIDTH], even dims. */
    private fun currentCaptureSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        densityDpi = metrics.densityDpi
        val scale = if (metrics.widthPixels > MAX_WIDTH) MAX_WIDTH.toDouble() / metrics.widthPixels else 1.0
        val w = (metrics.widthPixels * scale).toInt().coerceAtLeast(2) and 0x7FFFFFFE
        val h = (metrics.heightPixels * scale).toInt().coerceAtLeast(2) and 0x7FFFFFFE
        return w to h
    }

    private fun newReader(): ImageReader {
        val reader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processFrame(image)
            } finally {
                image.close()
            }
        }, captureHandler)
        return reader
    }

    /**
     * The capture surface is created in whatever orientation the phone is
     * in when the service starts (usually portrait, from the panel). If
     * the phone then rotates into the landscape game, a fixed surface
     * letterboxes the game into a band and every fractional read lands
     * wrong — this bit Fase 1's first live test. On rotation, rebuild
     * the reader at the new size and resize the virtual display to it.
     */
    private fun registerRotationWatcher() {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == android.view.Display.DEFAULT_DISPLAY) refreshSurfaceSize()
            }
        }
        dm.registerDisplayListener(listener, captureHandler)
        displayListener = listener
    }

    private fun refreshSurfaceSize() {
        val (w, h) = currentCaptureSize()
        if (w == frameWidth && h == frameHeight) return
        frameWidth = w
        frameHeight = h
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = newReader()
        try {
            virtualDisplay?.resize(w, h, densityDpi)
            virtualDisplay?.surface = imageReader?.surface
        } catch (_: Exception) {}
        sampleSaved = false // keep a fresh sample in the new orientation too
    }

    private fun processFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        // Sample a coarse grid (~80 x 45 points) — enough for a black-frame
        // verdict, cheap enough to run on every frame on a mid-range phone.
        val stepX = maxOf(1, width / 80)
        val stepY = maxOf(1, height / 45)
        val samples = IntArray(80 * 45 + 45)
        var n = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val idx = y * rowStride + x * pixelStride
                if (idx + 2 < buffer.capacity()) {
                    val r = buffer.get(idx).toInt() and 0xFF
                    val g = buffer.get(idx + 1).toInt() and 0xFF
                    val b = buffer.get(idx + 2).toInt() and 0xFF
                    if (n < samples.size) samples[n++] = (r shl 16) or (g shl 8) or b
                }
                x += stepX
            }
            y += stepY
        }
        val used = samples.copyOf(n)
        val black = FrameLuminance.isBlackFrame(used)

        val now = System.currentTimeMillis()
        accumulator.addFrame(now, black)
        CaptureStats.publish(accumulator.snapshot(now), width, height, now)

        // Fase 1: read the game state from this same frame (image still open).
        if (now - lastPerceptionMs >= PERCEPTION_INTERVAL_MS) {
            lastPerceptionMs = now
            try {
                val state = Perception.read(BufferFrameView(image), now)
                GameStateStore.latest = state
                if (now - lastJournalMs >= JOURNAL_INTERVAL_MS) {
                    lastJournalMs = now
                    EvidenceFiles.appendBoth(this, GAMESTATE_NAME, state.line() + "\n")
                }
            } catch (e: Exception) {
                // A failed read must never kill the capture loop; the next
                // frame tries again. The journal gap is the honest trace.
            }
        }

        if (!sampleSaved) {
            sampleSaved = saveSamplePng(image)
            CaptureStats.samplePngSaved = sampleSaved
        }
    }

    /**
     * Saves the current frame as sample_frame.png, once per session.
     * Written to BOTH internal filesDir and the external app files dir
     * (via EvidenceFiles) so the proof frame can be pulled with `adb pull`
     * during direct testing — internal storage cannot.
     */
    private fun saveSamplePng(image: Image): Boolean {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val paddedWidth = rowStride / pixelStride
            buffer.rewind()
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val cropped = if (paddedWidth == image.width) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            val bytes = java.io.ByteArrayOutputStream().use { baos ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, baos)
                baos.toByteArray()
            }
            if (cropped !== padded) cropped.recycle()
            padded.recycle()
            EvidenceFiles.writeBoth(this, SAMPLE_PNG_NAME, bytes).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun stopCapture() {
        CaptureStats.serviceRunning = false
        displayListener?.let { listener ->
            try {
                getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener)
            } catch (_: Exception) {}
        }
        displayListener = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "id.rancangan.mlai.action.CAPTURE_START"
        const val ACTION_STOP = "id.rancangan.mlai.action.CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val SAMPLE_PNG_NAME = "sample_frame.png"
        const val GAMESTATE_NAME = "gamestate.txt"
        private const val PERCEPTION_INTERVAL_MS = 250L
        private const val JOURNAL_INTERVAL_MS = 1000L
        private const val NOTIFICATION_ID = 1001
        private const val MAX_WIDTH = 960

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
