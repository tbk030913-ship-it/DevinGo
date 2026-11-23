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

    /**
     * The application-level ACP connection owner. Getting it here pins the
     * singleton to the keep-alive service's lifetime — service restarts reuse
     * the same manager/socket instead of spinning up a duplicate.
     */
    private lateinit var connectionManager: ConnectionManager

    override fun onCreate() {
        super.onCreate()
        connectionManager = ConnectionManager.get(application)
    }

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
                    promoteOngoing = intent.getBooleanExtra(EXTRA_PROMOTE, false),
                    allowLabel = intent.getStringExtra(EXTRA_ALLOW_LABEL) ?: "允许",
                    denyLabel = intent.getStringExtra(EXTRA_DENY_LABEL) ?: "拒绝",
                    sessionId = intent.getStringExtra(Notifier.EXTRA_SESSION_ID),
                    permissionRequestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L),
                    permissionToolCallId = intent.getStringExtra(EXTRA_TOOL_CALL_ID),
                    pendingCount = intent.getIntExtra(EXTRA_PENDING_COUNT, 0),
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

        // Broadcast actions answer the exact request in the background —
        // but only when it is THE pending approval; with several waiting the
        // card shows the count and tapping opens the app to handle them.
        if (status.state == LiveStatus.State.NeedsPermission && status.pendingCount <= 1) {
            builder
                .addAction(
                    0, status.allowLabel,
                    broadcastIntent(requestKey(status) * 2, PermissionActionReceiver.ACTION_ALLOW, status),
                )
                .addAction(
                    0, status.denyLabel,
                    broadcastIntent(requestKey(status) * 2 + 1, PermissionActionReceiver.ACTION_DENY, status),
                )
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
        LiveStatus.State.NeedsPermission -> if (status.pendingCount > 1) {
            "${status.pendingCount} 个审批待处理"
        } else {
            status.detail.ifBlank { "等待授权" }
        }
        LiveStatus.State.Reconnecting -> "连接中断，正在重试"
        LiveStatus.State.Done, LiveStatus.State.Failed -> status.detail
        LiveStatus.State.Idle -> "等待指令"
    }

    private fun openIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this, requestCode,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * The permission actions fire a broadcast, not the activity: the
     * receiver answers the exact (sessionId, requestId, toolCallId) request
     * in the background and refreshes this card itself.
     */
    /**
     * Per-request PendingIntent identity: a stale rendered card keeps ITS
     * request's extras instead of having them overwritten by the next
     * buildNotification — a tap then resolves nothing and opens the app.
     * (Extras don't count toward PendingIntent identity; requestCode does.)
     */
    private fun requestKey(status: LiveStatus): Int =
        31 * (status.sessionId?.hashCode() ?: 0) + (status.permissionRequestId?.hashCode() ?: 0)

    private fun broadcastIntent(
        requestCode: Int,
        action: String,
        status: LiveStatus,
    ): PendingIntent = PendingIntent.getBroadcast(
        this, requestCode,
        Intent(this, PermissionActionReceiver::class.java)
            .setAction(action)
            .putExtra(Notifier.EXTRA_SESSION_ID, status.sessionId)
            .putExtra(PermissionActionReceiver.EXTRA_REQUEST_ID, status.permissionRequestId)
            .putExtra(PermissionActionReceiver.EXTRA_TOOL_CALL_ID, status.permissionToolCallId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

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
        private const val EXTRA_PROMOTE = "live_promote"
        private const val EXTRA_ALLOW_LABEL = "live_allow_label"
        private const val EXTRA_DENY_LABEL = "live_deny_label"
        private const val EXTRA_FALLBACK_TITLE = "title"
        private const val EXTRA_REQUEST_ID = "live_permission_request_id"
        private const val EXTRA_TOOL_CALL_ID = "live_permission_tool_call_id"
        private const val EXTRA_PENDING_COUNT = "live_pending_count"

        /** Survives START_STICKY restarts while the process lives. Read by
         *  [PermissionActionReceiver] to keep the chronometer continuous. */
        @Volatile internal var lastStatus: LiveStatus? = null
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
                    .putExtra(EXTRA_STARTED_AT, status.startedAtMillis)
                    .putExtra(EXTRA_PROMOTE, status.promoteOngoing)
                    .putExtra(EXTRA_ALLOW_LABEL, status.allowLabel)
                    .putExtra(EXTRA_DENY_LABEL, status.denyLabel)
                    .putExtra(Notifier.EXTRA_SESSION_ID, status.sessionId)
                    .putExtra(EXTRA_REQUEST_ID, status.permissionRequestId)
                    .putExtra(EXTRA_TOOL_CALL_ID, status.permissionToolCallId)
                    .putExtra(EXTRA_PENDING_COUNT, status.pendingCount),
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
