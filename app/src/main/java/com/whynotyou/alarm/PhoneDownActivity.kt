package com.whynotyou.alarm

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Old entry point: Phone down now lives in its own tab, so this just opens that tab. */
class PhoneDownActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PHONE_TAB, true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}

/** The Phone down tab: on/off, today's minutes, the limit, strict mode, apps, messages. */
class PhoneDownPanel(private val a: Activity, private val col: LinearLayout) {
    private fun dp(v: Int) = Ui.dp(a, v)

    fun render() {
        col.removeAllViews()
        val pro = Pro.isPro(a)

        // Title + master switch
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.text(a, "Phone down", 28f, Ui.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(SwitchCompat(a).apply {
            isChecked = PhoneDown.isEnabled(a)
            setOnCheckedChangeListener { _, v ->
                if (v && (!PhoneDown.hasUsageAccess(a) || !PhoneDown.canDrawOver(a))) { isChecked = false; askPermissions(); return@setOnCheckedChangeListener }
                PhoneDown.setEnabled(a, v)
            }
        })
        col.addView(head)

        // Missing permissions
        if (!PhoneDown.hasUsageAccess(a) || !PhoneDown.canDrawOver(a)) {
            val b = Ui.box(a, 18, 18).apply { (this as View).layoutParams = lp(top = 16) }
            b.addView(Ui.text(a, "One permission first", 16f, Ui.TEXT, true))
            b.addView(Ui.text(a, "Phone down needs to see which app is open, so it knows when to remind you. Nothing leaves your phone.", 14f, Ui.GREY).apply { setPadding(0, dp(4), 0, dp(14)); setLineSpacing(0f, 1.3f) })
            b.addView(Ui.button(a, "Open settings", true, 52) { askPermissions() })
            col.addView(b)
        }

        // Today
        val days = PhoneDown.lastDays(a)
        val today = days.lastOrNull()?.second ?: 0
        val t = Ui.box(a, 20, 20).apply { layoutParams = lp(top = 16) }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(Ui.text(a, "Today in your apps", 13f, Ui.GREY), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Ui.text(a, "This week ›", 13f, Ui.ACCENT).apply {
            setOnClickListener { if (Pro.isPro(a)) a.startActivity(Intent(a, StatsActivity::class.java)) else Pro.lock(a, "Weekly stats with Pro", "See your week: time in each app, how often you got up on the first alarm, and how many nights you planned ahead.") }
        })
        t.addView(top)
        t.addView(bigNumber(today))
        t.addView(Ui.text(a, "Limit ${PhoneDown.limitMin(a)} min at a time", 14f, Ui.GREY))
        t.addView(bars(days), lp(top = 16))
        col.addView(t)

        // Limit + strict
        val s = Ui.box(a).apply { layoutParams = lp(top = 14) }
        s.addView(row("Remind me after", "${PhoneDown.limitMin(a)} min") {
            val l = PhoneDown.limits
            Sheet.pick(a, "Remind me after", l.map { "$it min" to null }, l.indexOf(PhoneDown.limitMin(a))) { i -> PhoneDown.setLimitMin(a, l[i]); render() }
        })
        s.addView(Ui.divider(a))
        val strictRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
        val label = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        label.addView(Ui.text(a, "Strict mode", 16f, Ui.TEXT, true)); label.addView(Ui.proBadge(a))
        strictRow.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        strictRow.addView(SwitchCompat(a).apply {
            isChecked = PhoneDown.isStrict(a)
            setOnCheckedChangeListener { _, v ->
                if (v && !Pro.isPro(a)) { isChecked = false; Pro.lock(a, "Strict mode with Pro", "No “5 more minutes”. When your time is up, the app stays closed until tomorrow."); return@setOnCheckedChangeListener }
                PhoneDown.setStrict(a, v)
            }
        })
        s.addView(strictRow)
        col.addView(s)

        // Apps
        col.addView(Ui.caption(a, "Apps", 28))
        val watched = PhoneDown.watched(a).toMutableSet()
        val apps = PhoneDown.installedKnownApps(a)
        val active = PhoneDown.effectiveWatched(a)
        val wrap = FlowLayout(a, dp(8))
        if (apps.isEmpty()) col.addView(Ui.text(a, "None of the usual apps are installed.", 14f, Ui.GREY))
        for (app in apps) {
            val pkgs = PhoneDown.knownApps.filter { it.name == app.name }.map { it.pkg }
            val on = pkgs.any { it in active }
            val chosenButLocked = !on && pkgs.any { it in watched }
            wrap.addView(chip(app.name + if (chosenButLocked) " +" else "", on) {
                if (on) { watched.removeAll(pkgs.toSet()); PhoneDown.setWatched(a, watched); render() }
                else if (!pro && active.map { PhoneDown.appName(a, it) }.distinct().size >= PhoneDown.FREE_APPS) {
                    Pro.lock(a, "More app limits with Pro", "Free includes ${PhoneDown.FREE_APPS} app limits. Pro adds unlimited apps and strict mode, where “5 more minutes” is turned off.")
                } else { watched.addAll(pkgs); PhoneDown.setWatched(a, watched); render() }
            })
        }
        col.addView(wrap)

        // Messages
        col.addView(Ui.caption(a, "Messages", 28))
        val msgs = PhoneDown.messages(a).toMutableList()
        val m = Ui.box(a)
        for ((i, msg) in msgs.withIndex()) {
            m.addView(row(msg, "×", size = 15f) { msgs.removeAt(i); PhoneDown.setMessages(a, msgs); render() })
            m.addView(Ui.divider(a))
        }
        m.addView(row("Add a message", "+", size = 15f) { addMessage(msgs) })
        col.addView(m)
    }

    private fun addMessage(msgs: MutableList<String>) {
        val et = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; maxLines = 6; gravity = Gravity.TOP; textSize = 18f; setTextColor(Ui.TEXT); setHintTextColor(0xFF6F7EA6.toInt())
            hint = "Go outside. The feed will still be here."; setBackgroundResource(R.drawable.bg_input); setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        Sheet.show(a, "New message", "Shown when the time is up", et, "Add") {
            val t = et.text.toString().trim(); if (t.isNotEmpty()) { msgs.add(t); PhoneDown.setMessages(a, msgs); render() }
        }
        et.requestFocus()
    }

    private fun askPermissions() {
        if (!PhoneDown.hasUsageAccess(a)) a.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        else if (!PhoneDown.canDrawOver(a)) a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}")))
    }

    private fun bigNumber(min: Int): TextView {
        val sb = android.text.SpannableStringBuilder()
        val h = min / 60; val mm = min % 60
        fun unit(u: String) { val st = sb.length; sb.append(u); sb.setSpan(android.text.style.RelativeSizeSpan(0.38f), st, sb.length, 0); sb.setSpan(android.text.style.ForegroundColorSpan(Ui.GREY), st, sb.length, 0) }
        if (h > 0) { sb.append("$h"); unit(" h  ") }
        sb.append("$mm"); unit(" min")
        return Ui.text(a, sb, 48f).apply { setPadding(0, dp(4), 0, 0) }
    }

    private fun bars(days: List<Pair<Long, Int>>): View {
        val max = (days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(30)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val f = java.text.SimpleDateFormat("EEEEE", java.util.Locale.getDefault())
        days.forEachIndexed { i, (start, minutes) ->
            val today = i == days.size - 1
            val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val holder = FrameLayout(a)
            val h = (dp(72) * minutes / max).coerceAtLeast(if (minutes > 0) dp(4) else 0)
            holder.addView(View(a).apply {
                background = if (today) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF4A74FF.toInt(), 0xFF2347F0.toInt())).apply { cornerRadius = dp(6).toFloat() }
                             else GradientDrawable().apply { setColor(Ui.SEC); cornerRadius = dp(6).toFloat() }
            }, FrameLayout.LayoutParams(-1, h, Gravity.BOTTOM))
            c.addView(holder, LinearLayout.LayoutParams(-1, dp(72)))
            c.addView(Ui.text(a, f.format(java.util.Date(start)), 11f, if (today) Ui.TEXT else Ui.CAPTION).apply { setPadding(0, dp(6), 0, 0) })
            row.addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        return row
    }

    private fun chip(t: String, on: Boolean, click: () -> Unit) = TextView(a).apply {
        text = t; textSize = 14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (on) Ui.TEXT else Ui.GREY); gravity = Gravity.CENTER; minHeight = dp(40)
        setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable().apply { setColor(if (on) Ui.SEC else 0x0AFFFFFF); cornerRadius = dp(20).toFloat() }
        setOnClickListener { click() }
    }

    private fun row(label: String, value: String?, size: Float = 16f, click: (() -> Unit)? = null) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56); setPadding(0, dp(12), 0, dp(12))
        addView(Ui.text(a, label, size, Ui.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (value != null) addView(Ui.text(a, value, 15f, Ui.GREY).apply { setPadding(dp(8), 0, 0, 0) })
        if (click != null) setOnClickListener { click() }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}

/** Minimal wrapping row layout for chips. */
class FlowLayout(c: android.content.Context, private val gap: Int) : ViewGroup(c) {
    override fun onMeasure(w: Int, h: Int) {
        val maxW = MeasureSpec.getSize(w); var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i); measureChild(ch, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), h)
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += ch.measuredWidth + gap; rowH = maxOf(rowH, ch.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l; var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (x > 0 && x + ch.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
            x += ch.measuredWidth + gap; rowH = maxOf(rowH, ch.measuredHeight)
        }
    }
}
