package com.devingo.app

import androidx.compose.runtime.Immutable

/**
 * The transcript's row model — turns, rows and lookups — built inside
 * [SessionStore] on a background dispatcher so the UI never re-derives it
 * on the main thread. [SessionStore.transcript] emits a new model only on
 * structural changes (items added, tool status flips, …); the still-growing
 * tail item's text rides [SessionStore.streamingTail] instead.
 */

/** One conversation turn: optional user bubble + ordered non-user body. */
@Immutable
data class Turn(
    val key: Long,
    val user: ChatItem?,
    val body: List<ChatItem>,
    /** Thought/Tool items collapsed under the WorkRow — never render inline. */
    val work: List<ChatItem>,
    /** Failed tool calls — the WorkRow's "· N 个失败" badge. */
    val failed: Int,
) {
    /** Turn-level +A/−R over all work diffs; computed once on first render. */
    val diffStats: Pair<Int, Int> by lazy { workDiffStats(work) }
}

/** Pure O(n) turn split at every Kind.User boundary. */
internal fun buildTurns(messages: List<ChatItem>): List<Turn> {
    val turns = mutableListOf<Turn>()
    var user: ChatItem? = null
    var body = mutableListOf<ChatItem>()
    fun flush() {
        if (user != null || body.isNotEmpty()) {
            val work =
                body.filter { it.kind == Kind.Thought || it.kind == Kind.Tool }
            turns += Turn(
                user?.id ?: body.firstOrNull()?.id ?: 0L,
                user,
                body.toList(),
                work,
                failedCount(work),
            )
        }
        body = mutableListOf()
    }
    for (m in messages) {
        if (m.kind == Kind.User) {
            flush()
            user = m
        } else {
            body += m
        }
    }
    flush()
    return turns
}

private fun failedCount(items: List<ChatItem>): Int =
    items.count { it.kind == Kind.Tool && it.status == "failed" }

/** Flat reversed lazy-row model: index 0 is the visual bottom. */
sealed interface RowKind {
    data object Status : RowKind
    data class Work(val turnKey: Long, val live: Boolean) : RowKind
    data class Body(val itemId: Long) : RowKind
    data class User(val itemId: Long) : RowKind
}

/**
 * Everything the transcript renders, precomputed off the main thread.
 * [rows] feeds the LazyColumn directly; [itemById]/[turnByKey] resolve it.
 */
@Immutable
data class TranscriptModel(
    val rows: List<RowKind> = emptyList(),
    val itemById: Map<Long, ChatItem> = emptyMap(),
    val turnByKey: Map<Long, Turn> = emptyMap(),
    val lastItemId: Long? = null,
    /** Newest user item — the scroll-to-bottom trigger. */
    val lastUserId: Long? = null,
    /** LiveStatus detail while running (tool title / "思考中"); null = generic. */
    val runningDetail: String? = null,
) {
    companion object {
        val EMPTY = TranscriptModel()
    }
}

/**
 * Rebuild the row model for [messages]. [running] decides whether the last
 * turn gets its live WorkRow — it must be a build input, not a UI-time
 * overlay, or a run ending wouldn't refresh the rows.
 */
internal fun buildTranscriptModel(
    messages: List<ChatItem>,
    running: Boolean,
): TranscriptModel {
    val turns = buildTurns(messages)
    val rows = buildList {
        if (messages.isNotEmpty()) add(RowKind.Status)
        turns.asReversed().forEachIndexed { revIdx, turn ->
            val isLast = revIdx == 0
            if (turn.work.isNotEmpty() || (isLast && running)) {
                add(RowKind.Work(turn.key, isLast && running))
            }
            turn.body.asReversed().forEach { item ->
                // Thoughts and tools collapse into the turn's WorkRow —
                // they never render as inline body items.
                if (item.kind != Kind.Thought && item.kind != Kind.Tool) {
                    add(RowKind.Body(item.id))
                }
            }
            turn.user?.let { add(RowKind.User(it.id)) }
        }
    }
    return TranscriptModel(
        rows = rows,
        itemById = messages.associateBy { it.id },
        turnByKey = turns.associateBy { it.key },
        lastItemId = messages.lastOrNull()?.id,
        lastUserId = messages.lastOrNull { it.kind == Kind.User }?.id,
        runningDetail = messages
            .lastOrNull { it.kind == Kind.Tool && it.status == "in_progress" }
            ?.text?.takeIf { it.isNotBlank() }
            ?: if (messages.lastOrNull { it.kind == Kind.Thought } != null) {
                "思考中"
            } else {
                null
            },
    )
}

// ---- diff stats (shared by WorkRow badges and DiffCard) --------------------

/** Multiset line difference → (added, removed); spec §B stats rule. */
internal fun diffStatsOf(diffs: List<ToolDiff>): Pair<Int, Int> {
    var added = 0
    var removed = 0
    diffs.forEach { d ->
        val oldLines = d.oldText?.lineSequence()?.toList().orEmpty()
        val newLines = d.newText.lineSequence().toList()
        val oldLeft = oldLines.groupingBy { it }.eachCount().toMutableMap()
        newLines.forEach { line ->
            val c = oldLeft[line] ?: 0
            if (c > 0) oldLeft[line] = c - 1 else added++
        }
        val newLeft = newLines.groupingBy { it }.eachCount().toMutableMap()
        oldLines.forEach { line ->
            val c = newLeft[line] ?: 0
            if (c > 0) newLeft[line] = c - 1 else removed++
        }
    }
    return added to removed
}

/** Turn-level +A/−R shown on the WorkRow header. */
internal fun workDiffStats(items: List<ChatItem>): Pair<Int, Int> =
    diffStatsOf(items.flatMap { it.diffs })
