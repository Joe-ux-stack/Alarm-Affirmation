package com.whynotyou.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms don't survive a reboot or an app update; this re-registers the stored one. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.restore(context)
    }
}
