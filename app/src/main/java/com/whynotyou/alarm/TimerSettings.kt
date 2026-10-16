package com.whynotyou.alarm

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog

object TimerSettings {
    private fun p(c: Context) = c.getSharedPreferences("timer_settings", Context.MODE_PRIVATE)

    /** Presets in minutes. */
    fun presets(c: Context): IntArray = intArrayOf(p(c).getInt("p1", 5), p(c).getInt("p2", 10), p(c).getInt("p3", 25))
    fun setPresets(c: Context, a: Int, b: Int, d: Int) = p(c).edit().putInt("p1", a).putInt("p2", b).putInt("p3", d).apply()
    fun volume(c: Context) = p(c).getInt("volume", 70)
    fun setVolume(c: Context, v: Int) = p(c).edit().putInt("volume", v).apply()
    /** Sound: "chime" for now; room for more. */
    fun sound(c: Context) = p(c).getString("sound", "chime") ?: "chime"

    fun showSheet(activity: Activity, onDone: () -> Unit) {
        val dialog = BottomSheetDialog(activity, R.style.SheetDark)
        val v = LayoutInflater.from(activity).inflate(R.layout.sheet_timer_settings, null)
        Sheet.float(dialog, v)
        val pr = presets(activity)
        val e1 = v.findViewById<EditText>(R.id.p1); val e2 = v.findViewById<EditText>(R.id.p2); val e3 = v.findViewById<EditText>(R.id.p3)
        e1.setText(pr[0].toString()); e2.setText(pr[1].toString()); e3.setText(pr[2].toString())
        val seek = v.findViewById<SeekBar>(R.id.seek_volume); val vol = v.findViewById<TextView>(R.id.txt_volume)
        seek.progress = volume(activity); vol.text = "${volume(activity)}%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, pp: Int, u: Boolean) { vol.text = "${pp.coerceAtLeast(5)}%" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        v.findViewById<TextView>(R.id.txt_sound).text = "Soft chime"
        fun save() {
            setPresets(activity,
                e1.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: pr[0],
                e2.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: pr[1],
                e3.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: pr[2])
            setVolume(activity, seek.progress.coerceAtLeast(5))
        }
        v.findViewById<TextView>(R.id.btn_done).setOnClickListener { save(); dialog.dismiss() }
        dialog.setOnDismissListener { save(); onDone() }
        dialog.show()
    }
}
