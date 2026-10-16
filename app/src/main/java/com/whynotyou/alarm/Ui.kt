package com.whynotyou.alarm

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/** Shared look: colours and small builders so every screen matches the design canvas. */
object Ui {
    const val BG = 0xFF060B18.toInt()
    const val SHEET = 0xFF0D1430.toInt()
    const val SEC = 0xFF1C2541.toInt()
    const val LINE = 0xFF1F2840.toInt()
    const val TEXT = 0xFFF2F4F8.toInt()
    const val GREY = 0xFF8A93A8.toInt()
    const val CAPTION = 0xFF5E6880.toInt()
    const val ACCENT = 0xFF93AEFF.toInt()
    const val BLUE = 0xFF3461FF.toInt()
    const val SOFT = 0x292D5BFF
    const val SOFT_TEXT = 0xFFC9D4FF.toInt()
    const val GREEN = 0xFF6FE3A5.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun button(c: Context, text: String, primary: Boolean = true, height: Int = 56, onClick: (() -> Unit)? = null) =
        MaterialButton(c).apply {
            this.text = text; isAllCaps = false; letterSpacing = 0f; typeface = medium; textSize = 16f
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else SOFT_TEXT)
            backgroundTintList = ColorStateList.valueOf(if (primary) BLUE else SOFT)
            cornerRadius = dp(c, height / 2); insetTop = 0; insetBottom = 0
            if (primary) { elevation = dp(c, 8).toFloat(); if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = 0xFF2D5BFF.toInt(); outlineAmbientShadowColor = 0xFF2D5BFF.toInt() } }
            else stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, height))
            onClick?.let { f -> setOnClickListener { f() } }
        }

    fun text(c: Context, t: CharSequence, size: Float = 16f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun caption(c: Context, t: String, top: Int = 32) = TextView(c).apply {
        text = t.uppercase(); textSize = 12f; setTextColor(CAPTION); letterSpacing = 0.08f
        setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(c, top), 0, dp(c, 10))
    }

    /** A filled box (no stroke) that holds rows. */
    fun box(c: Context, padH: Int = 16, padV: Int = 4) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_box)
        setPadding(dp(c, padH), dp(c, padV), dp(c, padH), dp(c, padV))
    }

    fun divider(c: Context) = View(c).apply {
        setBackgroundColor(LINE); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    /** PRO badge text next to a label. */
    fun proBadge(c: Context, label: String = "PRO") = TextView(c).apply {
        text = label; textSize = 10f; setTextColor(ACCENT); letterSpacing = 0.06f; setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(0xFF26315A.toInt()); cornerRadius = dp(c, 10).toFloat() }
        setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(c, 8) }
    }

    /** A radio dot: accent ring + dot when on, faint ring when off. */
    fun radio(c: Context, on: Boolean) = FrameLayout(c).apply {
        val size = dp(c, 22)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(c, 2), if (on) ACCENT else 0xFF2A3550.toInt()) }
        layoutParams = LinearLayout.LayoutParams(size, size)
        if (on) addView(View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) } },
            FrameLayout.LayoutParams(dp(c, 10), dp(c, 10), Gravity.CENTER))
    }
}

/**
 * Floating bottom sheet: inset 12dp from the edges, rounded on all corners, a handle, a title,
 * the content, and a Done button. Every pop-up in the app goes through here so they all match.
 */
object Sheet {
    fun show(a: Activity, title: String, sub: String? = null, content: View, done: String? = "Done", onDone: (() -> Unit)? = null): BottomSheetDialog {
        val d = BottomSheetDialog(a, R.style.SheetDark)
        val inner = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_sheet)
            setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 20))
        }
        inner.addView(View(a).apply { background = GradientDrawable().apply { setColor(0xFF2A3550.toInt()); cornerRadius = Ui.dp(a, 2).toFloat() } },
            LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        inner.addView(Ui.text(a, title, 20f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(a, 16), 0, 0) })
        if (sub != null) inner.addView(Ui.text(a, sub, 14f, Ui.GREY).apply { setPadding(0, Ui.dp(a, 4), 0, 0) })
        inner.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(a, 8) })
        if (done != null) inner.addView(Ui.button(a, done) { onDone?.invoke(); d.dismiss() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(a, 20)
        })
        val outer = FrameLayout(a).apply { setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 12)); addView(inner) }
        d.setContentView(outer)
        d.setOnShowListener {
            d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                it.setBackgroundColor(0); BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        d.show()
        return d
    }

    /** Put an existing sheet layout into the floating, inset style. */
    fun float(d: BottomSheetDialog, v: View) {
        val c = v.context
        v.setBackgroundResource(R.drawable.bg_sheet)
        val outer = FrameLayout(c).apply { setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 12)); addView(v) }
        d.setContentView(outer)
        d.setOnShowListener {
            d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                it.setBackgroundColor(0); BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    /** A list of choices with radio dots. Tapping picks; Done (or tapping) applies. */
    fun pick(a: Activity, title: String, options: List<Pair<String, String?>>, selected: Int, sub: String? = null, onTap: ((Int) -> Unit)? = null, onPick: (Int) -> Unit) {
        var sel = selected
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun render() {
            list.removeAllViews()
            options.forEachIndexed { i, (label, s2) ->
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dp(a, 56)
                    setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10)); isClickable = true
                    setOnClickListener { sel = i; render(); onTap?.invoke(i) }
                }
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
                col.addView(Ui.text(a, label, 16f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                if (s2 != null) col.addView(Ui.text(a, s2, 14f, Ui.GREY))
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Ui.radio(a, i == sel))
                list.addView(row)
                if (i < options.size - 1) list.addView(Ui.divider(a))
            }
        }
        render()
        show(a, title, sub, list) { onPick(sel) }.setOnDismissListener { Preview.stop() }
    }
}

/** Short previews when you tap a choice: a vibration pattern or a sound plays for a few seconds. */
object Preview {
    private var player: android.media.MediaPlayer? = null
    private var vib: android.os.Vibrator? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val stopper = Runnable { stop() }

    fun pattern(id: String): Pair<LongArray, Int> = when (id) {
        "heartbeat" -> longArrayOf(0, 120, 120, 120, 900) to 0
        "steady" -> longArrayOf(0, 1500, 400) to 0
        "gentle" -> longArrayOf(0, 200, 1400, 300, 1200, 450, 1000, 600, 800) to 7
        else -> longArrayOf(0, 500, 1000) to 0
    }

    fun vibrate(c: Context, id: String, ms: Long = 3500) {
        stop()
        val v = if (android.os.Build.VERSION.SDK_INT >= 31) (c.getSystemService(android.os.VibratorManager::class.java)).defaultVibrator
                else @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        val (wave, rep) = pattern(id)
        try { v.vibrate(android.os.VibrationEffect.createWaveform(wave, rep)) } catch (_: Throwable) {}
        vib = v; ui.postDelayed(stopper, ms)
    }

    fun sound(c: Context, uri: android.net.Uri, ms: Long = 5000) {
        stop()
        try {
            player = android.media.MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(c, uri); setOnPreparedListener { it.start() }; prepareAsync()
            }
        } catch (_: Throwable) { player = null }
        ui.postDelayed(stopper, ms)
    }

    fun stop() {
        ui.removeCallbacks(stopper)
        try { vib?.cancel() } catch (_: Throwable) {}; vib = null
        try { player?.stop() } catch (_: Throwable) {}; player?.release(); player = null
    }
}
