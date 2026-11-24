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
)

enum class Kind { User, Agent, Thought, Tool, Plan, System, Error, Answered }

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
 *  null sessionId = the attempt creates a new session. */
data class OpenTarget(
    val sessionId: String?,
    val cwd: String?,
)

/** A heads-up for something happening in a session that isn't on screen. */
data class SessionAlert(
    val sessionId: String,
    /** Short text shown in the snackbar. */
    val message: String,
    /** When true the snackbar offers a jump action to that session. */
    val openable: Boolean = true,
)
