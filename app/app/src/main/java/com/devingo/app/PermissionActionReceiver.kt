package com.devingo.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers the live notification's 允许/拒绝 in the background — no activity
 * trampoline (Android 12+ blocks those anyway). The tap resolves the exact
 * request it was posted for: [EXTRA_REQUEST_ID] names it, [EXTRA_TOOL_CALL_ID]
 * cross-checks it, and the store's atomic `choice.complete` refuses anything
 * already answered. A stale tap (answered elsewhere, withdrawn, or the store
 * is gone) never guesses — it just opens the app.
 */
class PermissionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(Notifier.EXTRA_SESSION_ID)
        val requestId = intent.getLongExtra(EXTRA_REQUEST_ID, 0L)
        val app = context.applicationContext as Application
        val cm = ConnectionManager.get(app)
        val store = sessionId?.let { cm.storeFor(it) }
        val answered = store?.resolvePermissionRequest(
            requestId = requestId,
            toolCallId = intent.getStringExtra(EXTRA_TOOL_CALL_ID),
            allow = intent.action == ACTION_ALLOW,
        ) == true
        if (!answered || store == null) {
            // Nothing matched — don't decide for the user. Best effort: a
            // broadcast can't always launch an activity from the background
            // (Android 10+ BAL), where the still-posted card is the way in.
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(Notifier.EXTRA_SESSION_ID, sessionId),
                )
            }
            return
        }
        // The resolved card must come down even when no activity lives to
        // push a fresh LiveStatus: rebuild once the request actually pops
        // off the queue head (the reply coroutine does that async).
        val pending = goAsync()
        cm.scope.launch {
            try {
                withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                    store.pendingPermission.first { it == null || it.requestId != requestId }
                }
                runCatching {
                    SessionService.update(app, liveStatus(context, cm, store))
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Rebuild the persistent card from the just-answered session's own
     * state. MainActivity's collector overwrites it moments later when it
     * is alive; when it isn't, this is the only refresh the card gets.
     */
    private fun liveStatus(
        context: Context,
        cm: ConnectionManager,
        store: SessionStore,
    ): LiveStatus {
        val sui = store.ui.value
        return deriveLiveStatus(
            ui = UiState(
                sessionId = store.sessionId,
                sessionTitle = sui.sessionTitle,
                running = sui.running,
                connected = cm.conn.value.connected,
                reconnecting = cm.conn.value.reconnecting,
            ),
            runningDetail = null,
            pendingPermission = store.pendingPermission.value,
            turnStartedAtMillis = SessionService.lastStatus
                ?.takeIf { it.sessionId == store.sessionId }
                ?.startedAtMillis ?: 0L,
            pendingCount = cm.pendingApprovalCount(),
        ).copy(promoteOngoing = Prefs(context).liveUpdates)
    }

    companion object {
        const val ACTION_ALLOW = "com.devingo.app.PERMISSION_ALLOW"
        const val ACTION_DENY = "com.devingo.app.PERMISSION_DENY"
        const val EXTRA_REQUEST_ID = "devingo.permission_request_id"
        const val EXTRA_TOOL_CALL_ID = "devingo.permission_tool_call_id"
        /** Bounded wait for the answered request to pop before rebuilding. */
        private const val REFRESH_TIMEOUT_MS = 2_000L
    }
}
