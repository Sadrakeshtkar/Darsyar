package com.darsyar.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "study_reminders"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "یادآوری مطالعه", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }.setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("زمان مطالعه رسیده!")
            .setContentText(intent.getStringExtra("subject") ?: "پارت مطالعاتی شما شروع شده است.")
            .setAutoCancel(true)
            .build()
        manager.notify(intent.getIntExtra("id", 1), notification)
    }
}
