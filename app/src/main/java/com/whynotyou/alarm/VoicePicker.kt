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

    private const val SAMPLE = "Good morning. It's Thursday. Standup at ten, lunch at twelve thirty. You've handled harder."

    fun show(a: Activity, current: String, onPick: (String) -> Unit) {
        val dialog = BottomSheetDialog(a, R.style.SheetDark)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0D12.toInt())
            setPadding(dp(a, 28), dp(a, 24), dp(a, 28), dp(a, 36))
        }
        var player: MediaPlayer? = null
        var playingBtn: ImageButton? = null
        fun stop() { player?.release(); player = null; playingBtn?.setImageResource(R.drawable.ic_play); playingBtn = null }

        for (v in VoiceEngine.voices) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(a, 12), 0, dp(a, 12)) }
            val play = ImageButton(a).apply {
                setImageResource(R.drawable.ic_play); setColorFilter(0xFFF2F4F8.toInt())
                setBackgroundResource(R.drawable.bg_circle_outline)
                layoutParams = LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)).apply { marginEnd = dp(a, 14) }
                contentDescription = "Play ${v.name}"
            }
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            col.addView(TextView(a).apply { text = v.name; textSize = 17f; setTextColor(0xFFF2F4F8.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            col.addView(TextView(a).apply { text = "${v.region} · ${v.sex}"; textSize = 13f; setTextColor(0xFF7A8294.toInt()) })
            val tick = TextView(a).apply {
                text = "\u2713"; textSize = 20f; setTextColor(0xFFF5A524.toInt())
                visibility = if (v.id == current) View.VISIBLE else View.INVISIBLE
                setPadding(dp(a, 12), 0, 0, 0)
            }
            play.setOnClickListener {
                if (playingBtn === play) { stop(); return@setOnClickListener }
                stop()
                val f = File(a.cacheDir, "preview_${v.id}.wav")
                play.setImageResource(R.drawable.ic_stop); playingBtn = play
                Thread {
                    if (!f.exists()) VoiceEngine.synthesizeToFile(a, SAMPLE, v.id, f)
                    a.runOnUiThread {
                        if (playingBtn !== play || !f.exists()) { if (!f.exists()) stop(); return@runOnUiThread }
                        player = MediaPlayer().apply { setDataSource(f.absolutePath); setOnCompletionListener { stop() }; prepare(); start() }
                    }
                }.start()
            }
            row.setOnClickListener { stop(); onPick(v.id); dialog.dismiss() }
            col.setOnClickListener { stop(); onPick(v.id); dialog.dismiss() }
            row.addView(play); row.addView(col); row.addView(tick)
            root.addView(row)
            root.addView(View(a).apply { setBackgroundColor(0xFF15181F.toInt()); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) })
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { stop() }
        dialog.show()
    }

    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
