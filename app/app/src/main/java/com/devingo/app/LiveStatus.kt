package com.devingo.app

import com.devingo.app.acp.PermissionOption

/**
 * Snapshot of what the persistent session notification should say. Derived
 * in MainActivity from the VM flows; [SessionService] renders it. Pure data
 * + mapper so the mapping stays testable and off the UI thread.
 */
data class LiveStatus(
    val state: State,
    val title: String,
    val detail: String,
    /** Turn start for the chronometer; 0 when no turn has run this launch. */
    val startedAtMillis: Long,
    /** Whether the liveUpdates pref asked for promotion (snapshot at push time). */
    val promoteOngoing: Boolean = false,
    /** Labels for the notification's permission actions. */
    val allowLabel: String = "允许",
    val denyLabel: String = "拒绝",
    /**
     * The session this status describes — its 允许/拒绝 actions must resolve
     * THIS session's pending request even if the user has since switched.
     */
    val sessionId: String? = null,
    /**
     * Identity of the pending request the lock-screen actions answer —
     * the wire requestKey plus its toolCallId cross-check, so a stale
     * PendingIntent can never resolve a different request by accident.
     */
    val permissionRequestId: Long = 0,
    val permissionToolCallId: String? = null,
    /**
     * Pending approvals across every live session. >1 swaps the 允许/拒绝
     * actions for a "N 个审批" count — tapping then opens the app instead.
     */
    val pendingCount: Int = 0,
) {
    enum class State { Working, NeedsPermission, Idle, Reconnecting, Done, Failed }
}

/**
 * Maps the live UI state onto a [LiveStatus]. Priority: a pending permission
 * beats everything (the user must act), then reconnect, then a running turn,
 * then idle. [runningDetail] comes precomputed with the transcript model so
 * this never scans the message list.
 */
fun deriveLiveStatus(
    ui: UiState,
    runningDetail: String?,
    pendingPermission: PendingPermission?,
    turnStartedAtMillis: Long,
    /** Set by the caller for a few minutes after a turn ends: Done or Failed. */
    finished: LiveStatus.State? = null,
    /** Pending approvals across all sessions (ConnectionManager.total). */
    pendingCount: Int = 0,
): LiveStatus {
    val title = ui.sessionTitle?.takeIf { it.isNotBlank() }
        ?: ui.agentTitle?.takeIf { it.isNotBlank() }
        ?: "DevinGo"

    pendingPermission?.let { p ->
        val toolTitle = p.toolCall.title?.takeIf { it.isNotBlank() }
            ?: p.toolCall.kind?.takeIf { it.isNotBlank() }
            ?: "权限请求"
        val allowOpt = p.options.firstOrNull { it.kind == "allow_once" }
        val denyOpt = p.options.firstOrNull { it.kind == "reject_once" }
            ?: p.options.firstOrNull { it.kind == "reject_always" }
        return LiveStatus(
            state = LiveStatus.State.NeedsPermission,
            title = title,
            detail = toolTitle,
            startedAtMillis = turnStartedAtMillis,
            allowLabel = allowOpt?.let(::permissionOptionLabel) ?: "允许",
            denyLabel = denyOpt?.let(::permissionOptionLabel) ?: "拒绝",
            sessionId = ui.sessionId,
            permissionRequestId = p.requestId,
            permissionToolCallId = p.toolCall.toolCallId,
            pendingCount = pendingCount,
        )
    }

    if (ui.reconnecting) {
        return LiveStatus(
            state = LiveStatus.State.Reconnecting,
            title = title,
            detail = "正在重连…",
            startedAtMillis = turnStartedAtMillis,
        )
    }

    if (ui.running) {
        // Latest in-progress tool wins; otherwise a recent thought means the
        // model is "thinking"; with neither, generic processing.
        return LiveStatus(
            state = LiveStatus.State.Working,
            title = title,
            detail = runningDetail ?: "处理中",
            startedAtMillis = turnStartedAtMillis,
        )
    }

    finished?.let {
        return LiveStatus(
            state = it,
            title = title,
            detail = if (it == LiveStatus.State.Failed) "本轮失败" else "本轮已完成",
            startedAtMillis = turnStartedAtMillis,
        )
    }

    return LiveStatus(
        state = LiveStatus.State.Idle,
        title = title,
        detail = "等待指令",
        startedAtMillis = turnStartedAtMillis,
    )
}

/** Chinese label for a permission option, mapped from its ACP kind. */
fun permissionOptionLabel(opt: PermissionOption): String = when (opt.kind) {
    "allow_once" -> "允许"
    "reject_once" -> "拒绝"
    "reject_always" -> "始终拒绝"
    "allow_always" -> "始终允许"
    else -> opt.name
}
