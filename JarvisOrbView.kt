package com.chtai.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.min

/** Lightweight neon orb. The view animates only while attached to the window. */
class JarvisOrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = 0.5f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0.32f, 0.82f).apply {
            duration = 2600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
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
        val radius = min(width, height) * 0.29f
        if (radius <= 0f) return

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, radius * (1.85f + pulse * 0.22f),
            intArrayOf(
                Color.argb((55 + pulse * 35).toInt(), 80, 35, 190),
                Color.argb(35, 20, 95, 220),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.9f, paint)

        paint.shader = RadialGradient(
            cx - radius * 0.25f, cy - radius * 0.28f, radius * 1.35f,
            intArrayOf(
                Color.rgb(185, 135, 255),
                Color.rgb(82, 54, 190),
                Color.rgb(12, 28, 76),
                Color.rgb(5, 7, 18)
            ),
            floatArrayOf(0f, 0.34f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * (0.88f + pulse * 0.025f), paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.color = Color.argb((110 + pulse * 90).toInt(), 120, 90, 255)
        canvas.drawCircle(cx, cy, radius * 0.96f, paint)
        paint.style = Paint.Style.FILL
    }
}
