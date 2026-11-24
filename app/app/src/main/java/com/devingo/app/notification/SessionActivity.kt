package com.devingo.app.notification

import com.devingo.app.LiveStatus

/** A finished card (and a finished aggregate row) stays visible this long. */
internal const val DONE_VISIBLE_MS = 10 * 60 * 1000L

/**
 * One session's notification status, derived per SessionStore inside
 * `ConnectionManager.watchStore` — the aggregate card's row source. Every
 * subscribed store contributes an entry, including Idle ones (the hero
 * fallback when no session is doing anything).
 */
internal data class SessionStatus(
    val status: LiveStatus,
    /** The session's cwd basename — the row's " · project" label. */
    val project: String,
    /** When the status flipped Done/Failed; the row expires at +[DONE_VISIBLE_MS]. */
    val finishedAtMillis: Long = 0,
) {
    val phase: ActivityPhase get() = ActivityPhase.forState(status.state)
}

/**
 * Everything [com.devingo.app.SessionService] renders: the hero [LiveStatus]
 * (its session/request identity drives the actions and content intent) plus
 * the relay-shaped extras map — empty when fewer than two rows are live, so
 * the service falls back to the single-session card.
 */
internal data class ActivitySnapshot(
    val hero: LiveStatus,
    val extras: Map<String, String> = emptyMap(),
) {
    fun withPromote(ongoing: Boolean) = copy(hero = hero.copy(promoteOngoing = ongoing))
}

/**
 * Fold every store's [SessionStatus] into the card payload. Rows are the
 * sessions actually doing something — non-idle states, with a finished row
 * counting until its TTL runs out — ordered needs-user → working → waiting →
 * failed → done, hero first. [collapseFinishedFor] names the on-screen
 * session whose finished card is suppressed (the user watched it end).
 */
internal fun aggregateActivity(
    statuses: Map<String, SessionStatus>,
    currentSessionId: String?,
    pendingCount: Int,
    collapseFinishedFor: String? = null,
    now: Long = System.currentTimeMillis(),
): ActivitySnapshot {
    val rows = statuses.entries
        .filter { (sessionId, entry) -> entry.isRow(sessionId, collapseFinishedFor, now) }
        .sortedBy { rank(it.value.phase) }

    // No live rows: the hero is the bound session's status (idle included),
    // matching the single-session card the notification used to be.
    val heroStatus = (rows.firstOrNull()?.value
        ?: statuses[currentSessionId]
        ?: statuses.values.firstOrNull())
        ?.displayStatus(collapseFinishedFor, now)
        ?: LiveStatus(
            state = LiveStatus.State.Idle,
            title = "DevinGo",
            detail = "等待指令",
            startedAtMillis = 0L,
        )

    val extras = if (rows.size >= 2) {
        buildMap {
            rows.take(ACTIVITY_MAX_ROWS).forEachIndexed { i, entry ->
                put(
                    activityLineKey(i),
                    "${entry.value.phase.status}\t" +
                        "${entry.value.status.title.sanitize()}\t" +
                        entry.value.project.sanitize(),
                )
            }
            put(KEY_PHASE, rows.first().value.phase.key)
            put(KEY_ACTIVE, if (rows.any { !it.value.phase.finished }) "true" else "false")
            put(KEY_ACTIVE_COUNT, rows.count { !it.value.phase.finished }.toString())
            put(KEY_ATTENTION_COUNT, rows.count { it.value.phase.needsUser }.toString())
            put(KEY_FAILED_COUNT, rows.count { it.value.phase == ActivityPhase.FAILED }.toString())
            put(KEY_THREAD_COUNT, rows.size.toString())
        }
    } else {
        emptyMap()
    }
    return ActivitySnapshot(hero = heroStatus.copy(pendingCount = pendingCount), extras = extras)
}

/** A row exists while the session has something to say. */
private fun SessionStatus.isRow(
    sessionId: String,
    collapseFinishedFor: String?,
    now: Long,
): Boolean {
    if (status.state == LiveStatus.State.Idle) return false
    if (!phase.finished) return true
    // The on-screen session's finished card stays collapsed — the user
    // watched the turn end, a "已完成" card would only repeat it.
    if (sessionId == collapseFinishedFor) return false
    // Lazily-expired rows drop out of the aggregate even when the owning
    // store hasn't emitted since the TTL passed.
    return finishedAtMillis == 0L || now - finishedAtMillis < DONE_VISIBLE_MS
}

/**
 * The status used for the zero-row hero fallback: a finished status whose
 * card is collapsed or expired reports plain idle instead.
 */
private fun SessionStatus.displayStatus(
    collapseFinishedFor: String?,
    now: Long,
): LiveStatus {
    val expired = phase.finished &&
        (status.sessionId == collapseFinishedFor ||
            (finishedAtMillis > 0 && now - finishedAtMillis >= DONE_VISIBLE_MS))
    return if (expired) {
        status.copy(state = LiveStatus.State.Idle, detail = "等待指令")
    } else {
        status
    }
}

/** needs-user → working → waiting → failed → done; stable within a rank. */
private fun rank(phase: ActivityPhase): Int = when {
    phase.needsUser -> 0
    phase == ActivityPhase.STARTING || phase == ActivityPhase.RUNNING -> 1
    phase == ActivityPhase.STALE -> 2
    phase == ActivityPhase.FAILED -> 3
    else -> 4
}

/** Tab/newline would corrupt the "status\ttitle\tproject" line encoding. */
private fun String.sanitize() = replace('\t', ' ').replace('\n', ' ')
