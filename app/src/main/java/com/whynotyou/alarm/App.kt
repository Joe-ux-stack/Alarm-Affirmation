package com.whynotyou.alarm

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Writes the last crash to a file so it can be shown in the app and pasted into a bug report. */
class App : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        try { Sync.schedule(this); Push.refresh(this) } catch (_: Throwable) {}
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText("Thread: ${t.name}\n$sw")
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        try { EveningPlan.schedule(this) } catch (_: Throwable) {}
        try { Billing.start(this) } catch (_: Throwable) {}
    }
    companion object {
        fun lastCrash(ctx: android.content.Context): String? = File(ctx.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()
        fun clearCrash(ctx: android.content.Context) { File(ctx.filesDir, "last_crash.txt").delete() }
    }
}
