package com.whynotyou.alarm

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tabAlarm: View
    private lateinit var tabPhone: View
    private lateinit var title: TextView
    private lateinit var orb: OrbView
    private var phone: PhoneDownPanel? = null
    private var tab = 0
    private lateinit var tabTimer: View
    private lateinit var tabStopwatch: View
    private lateinit var fab: View
    private lateinit var list: LinearLayout
    private lateinit var next: TextView
    private lateinit var warning: TextView
    private lateinit var empty: View

    private var timer: TimerTab? = null
    private var stopwatch: StopwatchTab? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { VoiceWarmup.start(this) } catch (_: Throwable) {}
        try { GuardService.start(this) } catch (_: Throwable) {}
        setContentView(R.layout.activity_main)

        tabAlarm = findViewById(R.id.tab_alarm); tabTimer = findViewById(R.id.tab_timer); tabStopwatch = findViewById(R.id.tab_stopwatch)
        fab = findViewById(R.id.fab_add); list = findViewById(R.id.list_alarms)
        next = findViewById(R.id.txt_next); warning = findViewById(R.id.txt_warning); empty = findViewById(R.id.box_empty)
        tabPhone = findViewById(R.id.tab_phonedown); title = findViewById(R.id.txt_title); orb = findViewById(R.id.home_orb)
        orb.mode = OrbView.Mode.BREATHE
        phone = PhoneDownPanel(this, findViewById(R.id.pd_col))
        findViewById<View>(R.id.btn_add_first).setOnClickListener { startActivity(Intent(this, AlarmDetailActivity::class.java)) }
        findViewById<View>(R.id.btn_stop_snooze).setOnClickListener { AlarmScheduler.stopSnooze(this); refresh() }

        fab.setOnClickListener { startActivity(Intent(this, AlarmDetailActivity::class.java)) }
        findViewById<View>(R.id.btn_gear).setOnClickListener { startActivity(Intent(this, MessagesActivity::class.java)) }
        warning.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        findViewById<View>(R.id.nav_alarm).setOnClickListener { showTab(0) }
        findViewById<View>(R.id.nav_phonedown).setOnClickListener { showTab(3) }
        findViewById<View>(R.id.nav_timer).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.nav_stopwatch).setOnClickListener { showTab(2) }

        timer = TimerTab(this, tabTimer)
        stopwatch = StopwatchTab(this, tabStopwatch)

        if (savedInstanceState == null) {
            if (!Onboarding.done(this)) startActivity(Intent(this, WelcomeActivity::class.java))
            else if (!Permissions.allGranted(this)) startActivity(Intent(this, SetupActivity::class.java))
        }
        showTab(if (intent?.getBooleanExtra(EXTRA_PHONE_TAB, false) == true) 3 else 0)
    }

    private fun showTab(i: Int) {
        tab = i
        tabAlarm.visibility = if (i == 0) View.VISIBLE else View.GONE
        tabPhone.visibility = if (i == 3) View.VISIBLE else View.GONE
        tabTimer.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabStopwatch.visibility = if (i == 2) View.VISIBLE else View.GONE
        fab.visibility = if (i == 0 && AlarmStore.all(this).isNotEmpty()) View.VISIBLE else View.GONE
        val ids = intArrayOf(R.id.nav_alarm, R.id.nav_timer, R.id.nav_stopwatch, R.id.nav_phonedown)
        ids.forEachIndexed { idx, id ->
            val item = findViewById<android.view.ViewGroup>(id)
            (item.getChildAt(0) as ImageView).setColorFilter(if (idx == i) Ui.ACCENT else Ui.CAPTION)
        }
        if (i == 3) phone?.render()
        if (i == 1) timer?.onShow()
        if (i == 2) stopwatch?.onShow()
    }

    override fun onResume() { super.onResume(); CalendarSync.refreshToday(this); syncNow(); refresh(); timer?.onShow(); if (tab == 3) phone?.render() }
    /** Pull what the user's AI changed (if signed in) and redraw when something arrived. */
    private fun syncNow() {
        if (!Account.signedIn(this)) return
        lifecycleScope.launch { if (Sync.run(this@MainActivity) > 0) refresh() }
    }
    override fun onPause() { super.onPause(); stopwatch?.onHide() }

    private fun refresh() {
        val alarms = AlarmStore.all(this)
        empty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
        fab.visibility = if (alarms.isEmpty() || tab != 0) View.GONE else View.VISIBLE

        val snoozed = AlarmScheduler.snoozedUntil(this)
        findViewById<View>(R.id.row_snoozed).visibility = if (snoozed != null) View.VISIBLE else View.GONE
        val n = AlarmStore.next(this)
        val at = listOfNotNull(snoozed, n?.second).minOrNull()
        when {
            alarms.isEmpty() -> { title.text = "No alarms yet"; next.text = "Your alarms show up here" }
            at == null -> { title.text = "No alarm set"; next.text = "Turn one on below" }
            else -> {
                title.text = "Alarm in " + untilText(at)
                next.text = SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(Date(at)).replace("AM", "am").replace("PM", "pm")
            }
        }
        if (snoozed != null) findViewById<TextView>(R.id.txt_snoozed).text = "Rings again at " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(snoozed)).replace("AM", "am").replace("PM", "pm")

        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (a in alarms) {
            val v = inflater.inflate(R.layout.item_alarm, list, false)
            val time = v.findViewById<TextView>(R.id.txt_time)
            val days = v.findViewById<TextView>(R.id.txt_days)
            val sw = v.findViewById<SwitchCompat>(R.id.switch_on)
            val content = v.findViewById<View>(R.id.content)
            val plan = v.findViewById<View>(R.id.row_plan)
            val planText = v.findViewById<TextView>(R.id.txt_plan)

            time.text = formatTime(a.hour, a.minute)
            v.findViewById<TextView>(R.id.txt_label).apply { text = a.label; visibility = if (a.label.isBlank()) View.GONE else View.VISIBLE }
            days.text = daysSpannable(a)
            v.alpha = if (a.enabled) 1f else 0.3f
            sw.isChecked = a.enabled
            sw.setOnCheckedChangeListener { _, on -> AlarmStore.setEnabled(this, a.id, on); refresh() }

            v.findViewById<View>(R.id.row_main).setOnClickListener {
                startActivity(Intent(this, AlarmDetailActivity::class.java).putExtra(AlarmDetailActivity.EXTRA_ID, a.id))
            }
            content.setOnClickListener {
                startActivity(Intent(this, AlarmDetailActivity::class.java).putExtra(AlarmDetailActivity.EXTRA_ID, a.id))
            }
            val askDelete = View.OnLongClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Delete this alarm?")
                    .setPositiveButton("Delete") { _, _ -> AlarmStore.delete(this, a.id); refresh() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            content.setOnLongClickListener(askDelete)
            v.findViewById<View>(R.id.row_main).setOnLongClickListener(askDelete)

            plan.visibility = View.GONE
            list.addView(v)
        }

        val crash = App.lastCrash(this)
        findViewById<TextView>(R.id.txt_crash).apply {
            visibility = if (crash == null) View.GONE else View.VISIBLE
            text = crash?.lines()?.take(14)?.joinToString("\n") ?: ""
            setOnLongClickListener { App.clearCrash(this@MainActivity); visibility = View.GONE; true }
        }

        val missing = Permissions.missingCount(this)
        warning.visibility = if (missing == 0) View.GONE else View.VISIBLE
        warning.text = "$missing permission${if (missing > 1) "s" else ""} missing — fix"
    }

    private fun daysSpannable(a: Alarm): CharSequence {
        val letters = "MTWTFSS"
        val sb = android.text.SpannableStringBuilder()
        for (i in 0 until 7) {
            val start = sb.length
            sb.append(letters[i]); if (i < 6) sb.append(' ')
            val col = if (a.days[i]) 0xFFFFFFFF.toInt() else 0xFF3A4670.toInt()
            sb.setSpan(android.text.style.ForegroundColorSpan(col), start, start + 1, 0)
        }
        return sb
    }

    private fun untilText(t: Long): String {
        val mins = ((t - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0)
        return when {
            mins >= 24 * 60 -> "${mins / (24 * 60)} d"
            mins >= 60 -> "${mins / 60} h"
            else -> "$mins min"
        }
    }

    companion object {
        const val EXTRA_PHONE_TAB = "phone_tab"
        fun formatTime(h: Int, m: Int): CharSequence {
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
            val t = SimpleDateFormat("h:mm", Locale.getDefault()).format(cal.time)
            val ap = SimpleDateFormat("a", Locale.getDefault()).format(cal.time).lowercase()
            val sb = android.text.SpannableStringBuilder("$t $ap")
            sb.setSpan(android.text.style.RelativeSizeSpan(0.38f), t.length + 1, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(0xFF8A93A8.toInt()), t.length + 1, sb.length, 0)
            return sb
        }
    }
}
