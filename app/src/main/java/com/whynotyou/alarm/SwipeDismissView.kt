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
    private val baseR = 42f * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x14FFFFFF }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x4DFFFFFF }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x0AFFFFFF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x66FFFFFF }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; color = 0xE6F2F4F8.toInt() }

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
        val breathe = (sin(t * 2 * PI / 2.4).toFloat() + 1f) / 2f
        val haloR = baseR + 8 * d + 6 * d * breathe
        val progress = if (threshold() > 0) (drag / threshold()).coerceIn(0f, 1f) else 0f

        // idle halo, then the drag ring that follows the finger
        halo.alpha = (0x0A + 0x18 * progress).toInt()
        c.drawCircle(cx, cy, max(haloR, baseR + drag), halo)
        if (drag > 0f) {
            ring.alpha = (0x40 + 0xBF * progress).toInt()
            c.drawCircle(cx, cy, baseR + drag, ring)
        }
        // glass button
        fill.alpha = (0x14 + 0x24 * progress).toInt()
        c.drawCircle(cx, cy, baseR, fill)
        c.drawCircle(cx, cy, baseR, stroke)
        val s = 9 * d
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
                drag = (dist - baseR).coerceIn(0f, threshold() + 8 * d)
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
