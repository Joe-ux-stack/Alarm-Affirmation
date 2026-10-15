package com.whynotyou.alarm

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "Plan tomorrow": intention field + a glance at tomorrow's calendar. */
object PlanSheet {

    fun show(activity: Activity, onDone: () -> Unit) {
        val dialog = BottomSheetDialog(activity, R.style.SheetDark)
        val v = LayoutInflater.from(activity).inflate(R.layout.sheet_plan, null)
        dialog.setContentView(v)

        val input = v.findViewById<EditText>(R.id.edit_intention)
        val calendar = v.findViewById<TextView>(R.id.txt_calendar)
        val calendarLink = v.findViewById<TextView>(R.id.txt_calendar_link)
        input.setText(Brief.intention(activity))
        input.setSelection(input.text.length)

        if (CalendarSync.hasPermission(activity)) {
            val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            val events = CalendarSync.eventsFor(activity, tomorrow)
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            calendar.text = if (events.isEmpty()) "Nothing on the calendar" else
                events.joinToString("\n") { if (it.allDay) it.title else "${fmt.format(Date(it.startMillis)).lowercase()}  ${it.title}" }
            calendar.visibility = View.VISIBLE
            calendarLink.visibility = View.GONE
        } else {
            calendar.visibility = View.GONE
            calendarLink.visibility = View.VISIBLE
            calendarLink.setOnClickListener {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CALENDAR), 7)
                dialog.dismiss()
            }
        }

        v.findViewById<View>(R.id.btn_done).setOnClickListener {
            Brief.setIntention(activity, input.text.toString())
            dialog.dismiss()
        }
        dialog.setOnDismissListener { Brief.setIntention(activity, input.text.toString()); onDone() }
        dialog.show()
    }
}
