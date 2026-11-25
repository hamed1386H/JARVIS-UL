package com.chtai.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

class JarvisOrbView(context: Context) : View(context) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = 0.45f
    private var rotation = 0f
    private var mode = Mode.IDLE
    private var audioLevel = 0f
    fun setAudioLevel(level: Float) { audioLevel = level.coerceIn(0f, 1f); invalidate() }
    private var animator: ValueAnimator? = null

    fun setMode(next: Mode) { mode = next; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; rotation = pulse * 360f; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.315f
        if (r <= 0f) return
        val accent = when (mode) {
            Mode.LISTENING -> Color.rgb(50, 220, 255)
            Mode.THINKING -> Color.rgb(180, 92, 255)
            Mode.SPEAKING -> Color.rgb(70, 255, 218)
            Mode.IDLE -> Color.rgb(90, 120, 255)
        }
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx, cy, r * 2.1f,
            intArrayOf(Color.argb(95, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(24, 45, 65, 180), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * (1.95f + pulse * 0.12f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.color = Color.argb(180, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawCircle(cx, cy, r * (1.12f + pulse * 0.025f + audioLevel * 0.18f), paint)
        paint.strokeWidth = 1f
        paint.color = Color.argb(75, 100, 130, 255)
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        canvas.drawOval(RectF(cx-r*1.34f, cy-r*0.52f, cx+r*1.34f, cy+r*0.52f), paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(cx-r*0.28f, cy-r*0.34f, r*1.42f,
            intArrayOf(Color.rgb(205, 170, 255), Color.rgb(108, 70, 220), Color.rgb(28, 42, 112), Color.rgb(5, 9, 25)),
            floatArrayOf(0f, 0.34f, 0.76f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r*(0.96f + pulse*0.018f + audioLevel * 0.035f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = Color.argb(95, 190, 180, 255)
        canvas.drawCircle(cx, cy, r*0.98f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 100, 205, 255)
        canvas.drawCircle(cx+r*0.95f, cy-r*0.28f, 3.5f, paint)
    }
}