package com.devingo.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Local notifications for events the user may miss while the app is
 * backgrounded. Three channels: permission requests (high importance),
 * finished turns, and the persistent session/Live Updates notification
 * posted by [SessionService]. Posts are gated on the Prefs switches and on
 * "仅在后台时提醒" at post time.
 */
class Notifier(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val manager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PERMISSION,
                    "权限请求",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Devin 等你授权时提醒" },
            )
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TURN,
                    "本轮完成",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "一轮对话结束时提醒" },
            )
            // LOW still qualifies for Live Updates promotion and stays silent.
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SESSION,
                    "会话状态与实时活动",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "会话保持连接与实时进度" },
            )
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Shared gate: "仅在后台时提醒" suppresses posts only while the app is
     * resumed AND this session's chat is on top — alerts still fire from
     * Home or while the user is watching a different session.
     */
    private fun shouldPost(sessionId: String?): Boolean =
        !prefs.notifyOnlyInBackground ||
            !(AppVisibility.isForeground && AppVisibility.chatVisible &&
                AppVisibility.chatSessionId == sessionId)

    /** The agent is blocked on a permission decision (or a question). */
    fun notifyPermissionPending(
        title: String,
        sessionId: String? = null,
        headline: String = "DevinGo 需要授权",
    ) {
        if (!prefs.notifyPermission || !shouldPost(sessionId)) return
        post(
            channelId = CHANNEL_PERMISSION,
            id = ID_PERMISSION,
            title = headline,
            text = title.ifBlank { "Devin 请求执行操作" },
            priority = NotificationCompat.PRIORITY_HIGH,
            sessionId = sessionId,
        )
    }

    /** A prompt turn finished while the user may not be watching. */
    fun notifyTurnDone(title: String, sessionId: String? = null) {
        if (!prefs.notifyTurnDone || !shouldPost(sessionId)) return
        post(
            channelId = CHANNEL_TURN,
            id = ID_TURN_DONE,
            title = title.ifBlank { "DevinGo" },
            text = "本轮已完成",
            priority = NotificationCompat.PRIORITY_DEFAULT,
            sessionId = sessionId,
        )
    }

    private fun post(
        channelId: String,
        id: Int,
        title: String,
        text: String,
        priority: Int,
        sessionId: String? = null,
    ) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_devingo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(priority)
            .setContentIntent(contentIntent(sessionId))
            .build()
        runCatching { manager?.notify(id, notification) }
    }

    /** Tap → open the session the alert came from. */
    private fun contentIntent(sessionId: String?): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).apply {
            if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Runtime request for POST_NOTIFICATIONS (Android 13+). Manifest declares
     * the permission already; the Settings page is the canonical entry point.
     */
    fun ensurePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canPost()) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS,
            )
        }
    }

    companion object {
        const val CHANNEL_PERMISSION = "devingo.permission"
        const val CHANNEL_TURN = "devingo.turn"
        const val CHANNEL_SESSION = "devingo.session"
        /** Intent extra carrying the sessionId a notification belongs to. */
        const val EXTRA_SESSION_ID = "devingo.sessionId"
        const val REQ_POST_NOTIFICATIONS = 4201
        private const val ID_PERMISSION = 1001
        private const val ID_TURN_DONE = 1002
    }
}
