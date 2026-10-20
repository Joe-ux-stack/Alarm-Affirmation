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
        onBackPressedDispatcher.addCallback(this, backInSelect)
        try { VoiceWarmup.start(this) } catch (_: Throwable) {}
        // Phone down is off for v1 (kept in the code): stop the watcher if an older version started it.
        try { stopService(android.content.Intent(this, GuardService::class.java)); PhoneDown.setEnabled(this, false) } catch (_: Throwable) {}
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
        findViewById<View>(R.id.nav_phonedown).visibility = View.GONE
        findViewById<View>(R.id.nav_timer).setOnClickListener { showTab(1) }
        findViewById<View>(R.id.nav_stopwatch).setOnClickListener { showTab(2) }

        timer = TimerTab(this, tabTimer)
        stopwatch = StopwatchTab(this, tabStopwatch)

        if (savedInstanceState == null) {
            if (!Onboarding.done(this)) startActivity(Intent(this, WelcomeActivity::class.java))
            else if (!Permissions.allGranted(this)) startActivity(Intent(this, SetupActivity::class.java))
        }
        showTab(0)
    }

    private fun showTab(i: Int) {
        if (selecting && i != 0) { selecting = false; selected.clear(); backInSelect.isEnabled = false; selectBar?.visibility = View.GONE; findViewById<View>(R.id.nav).visibility = View.VISIBLE }
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

    override fun onResume() { super.onResume(); CalendarSync.refreshToday(this); syncNow(); Feedback.maybeAsk(this); title.removeCallbacks(headerTick); title.postDelayed(headerTick, 15_000L); refresh(); timer?.onShow(); if (tab == 3) phone?.render() }
    /** Pull what the user's AI changed (if signed in) and redraw when something arrived. */
    private fun syncNow() {
        if (!Account.signedIn(this)) return
        lifecycleScope.launch { if (Sync.run(this@MainActivity) > 0) refresh() }
    }
    override fun onPause() { super.onPause(); stopwatch?.onHide(); title.removeCallbacks(headerTick) }

    private fun refresh() {
        val alarms = AlarmStore.all(this)
        empty.visibility = if (alarms.isEmpty()) View.VISIBLE else View.GONE
        fab.visibility = if (alarms.isEmpty() || tab != 0 || selecting) View.GONE else View.VISIBLE
        findViewById<View>(R.id.nav).visibility = if (selecting) View.INVISIBLE else View.VISIBLE
        selectBar?.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting && ::selectDelete.isInitialized) selectDelete.text = "Delete ${selected.size}"

        val snoozed = AlarmScheduler.snoozedUntil(this)
        findViewById<View>(R.id.row_snoozed).visibility = if (snoozed != null) View.VISIBLE else View.GONE
        val n = AlarmStore.next(this)
        val at = listOfNotNull(snoozed, n?.second).minOrNull()
        headerAt = at
        when {
            alarms.isEmpty() -> { title.text = "No alarms yet"; next.text = "Your alarms show up here" }
            at == null -> { title.text = "No alarm set"; next.text = "Turn one on below" }
            else -> {
                title.text = "Alarm in " + untilText(at)
                next.text = SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(Date(at)).replace("AM", "am").replace("PM", "pm")
            }
        }
        if (selecting) { title.text = "${selected.size} selected"; next.text = "Tap alarms to select" }
        if (snoozed != null) findViewById<TextView>(R.id.txt_snoozed).text = "Rings again at " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(snoozed)).replace("AM", "am").replace("PM", "pm")

        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val nextId = AlarmStore.next(this)?.first?.id
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
            days.text = daysSpannable(a); days.letterSpacing = if (a.isOnce) 0f else 0.15f
            v.alpha = if (a.enabled) 1f else 0.3f
            sw.isChecked = a.enabled
            sw.setOnCheckedChangeListener { _, on -> AlarmStore.setEnabled(this, a.id, on); refresh() }

            v.findViewById<View>(R.id.row_main).setOnClickListener {
                startActivity(Intent(this, AlarmDetailActivity::class.java).putExtra(AlarmDetailActivity.EXTRA_ID, a.id))
            }
            content.setOnClickListener {
                startActivity(Intent(this, AlarmDetailActivity::class.java).putExtra(AlarmDetailActivity.EXTRA_ID, a.id))
            }
            // Long press starts selecting (to delete several at once).
            val startSelect = View.OnLongClickListener { it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); enterSelect(a.id); true }
            content.setOnLongClickListener(startSelect)
            v.findViewById<View>(R.id.row_main).setOnLongClickListener(startSelect)
            if (selecting) {
                val on = a.id in selected
                sw.visibility = View.GONE
                v.findViewById<android.widget.ImageView>(R.id.sel_check).apply { visibility = View.VISIBLE; setImageResource(if (on) R.drawable.ic_check_on else R.drawable.ic_check_off) }
                v.alpha = if (on) 1f else 0.55f
                val toggle = View.OnClickListener { if (!selected.remove(a.id)) selected.add(a.id); if (selected.isEmpty()) exitSelect() else refresh() }
                v.findViewById<View>(R.id.row_main).setOnClickListener(toggle); content.setOnClickListener(toggle); plan.setOnClickListener(toggle)
                v.setOnClickListener(toggle)
            }

            // The plan (yours or your AI's) sits on the card of the alarm that will read it out next.
            // Each alarm shows its own message; the next alarm without one shows the evening plan it will read.
            val ring = a.nextTrigger()
            val dayMsg = ring?.let { a.dayNotes[Brief.isoDay(it)] }.orEmpty()
            val dated = ring?.let { Brief.datePlan(this, it) }.orEmpty()
            val intent = dated.ifBlank { if (a.id == nextId) Brief.intention(this) else "" }
            val fq = Followup.question(a)
            val noteText = listOf(dayMsg.ifBlank { a.note }, if (fq.isNotBlank()) "Then asks: $fq · Yes / No" else "").filter { it.isNotBlank() }.joinToString("\n")
            val own = noteText.isNotBlank()
            val showPlan = own || intent.isNotBlank()
            plan.visibility = if (showPlan) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.plan_line).visibility = plan.visibility
            if (showPlan) {
                planText.text = if (own) noteText else intent
                v.findViewById<TextView>(R.id.txt_plan_caption).text = when {
                    dayMsg.isNotBlank() -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date(ring!!)).uppercase() + "'S MESSAGE"
                    own -> if (a.noteFromAi) "MESSAGE · FROM YOUR AI" else "MESSAGE"
                    Account.planFromAi(this, intent) -> "YOUR PLAN · FROM YOUR AI"
                    else -> "YOUR PLAN"
                }
                plan.setOnClickListener {
                    if (own) startActivity(Intent(this, AlarmDetailActivity::class.java).putExtra(AlarmDetailActivity.EXTRA_ID, a.id))
                    else startActivity(Intent(this, PlanActivity::class.java))
                }
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
        if (a.isOnce) {
            // One-time alarms ring once, then switch off. Say so instead of showing seven grey letters.
            val t = a.nextTrigger()
            val s = when {
                t == null -> "Once · turn on to use again"
                a.date.isNotEmpty() && !android.text.format.DateUtils.isToday(t) && t - System.currentTimeMillis() > 36 * 3600_000L ->
                    "Once · " + java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
                android.text.format.DateUtils.isToday(t) -> "Once · today"
                else -> "Once · tomorrow"
            }
            return android.text.SpannableString(s).apply { setSpan(android.text.style.ForegroundColorSpan(0xFFA9B8E8.toInt()), 0, s.length, 0) }
        }
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

    // ---- Select to delete (long press a card) ----------------------------------------------
    private var selecting = false
    private val selected = linkedSetOf<String>()
    private var selectBar: View? = null
    private lateinit var selectDelete: com.google.android.material.button.MaterialButton
    private val backInSelect = object : androidx.activity.OnBackPressedCallback(false) { override fun handleOnBackPressed() { exitSelect() } }

    private fun enterSelect(id: String) {
        if (selecting) return
        selecting = true; selected.clear(); selected.add(id); backInSelect.isEnabled = true
        ensureSelectBar(); refresh()
    }

    private fun exitSelect() {
        selecting = false; selected.clear(); backInSelect.isEnabled = false; refresh()
    }

    /** Cancel / Delete N, in a floating sheet at the bottom (design C). */
    private fun ensureSelectBar() {
        if (selectBar != null) return
        val root = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as android.widget.FrameLayout
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundResource(R.drawable.bg_sheet)
            setPadding(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16))
            elevation = Ui.dp(this@MainActivity, 12).toFloat(); isClickable = true
        }
        val cancel = Ui.button(this, "Cancel", false, 56) { exitSelect() }.apply { layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 56), 1f).apply { marginEnd = Ui.dp(this@MainActivity, 10) } }
        selectDelete = Ui.button(this, "Delete", false, 56) { deleteSelected() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 56), 1.4f)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0x2EFF4D5E); setTextColor(0xFFFF4D5E.toInt())
        }
        bar.addView(cancel); bar.addView(selectDelete)
        root.addView(bar, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM).apply {
            val m = Ui.dp(this@MainActivity, 12); setMargins(m, 0, m, m + Ui.dp(this@MainActivity, 8))
        })
        selectBar = bar
    }

    /** Deletes right away (no confirmation), with Undo for a few seconds. */
    private fun deleteSelected() {
        val gone = selected.mapNotNull { AlarmStore.get(this, it) }.map { it.toJson().toString() }
        selected.toList().forEach { AlarmStore.delete(this, it) }
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        exitSelect()
        com.google.android.material.snackbar.Snackbar.make(list, "${gone.size} alarm${if (gone.size == 1) "" else "s"} deleted", 5000)
            .setAction("Undo") { gone.forEach { AlarmStore.save(this, Alarm.fromJson(org.json.JSONObject(it))) }; refresh() }
            .setActionTextColor(Ui.ACCENT)
            .setAnchorView(findViewById(R.id.nav))
            .show()
    }

    /** Exact time left: "44 min", "1h 59m", "2h", "1d 3h". Rounded up to the minute, like a countdown. */
    private fun untilText(t: Long): String {
        val mins = ((t - System.currentTimeMillis() + 59_999L) / 60_000L).coerceAtLeast(0)
        val d = mins / (24 * 60); val h = (mins / 60) % 24; val m = mins % 60
        return when {
            d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            else -> "$mins min"
        }
    }

    // Keeps "Alarm in …" correct while the screen is open.
    private var headerAt: Long? = null
    private val headerTick = object : Runnable {
        override fun run() {
            headerAt?.let { if (it > System.currentTimeMillis()) title.text = "Alarm in " + untilText(it) else refresh() }
            title.postDelayed(this, 15_000L)
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
