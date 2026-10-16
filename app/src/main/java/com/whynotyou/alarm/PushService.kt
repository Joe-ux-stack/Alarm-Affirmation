package com.whynotyou.alarm

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/** The server sends {type: "sync"} after the AI changes something; we sync straight away, even if the app is closed. */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        if (m.data["type"] == "sync") {
            // Runs on a background thread with ~10 s allowed: enough for one sync.
            val n = try { runBlocking { Sync.run(applicationContext) } } catch (_: Throwable) { -1 }
            if (n < 0) Push.fallback(applicationContext)
        }
    }
    override fun onNewToken(token: String) {
        Push.save(applicationContext, token)
        if (Account.signedIn(applicationContext)) Push.fallback(applicationContext)
    }
}

object Push {
    private fun p(c: Context) = c.getSharedPreferences("account", Context.MODE_PRIVATE)
    fun token(c: Context): String? = p(c).getString("push", null)
    fun save(c: Context, t: String) = p(c).edit().putString("push", t).apply()

    /** Ask Firebase for this phone's token (no-op if Firebase isn't set up in this build). */
    fun refresh(c: Context) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                if (t != null && t != token(c)) { save(c, t); if (Account.signedIn(c)) fallback(c) }
            }
        } catch (_: Throwable) {}
    }

    /** One-off sync as soon as there's a network. */
    fun fallback(c: Context) {
        val req = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
        androidx.work.WorkManager.getInstance(c).enqueueUniqueWork("sync_now", androidx.work.ExistingWorkPolicy.REPLACE, req)
    }
}
