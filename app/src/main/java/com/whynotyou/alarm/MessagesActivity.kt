package com.whynotyou.alarm

import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Settings (the gear). Kept under this class name so existing links keep working. */
class MessagesActivity : ScreenActivity() {

    private val voiceTick = Runnable { if (!isFinishing) rebuild() }

    override fun build() {
        header("Settings")
        val pro = Pro.isPro(this)

        if (!pro) {
            val card = row("Try Pro free", sub = "Calendar, your AI, unlimited limits") { startActivity(Intent(this, TrialActivity::class.java)) }.apply {
                setBackgroundResource(R.drawable.bg_snooze); setPadding(dp(16), dp(18), dp(16), dp(18))
                ((getChildAt(0) as LinearLayout).getChildAt(1) as? android.widget.TextView)?.setTextColor(Ui.ACCENT)
            }
            add(card, 20)
        }

        val v = ModelStore.selected(this)
        val voices = when {
            ModelStore.isDownloading() -> { col.postDelayed(voiceTick, 700); "Downloading ${ModelStore.progress}%" }
            ModelStore.isDownloaded(this, v) -> "${v.label} · ready"
            else -> "Download · ${v.mb} MB"
        }
        val acct = if (Account.signedIn(this)) row("Account", Account.email(this)) { AccountUi.sheet(this) { rebuild() } }
                   else row("Sign in with Google", sub = "So your AI can reach your alarms") { AccountUi.signIn(this) { rebuild() } }
        add(boxOf(
            acct,
            row("Connect your AI", sub = "Claude, ChatGPT and more") { startActivity(Intent(this, ConnectAiActivity::class.java)) },
            row("Calendar", badge = "PRO") {
                if (Pro.isPro(this)) startActivity(Intent(this, CalendarActivity::class.java))
                else Pro.lock(this, "Your calendar with Pro", "Tomorrow's events go into your wake-up plan and the evening reminder.")
            },
            row("Subscription", if (pro) "Pro" else "Free") { startActivity(Intent(this, SubscriptionActivity::class.java)) },
            row("Voices", voices) { voiceSheet() },
            row("Permissions", if (Permissions.allGranted(this)) "All set ✓" else "${Permissions.missingCount(this)} missing") { startActivity(Intent(this, SetupActivity::class.java)) }
        ), if (pro) 20 else 14)

        col.addView(Ui.caption(this, "Test", 28))
        val proRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
        val pl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pl.addView(Ui.text(this, "Pretend I have Pro", 16f, Ui.TEXT, true))
        pl.addView(Ui.text(this, "Until buying is set up", 14f, Ui.GREY))
        proRow.addView(pl, LinearLayout.LayoutParams(0, -2, 1f))
        proRow.addView(SwitchCompat(this).apply { isChecked = pro; setOnCheckedChangeListener { _, on -> Pro.setTestPro(this@MessagesActivity, on); rebuild() } })
        add(boxOf(
            row("Preview wake-up") { startActivity(Intent(this, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_PREVIEW, true)) },
            row("Test alarm in 1 min") {
                AlarmScheduler.scheduleTestInMinutes(this, 1)
                Toast.makeText(this, "Lock the phone. Firing in 60 s.", Toast.LENGTH_LONG).show()
            },
            proRow
        ), 0)
        AlarmScheduler.lastFireLog(this)?.let { log ->
            add(Ui.text(this, log, 12f, Ui.GREY).apply { typeface = android.graphics.Typeface.MONOSPACE; setPadding(dp(4), 0, dp(4), 0); setTextIsSelectable(true) }, 12)
        }
    }

    private fun voiceSheet() {
        val variants = ModelStore.variants
        val subs = mapOf("standard" to "good on every phone", "high" to "clearer, slower to prepare")
        val opts = variants.map { it.label to "${it.mb} MB · ${subs[it.id] ?: ""}" + if (ModelStore.isDownloaded(this, it)) " · ready" else "" }
        Sheet.pick(this, "Voices", opts, variants.indexOf(ModelStore.selected(this)),
            sub = "Downloaded once over Wi-Fi. Until then the phone's own voice is used.") { i ->
            val pick = variants[i]
            ModelStore.select(this, pick.id)
            if (!ModelStore.isDownloaded(this, pick) && !ModelStore.isDownloading()) {
                ModelStore.download(this, pick) { ok -> runOnUiThread {
                    if (ok) VoiceWarmup.start(this) else Toast.makeText(this, "Download failed: ${ModelStore.error}", Toast.LENGTH_LONG).show()
                    rebuild()
                } }
            }
            rebuild()
        }
    }

    override fun onPause() { super.onPause(); col.removeCallbacks(voiceTick) }
}

/** Sign-in and the account pop-up, shared by Settings and Connect your AI. */
object AccountUi {
    fun signIn(a: androidx.appcompat.app.AppCompatActivity, done: () -> Unit) {
        a.lifecycleScope.launch {
            val err = Account.signIn(a)
            if (!err.isNullOrEmpty()) Toast.makeText(a, err, Toast.LENGTH_LONG).show()
            else if (err == null) Toast.makeText(a, "Signed in as ${Account.email(a)}", Toast.LENGTH_SHORT).show()
            done()
        }
    }

    fun sheet(a: androidx.appcompat.app.AppCompatActivity, done: () -> Unit) {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val last = Account.lastSync(a)
        val when_ = if (last == 0L) "Not synced yet" else "Last synced " + android.text.format.DateUtils.getRelativeTimeSpanString(last)
        col.addView(Ui.text(a, when_, 14f, Ui.GREY))
        var d: com.google.android.material.bottomsheet.BottomSheetDialog? = null
        col.addView(Ui.button(a, "Sync now", true, 56) {
            a.lifecycleScope.launch {
                val n = Sync.run(a)
                Toast.makeText(a, when { n < 0 -> "Couldn't sync. Check your connection."; n == 0 -> "Up to date"; else -> "$n change${if (n == 1) "" else "s"} from your AI" }, Toast.LENGTH_SHORT).show()
                d?.dismiss(); done()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(a, 20) })
        col.addView(Ui.button(a, "Sign out", false, 52) { Account.signOut(a); d?.dismiss(); done() }
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(a, 8) })
        col.addView(Ui.text(a, "Delete account", 15f, 0xFFFF8A8A.toInt()).apply {
            gravity = Gravity.CENTER; minHeight = Ui.dp(a, 48); setPadding(0, Ui.dp(a, 12), 0, 0)
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(a)
                    .setTitle("Delete your account?")
                    .setMessage("This removes your alarms, plans and screen time from our server and disconnects your AI. Alarms on this phone stay.")
                    .setPositiveButton("Delete") { _, _ -> a.lifecycleScope.launch {
                        val ok = Account.delete(a)
                        Toast.makeText(a, if (ok) "Account deleted" else "Couldn't delete. Try again online.", Toast.LENGTH_LONG).show()
                        d?.dismiss(); done()
                    } }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        d = Sheet.show(a, "Account", Account.email(a), col, done = null)
    }
}
