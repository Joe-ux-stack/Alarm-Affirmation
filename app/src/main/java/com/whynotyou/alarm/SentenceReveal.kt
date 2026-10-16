package com.whynotyou.alarm

import android.animation.ValueAnimator
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView

/**
 * Shows the brief one sentence at a time; each one fades in when it is spoken.
 */
class SentenceReveal(private val tv: TextView, text: String) {
    private val parts = split(text)
    private val full = parts.joinToString(" ")
    private val starts = IntArray(parts.size)
    private val fractions = FloatArray(parts.size)
    private val alpha = FloatArray(parts.size)
    private var shown = -1

    init {
        var pos = 0
        for ((i, p) in parts.withIndex()) { starts[i] = pos; pos += p.length + 1 }
        // where each sentence starts as a share of the spoken length (+ the pause between sentences)
        val weights = parts.map { it.length + 12f }
        val total = weights.sum()
        var acc = 0f
        for (i in parts.indices) { fractions[i] = acc / total; acc += weights[i] }
        render()
        showIndex(0)
    }

    fun showUpTo(fraction: Float) { var i = shown; while (i + 1 < parts.size && fractions[i + 1] <= fraction) i++; showIndex(i) }
    fun showAll() = showIndex(parts.size - 1)

    fun showIndex(i: Int) {
        if (i <= shown || parts.isEmpty()) return
        for (k in shown + 1..i) {
            val idx = k
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                addUpdateListener { alpha[idx] = it.animatedValue as Float; render() }
                start()
            }
        }
        shown = i
    }

    /** Only sentences already spoken are laid out, so centred lines stay centred. */
    private fun render() {
        if (shown < 0) { tv.text = ""; return }
        val end = starts[shown] + parts[shown].length
        val sp = SpannableString(full.substring(0, end))
        for (i in 0..shown) {
            val a = (alpha[i] * 255).toInt().coerceIn(0, 255)
            val base = if (i == shown) 0xF2F4F8 else 0x9AA3B5
            sp.setSpan(ForegroundColorSpan((a shl 24) or base), starts[i], starts[i] + parts[i].length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sp
    }

    companion object {
        fun split(text: String): List<String> =
            Regex("(?<=[.!?])\\s+").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
