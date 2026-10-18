package com.whynotyou.alarm

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** The Why Not You account: Google sign-in, talking to the server, and syncing with the user's AI. */
object Account {
    const val SERVER = "https://mcp.getwhynotyou.com"
    private fun p(c: Context) = c.getSharedPreferences("account", Context.MODE_PRIVATE)

    fun signedIn(c: Context) = token(c) != null
    fun email(c: Context): String? = p(c).getString("email", null)
    private fun token(c: Context): String? = p(c).getString("token", null)
    fun serverPro(c: Context) = signedIn(c) && p(c).getBoolean("pro", false)
    fun lastSync(c: Context) = p(c).getLong("last_sync", 0L)
    /** True if this plan text is exactly what the AI last wrote (the user hasn't edited it since). */
    fun planFromAi(c: Context, plan: String) = plan.isNotBlank() && p(c).getString("plan_ai", null) == plan
    internal fun setPlanFromAi(c: Context, plan: String) = p(c).edit().putString("plan_ai", plan).apply()
    /** What the AI did recently, newest first. */
    fun actions(c: Context): List<String> = try {
        val a = JSONArray(p(c).getString("actions", "[]")); (0 until a.length()).map { a.getJSONObject(it).optString("summary") }
    } catch (_: Throwable) { emptyList() }

    /** Shows Google's sign-in sheet, then logs in to our server. Returns null on success, else a short error. */
    suspend fun signIn(a: Activity): String? {
        if (BuildConfig.WEB_CLIENT_ID.isBlank()) return "Sign-in isn't set up in this build yet."
        val idToken = try {
            val req = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()).build()
            val cred = CredentialManager.create(a).getCredential(a, req).credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                GoogleIdTokenCredential.createFrom(cred.data).idToken else return "Google didn't return an account."
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) { return "" }
        catch (e: Throwable) { return "Couldn't sign in: ${e.message ?: e.javaClass.simpleName}" }
        val r = call(a, "POST", "/api/login", JSONObject().put("idToken", idToken), auth = false) ?: return "Couldn't reach the server."
        if (!r.has("token")) return "The server didn't accept the sign-in."
        p(a).edit().putString("token", r.getString("token")).putString("email", r.optString("email")).putBoolean("pro", r.optBoolean("pro")).apply()
        Sync.schedule(a)
        Push.refresh(a)
        Sync.run(a)
        return null
    }

    fun signOut(c: Context) {
        val push = Push.token(c)
        p(c).edit().clear().apply { if (push != null) putString("push", push) }.apply()
        WorkManager.getInstance(c).cancelUniqueWork(Sync.WORK)
    }

    /** Deletes everything the server holds for this person, then signs out. */
    suspend fun delete(c: Context): Boolean {
        val r = call(c, "DELETE", "/api/account", null) ?: return false
        if (!r.optBoolean("deleted")) return false
        signOut(c); return true
    }

    internal fun saveSync(c: Context, pro: Boolean, actions: JSONArray?) {
        p(c).edit().putBoolean("pro", pro).putLong("last_sync", System.currentTimeMillis())
            .apply { if (actions != null) putString("actions", actions.toString()) }.apply()
    }

    /** JSON request to our server. Null if offline/failed. Signs out on 401. */
    internal suspend fun call(c: Context, method: String, path: String, body: JSONObject?, auth: Boolean = true): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(SERVER + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method; connectTimeout = 15000; readTimeout = 20000
                setRequestProperty("content-type", "application/json")
                if (auth) token(c)?.let { setRequestProperty("authorization", "Bearer $it") }
                if (body != null) { doOutput = true; outputStream.use { it.write(body.toString().toByteArray()) } }
            }
            val code = conn.responseCode
            if (code == 401 && auth) { signOut(c); return@withContext null }
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) null else JSONObject(text.ifBlank { "{}" })
        } catch (_: Throwable) { null }
    }
}

/** Sends the phone's state up, and applies what the AI changed. Runs on open and every 15 minutes. */
object Sync {
    const val WORK = "sync"
    private val lock = Mutex()

    fun schedule(c: Context) {
        if (!Account.signedIn(c)) return
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Returns how many AI changes were applied, or -1 if it couldn't sync. */
    suspend fun run(c: Context): Int = lock.withLock {
        if (!Account.signedIn(c)) return -1
        withContext(Dispatchers.IO) { Push.ensure(c) }
        var r = Account.call(c, "POST", "/api/sync", state(c)) ?: return -1
        val changes = r.optJSONArray("changes") ?: JSONArray()
        val applied = JSONArray()
        withContext(Dispatchers.Main) {
            for (i in 0 until changes.length()) {
                val ch = changes.getJSONObject(i)
                try { apply(c, ch.getString("kind"), ch.getJSONObject("payload")) } catch (_: Throwable) {}
                applied.put(ch.getLong("id")) // mark as handled even if it couldn't apply, so it doesn't repeat forever
            }
        }
        if (applied.length() > 0) r = Account.call(c, "POST", "/api/sync", state(c).put("applied", applied)) ?: r
        Account.saveSync(c, r.optBoolean("pro"), r.optJSONArray("actions"))
        applied.length()
    }

    private fun state(c: Context): JSONObject {
        val alarms = JSONArray().also { a -> AlarmStore.all(c).forEach { a.put(it.toJson()) } }
        val tomorrow = JSONArray().also { a ->
            val f = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.US)
            Brief.cachedEvents(c).forEach { e -> a.put(JSONObject().put("time", if (e.allDay) "all day" else f.format(java.util.Date(e.startMillis))).put("title", e.title)) }
        }
        val o = JSONObject().apply { Push.token(c)?.let { put("push", it) } }.put("alarms", alarms).put("plan", Brief.intention(c)).put("tomorrow", tomorrow)
            .put("limits", JSONObject().put("on", PhoneDown.isEnabled(c)).put("minutes", PhoneDown.limitMin(c)).put("apps", JSONArray(PhoneDown.watchedNames(c))))
        usage(c)?.let { o.put("usage", it) }
        return o
    }

    /** Minutes per watched app per day, last 7 days. */
    private fun usage(c: Context): JSONObject? = try {
        if (!PhoneDown.hasUsageAccess(c)) null else {
            val out = JSONObject(); val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
            val pkgs = PhoneDown.watched(c)
            for (i in 0 until 7) {
                val from = cal.timeInMillis; val to = if (i == 0) System.currentTimeMillis() else from + 86_400_000L
                val d = JSONObject(); pkgs.forEach { pk -> d.put(PhoneDown.appName(c, pk), PhoneDown.minutesIn(c, from, to, setOf(pk))) }
                out.put(day.format(java.util.Date(from)), d)
                cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            }
            out
        }
    } catch (_: Throwable) { null }

    private fun apply(c: Context, kind: String, p: JSONObject) {
        when (kind) {
            "set_plan" -> { Brief.setIntention(c, p.optString("plan")); Account.setPlanFromAi(c, p.optString("plan")); if (p.optString("plan").isNotBlank()) Mornings.recordPlan(c) }
            "create_alarm" -> {
                val d = p.optJSONArray("days")
                AlarmStore.save(c, Alarm(p.getString("id"), p.getInt("h"), p.getInt("m"), BooleanArray(7) { d?.optBoolean(it) ?: false }, p.optBoolean("on", true)).apply { label = p.optString("label", "") })
            }
            "update_alarm" -> AlarmStore.get(c, p.getString("id"))?.let { a ->
                if (p.has("h")) { a.hour = p.getInt("h"); a.minute = p.getInt("m") }
                p.optJSONArray("days")?.let { d -> a.days = BooleanArray(7) { d.optBoolean(it) } }
                if (p.has("on")) a.enabled = p.getBoolean("on")
                if (p.has("label")) a.label = p.optString("label", "")
                AlarmStore.save(c, a)
            }
            "delete_alarm" -> if (AlarmStore.get(c, p.getString("id")) != null) AlarmStore.delete(c, p.getString("id"))
            "set_app_limit" -> {
                val name = p.optString("app").trim().lowercase(); val min = p.optInt("minutes")
                val pkgs = PhoneDown.knownApps.filter { it.name.lowercase() == name || (name == "twitter" && it.name == "X") }.map { it.pkg }.toSet()
                if (pkgs.isEmpty()) return
                if (min > 0) {
                    PhoneDown.setWatched(c, PhoneDown.watched(c) + pkgs)
                    PhoneDown.setLimitMin(c, PhoneDown.limits.minByOrNull { kotlin.math.abs(it - min) } ?: min)
                } else PhoneDown.setWatched(c, PhoneDown.watched(c) - pkgs)
            }
        }
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = if (Sync.run(applicationContext) >= 0) Result.success() else Result.retry()
}
