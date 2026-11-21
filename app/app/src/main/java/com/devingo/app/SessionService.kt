package com.devingo.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Keeps the process (and therefore the WebSocket to the bridge) alive while
 * a Devin session is open. The persistent notification doubles as the
 * Android Live Updates surface: [ACTION_UPDATE] rebuilds it from the latest
 * [LiveStatus] — working turns get a chronometer + indeterminate progress and
 * request promotion; idle/reconnecting states never promote.
 */
class SessionService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var idleRepost: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                idleRepost?.let(mainHandler::removeCallbacks)
                idleRepost = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                lastStatus = LiveStatus(
                    state = runCatching {
                        LiveStatus.State.valueOf(intent.getStringExtra(EXTRA_STATE).orEmpty())
                    }.getOrDefault(LiveStatus.State.Idle),
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "DevinGo" },
                    detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty(),
                    startedAtMillis = intent.getLongExtra(EXTRA_STARTED_AT, 0L),
                )
                scheduleIdleRepost()
            }
        }
        // null intent = START_STICKY restart: repost whatever we last knew.
        startForegroundWithType(buildNotification())
        return START_STICKY
    }

    /**
     * A finished card (Done/Failed) clears itself via setTimeoutAfter; when
     * that fires the FGS must still show something, so repost plain idle text.
     */
    private fun scheduleIdleRepost() {
        idleRepost?.let(mainHandler::removeCallbacks)
        idleRepost = null
        val s = lastStatus
        if (s != null && (s.state == LiveStatus.State.Done || s.state == LiveStatus.State.Failed)) {
            val r = Runnable {
                idleRepost = null
                lastStatus = LiveStatus(
                    state = LiveStatus.State.Idle,
                    title = s.title,
                    detail = "等待指令",
                    startedAtMillis = s.startedAtMillis,
                )
                startForegroundWithType(buildNotification())
            }
            idleRepost = r
            mainHandler.postDelayed(r, DONE_VISIBLE_MS)
        }
    }

    /** dataSync hit its daily quota (API 35+): stop cleanly, don't crash. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val status = lastStatus
            ?: LiveStatus(
                state = LiveStatus.State.Idle,
                title = fallbackTitle ?: "DevinGo",
                detail = "等待指令",
                startedAtMillis = 0L,
            )
        val builder = NotificationCompat.Builder(this, Notifier.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_devingo)
            .setContentTitle(notificationTitle(status))
            .setContentText(notificationText(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // Colorized notifications can't be promoted to Live Updates.
            .setColorized(false)
            .setContentIntent(openIntent(REQ_CONTENT))
            .addAction(0, "打开", openIntent(REQ_OPEN))

        if (status.state == LiveStatus.State.NeedsPermission) {
            builder
                .addAction(0, status.allowLabel, openIntent(REQ_ALLOW, EXTRA_ACTION_ALLOW))
                .addAction(0, status.denyLabel, openIntent(REQ_DENY, EXTRA_ACTION_DENY))
        }

        when (status.state) {
            LiveStatus.State.Working -> {
                builder
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setUsesChronometer(true)
                    .setWhen(
                        status.startedAtMillis.takeIf { it > 0 }
                            ?: System.currentTimeMillis(),
                    )
                    .setStyle(
                        NotificationCompat.ProgressStyle()
                            .setProgressIndeterminate(true),
                    )
                    .setShortCriticalText("工作中")
                    .setRequestPromotedOngoing(status.promoteOngoing)
            }
            LiveStatus.State.NeedsPermission -> {
                builder
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setShortCriticalText("待授权")
                    .setRequestPromotedOngoing(status.promoteOngoing)
            }
            LiveStatus.State.Reconnecting -> {
                builder
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setRequestPromotedOngoing(false)
            }
            LiveStatus.State.Done -> {
                builder
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setShortCriticalText("已完成")
                    .setRequestPromotedOngoing(status.promoteOngoing)
                    .setTimeoutAfter(DONE_VISIBLE_MS)
            }
            LiveStatus.State.Failed -> {
                builder
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setShortCriticalText("失败")
                    .setRequestPromotedOngoing(status.promoteOngoing)
                    .setTimeoutAfter(DONE_VISIBLE_MS)
            }
            LiveStatus.State.Idle -> {
                builder
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setRequestPromotedOngoing(false)
            }
        }
        return builder.build()
    }

    private fun notificationTitle(status: LiveStatus): String = when (status.state) {
        LiveStatus.State.Working -> "Devin 正在工作"
        LiveStatus.State.NeedsPermission -> "需要你的授权"
        LiveStatus.State.Reconnecting -> "正在重连…"
        LiveStatus.State.Done, LiveStatus.State.Failed -> status.title
        LiveStatus.State.Idle -> status.title
    }

    private fun notificationText(status: LiveStatus): String = when (status.state) {
        LiveStatus.State.Working -> status.detail.ifBlank { "处理中" }
        LiveStatus.State.NeedsPermission -> status.detail.ifBlank { "等待授权" }
        LiveStatus.State.Reconnecting -> "连接中断，正在重试"
        LiveStatus.State.Done, LiveStatus.State.Failed -> status.detail
        LiveStatus.State.Idle -> "等待指令"
    }

    private fun openIntent(requestCode: Int, action: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        if (action != null) intent.putExtra(EXTRA_NOTIFICATION_ACTION, action)
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        private const val ID = 1000
        const val ACTION_STOP = "com.devingo.app.STOP_SESSION_SERVICE"
        const val ACTION_UPDATE = "com.devingo.app.UPDATE_SESSION_SERVICE"
        const val EXTRA_NOTIFICATION_ACTION = "live_notification_action"
        const val EXTRA_ACTION_ALLOW = "allow"
        const val EXTRA_ACTION_DENY = "deny"
        private const val REQ_CONTENT = 0
        private const val REQ_OPEN = 1
        private const val REQ_ALLOW = 2
        private const val REQ_DENY = 3
        private const val DONE_VISIBLE_MS = 10 * 60 * 1000L
        private const val EXTRA_STATE = "live_state"
        private const val EXTRA_TITLE = "live_title"
        private const val EXTRA_DETAIL = "live_detail"
        private const val EXTRA_STARTED_AT = "live_started_at"
        private const val EXTRA_FALLBACK_TITLE = "title"

        /** Survives START_STICKY restarts while the process lives. */
        @Volatile private var lastStatus: LiveStatus? = null
        @Volatile private var fallbackTitle: String? = null

        fun start(context: Context, title: String?) {
            fallbackTitle = title
            val intent = Intent(context, SessionService::class.java)
                .putExtra(EXTRA_FALLBACK_TITLE, title)
            context.startForegroundService(intent)
        }

        fun update(context: Context, status: LiveStatus) {
            lastStatus = status
            context.startService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_UPDATE)
                    .putExtra(EXTRA_STATE, status.state.name)
                    .putExtra(EXTRA_TITLE, status.title)
                    .putExtra(EXTRA_DETAIL, status.detail)
                    .putExtra(EXTRA_STARTED_AT, status.startedAtMillis),
            )
        }

        fun stop(context: Context) {
            lastStatus = null
            context.startService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }
}
