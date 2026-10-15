package com.whynotyou.alarm

import android.content.Intent
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
    private lateinit var tabTimer: View
    private lateinit var tabStopwatch: View
    private lateinit var fab: View
    private lateinit var list: LinearLayout
    private lateinit var next: TextView
    private lateinit var warning: TextView
    private lateinit var empty: TextView

    private var timer: TimerTab? = null
    private var stopwatch: StopwatchTab? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabAlarm = findViewById(R.id.tab_alarm); tabTimer = findViewById(R.id.tab_timer); tabStopwatch = findViewById(R.id.tab_stopwatch)
        fab = findViewById(R.id.fab_add); list = findViewById(R.id.list_alarms)
        next = findViewById(R.id.txt_next); warning = findViewById(R.id.txt_warning); empty = findViewById(R.id.txt_empty)

        fab.setOnClickListener { startActivity(Intent(this, AlarmDetailActivity::class.java)) }
        findViewById<View>(R.id.btn_gear).setOnClickListener { startActivity(Intent(this, MessagesActivity::class.java)) }
        warning.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        findViewById<View>(R.id.nav_alarm).setOnClickListener { showTab(0) }
        findViewById<View>(R.id.nav_timer).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.nav_stopwatch).setOnClickListener { showTab(2) }

        timer = TimerTab(this, tabTimer)
        stopwatch = StopwatchTab(this, tabStopwatch)

        if (savedInstanceState == null && !Permissions.allGranted(this)) startActivity(Intent(this, SetupActivity::class.java))
        showTab(0)
    }

    private fun showTab(i: Int) {
        tabAlarm.visibility = if (i == 0) View.VISIBLE else View.GONE
        tabTimer.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabStopwatch.visibility = if (i == 2) View.VISIBLE else View.GONE
        fab.visibility = if (i == 0) View.VISIBLE else View.GONE
        val ids = intArrayOf(R.id.nav_alarm, R.id.nav_timer, R.id.nav_stopwatch)
        ids.forEachIndexed { idx, id ->
            val item = findViewById<LinearLayout>(id)
            val col = if (idx == i) 0xFFF2F4F8.toInt() else 0xFF555B68.toInt()
            (item.getChildAt(0) as ImageView).setColorFilter(col)
            (item.getChildAt(1) as TextView).setTextColor(col)
        }
        if (i == 1) timer?.onShow()
        if (i == 2) stopwatch?.onShow()
    }

    override fun onResume() { super.onResume(); CalendarSync.refreshToday(this); refresh(); timer?.onShow() }
    override fun onPause() { super.onPause(); stopwatch?.onHide() }

    private fun refresh() {
        val alarms = AlarmStore.all(this)
        empty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE

        val n = AlarmStore.next(this)
        next.text = if (n == null) "" else {
            val at = n.second
            SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(at)).lowercase().replaceFirstChar { it.uppercase() } + " · " + untilText(at)
        }

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
            days.text = daysSpannable(a)
            content.alpha = if (a.enabled) 1f else 0.45f
            sw.isChecked = a.enabled
            sw.setOnCheckedChangeListener { _, on -> AlarmStore.setEnabled(this, a.id, on); refresh() }

            v.findViewById<View>(R.id.row_main).setOnClickListener {
                startActivity(Intent(this, AlarmDetailActivity::class.java).putExtra(AlarmDetailActivity.EXTRA_ID, a.id))
            }
            content.setOnClickListener {
                startActivity(Intent(this, AlarmDetailActivity::class.java).putExtra(AlarmDetailActivity.EXTRA_ID, a.id))
            }

            if (a.brief && a.enabled) {
                plan.visibility = View.VISIBLE
                val intention = Brief.intention(this)
                planText.text = if (intention.isBlank()) "Plan tomorrow" else intention
                planText.setTextColor(if (intention.isBlank()) 0xFF7A8294.toInt() else 0xFFF2F4F8.toInt())
                plan.setOnClickListener { PlanSheet.show(this) { refresh() } }
            }
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
            val col = if (a.days[i]) 0xFFF2F4F8.toInt() else 0xFF3A3F4B.toInt()
            sb.setSpan(android.text.style.ForegroundColorSpan(col), start, start + 1, 0)
        }
        return sb
    }

    private fun untilText(t: Long): String {
        val mins = ((t - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        return if (mins >= 60) "in ${mins / 60}h ${mins % 60}m" else "in ${mins}m"
    }

    companion object {
        fun formatTime(h: Int, m: Int): CharSequence {
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
            val t = SimpleDateFormat("h:mm", Locale.getDefault()).format(cal.time)
            val ap = SimpleDateFormat("a", Locale.getDefault()).format(cal.time).lowercase()
            val sb = android.text.SpannableStringBuilder("$t $ap")
            sb.setSpan(android.text.style.RelativeSizeSpan(0.38f), t.length + 1, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(0xFF7A8294.toInt()), t.length + 1, sb.length, 0)
            return sb
        }
    }
}
