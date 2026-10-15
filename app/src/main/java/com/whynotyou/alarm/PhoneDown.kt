package com.whynotyou.alarm

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import org.json.JSONArray

/** "Phone down": settings for the usage guard that draws over distracting apps after a time limit. */
object PhoneDown {
    private fun prefs(c: Context) = c.getSharedPreferences("phonedown", Context.MODE_PRIVATE)

    data class App(val pkg: String, val name: String)
    val knownApps = listOf(
        App("com.instagram.android", "Instagram"),
        App("com.zhiliaoapp.musically", "TikTok"),
        App("com.ss.android.ugc.trill", "TikTok"),
        App("com.google.android.youtube", "YouTube"),
        App("com.facebook.katana", "Facebook"),
        App("com.twitter.android", "X"),
        App("com.reddit.frontpage", "Reddit"),
        App("com.snapchat.android", "Snapchat"),
        App("com.pinterest", "Pinterest"),
        App("com.netflix.mediaclient", "Netflix"),
        App("tv.twitch.android.app", "Twitch"),
        App("com.linkedin.android", "LinkedIn"),
        App("org.telegram.messenger", "Telegram"),
        App("com.whatsapp", "WhatsApp"),
    )
    val defaultMessages = listOf(
        "You said you'd put it down.",
        "What were you going to do today?",
        "This can wait. You can't.",
        "Your future self is watching.",
        "Look up. Breathe. Choose.",
        "Why not you? Go do the thing.",
    )
    val limits = listOf(1, 5, 10, 15, 20, 30, 45, 60)

    fun installedKnownApps(c: Context): List<App> = knownApps.filter {
        try { c.packageManager.getApplicationInfo(it.pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
    }.distinctBy { it.name }

    fun isEnabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        if (on) GuardService.start(c) else GuardService.stop(c)
    }

    fun limitMin(c: Context) = prefs(c).getInt("limit", 20)
    fun setLimitMin(c: Context, m: Int) = prefs(c).edit().putInt("limit", m).apply()

    fun watched(c: Context): Set<String> =
        prefs(c).getStringSet("apps", null) ?: setOf("com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.google.android.youtube", "com.facebook.katana", "com.twitter.android", "com.reddit.frontpage")
    fun setWatched(c: Context, s: Set<String>) = prefs(c).edit().putStringSet("apps", HashSet(s)).apply()

    fun messages(c: Context): List<String> {
        val raw = prefs(c).getString("msgs", null) ?: return defaultMessages
        return try { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { defaultMessages }
    }
    fun setMessages(c: Context, l: List<String>) = prefs(c).edit().putString("msgs", JSONArray(l).toString()).apply()

    /** Rotates through the messages so the same one doesn't repeat back to back. */
    fun nextMessage(c: Context): String {
        val l = messages(c).ifEmpty { defaultMessages }
        val i = prefs(c).getInt("msg_i", 0)
        prefs(c).edit().putInt("msg_i", (i + 1) % l.size).apply()
        return l[i % l.size]
    }

    fun appName(c: Context, pkg: String): String =
        knownApps.firstOrNull { it.pkg == pkg }?.name ?: try {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { "this app" }

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
                   else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
    fun canDrawOver(c: Context) = android.provider.Settings.canDrawOverlays(c)
}
