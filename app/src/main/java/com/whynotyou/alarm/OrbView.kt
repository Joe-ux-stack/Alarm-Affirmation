package com.whynotyou.alarm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Concentric amber circles. BREATHE: slow sine. SPEAK: driven by [level] (0..1) with smoothing.
 * STILL: a small dot.
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { BREATHE, SPEAK, STILL }

    var mode: Mode = Mode.BREATHE
        set(v) { field = v; if (v == Mode.STILL) targetLevel = 0f }

    /** External audio level 0..1 (only used in SPEAK). */
    fun setLevel(l: Float) { targetLevel = l.coerceIn(0f, 1f) }

    private var targetLevel = 0f
    private var level = 0f
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5A524.toInt() }

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            phase += 0.016f
            // smooth toward target: fast attack, slow release
            level += (targetLevel - level) * (if (targetLevel > level) 0.35f else 0.08f)
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ticker.start() }
    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val rMax = min(width, height) / 2f

        val t = when (mode) {
            Mode.BREATHE -> 0.55f + 0.45f * ((sin(phase * 2 * PI / 4.0).toFloat() + 1f) / 2f) // 4 s cycle
            Mode.SPEAK -> 0.35f + 0.65f * level
            Mode.STILL -> 0.15f
        }

        // four halos + core
        val rings = floatArrayOf(1.00f, 0.72f, 0.48f, 0.30f)
        val alphas = intArrayOf(14, 30, 56, 115)
        for (i in rings.indices) {
            paint.alpha = alphas[i]
            canvas.drawCircle(cx, cy, rMax * rings[i] * t, paint)
        }
        paint.alpha = 255
        canvas.drawCircle(cx, cy, rMax * 0.16f * (0.7f + 0.3f * t), paint)
    }
}
