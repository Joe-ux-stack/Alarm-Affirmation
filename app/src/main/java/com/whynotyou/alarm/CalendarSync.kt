package com.whynotyou.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Reads today's timed events from the phone's own calendar provider (covers Google, Samsung,
 * Outlook — whatever accounts are synced to the device). No OAuth needed.
 */
object CalendarSync {

    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events for the given day, timed only, not declined, not all-day. */
    fun eventsFor(c: Context, day: Calendar = Calendar.getInstance()): List<BriefItem> {
        if (!hasPermission(c) || !Pro.isPro(c)) return emptyList()

        val start = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = start + 24 * 60 * 60 * 1000L

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString()).appendPath(end.toString()).build()
        val proj = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.VISIBLE,
            CalendarContract.Instances.CALENDAR_ID
        )
        val out = mutableListOf<BriefItem>()
        try {
            c.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                while (cur.moveToNext()) {
                    val allDay = cur.getInt(2) == 1
                    val status = cur.getInt(3)
                    val visible = cur.getInt(4) == 1
                    if (!visible) continue
                    if (!CalendarPrefs.isOn(c, cur.getLong(5))) continue
                    if (status == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val title = cur.getString(1)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    out += BriefItem(cur.getLong(0), title, allDay)
                }
            }
        } catch (e: Exception) { /* provider hiccup: brief just says nothing on the calendar */ }
        return out.distinctBy { it.startMillis to it.title }
    }

    /** Cache today's events for the brief. Called when the alarm fires and when the app opens. */
    fun refreshToday(c: Context) {
        if (!hasPermission(c)) return
        Brief.storeEvents(c, eventsFor(c))   // empty without Pro
    }
}
