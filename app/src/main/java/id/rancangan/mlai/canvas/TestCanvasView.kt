package id.rancangan.mlai.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import id.rancangan.mlai.touch.ScriptedTouchTest

/**
 * Our own test target: a fake game pad drawn on screen.
 *
 * Layout matches the fractions in [ScriptedTouchTest]: joystick bottom-left,
 * three skill circles bottom-right. Every touch — human finger or injected
 * accessibility gesture — lands in onTouchEvent and is written to [TouchLog]
 * with pointer count, action, and per-pointer coordinates, and active touch
 * points are drawn as red dots so a screenshot alone shows what happened.
 */
class TestCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 255, 255, 255)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }

    // Currently-down touch points, drawn until they lift.
    private val activePoints = mutableMapOf<Int, Pair<Float, Float>>()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.DKGRAY)
        val w = width.toFloat()
        val h = height.toFloat()

        // Joystick
        val jx = ScriptedTouchTest.JOYSTICK_X * w
        val jy = ScriptedTouchTest.JOYSTICK_Y * h
        canvas.drawCircle(jx, jy, 130f, fillPaint)
        canvas.drawCircle(jx, jy, 130f, basePaint)
        canvas.drawCircle(jx, jy, 55f, basePaint)
        canvas.drawText("JOYSTICK", jx, jy - 160f, textPaint)

        // Skills — S1 position matches ScriptedTouchTest; S2/S3 flank it.
        drawSkill(canvas, ScriptedTouchTest.SKILL1_X * w, ScriptedTouchTest.SKILL1_Y * h, "S1")
        drawSkill(canvas, 0.90f * w, 0.63f * h, "S2")
        drawSkill(canvas, 0.68f * w, 0.63f * h, "S3")

        canvas.drawText("KANVAS UJI — sentuh atau jalankan Uji Sentuh", w / 2, 70f, textPaint)

        for ((_, p) in activePoints) {
            canvas.drawCircle(p.first, p.second, 26f, touchPaint)
        }
    }

    private fun drawSkill(canvas: Canvas, x: Float, y: Float, label: String) {
        canvas.drawCircle(x, y, 75f, fillPaint)
        canvas.drawCircle(x, y, 75f, basePaint)
        canvas.drawText(label, x, y + 14f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionName = MotionEvent.actionToString(event.actionMasked)
        val sb = StringBuilder()
        sb.append("t=").append(event.eventTime)
            .append(' ').append(actionName)
            .append(" ptr=").append(event.pointerCount)
        for (i in 0 until event.pointerCount) {
            sb.append(" #").append(event.getPointerId(i))
                .append("=(").append(event.getX(i).toInt())
                .append(',').append(event.getY(i).toInt()).append(')')
        }
        TouchLog.record(sb.toString())
        TouchLog.notePointerCount(event.pointerCount)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    activePoints[event.getPointerId(i)] = event.getX(i) to event.getY(i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                activePoints.remove(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> activePoints.clear()
        }
        invalidate()
        return true
    }
}
