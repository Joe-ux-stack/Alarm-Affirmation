package com.whynotyou.alarm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Settings for Phone down: on/off, limit, which apps, which messages. */
class PhoneDownActivity : AppCompatActivity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(56), dp(12), dp(48)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(col) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        col.removeAllViews()
        col.addView(TextView(this).apply { text = "Phone down"; textSize = 28f; setTextColor(0xFFF2F4F8.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "After a while in the apps below, a reminder covers the screen."
            textSize = 14f; setTextColor(0xFF7A8294.toInt()); setPadding(0, dp(6), 0, dp(20))
        })

        // on/off
        val on = PhoneDown.isEnabled(this)
        row("On", null) {
            addView(SwitchCompat(this@PhoneDownActivity).apply {
                isChecked = on
                setOnCheckedChangeListener { _, v ->
                    if (v && (!PhoneDown.hasUsageAccess(this@PhoneDownActivity) || !PhoneDown.canDrawOver(this@PhoneDownActivity))) {
                        isChecked = false; askPermissions(); return@setOnCheckedChangeListener
                    }
                    PhoneDown.setEnabled(this@PhoneDownActivity, v)
                }
            })
        }

        // permissions (only shown when missing)
        if (!PhoneDown.hasUsageAccess(this)) row("Usage access", "Needed to see which app is open") {
            setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            addView(value("Allow"))
        }
        if (!PhoneDown.canDrawOver(this)) row("Show over other apps", null) {
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            addView(value("Allow"))
        }

        // limit
        row("Limit", null) {
            addView(value("${PhoneDown.limitMin(this@PhoneDownActivity)} min"))
            setOnClickListener {
                val l = PhoneDown.limits; val cur = l.indexOf(PhoneDown.limitMin(this@PhoneDownActivity))
                AlertDialog.Builder(this@PhoneDownActivity).setTitle("Limit")
                    .setSingleChoiceItems(l.map { "$it min" }.toTypedArray(), cur) { d, i -> PhoneDown.setLimitMin(this@PhoneDownActivity, l[i]); d.dismiss(); render() }.show()
            }
        }

        // apps
        caption("Apps")
        val watched = PhoneDown.watched(this).toMutableSet()
        val apps = PhoneDown.installedKnownApps(this)
        if (apps.isEmpty()) col.addView(TextView(this).apply { text = "None of the usual apps are installed."; setTextColor(0xFF7A8294.toInt()); textSize = 14f; setPadding(0, dp(8), 0, dp(8)) })
        for (a in apps) row(a.name, null) {
            val pkgs = PhoneDown.knownApps.filter { it.name == a.name }.map { it.pkg }
            addView(SwitchCompat(this@PhoneDownActivity).apply {
                isChecked = pkgs.any { it in watched }
                setOnCheckedChangeListener { _, v -> if (v) watched.addAll(pkgs) else watched.removeAll(pkgs.toSet()); PhoneDown.setWatched(this@PhoneDownActivity, watched) }
            })
        }

        // messages
        caption("Messages")
        val msgs = PhoneDown.messages(this).toMutableList()
        for ((i, m) in msgs.withIndex()) row(m, null) {
            addView(ImageButton(this@PhoneDownActivity).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(0xFF7A8294.toInt())
                background = null; contentDescription = "Remove"
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener { msgs.removeAt(i); PhoneDown.setMessages(this@PhoneDownActivity, msgs); render() }
            })
        }
        row("Add a message", null) {
            addView(value("+"))
            setOnClickListener {
                val et = EditText(this@PhoneDownActivity).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; hint = "Write it like a friend would" }
                AlertDialog.Builder(this@PhoneDownActivity).setTitle("New message").setView(et)
                    .setPositiveButton("Add") { _, _ -> val t = et.text.toString().trim(); if (t.isNotEmpty()) { msgs.add(t); PhoneDown.setMessages(this@PhoneDownActivity, msgs); render() } }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun askPermissions() {
        if (!PhoneDown.hasUsageAccess(this)) startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        else if (!PhoneDown.canDrawOver(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun row(label: String, sub: String?, build: LinearLayout.() -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56); setPadding(0, dp(12), 0, dp(12))
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        textCol.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(0xFFF2F4F8.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        if (sub != null) textCol.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(0xFF7A8294.toInt()) })
        r.addView(textCol); r.build()
        col.addView(r)
        col.addView(View(this).apply { setBackgroundColor(0xFF15181F.toInt()); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1) })
    }
    private fun value(t: String) = TextView(this).apply { text = t; textSize = 15f; setTextColor(0xFF9AA3B5.toInt()); setPadding(dp(8), 0, 0, 0) }
    private fun caption(t: String) = col.addView(TextView(this).apply {
        text = t.uppercase(); textSize = 12f; setTextColor(0xFF555B68.toInt()); letterSpacing = 0.08f
        setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(32), 0, dp(4))
    })
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
