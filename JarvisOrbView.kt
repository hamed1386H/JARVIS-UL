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
    private var animator: ValueAnimator? = null

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun setMode(next: Mode) {
        mode = next
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                rotation = pulse * 360f
                invalidate()
            }
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
        val r = min(width, height) * 0.285f
        if (r <= 0f) return

        val accent = when (mode) {
            Mode.LISTENING -> Color.rgb(65, 190, 255)
            Mode.THINKING -> Color.rgb(177, 105, 255)
            Mode.SPEAKING -> Color.rgb(105, 135, 255)
            Mode.IDLE -> Color.rgb(145, 105, 255)
        }
        val energy = (audioLevel * 0.7f) + if (mode == Mode.THINKING) 0.16f else 0f
        val breathing = 0.96f + pulse * 0.035f + energy * 0.08f

        // Soft ambient halo.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, r * 2.15f,
            intArrayOf(
                Color.argb(100, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.argb(36, 37, 84, 170),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 2.1f, paint)
        paint.shader = null

        // Outer instrument rings.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f
        paint.color = Color.argb(205, Color.red(accent), Color.green(accent), Color.blue(accent))
        canvas.drawCircle(cx, cy, r * (1.24f + energy * 0.08f), paint)
        paint.strokeWidth = 1f
        paint.color = Color.argb(95, 128, 177, 255)
        canvas.drawCircle(cx, cy, r * 1.13f, paint)

        // Rotating segmented orbit.
        canvas.save()
        canvas.rotate(-rotation, cx, cy)
        paint.strokeWidth = 2.6f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(225, 130, 112, 255)
        val oval = RectF(cx - r * 1.39f, cy - r * 1.39f, cx + r * 1.39f, cy + r * 1.39f)
        canvas.drawArc(oval, 208f, 72f, false, paint)
        paint.color = Color.argb(180, 70, 190, 255)
        canvas.drawArc(oval, 18f, 42f, false, paint)
        paint.strokeCap = Paint.Cap.BUTT
        canvas.restore()

        // Rotating orbital ellipse and satellites.
        canvas.save()
        canvas.rotate(rotation * 0.42f, cx, cy)
        paint.strokeWidth = 1f
        paint.color = Color.argb(85, 111, 174, 255)
        canvas.drawOval(RectF(cx - r * 1.52f, cy - r * 0.52f, cx + r * 1.52f, cy + r * 0.52f), paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(117, 239, 255)
        canvas.drawCircle(cx + r * 1.48f, cy, 3.5f + energy * 2f, paint)
        paint.color = Color.rgb(180, 153, 255)
        canvas.drawCircle(cx - r * 1.48f, cy, 2.6f, paint)
        canvas.restore()

        // Glass core.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.32f, cy - r * 0.38f, r * 1.48f,
            intArrayOf(
                Color.rgb(190, 165, 255),
                Color.rgb(105, 76, 190),
                Color.rgb(38, 27, 91),
                Color.rgb(5, 7, 24)
            ),
            floatArrayOf(0f, 0.34f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * breathing, paint)
        paint.shader = null

        // Fine inner rim.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = Color.argb(155, 180, 225, 255)
        canvas.drawCircle(cx, cy, r * breathing, paint)

        // Central luminous iris.
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, r * 0.52f,
            intArrayOf(Color.argb(230, 235, 220, 255), Color.argb(190, 145, 100, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.22f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 0.52f, paint)
        paint.shader = null

        // Voice-reactive equalizer ticks around the core.
        val bars = 48
        for (i in 0 until bars) {
            val angle = (i * 360f / bars) + rotation * 0.16f
            val radians = Math.toRadians(angle.toDouble())
            val modulation = if (mode == Mode.LISTENING || mode == Mode.SPEAKING) audioLevel else 0.12f
            val length = 3f + modulation * (5f + ((i * 7) % 5))
            val inner = r * 0.78f
            val outer = inner + length
            val x1 = cx + kotlin.math.cos(radians).toFloat() * inner
            val y1 = cy + kotlin.math.sin(radians).toFloat() * inner
            val x2 = cx + kotlin.math.cos(radians).toFloat() * outer
            val y2 = cy + kotlin.math.sin(radians).toFloat() * outer
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (i % 4 == 0) 2.2f else 1.1f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.argb(if (i % 3 == 0) 190 else 95, Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
    }
}
