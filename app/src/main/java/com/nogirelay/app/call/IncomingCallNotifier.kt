package com.nogirelay.app.call

import android.app.Notification
import android.app.NotificationManager
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.nogirelay.app.MainActivity
import com.nogirelay.app.R
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.notification.NotificationChannels
import com.nogirelay.app.translation.substituteNickname

object IncomingCallNotifier {
    const val EXTRA_MESSAGE_ID = "message_id"
    const val EXTRA_AUTO_ANSWER = "auto_answer"
    const val ACTION_ANSWER = "com.nogirelay.app.ANSWER_CALL"
    const val ACTION_DECLINE = "com.nogirelay.app.DECLINE_CALL"

    fun show(context: Context, message: RelayMessage) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(EXTRA_MESSAGE_ID, message.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val creatorOptions = ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
            }
            PendingIntent.getActivity(
                context,
                message.id.hashCode(),
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions.toBundle(),
            )
        } else {
            PendingIntent.getActivity(
                context,
                message.id.hashCode(),
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val answerIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_ANSWER
            putExtra(EXTRA_MESSAGE_ID, message.id)
        }
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_DECLINE
            putExtra(EXTRA_MESSAGE_ID, message.id)
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            context,
            message.id.hashCode() + 1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            message.id.hashCode() + 2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val displayName = substituteNickname(message.incomingCallFrom ?: message.memberName, AppGraph.settings.read().userNickname) ?: (message.incomingCallFrom ?: message.memberName)
        val builder = Notification.Builder(context, NotificationChannels.CALLS)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle(displayName)
            .setContentText("乃木坂46メッセージから着信中")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(0xFF7A2A90.toInt())
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder()
                .setName(displayName)
                .setImportant(true)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_person))
                .build()
            builder.setStyle(Notification.CallStyle.forIncomingCall(person, declinePendingIntent, answerPendingIntent))
        } else {
            builder.addAction(Notification.Action.Builder(null, "拒绝", declinePendingIntent).build())
            builder.addAction(Notification.Action.Builder(null, "接听", answerPendingIntent).build())
        }

        Log.d("NogiRelay", "Showing incoming call notification for ${message.id}, isAppInForeground=${isAppInForeground(context)}")
        notificationManager.notify(notificationId(message.id), builder.build())

        // Direct launch approach: if we have SYSTEM_ALERT_WINDOW, try direct start
        runCatching {
            if (isAppInForeground(context)) {
                Log.d("NogiRelay", "App in foreground, starting IncomingCallActivity directly")
                context.startActivity(fullScreenIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
                Log.d("NogiRelay", "App in background but has overlay permission, starting IncomingCallActivity directly")
                context.startActivity(fullScreenIntent)
            } else {
                Log.d("NogiRelay", "App in background, no overlay permission, relying on notification fullScreenIntent")
            }
        }.onFailure { error ->
            Log.w("NogiRelay", "Call activity launch failed", error)
        }
    }

    /** Shows a retryable notification without opening the call page prematurely. */
    fun showUnavailable(context: Context, message: RelayMessage, reason: String) {
        val retryIntent = Intent(context, IncomingCallPreparationService::class.java).apply {
            putExtra(EXTRA_MESSAGE_ID, message.id)
        }
        val pendingIntent = PendingIntent.getService(
            context,
            message.id.hashCode(),
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val displayName = substituteNickname(message.incomingCallFrom ?: message.memberName, AppGraph.settings.read().userNickname) ?: (message.incomingCallFrom ?: message.memberName)
        val notification = Notification.Builder(context, NotificationChannels.CALLS)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle(displayName)
            .setContentText(reason)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(message.id), notification)
    }

    fun showMessage(context: Context, message: RelayMessage) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_MESSAGE_ID, message.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            message.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = when (message.type) {
            com.nogirelay.app.data.MessageType.IMAGE -> "发来了一张图片"
            com.nogirelay.app.data.MessageType.AUDIO -> "发来了一条语音"
            com.nogirelay.app.data.MessageType.VIDEO -> "发来了一段视频"
            com.nogirelay.app.data.MessageType.TEXT -> message.text.orEmpty()
        }
        val displayName = substituteNickname(message.memberName, AppGraph.settings.read().userNickname) ?: message.memberName
        val notification = Notification.Builder(context, NotificationChannels.MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_message)
            .setContentTitle(displayName)
            .setContentText(label)
            .setStyle(Notification.BigTextStyle().bigText(label))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setColor(0xFF7A2A90.toInt())
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(message.id), notification)
    }

    fun cancel(context: Context, messageId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(messageId))
    }

    private fun isAppInForeground(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.runningAppProcesses
            ?.firstOrNull { it.processName == context.packageName }
            ?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun notificationId(messageId: String): Int = 10_000 + (messageId.hashCode() and 0x0FFF_FFFF)
}
