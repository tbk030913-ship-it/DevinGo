package com.devingo.app

import androidx.compose.runtime.Immutable
import com.devingo.app.acp.AcpException
import com.devingo.app.acp.AvailableCommand
import com.devingo.app.acp.ConfigOption
import com.devingo.app.acp.PermissionOption
import com.devingo.app.acp.PlanEntry
import com.devingo.app.acp.SessionMode
import com.devingo.app.acp.SessionModel
import com.devingo.app.acp.ToolCallInfo
import com.devingo.app.acp.UserInputQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

/**
 * Shared transcript/session types. They live outside [ChatViewModel] because
 * the state they describe belongs to [SessionStore] — one per session — and
 * the ViewModel only flattens the currently bound store for the UI.
 */

/** One-line user-facing form of a thrown connection/protocol error. */
internal fun Throwable.display(): String = when (this) {
    is AcpException -> message ?: "ACP 错误 $code"
    is java.net.SocketTimeoutException -> "连接超时"
    else -> message ?: javaClass.simpleName
}

/** Connection/session pipeline phase — the view contract's progress vocabulary. */
enum class Phase { Idle, Checking, Connecting, Authenticating, StartingSession, Ready, Error }

/**
 * The merged UI state the screens read: connection-level fields come from
 * [ConnectionManager]'s [ConnState], session-scoped fields from the bound
 * [SessionStore]'s [SessionUi]. [ChatViewModel] flattens them into this one
 * shape so the UI stays unchanged.
 */
data class UiState(
    val phase: Phase = Phase.Idle,
    val statusText: String = "",
    val error: String? = null,
    val agentTitle: String? = null,
    val sessionId: String? = null,
    val modes: List<SessionMode> = emptyList(),
    val currentModeId: String? = null,
    val models: List<SessionModel> = emptyList(),
    val currentModelId: String? = null,
    val commands: List<AvailableCommand> = emptyList(),
    val sessionTitle: String? = null,
    /** Context window usage: [contextUsed] of [contextSize] tokens. */
    val contextUsed: Long = 0,
    val contextSize: Long = 0,
    /** Cumulative turn token accounting from `usage_update` (if the agent reports it). */
    val usageInput: Long = 0,
    val usageOutput: Long = 0,
    val usageCacheRead: Long = 0,
    val running: Boolean = false,
    /** True while `authenticate` is in flight — the browser is waiting on the PC. */
    val awaitingBrowserLogin: Boolean = false,
    /** True while the auto-reconnect loop is retrying a dropped socket. */
    val reconnecting: Boolean = false,
    /**
     * Wall-clock when the reconnect loop's next attempt fires — the floating
     * capsule's countdown source; null while dialing (or not reconnecting).
     */
    val reconnectRetryAtMs: Long? = null,
    /** The shared socket is up — sessions may be streaming off-screen. */
    val connected: Boolean = false,
    /** The bound store is subscribed — false mid-attach or after a failed
     *  attach on a still-live socket (the chat looks dead but isn't down). */
    val attached: Boolean = false,
    /** configOptions entries outside mode/model (effort, thinking, …). */
    val extraConfigOptions: List<ConfigOption> = emptyList(),
    /**
     * Negotiated at handshake from `agentCapabilities._meta` (A7.1); a
     * `_cognition.ai/revert/…` call answering method_not_found still flips
     * it off and the menu entry hides.
     */
    val revertSupported: Boolean = false,
    /**
     * Same negotiate-then-probe contract as [revertSupported] but for the
     * `_cognition.ai/subagent/…` control surface (A7.4) — gates the cards'
     * 切后台/切前台/停止 chips.
     */
    val subagentSupported: Boolean = false,
    /**
     * Bridge `_devingo/hello` advertised the W8.3 spawn-profile surface
     * (`_meta` agentType/refusalFallback on session/new|load). False only
     * when hello answered without the capability — an old bridge.
     */
    val agentTypesSupported: Boolean = true,
)

enum class Kind {
    User, Agent, Thought, Tool, Plan, System, Error, Answered,
    /**
     * `_cognition.ai/compaction` divider (A7.6): `text` = label,
     * `status` = in_progress/completed/failed, `detail` = the formatted
     * "128k → 40k" counts when the wire reported them.
     */
    Compaction,
    /**
     * A shell-manager command echo (A7.6): `text` = `$ cmd`, `toolCallId`
     * binds the terminalId once commandStarted names it; `exitCode`/`detail`
     * carry the completion's code/output tail.
     */
    ShellCommand,
    /**
     * Quota/rate-limit turn failure (M9.2): `stopReason=quota_exhausted` or a
     * quota-class prompt error. `text` = headline, `detail` = the wire's raw
     * reason, `resetAtMs` = the reported reset instant when one rode the
     * frame; `status` = "retried" once the card's 重试 fired.
     */
    UsageLimit,
}

@Immutable
data class ChatItem(
    val id: Long,
    val kind: Kind,
    val text: String,
    val status: String? = null,
    val toolCallId: String? = null,
    /** ACP tool kind (execute/read/edit/…) — drives the timeline node icon. */
    val toolKind: String? = null,
    /** Expandable detail for tool calls: output text, diff paths, etc. */
    val detail: String? = null,
    /** The head of [detail] was dropped (command output keeps its tail). */
    val detailTruncated: Boolean = false,
    /** `_meta.terminal_exit.exit_code` on exec tool updates — non-zero fails. */
    val exitCode: Int? = null,
    /** File diffs reported inside the tool call's content blocks. */
    val diffs: List<ToolDiff> = emptyList(),
    val planEntries: List<PlanEntry> = emptyList(),
    /** True when the prompt never reached the agent (send threw before any update). */
    val failed: Boolean = false,
    /**
     * The user prompt's image bytes — only a [failed] echo needs them
     * (重发 source); SessionStore clears them once the prompt is delivered.
     */
    val attachments: List<ImageAttachment> = emptyList(),
    /** Images sent with this prompt — badge source once bytes are cleared. */
    val attachmentCount: Int = 0,
    /** Local wall-clock at creation — the fresh-entry animation window. */
    val createdAtMs: Long = System.currentTimeMillis(),
    /** Replayed history (session/load, attach backlog, cache) — never animates entry. */
    val isReplay: Boolean = false,
    /**
     * This prompt interrupted a running turn (steer/midTurnPrompts) — the
     * bubble gets a "↪ 已转向" corner badge (U5.4).
     */
    val steered: Boolean = false,
    /**
     * Usage-limit reset instant (epoch ms) reported on the quota frame —
     * the [Kind.UsageLimit] card's "将于 X 重置" line; not cache-persisted.
     */
    val resetAtMs: Long? = null,
)

/** One `diff` content item of a tool call: unified old/new file text. */
data class ToolDiff(val path: String, val oldText: String?, val newText: String)

/**
 * A file's merged change for the review sheet (N6.1): every tool diff of a
 * path collapses to its first [oldText]/latest [newText]; counts are the
 * multiset line stats the diff pills already show.
 */
@Immutable
data class SessionFileDiff(
    val path: String,
    val oldText: String?,
    val newText: String,
    val additions: Int,
    val deletions: Int,
)

/**
 * All tool diffs of a session, aggregated for the review sheet. [files]
 * merges every turn's diffs by path ("本会话工具变更"); [byTurn] keeps the
 * same merge per turn ("本轮变更" entries).
 */
@Immutable
data class SessionDiffs(
    val files: List<SessionFileDiff> = emptyList(),
    /** turnKey → that turn's merged files; only turns with diffs appear. */
    val byTurn: Map<Long, List<SessionFileDiff>> = emptyMap(),
    /** turnKeys with diffs, oldest → newest. */
    val turnOrder: List<Long> = emptyList(),
    val additions: Int = 0,
    val deletions: Int = 0,
) {
    val latestTurnKey: Long? get() = turnOrder.lastOrNull()

    companion object {
        val EMPTY = SessionDiffs()
    }
}

/**
 * A permission request the user (or a peer client) can still answer.
 * [requestId] is the wire id the gateway's `_devingo/permission_resolved`
 * broadcast references.
 */
@Immutable
data class PendingPermission(
    val toolCall: ToolCallInfo,
    val options: List<PermissionOption>,
    internal val choice: CompletableDeferred<String?>,
    internal val requestId: Long = -1,
)

/**
 * An `elicitation/create` request the user (or a peer client) can still
 * answer — Devin asked questions via the question card (A7.2). [choice]
 * completes with the card's reply (null = dismissed/withdrawn).
 */
@Immutable
data class PendingElicitation(
    val message: String,
    val questions: List<UserInputQuestion>,
    internal val choice: CompletableDeferred<ElicitationReply?>,
    internal val requestId: Long = -1,
)

/** The question card's submit — wire `content` plus the WorkLog history lines. */
data class ElicitationReply(
    val content: JsonObject,
    /** "question → answer" lines recorded into the turn's WorkLog. */
    val lines: List<String>,
)

/**
 * A secret ask the user can still answer (A7.6 — `_cognition.ai/secret/create`
 * request, or a notification/`request_secret` update surfaced the same way).
 * [choice] completes with the typed value on save, null on decline/withdrawal.
 */
@Immutable
data class PendingSecret(
    /** Display name — `label`/`secretName`/`envVarName`/`title`. */
    val label: String,
    /** Wire `secretName` when it named the stored credential. */
    val secretName: String? = null,
    val envVarName: String? = null,
    /** Shell-scoped ask — echoes back so the agent binds the value. */
    val shellId: String? = null,
    /** Why the agent needs it (`reason`/`secretInstructions`). */
    val reason: String? = null,
    /** `plain`/`totp` — echoed back with the answer. */
    val secretType: String? = null,
    /** `org`/`user` — echoed back with the answer. */
    val saveScope: String? = null,
    /** Masked input — wire `sensitive`, default true for a secret field. */
    val sensitive: Boolean = true,
    internal val choice: CompletableDeferred<SecretReply?>,
    internal val requestId: Long = -1,
)

/** The card's save — decline completes with null instead. */
data class SecretReply(val value: String)

/**
 * One shell-manager terminal (A7.6): user `!` commands and agent background
 * shells, tracked via the terminalLifecycle notifications. [itemId] binds
 * the transcript row when one exists (`!` echoes, started-shell rows).
 */
@Immutable
data class BackgroundShell(
    val terminalId: String,
    val command: String,
    /** running | completed | failed | killed */
    val status: String = "running",
    val exitCode: Int? = null,
    /** Completion output tail (bounded). */
    val output: String? = null,
    val startedAt: Long = 0L,
    /** The command came from this client's `!` composer. */
    val userShell: Boolean = false,
    /** Bound transcript item (Kind.ShellCommand), when one was written. */
    val itemId: Long = -1L,
) {
    val running: Boolean get() = status == "running"
}

/**
 * A Devin subagent spawned via a task/subagent tool call (A7.4), tracked
 * live off the wire. [status] is normalized at ingestion onto
 * pending/working/background/completed/failed/stopped.
 */
@Immutable
data class Subagent(
    /** `sharedSubagentId` when the wire carries one, else the toolCallId. */
    val id: String,
    val toolCallId: String,
    val title: String,
    val status: String,
    /** The instruction the parent handed the agent (rawInput), when sent. */
    val prompt: String? = null,
    /** Latest progress/output text from the call's content blocks. */
    val detail: String? = null,
    /** Final output — frozen copy of [detail] at the terminal update. */
    val result: String? = null,
    val startedAt: Long = 0L,
    /** Wall-clock the agent reached a terminal status; 0 while it runs. */
    val endedAt: Long = 0L,
    /** Child session id — `cognition.ai/childSessionId` or the URL tail. */
    val childSessionId: String? = null,
    val childSessionUrl: String? = null,
    /** Owning turn's key (its user item id); 0 when no turn exists yet. */
    val turnId: Long = 0L,
) {
    /** Still doing work — includes `background` (running, just unfocused). */
    val live: Boolean
        get() = status == "pending" || status == "working" || status == "background"
}

/** A photo/screenshot picked on-device, sent as an ACP `image` block. */
class ImageAttachment(val bytes: ByteArray, val mimeType: String)

/**
 * A quote lifted from an assistant message and pinned to the composer — the
 * chat's counterpart of t3code's AssistantCitation. Carried with the draft;
 * [SessionStore] merges it into the prompt text as a `>` block (plus the
 * optional [comment]) at send/steer time, so a queued prompt keeps it.
 */
@Immutable
data class CitationAttachment(
    /** Transcript item id the quote came from — the sheet's 定位到原消息 target. */
    val messageId: Long,
    /** Raw markdown the user quoted (editable in the citation sheet). */
    val quotedText: String,
    /** User note appended after the quote on send. */
    val comment: String = "",
)

/**
 * A prompt parked behind the running turn (B4 keeps its image bytes).
 * [id] is a store-local counter — it keys the queue sheet's lazy rows and
 * the edit-target slot; [createdAtMs] is the sheet's timestamp.
 */
data class QueuedPrompt(
    val id: Long,
    val text: String,
    val attachments: List<ImageAttachment> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
)

/** Per-turn timing owned by a SessionStore (was UI-local in ChatScreen). */
@Immutable
data class TurnTiming(val start: Long?, val end: Long?) {
    val durationSec: Long?
        get() = if (start != null && end != null && end > start) {
            (end - start) / 1000
        } else null
}

/**
 * The session-scoped half of the chat UI state — one per SessionStore.
 * Connection-level fields (phase, status, auth, reconnect) live on
 * [ChatViewModel.UiState], which merges these in for the bound session.
 */
data class SessionUi(
    val sessionId: String? = null,
    val modes: List<SessionMode> = emptyList(),
    val currentModeId: String? = null,
    val models: List<SessionModel> = emptyList(),
    val currentModelId: String? = null,
    val commands: List<AvailableCommand> = emptyList(),
    val sessionTitle: String? = null,
    /** Context window usage: [contextUsed] of [contextSize] tokens. */
    val contextUsed: Long = 0,
    val contextSize: Long = 0,
    /** Cumulative turn token accounting from `usage_update` (if reported). */
    val usageInput: Long = 0,
    val usageOutput: Long = 0,
    val usageCacheRead: Long = 0,
    val running: Boolean = false,
    /** The store is subscribed at the gateway — false while attaching and
     *  after a failed attach/subscription loss on a still-live socket. */
    val attached: Boolean = false,
    /** configOptions entries outside mode/model (effort, thinking, …). */
    val extraConfigOptions: List<ConfigOption> = emptyList(),
)

/** Live per-session flags rendered as sidebar badges on the session list. */
data class SessionBadge(
    val pendingPermission: Boolean = false,
    /** An elicitation card waits for answers in that session (A7.2). */
    val pendingInput: Boolean = false,
    val running: Boolean = false,
    /** Output arrived while another session was bound. */
    val unread: Boolean = false,
)

/**
 * Root navigation target — which top-level screen is up. Kept apart from
 * the connection [Phase]: a dropped/reconnecting socket changes the banner
 * on the chat page, never the page itself. Derived from
 * [ConnectionManager.currentStore] (null = Home).
 */
sealed interface Page {
    data object Home : Page
    data class Chat(val sessionId: String) : Page
}

/** The session an open/switch attempt is aimed at (drawer spinner + retry);
 *  null sessionId = the attempt creates a new session on [agentType]'s
 *  `devin acp` profile (W8.3 — Default rides the resident process). */
data class OpenTarget(
    val sessionId: String?,
    val cwd: String?,
    val agentType: com.devingo.app.acp.AgentType = com.devingo.app.acp.AgentType.Default,
)

/** A heads-up for something happening in a session that isn't on screen. */
data class SessionAlert(
    val sessionId: String,
    /** Short text shown in the snackbar. */
    val message: String,
    /** When true the snackbar offers a jump action to that session. */
    val openable: Boolean = true,
)
