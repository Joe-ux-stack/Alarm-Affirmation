package com.whynotyou.alarm

import android.app.Activity
import android.content.Intent

/** Kept for old callers: the plan is now its own screen (design A). */
object PlanSheet {
    fun show(activity: Activity, onDone: () -> Unit) {
        activity.startActivity(Intent(activity, PlanActivity::class.java))
        onDone()
    }
}
