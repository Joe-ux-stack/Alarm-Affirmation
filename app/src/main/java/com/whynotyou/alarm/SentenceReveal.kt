package com.whynotyou.alarm

import android.animation.ValueAnimator
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * Shows the brief one sentence at a time ("C" in the text-reveal mockup): only the sentence being
 * spoken is on screen, its words light up as they are said, and when the next sentence starts the
 * old one floats up and fades while the new one rises in.
 */
class SentenceReveal(private val tv: TextView, text: String) {
    private val parts = split(text)
    private val fractions = FloatArray(parts.size)   // where each sentence starts, as a share of the audio
    private val ends = FloatArray(parts.size)
    private var shown = -1
    private var lit = 0f                              // 0..1 progress through the current sentence's words
    private var wordAnim: ValueAnimator? = null
    private val density = tv.resources.displayMetrics.density

    init {
        // weights = spoken length + the pause between sentences (same estimate the audio timing uses)
        val weights = parts.map { it.length + 12f }
        val total = weights.sum()
        var acc = 0f
        for (i in parts.indices) { fractions[i] = acc / total; acc += weights[i]; ends[i] = (acc - 12f) / total }
        tv.textSize = 28f
        tv.minLines = 3
        tv.text = ""
        showIndex(0)
    }

    /** Audio playback position (0..1): picks the sentence and how many of its words are lit. */
    fun showUpTo(fraction: Float) {
        var i = shown; while (i + 1 < parts.size && fractions[i + 1] <= fraction) i++
        showIndex(i)
        if (shown in parts.indices) {
            val span = (ends[shown] - fractions[shown]).coerceAtLeast(0.0001f)
            val p = ((fraction - fractions[shown]) / span).coerceIn(0f, 1f)
            if (p > lit) { wordAnim?.cancel(); lit = p; render() }
        }
    }

    fun showAll() {
        showIndex(parts.size - 1)
        wordAnim?.cancel(); lit = 1f; render()
    }

    fun showIndex(i: Int) {
        if (i <= shown || i !in parts.indices) return
        val first = shown < 0
        shown = i
        lit = 0f
        wordAnim?.cancel()
        val swapIn = {
            render()
            tv.translationY = 18 * density; tv.alpha = 0f
            tv.animate().translationY(0f).alpha(1f).setDuration(450).setInterpolator(DecelerateInterpolator()).start()
            // Without exact word timing (the phone's own voice), light the words at speaking pace.
            wordAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (parts[i].length * 62L).coerceIn(700L, 6000L)
                addUpdateListener { val v = it.animatedValue as Float; if (v > lit) { lit = v; render() } }
                start()
            }
        }
        if (first) swapIn()
        else tv.animate().translationY(-22 * density).alpha(0f).setDuration(300).withEndAction(swapIn).start()
    }

    /** The current sentence; words already spoken in white, the rest in grey. */
    private fun render() {
        if (shown !in parts.indices) { tv.text = ""; return }
        val s = parts[shown]
        val sp = SpannableString(s)
        val cut = (s.length * lit).toInt().let { c -> if (c >= s.length) s.length else s.lastIndexOf(' ', c).let { if (it < 0) 0 else it } }
        if (cut > 0) sp.setSpan(ForegroundColorSpan(0xFFF2F4F8.toInt()), 0, cut, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (cut < s.length) sp.setSpan(ForegroundColorSpan(0xFF9AA3B5.toInt()), cut, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = sp
    }

    companion object {
        fun split(text: String): List<String> =
            Regex("(?<=[.!?])\\s+").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
