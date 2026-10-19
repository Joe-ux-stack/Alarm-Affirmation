package com.whynotyou.alarm

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Samsung-style dismiss: a glass circle with an X. Touch it and swipe in any direction; a ring
 * follows your finger. Past the threshold it ticks (haptic) and releasing dismisses.
 */
class SwipeDismissView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onDismiss: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val baseR = 44f * d
    // Blue, solid: a filled button with a soft glow, inside a faint ring that breathes.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x402D5BFF; maskFilter = android.graphics.BlurMaskFilter(14f * d, android.graphics.BlurMaskFilter.Blur.NORMAL) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x1A2D5BFF }
    private val haloEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x734A74FF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = 0xFF4A74FF.toInt() }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt() }

    private var drag = 0f          // current ring radius beyond the circle, px
    private var dragging = false
    private var armed = false
    private var t = 0f

    private val idle = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { t += 1f / 60f; invalidate() }
    }

    private fun threshold() = min(width, height) / 2f - baseR - 6 * d

    override fun onAttachedToWindow() { super.onAttachedToWindow(); idle.start() }
    override fun onDetachedFromWindow() { idle.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val maxR = min(width, height) / 2f - 2 * d
        val breathe = (sin(t * 2 * PI / 2.4).toFloat() + 1f) / 2f
        val haloR = min(maxR, 75f * d + 4 * d * breathe)
        val progress = if (threshold() > 0) (drag / threshold()).coerceIn(0f, 1f) else 0f

        // faint breathing ring, then the drag ring that follows the finger (stays inside the view)
        c.drawCircle(cx, cy, max(haloR, baseR + drag), halo)
        if (drag <= 0f) c.drawCircle(cx, cy, haloR, haloEdge)
        else { ring.alpha = (0x80 + 0x7F * progress).toInt(); c.drawCircle(cx, cy, min(maxR, baseR + drag), ring) }
        // glow + solid blue button
        glow.alpha = (0x40 + 0x40 * progress).toInt()
        c.drawCircle(cx, cy + 6 * d, baseR + 8 * d, glow)
        fill.shader = android.graphics.LinearGradient(cx, cy - baseR, cx, cy + baseR, 0xFF4A74FF.toInt(), 0xFF2347F0.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, baseR, fill)
        val s = 10 * d
        c.drawLine(cx - s, cy - s, cx + s, cy + s, xPaint)
        c.drawLine(cx + s, cy - s, cx - s, cy + s, xPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f; val cy = height / 2f
        val dist = hypot(e.x - cx, e.y - cy)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (dist > baseR + 16 * d) return false
                dragging = true; armed = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                drag = (dist - baseR).coerceIn(0f, threshold() + 4 * d)  // ring stays inside the view, never clipped
                val nowArmed = drag >= threshold()
                if (nowArmed && !armed) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                armed = nowArmed
                invalidate(); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (armed && e.actionMasked == MotionEvent.ACTION_UP) { onDismiss?.invoke() }
                else ValueAnimator.ofFloat(drag, 0f).apply {
                    duration = 220; interpolator = DecelerateInterpolator()
                    addUpdateListener { drag = it.animatedValue as Float; invalidate() }
                    start()
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); onDismiss?.invoke(); return true }
}
