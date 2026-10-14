package com.whynotyou.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** One place to ask "is the phone set up so the alarm can actually fire?" */
object Permissions {

    fun notifications(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun exactAlarms(c: Context) = AlarmScheduler.canScheduleExact(c)

    fun battery(c: Context) =
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    fun overlay(c: Context) = Settings.canDrawOverlays(c)

    fun allGranted(c: Context) = notifications(c) && exactAlarms(c) && battery(c) && overlay(c)

    fun missingCount(c: Context) =
        listOf(notifications(c), exactAlarms(c), battery(c), overlay(c)).count { !it }
}
