package com.whynotyou.alarm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Pre-rendered brief audio. For each enabled alarm with brief on, a worker runs ~6 h before the
 * trigger (and when the intention changes) and writes brief_<alarmId>.wav. AlarmActivity uses the
 * file if its text hash still matches what the brief would say now; otherwise it regenerates on
 * the spot if it can, else falls back to the phone's TTS.
 */
object BriefAudio {

    fun file(c: Context, alarmId: String) = File(c.filesDir, "brief_$alarmId.wav")
    private fun hashFile(c: Context, alarmId: String) = File(c.filesDir, "brief_$alarmId.hash")

    fun hash(text: String, voice: String): String =
        MessageDigest.getInstance("SHA-1").digest("$voice|$text".toByteArray()).joinToString("") { "%02x".format(it) }

    /** Cached WAV if present and still matching this text+voice, else null. */
    fun cached(c: Context, alarmId: String, text: String, voice: String): File? {
        val f = file(c, alarmId); val h = hashFile(c, alarmId)
        if (!f.exists() || !h.exists()) return null
        return if (h.readText() == hash(text, voice)) f else null
    }

    /** Synchronous render (used by the worker and as the at-fire-time fallback). */
    fun render(c: Context, alarmId: String, text: String, voice: String): File? {
        if (!VoiceEngine.isAvailable(c)) return null
        val f = file(c, alarmId)
        val tmp = File(c.filesDir, "brief_$alarmId.tmp")
        val ok = VoiceEngine.synthesizeToFile(c, text, voice, tmp)
        if (!ok) { tmp.delete(); return null }
        tmp.renameTo(f)
        hashFile(c, alarmId).writeText(hash(text, voice))
        return f
    }

    /** Line up render jobs for every enabled alarm with brief on. Idempotent. */
    fun scheduleAll(c: Context) {
        val wm = WorkManager.getInstance(c)
        val now = System.currentTimeMillis()
        for (a in AlarmStore.all(c)) {
            val name = "brief_${a.id}"
            if (!a.enabled || !a.brief) { wm.cancelUniqueWork(name); continue }
            val at = a.nextTrigger(now) ?: continue
            val delay = (at - LEAD_MS - now).coerceAtLeast(0L)
            val req = OneTimeWorkRequestBuilder<RenderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("alarmId" to a.id))
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
        }
    }

    /** Render now for every alarm whose brief is stale (called when the intention changes). */
    fun refreshNow(c: Context) {
        val wm = WorkManager.getInstance(c)
        for (a in AlarmStore.all(c)) {
            if (!a.enabled || !a.brief) continue
            val req = OneTimeWorkRequestBuilder<RenderWorker>().setInputData(workDataOf("alarmId" to a.id)).build()
            wm.enqueueUniqueWork("brief_now_${a.id}", ExistingWorkPolicy.REPLACE, req)
        }
    }

    private const val LEAD_MS = 6 * 60 * 60 * 1000L

    class RenderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
            val id = inputData.getString("alarmId") ?: return@withContext Result.failure()
            val alarm = AlarmStore.get(applicationContext, id) ?: return@withContext Result.success()
            CalendarSync.refreshToday(applicationContext)
            // Brief text for the alarm's trigger time, not for "now".
            val at = alarm.nextTrigger() ?: return@withContext Result.success()
            val text = Brief.compose(applicationContext, java.util.Date(at))
            if (text.isBlank()) return@withContext Result.success()
            if (cached(applicationContext, id, text, alarm.voice) != null) return@withContext Result.success()
            val f = render(applicationContext, id, text, alarm.voice)
            if (f != null) Result.success() else Result.retry()
        }
    }
}
