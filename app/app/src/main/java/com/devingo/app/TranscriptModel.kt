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
    /** [work] as displayed: consecutive same-kind read-only tools grouped. */
    val workEntries: List<WorkEntry>,
    /** Failed tool calls — the WorkRow's "· N 个失败" badge. */
    val failed: Int,
) {
    /** Turn-level +A/−R over all work diffs; computed once on first render. */
    val diffStats: Pair<Int, Int> by lazy { workDiffStats(work) }
}

/**
 * One row of the expanded work log. [Step] is a single thought/tool call;
 * [ToolGroup] is ≥2 consecutive same-kind read-only tool calls collapsed
 * into one summary row (t3code's ThreadWorkGroupToggle — U5.3). Built in
 * [buildTurns] on the Default dispatcher, never in composition.
 */
@Immutable
sealed interface WorkEntry {
    @Immutable
    data class Step(val item: ChatItem) : WorkEntry

    @Immutable
    data class ToolGroup(
        /** Stable across rebuilds — anchored on the first member's id. */
        val key: String,
        val toolKind: String,
        val items: List<ChatItem>,
    ) : WorkEntry
}

/** ACP kinds that auto-group — read-only probes only (read/search/fetch). */
private val GROUPABLE_TOOL_KINDS = setOf("read", "search", "fetch")

/**
 * Collapse runs of ≥2 same-[GROUPABLE_TOOL_KINDS] tool calls into a
 * [WorkEntry.ToolGroup]; everything else stays a [WorkEntry.Step]. Pure O(n).
 */
internal fun groupWorkEntries(work: List<ChatItem>): List<WorkEntry> {
    val out = ArrayList<WorkEntry>(work.size)
    var i = 0
    while (i < work.size) {
        val item = work[i]
        val kind = item.toolKind
        if (item.kind == Kind.Tool && kind != null && kind in GROUPABLE_TOOL_KINDS) {
            var end = i + 1
            while (end < work.size && work[end].kind == Kind.Tool &&
                work[end].toolKind == kind
            ) {
                end++
            }
            if (end - i >= 2) {
                out += WorkEntry.ToolGroup(
                    key = "g${item.id}",
                    toolKind = kind,
                    items = work.subList(i, end).toList(),
                )
                i = end
                continue
            }
        }
        out += WorkEntry.Step(item)
        i++
    }
    return out
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
                groupWorkEntries(work),
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
    data class Body(val itemId: Long, val turnKey: Long) : RowKind
    data class User(val itemId: Long) : RowKind

    /**
     * A not-yet-sent queued prompt's dashed preview bubble (U5.4).
     * [index] resolves the prompt in the live `queued` list; [queuedId]
     * is the stable LazyColumn key.
     */
    data class Queue(val index: Int, val queuedId: Long) : RowKind
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
    /**
     * Each settled turn's last agent-text item — the only rows that show
     * the bottom meta bar (copy/fork/diff/timestamp). The live turn is
     * excluded so streaming stays bare markdown (U5.2).
     */
    val terminalAgentItemIds: Set<Long> = emptySet(),
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
    queued: List<QueuedPrompt> = emptyList(),
): TranscriptModel {
    val turns = buildTurns(messages)
    val rows = buildList {
        if (messages.isNotEmpty() || queued.isNotEmpty()) add(RowKind.Status)
        // U5.4: parked prompts preview directly above the status line —
        // exactly where the bubbles land as they drain, earliest first.
        queued.forEachIndexed { i, p -> add(RowKind.Queue(i, p.id)) }
        turns.asReversed().forEachIndexed { revIdx, turn ->
            val isLast = revIdx == 0
            if (turn.work.isNotEmpty() || (isLast && running)) {
                add(RowKind.Work(turn.key, isLast && running))
            }
            turn.body.asReversed().forEach { item ->
                // Thoughts and tools collapse into the turn's WorkRow —
                // they never render as inline body items.
                if (item.kind != Kind.Thought && item.kind != Kind.Tool) {
                    add(RowKind.Body(item.id, turn.key))
                }
            }
            turn.user?.let { add(RowKind.User(it.id)) }
        }
    }
    // t3code's terminalAssistantMessageIds: the last agent message of each
    // settled turn carries the meta bar; the live turn's rows stay bare.
    val terminalAgentIds = turns.mapIndexedNotNullTo(HashSet()) { i, turn ->
        if (running && i == turns.lastIndex) {
            null
        } else {
            turn.body.lastOrNull { it.kind == Kind.Agent }?.id
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
        terminalAgentItemIds = terminalAgentIds,
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
