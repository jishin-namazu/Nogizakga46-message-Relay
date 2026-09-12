package com.nogirelay.app.blog

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.nogirelay.app.MainActivity
import com.nogirelay.app.R
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.notification.NotificationChannels

object BlogNotifier {
    const val EXTRA_BLOG_ID = "blog_id"

    fun show(context: Context, blog: BlogPost) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_BLOG_ID, blog.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            blog.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, NotificationChannels.BLOGS)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(blog.memberName)
            .setContentText("发布了新 BLOG：${blog.title}")
            .setStyle(Notification.BigTextStyle().bigText("发布了新 BLOG：${blog.title}"))
            .setCategory(Notification.CATEGORY_SOCIAL)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setColor(0xFF7A2A90.toInt())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(20_000 + (blog.id.hashCode() and 0x0FFF_FFFF), notification)
    }
}
