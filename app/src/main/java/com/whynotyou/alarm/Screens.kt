package com.whynotyou.alarm

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** A plain screen: back arrow + title at the top, a column of content, an optional button pinned at the bottom. */
abstract class ScreenActivity : AppCompatActivity() {
    protected lateinit var col: LinearLayout
    private lateinit var bottom: LinearLayout
    protected fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(140)) }
        root.addView(ScrollView(this).apply { addView(col); isFillViewport = true })
        bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); rebuild() }
    protected fun rebuild() { col.removeAllViews(); bottom.removeAllViews(); build() }
    abstract fun build()

    protected fun header(title: String, badge: String? = null, back: Boolean = true) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44) }
        if (back) r.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Ui.GREY); background = null; contentDescription = "Back"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = -dp(10) })
        if (title.isNotEmpty()) r.addView(Ui.text(this, title, 28f, Ui.TEXT, true))
        if (badge != null) r.addView(Ui.proBadge(this, badge))
        col.addView(r)
    }
    protected fun para(t: CharSequence, size: Float = 16f, color: Int = Ui.GREY, top: Int = 6, center: Boolean = false) =
        col.addView(Ui.text(this, t, size, color).apply { setLineSpacing(0f, 1.35f); setPadding(0, dp(top), 0, 0); if (center) gravity = Gravity.CENTER })
    protected fun add(v: View, top: Int = 14) = col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    protected fun pinned(v: View, top: Int = 0) = bottom.addView(v, (v.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply { topMargin = dp(top) })
    protected fun orb(size: Int, top: Int = 0) {
        val f = FrameLayout(this)
        f.addView(View(this).apply { setBackgroundResource(R.drawable.bg_glow) }, FrameLayout.LayoutParams(-1, -1))
        f.addView(OrbView(this).apply { mode = OrbView.Mode.BREATHE }, FrameLayout.LayoutParams(-1, -1))
        col.addView(f, LinearLayout.LayoutParams(dp(size), dp(size)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(top) })
    }
    protected fun row(label: String, value: String? = null, sub: String? = null, badge: String? = null, chevron: Boolean = true, click: (() -> Unit)? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(0, dp(12), 0, dp(12))
        val l = LinearLayout(this@ScreenActivity).apply { orientation = LinearLayout.VERTICAL }
        val t = LinearLayout(this@ScreenActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        t.addView(Ui.text(this@ScreenActivity, label, 16f, Ui.TEXT, true)); if (badge != null) t.addView(Ui.proBadge(this@ScreenActivity, badge))
        l.addView(t); if (sub != null) l.addView(Ui.text(this@ScreenActivity, sub, 14f, Ui.GREY))
        addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(Ui.text(this@ScreenActivity, value, 15f, Ui.GREY))
        if (chevron && click != null) addView(android.widget.ImageView(this@ScreenActivity).apply { setImageResource(R.drawable.ic_chevron); setColorFilter(Ui.GREY) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
        if (click != null) setOnClickListener { click() }
    }
    protected fun boxOf(vararg rows: View): LinearLayout = Ui.box(this).apply {
        rows.forEachIndexed { i, r -> addView(r); if (i < rows.size - 1) addView(Ui.divider(this@ScreenActivity)) }
    }
    protected fun bullet(t: String, sub: String? = null, pad: Int = 10) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(pad), 0, dp(pad))
        addView(FrameLayout(this@ScreenActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF26315A.toInt()) }
            addView(Ui.text(this@ScreenActivity, "✓", 12f, Ui.ACCENT, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { topMargin = dp(2); marginEnd = dp(14) })
        val c = LinearLayout(this@ScreenActivity).apply { orientation = LinearLayout.VERTICAL }
        c.addView(Ui.text(this@ScreenActivity, t, 16f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        if (sub != null) c.addView(Ui.text(this@ScreenActivity, sub, 14f, Ui.GREY))
        addView(c)
    }
    protected fun soon(what: String) = Toast.makeText(this, "$what is coming soon", Toast.LENGTH_SHORT).show()
}

object Onboarding {
    private fun p(c: Context) = c.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    fun done(c: Context) = p(c).getBoolean("done", false) || AlarmStore.all(c).isNotEmpty()
    fun setDone(c: Context) = p(c).edit().putBoolean("done", true).apply()
    fun trialShown(c: Context) = p(c).getBoolean("trial_shown", false)
    fun setTrialShown(c: Context) = p(c).edit().putBoolean("trial_shown", true).apply()
}

/** 1.1 Welcome */
class WelcomeActivity : ScreenActivity() {
    override fun build() {
        orb(220, 48)
        add(Ui.text(this, "The alarm your\nAI assistant runs", 32f, Ui.TEXT, true).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f) }, 32)
        para("Wake up to your plan for the day. Give every alarm its own message. Let your AI set it all up for you.", center = true, top = 14)
        pinned(Ui.button(this, "Get started", true, 64) {
            Onboarding.setDone(this)
            startActivity(Intent(this, SetupActivity::class.java)); finish()
        })
    }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { finishAffinity() }
}

/** 1.3 Try Pro (paywall). Billing is not connected yet. */
class TrialActivity : ScreenActivity() {
    private var plan = 0
    override fun build() {
        Onboarding.setTrialShown(this)
        col.setPadding(dp(20), dp(16), dp(20), dp(150))   // must fit one screen: no scrolling
        val top = LinearLayout(this).apply { gravity = Gravity.END }
        top.addView(Ui.text(this, "Not now", 15f, Ui.GREY).apply { setPadding(dp(4), dp(6), dp(4), dp(6)); setOnClickListener { finish() } })
        col.addView(top)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(this, "Try Pro free\nfor 7 days", 28f, Ui.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(FrameLayout(this).apply {
            addView(View(this@TrialActivity).apply { setBackgroundResource(R.drawable.bg_glow) }, FrameLayout.LayoutParams(-1, -1))
            addView(OrbView(this@TrialActivity).apply { mode = OrbView.Mode.BREATHE }, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(dp(104), dp(104)))
        col.addView(head)
        add(bullet("Your calendar, read to you", "Tomorrow's events in your wake-up plan", 6), 4)
        col.addView(bullet("Your AI plans your day", "Works with Claude, ChatGPT and more", 6))
        col.addView(bullet("Follow-up questions", "\"Are they crisp?\" Yes or No decides what's next", 6))
        col.addView(bullet("Plan the whole week", "Every alarm and message, set in one go", 6))
        val plans = listOf(Triple("Yearly", "$39.99", "$3.33 a month · 7 days free"), Triple("Monthly", "$6.99", "7 days free"), Triple("Lifetime", "$79", "Early supporters only · pay once"))
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun render() {
            holder.removeAllViews()
            plans.forEachIndexed { i, (n, price, sub) ->
                val on = i == plan
                holder.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = GradientDrawable().apply { setColor(0xFF141B37.toInt()); cornerRadius = dp(20).toFloat(); if (on) setStroke(dp(2), Ui.ACCENT) }
                    addView(Ui.radio(this@TrialActivity, on).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14) })
                    val c = LinearLayout(this@TrialActivity).apply { orientation = LinearLayout.VERTICAL }
                    val nr = LinearLayout(this@TrialActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    nr.addView(Ui.text(this@TrialActivity, n, 16f, Ui.TEXT, true))
                    if (i == 0) nr.addView(Ui.text(this@TrialActivity, "SAVE 50%", 11f, 0xFF0A1022.toInt(), true).apply {
                        background = GradientDrawable().apply { setColor(Ui.ACCENT); cornerRadius = dp(10).toFloat() }; setPadding(dp(8), dp(3), dp(8), dp(3))
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) } })
                    c.addView(nr); c.addView(Ui.text(this@TrialActivity, sub, 14f, Ui.GREY))
                    addView(c, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Ui.text(this@TrialActivity, price, 16f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                    setOnClickListener { plan = i; render() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
        }
        render(); add(holder, 4)
        pinned(Ui.button(this, "Start free trial", true, 64) { soon("Buying Pro") })
        pinned(Ui.text(this, "Free for 7 days, then $39.99 a year. Cancel anytime in Google Play.", 13f, Ui.GREY).apply { gravity = Gravity.CENTER }, 8)
    }
}

/** 5.7 This week (Pro) */
class StatsActivity : ScreenActivity() {
    override fun build() {
        header("This week", "PRO")
        val days = PhoneDown.lastDays(this)
        val total = days.sumOf { it.second }
        val prev = PhoneDown.minutesIn(this, days.first().first - 7 * 86_400_000L, days.first().first)
        val box = Ui.box(this, 20, 20)
        box.addView(Ui.text(this, "In your limited apps", 13f, Ui.GREY))
        box.addView(Ui.text(this, "${total / 60} h ${total % 60} min", 40f).apply { setPadding(0, dp(4), 0, 0) })
        if (prev > 0) {
            val pct = ((prev - total) * 100 / prev)
            box.addView(Ui.text(this, if (pct >= 0) "$pct% less than last week" else "${-pct}% more than last week", 14f, if (pct >= 0) Ui.GREEN else 0xFFFFA38A.toInt()))
        }
        add(box, 16)
        col.addView(Ui.caption(this, "Top apps", 28))
        val start = days.first().first; val now = System.currentTimeMillis()
        val per = PhoneDown.watchedNames(this).map { n -> n to PhoneDown.minutesIn(this, start, now, PhoneDown.knownApps.filter { it.name == n }.map { it.pkg }.toSet()) }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
        val top = Ui.box(this)
        val max = per.maxOfOrNull { it.second } ?: 1
        if (per.isEmpty()) top.addView(Ui.text(this, "Nothing yet this week.", 15f, Ui.GREY).apply { setPadding(0, dp(16), 0, dp(16)) })
        per.forEachIndexed { i, (n, m) ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            l.addView(Ui.text(this, n, 15f, Ui.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
            l.addView(Ui.text(this, if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min", 15f, Ui.GREY))
            r.addView(l)
            val track = FrameLayout(this).apply { background = GradientDrawable().apply { setColor(Ui.SEC); cornerRadius = dp(3).toFloat() } }
            track.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF4A74FF.toInt(), 0xFF2347F0.toInt())).apply { cornerRadius = dp(3).toFloat() } },
                FrameLayout.LayoutParams(0, -1).also { it.width = 1 })
            r.addView(track, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
            track.post { track.getChildAt(0).layoutParams.width = track.width * m / max; track.getChildAt(0).requestLayout() }
            top.addView(r); if (i < per.size - 1) top.addView(Ui.divider(this))
        }
        add(top, 0)
        col.addView(Ui.caption(this, "Mornings", 28))
        val st = Mornings.week(this)
        add(boxOf(row("Up on the first alarm", "${st.first} of ${st.third} days", chevron = false), row("Plans set the night before", "${st.second} of ${st.third}", chevron = false)), 0)
    }
}

/** Simple local morning stats for the Stats screen. */
object Mornings {
    private fun p(c: Context) = c.getSharedPreferences("mornings", Context.MODE_PRIVATE)
    private fun day(t: Long) = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(t))
    fun recordWake(c: Context, firstAlarm: Boolean) { p(c).edit().putBoolean("w_" + day(System.currentTimeMillis()), firstAlarm).apply() }
    fun recordPlan(c: Context) { p(c).edit().putBoolean("p_" + day(System.currentTimeMillis()), true).apply() }
    /** (up on first alarm, plans set, days with an alarm) over the last 7 days. */
    fun week(c: Context): Triple<Int, Int, Int> {
        var up = 0; var plans = 0; var days = 0
        for (i in 0 until 7) {
            val d = day(System.currentTimeMillis() - i * 86_400_000L)
            if (p(c).contains("w_$d")) { days++; if (p(c).getBoolean("w_$d", false)) up++ }
            if (p(c).getBoolean("p_" + day(System.currentTimeMillis() - (i + 1) * 86_400_000L), false)) plans++
        }
        return Triple(up, plans, days.coerceAtLeast(1))
    }
}

/** 7.2 / 7.3 Connect your AI (server not live yet: shows the steps). */
class ConnectAiActivity : ScreenActivity() {
    private var pick = 0
    private val ais = listOf("Claude", "ChatGPT", "Perplexity", "Grok", "Le Chat")
    private val steps = mapOf(
        "Claude" to "In Claude, open Settings → Connectors → Add custom connector",
        "ChatGPT" to "In ChatGPT, open Settings → Apps & Connectors → Create (developer mode)",
        "Perplexity" to "In Perplexity, open Settings → Connectors → Add connector",
        "Grok" to "In Grok, open Settings → Connectors → Add",
        "Le Chat" to "In Le Chat, open Connectors → Add a custom connector")
    private val openUrl = mapOf(
        "Claude" to "https://claude.ai/settings/connectors",
        "ChatGPT" to "https://chatgpt.com/#settings/Connectors",
        "Perplexity" to "https://www.perplexity.ai/account/connectors",
        "Grok" to "https://grok.com",
        "Le Chat" to "https://chat.mistral.ai/connections")
    override fun onResume() { super.onResume(); if (Account.signedIn(this)) lifecycleScope.launch { Sync.run(this@ConnectAiActivity); rebuild() } }
    override fun build() {
        header("Connect your AI")
        para("Let the AI you already use see your alarms and plans, and change them for you.", top = 4)
        col.addView(Ui.caption(this, "Choose your AI", 24))
        val flow = FlowLayout(this, dp(8))
        ais.forEachIndexed { i, n ->
            flow.addView(Ui.text(this, n, 15f, if (i == pick) 0xFFFFFFFF.toInt() else 0xFFB8BFCC.toInt(), i == pick).apply {
                gravity = Gravity.CENTER; minHeight = dp(44); setPadding(dp(16), 0, dp(16), 0)
                background = GradientDrawable().apply { setColor(if (i == pick) 0x472D5BFF else Ui.SEC); cornerRadius = dp(22).toFloat(); if (i == pick) setStroke(dp(2), Ui.ACCENT) }
                setOnClickListener { pick = i; rebuild() }
            })
        }
        col.addView(flow)
        para("Gemini and Copilot don't support this yet.", 13f, Ui.CAPTION, 10)
        val name = ais[pick]
        col.addView(Ui.caption(this, "How to connect $name", 28))
        val link = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Ui.SEC); cornerRadius = dp(14).toFloat() }
            addView(Ui.text(this@ConnectAiActivity, AiLink.URL, 13f).apply { typeface = Typeface.MONOSPACE }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ui.button(this@ConnectAiActivity, "Copy", true, 32) {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("link", AiLink.URL))
                Toast.makeText(this@ConnectAiActivity, "Copied", Toast.LENGTH_SHORT).show()
            }.apply { layoutParams = LinearLayout.LayoutParams(-2, dp(32)); textSize = 13f; elevation = 0f })
        }
        val s2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        s2.addView(step(2, "Paste this link")); s2.addView(link, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); marginStart = dp(26) })
        val signed = Account.signedIn(this)
        add(boxOf(step(1, steps[name]!!), s2, step(3, "Sign in with Google, using ${if (signed) Account.email(this) else "the same account as in this app"}")), 0)
        val dot = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        dot.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (signed) Ui.GREEN else Ui.CAPTION) } }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
        dot.addView(Ui.text(this, if (signed) "This phone is signed in · syncs every 15 min" else "Sign in on this phone first", 14f, Ui.GREY))
        add(dot, 16)
        val recent = Account.actions(this).take(5)
        if (signed && recent.isNotEmpty()) {
            col.addView(Ui.caption(this, "Recently by your AI", 28))
            add(boxOf(*recent.map { row(it, chevron = false) }.toTypedArray()), 0)
        }
        if (!signed) pinned(Ui.button(this, "Sign in with Google", true, 60) { AccountUi.signIn(this) { rebuild() } })
        else pinned(Ui.button(this, "Open $name", true, 60) {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(openUrl[name]))) } catch (_: Throwable) { soon("Opening $name") }
        })
    }
    private fun step(n: Int, t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(12))
        addView(Ui.text(this@ConnectAiActivity, "$n", 15f, Ui.ACCENT, true), LinearLayout.LayoutParams(dp(26), -2))
        addView(Ui.text(this@ConnectAiActivity, t, 15f), LinearLayout.LayoutParams(0, -2, 1f))
    }
}
object AiLink { const val URL = "https://mcp.getwhynotyou.com/mcp" }

/** 7.4 Calendar (Pro): which phone calendars feed the plan. */
class CalendarActivity : ScreenActivity() {
    override fun build() {
        header("Calendar", "PRO")
        para("Tomorrow's events go into your wake-up plan and the evening reminder.", top = 4)
        val granted = checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.bg_box); setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(View(this@CalendarActivity).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (granted) Ui.GREEN else Ui.CAPTION) } }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(14) })
            val c = LinearLayout(this@CalendarActivity).apply { orientation = LinearLayout.VERTICAL }
            c.addView(Ui.text(this@CalendarActivity, if (granted) "Calendar access on" else "Calendar access off", 16f, Ui.TEXT, true))
            c.addView(Ui.text(this@CalendarActivity, if (granted) "Uses the calendars on this phone" else "Tap to allow", 14f, Ui.GREY))
            addView(c)
            if (!granted) setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.READ_CALENDAR), 7) }
        }
        add(status, 20)
        if (!granted) return
        col.addView(Ui.caption(this, "Calendars to include", 28))
        val cals = CalendarPrefs.list(this)
        val box = Ui.box(this)
        cals.forEachIndexed { i, cal ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64); setPadding(0, dp(10), 0, dp(10)) }
            r.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cal.color or 0xFF000000.toInt()) } }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(Ui.text(this, cal.name, 16f, Ui.TEXT, true)); c.addView(Ui.text(this, cal.account, 14f, Ui.GREY))
            r.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(SwitchCompat(this).apply { isChecked = CalendarPrefs.isOn(this@CalendarActivity, cal.id); setOnCheckedChangeListener { _, v -> CalendarPrefs.set(this@CalendarActivity, cal.id, v) } })
            box.addView(r); if (i < cals.size - 1) box.addView(Ui.divider(this))
        }
        add(box, 0)
        para("Events stay on your phone. Your AI only sees them if you connect it.", 13f, Ui.CAPTION, 14)
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) { super.onRequestPermissionsResult(rc, p, g); rebuild() }
}

/** Which calendars to read (all on by default). */
object CalendarPrefs {
    data class Cal(val id: Long, val name: String, val account: String, val color: Int)
    private fun p(c: Context) = c.getSharedPreferences("calendars", Context.MODE_PRIVATE)
    fun isOn(c: Context, id: Long) = p(c).getBoolean("c_$id", true)
    fun set(c: Context, id: Long, on: Boolean) = p(c).edit().putBoolean("c_$id", on).apply()
    fun list(c: Context): List<Cal> = try {
        val out = ArrayList<Cal>()
        c.contentResolver.query(android.provider.CalendarContract.Calendars.CONTENT_URI,
            arrayOf(android.provider.CalendarContract.Calendars._ID, android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                android.provider.CalendarContract.Calendars.ACCOUNT_NAME, android.provider.CalendarContract.Calendars.CALENDAR_COLOR), null, null, null)?.use { cur ->
            while (cur.moveToNext()) out.add(Cal(cur.getLong(0), cur.getString(1) ?: "Calendar", cur.getString(2) ?: "", cur.getInt(3)))
        }
        out
    } catch (_: Exception) { emptyList() }
}

/** 7.5 Subscription */
class SubscriptionActivity : ScreenActivity() {
    override fun build() {
        header("Subscription")
        val pro = Pro.isPro(this)
        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.bg_box); setPadding(dp(20), dp(20), dp(20), dp(20)) }
        card.addView(OrbView(this).apply { mode = OrbView.Mode.BREATHE }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(16) })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(Ui.text(this, if (pro) "Pro" else "Free", 20f, Ui.TEXT, true))
        c.addView(Ui.text(this, if (pro) "Test mode · billing isn't connected yet" else "Alarms, plans and a message on every alarm", 14f, Ui.GREY))
        card.addView(c, LinearLayout.LayoutParams(0, -2, 1f))
        add(card, 16)
        col.addView(Ui.caption(this, if (pro) "Included" else "With Pro", 28))
        col.addView(bullet("Your calendar, read to you")); col.addView(bullet("Your AI plans your day"))
        col.addView(bullet("Follow-up questions")); col.addView(bullet("Plan the whole week in one go"))
        add(boxOf(
            row(if (pro) "Change plan" else "Try Pro free") { startActivity(Intent(this, TrialActivity::class.java)) },
            row("Manage in Google Play") { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"))) } catch (_: Exception) {} },
            row("Restore purchase") { soon("Restoring purchases") }), 20)
    }
}
