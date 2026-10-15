package com.whynotyou.alarm

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView

/** Timer tab controller. */
class TimerTab(private val a: Activity, root: View) {
    private val pick: View = root.findViewById(R.id.timer_pick)
    private val run: View = root.findViewById(R.id.timer_run)
    private val h: NumberPicker = root.findViewById(R.id.np_h)
    private val m: NumberPicker = root.findViewById(R.id.np_m)
    private val s: NumberPicker = root.findViewById(R.id.np_s)
    private val remaining: TextView = root.findViewById(R.id.timer_remaining)
    private val orb: OrbView = root.findViewById(R.id.timer_orb)
    private val pause: Button = root.findViewById(R.id.timer_pause)
    private val ui = Handler(Looper.getMainLooper())

    init {
        setup(h, 0, 99); setup(m, 0, 59); setup(s, 0, 59)
        val last = TimerState.lastDuration(a) / 1000
        h.value = (last / 3600).toInt(); m.value = ((last % 3600) / 60).toInt(); s.value = (last % 60).toInt()

        root.findViewById<View>(R.id.preset_5).setOnClickListener { set(0, 5, 0) }
        root.findViewById<View>(R.id.preset_10).setOnClickListener { set(0, 10, 0) }
        root.findViewById<View>(R.id.preset_25).setOnClickListener { set(0, 25, 0) }
        root.findViewById<View>(R.id.timer_start).setOnClickListener {
            val ms = ((h.value * 3600L) + (m.value * 60L) + s.value) * 1000L
            if (ms > 0) { TimerService.start(a, ms); onShow() }
        }
        root.findViewById<View>(R.id.timer_cancel).setOnClickListener { TimerService.cancel(a); ui.removeCallbacks(tick); onShow() }
        pause.setOnClickListener {
            if (TimerState.isPaused(a)) TimerService.resume(a) else TimerService.pause(a)
            ui.postDelayed({ render() }, 150)
        }
    }

    private fun setup(np: NumberPicker, lo: Int, hi: Int) {
        np.minValue = lo; np.maxValue = hi; np.wrapSelectorWheel = true
        np.setFormatter { "%02d".format(it) }
        np.descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
    }
    private fun set(hh: Int, mm: Int, ss: Int) { h.value = hh; m.value = mm; s.value = ss }

    fun onShow() {
        val active = TimerState.isActive(a)
        pick.visibility = if (active) View.GONE else View.VISIBLE
        run.visibility = if (active) View.VISIBLE else View.GONE
        if (active) { ui.removeCallbacks(tick); ui.post(tick) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!TimerState.isActive(a)) { onShow(); return }
            render(); ui.postDelayed(this, 250)
        }
    }

    private fun render() {
        val ms = TimerState.remainingMs(a); val sec = (ms + 999) / 1000
        remaining.text = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
        val paused = TimerState.isPaused(a)
        orb.mode = if (paused) OrbView.Mode.STILL else OrbView.Mode.BREATHE
        pause.text = if (paused) "Resume" else "Pause"
    }
}

/** Stopwatch tab controller (in-memory; resets if the app is killed). */
class StopwatchTab(private val a: Activity, root: View) {
    private val time: TextView = root.findViewById(R.id.sw_time)
    private val laps: LinearLayout = root.findViewById(R.id.sw_laps)
    private val start: Button = root.findViewById(R.id.sw_start)
    private val lap: Button = root.findViewById(R.id.sw_lap)
    private val ui = Handler(Looper.getMainLooper())

    private var running = false
    private var base = 0L        // elapsed when last stopped
    private var startedAt = 0L
    private var lapTimes = mutableListOf<Long>()

    init {
        start.setOnClickListener { if (running) stop() else go() }
        lap.setOnClickListener { if (running) addLap() else reset() }
        render()
    }

    private fun elapsed() = base + if (running) System.currentTimeMillis() - startedAt else 0L

    private fun go() { running = true; startedAt = System.currentTimeMillis(); start.text = "Stop"; lap.text = "Lap"; ui.post(tick) }
    private fun stop() { base = elapsed(); running = false; start.text = "Start"; lap.text = "Reset"; ui.removeCallbacks(tick); render() }
    private fun reset() { base = 0; lapTimes.clear(); laps.removeAllViews(); lap.text = "Lap"; render() }

    private fun addLap() {
        val now = elapsed(); val prev = lapTimes.lastOrNull() ?: 0L
        lapTimes.add(now)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(14)) }
        row.addView(TextView(a).apply { text = "Lap ${lapTimes.size}"; setTextColor(0xFF7A8294.toInt()); textSize = 16f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(TextView(a).apply { text = fmt(now - prev); setTextColor(0xFFF2F4F8.toInt()); textSize = 16f; gravity = Gravity.END })
        laps.addView(row, 0)
        laps.addView(View(a).apply { setBackgroundColor(0xFF15181F.toInt()); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) }, 1)
    }

    private val tick = object : Runnable { override fun run() { render(); if (running) ui.postDelayed(this, 33) } }
    private fun render() { time.text = fmt(elapsed()) }
    private fun fmt(ms: Long): String { val cs = (ms / 10) % 100; val s = (ms / 1000) % 60; val m = ms / 60000; return "%02d:%02d.%02d".format(m, s, cs) }

    fun onShow() { if (running) ui.post(tick) }
    fun onHide() { ui.removeCallbacks(tick) }
    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
