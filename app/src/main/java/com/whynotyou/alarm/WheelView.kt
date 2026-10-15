package com.whynotyou.alarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Minimal vertical wheel: selected row full white, neighbours at flat low alpha, no dividers.
 * Three rows visible. Wraps. Snaps to nearest on release.
 */
class WheelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var min = 0
    var max = 59
    var labels: List<String>? = null           // overrides numeric formatting when set
    var wrap = true                            // false: clamp to [min,max], no neighbours beyond the ends
    var onChange: ((Int) -> Unit)? = null

    private val range get() = max - min + 1
    private var rowH = 0f
    private var offset = 0f                     // in rows; value = round(offset)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = 0xFFF2F4F8.toInt()
    }
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var lastY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var value: Int
        get() = if (wrap) min + Math.floorMod(offset.roundToInt(), range) else min + offset.roundToInt().coerceIn(0, range - 1)
        set(v) { offset = (v - min).toFloat(); invalidate() }

    private fun clampOffset() { if (!wrap) offset = offset.coerceIn(0f, (range - 1).toFloat()) }

    fun setRange(lo: Int, hi: Int) { min = lo; max = hi; offset = offset.coerceIn(0f, (range - 1).toFloat()); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rowH = h / 3f
        paint.textSize = rowH * 0.62f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val center = height / 2f
        val base = offset.roundToInt()
        val frac = offset - base
        for (i in -2..2) {
            val idx = base + i
            val y = center - (i - frac) * rowH
            if (y < -rowH || y > height + rowH) continue
            if (!wrap && (idx < 0 || idx >= range)) continue
            val dist = abs(i - frac)
            paint.alpha = when {
                dist < 0.5f -> 255
                dist < 1.5f -> 56
                else -> 0
            }
            if (paint.alpha == 0) continue
            val v = min + Math.floorMod(idx, range)
            val text = labels?.getOrNull(v - min) ?: "%02d".format(v)
            val ty = y - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, cx, ty, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true); lastY = e.y; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - lastY
                if (!dragging && abs(dy) > touchSlop) dragging = true
                if (dragging) { offset += dy / rowH; clampOffset(); lastY = e.y; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                velocity?.recycle(); velocity = null
                if (abs(vy) > 400) fling(vy / rowH) else snap()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private var flingStart = 0f
    private fun fling(rowsPerSec: Float) {
        flingStart = offset
        scroller.fling(0, 0, 0, (rowsPerSec * 1000).toInt(), 0, 0, -100_000, 100_000)
        postOnAnimation(flingTick)
    }
    private val flingTick = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                offset = flingStart + scroller.currY / 1000f
                clampOffset()
                invalidate(); postOnAnimation(this)
            } else snap()
        }
    }

    private fun snap() {
        clampOffset()
        val target = offset.roundToInt().toFloat()
        val start = offset
        val t0 = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val p = ((System.currentTimeMillis() - t0) / 120f).coerceAtMost(1f)
                offset = start + (target - start) * p
                invalidate()
                if (p < 1f) postOnAnimation(this) else onChange?.invoke(value)
            }
        }
        postOnAnimation(r)
    }
}
