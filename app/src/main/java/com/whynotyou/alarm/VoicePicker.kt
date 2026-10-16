package com.whynotyou.alarm

import android.app.Activity
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File

/** Sheet listing the bundled voices with a play preview. Previews render once and are cached. */
object VoicePicker {

    private const val SAMPLE = VoiceWarmup.SAMPLE

    fun show(a: Activity, current: String, onPick: (String) -> Unit) {
        var sel = current
        var player: MediaPlayer? = null
        var speaker: BriefSpeaker? = null
        var playingBtn: ImageButton? = null
        fun stop() {
            player?.release(); player = null
            speaker?.release(); speaker = null
            playingBtn?.setImageResource(R.drawable.ic_play); playingBtn = null
        }

        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val radios = HashMap<String, android.widget.FrameLayout>()
        fun renderRadios() = radios.forEach { (id, holder) -> holder.removeAllViews(); holder.addView(Ui.radio(a, id == sel)) }

        for ((group, region) in listOf("American" to "US", "British" to "UK")) {
            list.addView(Ui.caption(a, group, if (region == "US") 8 else 24))
            val vs = VoiceEngine.voices.filter { it.region == region }.sortedWith(compareBy({ it.sex }, { it.name }))
            vs.forEachIndexed { i, v ->
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(a, 56); setPadding(0, dp(a, 8), 0, dp(a, 8)) }
                val play = ImageButton(a).apply {
                    setImageResource(R.drawable.ic_play); setColorFilter(Ui.TEXT)
                    setBackgroundResource(R.drawable.bg_circle_secondary); setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12)); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)).apply { marginEnd = dp(a, 14) }
                    contentDescription = "Play ${v.name}"
                }
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
                col.addView(TextView(a).apply { text = v.name; textSize = 16f; setTextColor(Ui.TEXT); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
                col.addView(TextView(a).apply { text = "${v.region} · ${if (v.sex == "F") "female" else "male"}"; textSize = 14f; setTextColor(Ui.GREY) })
                val holder = android.widget.FrameLayout(a); radios[v.id] = holder
                play.setOnClickListener {
                    if (playingBtn === play) { stop(); return@setOnClickListener }
                    stop()
                    val f = VoiceWarmup.previewFile(a, v.id)
                    play.setImageResource(R.drawable.ic_stop); playingBtn = play
                    if (!f.exists() || f.length() < 1000) {
                        speaker = BriefSpeaker(a) { a.runOnUiThread { if (playingBtn === play) stop() } }.also { it.speak(SAMPLE) }
                        return@setOnClickListener
                    }
                    player = MediaPlayer().apply { setDataSource(f.absolutePath); setOnCompletionListener { stop() }; prepare(); start() }
                }
                row.setOnClickListener { sel = v.id; renderRadios() }
                row.addView(play); row.addView(col); row.addView(holder)
                list.addView(row)
                if (i < vs.size - 1) list.addView(Ui.divider(a))
            }
        }
        renderRadios()
        if (!VoiceEngine.isAvailable(a)) list.addView(TextView(a).apply {
            text = if (ModelStore.isDownloading()) "Downloading voices… ${ModelStore.progress}%" else "Voices aren't downloaded yet. Until they are, the alarm uses the phone's voice. Download in Settings → Voices."
            textSize = 13f; setTextColor(Ui.GREY); setPadding(0, dp(a, 16), 0, 0)
        })
        // Long list: scroll inside the sheet, at most ~60% of the screen.
        val scroll = object : android.widget.ScrollView(a) {
            override fun onMeasure(w: Int, h: Int) {
                val max = (a.resources.displayMetrics.heightPixels * 0.6f).toInt()
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }.apply { addView(list); isVerticalScrollBarEnabled = false }
        Sheet.show(a, "Voice", null, scroll) { stop(); onPick(sel) }.setOnDismissListener { stop() }
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
