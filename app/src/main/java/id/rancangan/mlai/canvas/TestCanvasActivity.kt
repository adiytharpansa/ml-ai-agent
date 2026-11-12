package id.rancangan.mlai.canvas

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import id.rancangan.mlai.R

/**
 * Hosts [TestCanvasView] full-screen. Keeps a static [instance] so the
 * self-test can close the canvas after measuring, and mirrors [TouchLog]
 * into the on-screen log panel.
 */
class TestCanvasActivity : Activity() {

    private lateinit var tvTouchLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_canvas)
        instance = this
        tvTouchLog = findViewById(R.id.tvTouchLog)
        TouchLog.listener = { runOnUiThread { refreshLog() } }
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        val view = findViewById<android.view.View>(R.id.canvasView)
        view.post {
            viewWidth = view.width
            viewHeight = view.height
        }
    }

    override fun onPause() {
        inForeground = false
        super.onPause()
    }

    private fun refreshLog() {
        val lines = TouchLog.recentLines(12)
        tvTouchLog.text = if (lines.isEmpty()) "Log sentuh kosong — belum ada sentuhan."
        else lines.joinToString("\n")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        TouchLog.listener = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: TestCanvasActivity? = null

        /** True only while the canvas is the resumed, foreground activity. */
        @Volatile
        var inForeground: Boolean = false

        /** The canvas view's real pixel size, captured after layout. */
        @Volatile
        var viewWidth: Int = 0

        @Volatile
        var viewHeight: Int = 0
    }
}
