package com.devingo.app.acp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ACP (Agent Client Protocol) v1 — https://agentclientprotocol.com
// Only the slice DevinGo needs is modelled; unknown fields are ignored on
// decode and extension notifications (`_cognition.ai/*`) pass through as
// raw JsonObjects.

@Serializable
data class InitializeParams(
    val protocolVersion: Int,
    val clientCapabilities: ClientCapabilities,
    val clientInfo: ClientInfo,
    /**
     * Top-level capability metadata — kept to the revert declaration (devin
     * negotiates off `clientCapabilities._meta`; this mirrors the gateway's
     * own initialize params).
     */
    val _meta: JsonObject? = null,
)

@Serializable
data class ClientCapabilities(
    val fs: FsCapabilities = FsCapabilities(),
    val terminal: Boolean = false,
    /**
     * ACP elicitation capability, serialized as an empty object — backed
     * by the A7.2 `PendingUserInputCard`; devin reads the full declared
     * capability set when deciding what `agentCapabilities._meta` to echo.
     */
    val elicitation: JsonObject = JsonObject(emptyMap()),
    /**
     * Devin extension negotiation: flags declared here
     * (`cognition.ai/<name>`) are what the agent echoes back under
     * `agentCapabilities._meta` (probe: acptest/probe-logs/01-init-all.log).
     */
    val _meta: JsonObject? = null,
)

@Serializable
data class FsCapabilities(
    val readTextFile: Boolean = false,
    val writeTextFile: Boolean = false,
)

@Serializable
data class ClientInfo(val name: String, val version: String)

@Serializable
data class InitializeResult(
    val protocolVersion: Int = 1,
    val authMethods: List<AuthMethod> = emptyList(),
    val agentInfo: AgentInfo? = null,
    val agentCapabilities: JsonObject? = null,
    /** Result-level metadata — devin puts `mcpConfigPath` here. */
    val _meta: JsonObject? = null,
)

@Serializable
data class AuthMethod(
    val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class AgentInfo(
    val name: String? = null,
    val title: String? = null,
    val version: String? = null,
)

@Serializable
data class AuthenticateParams(
    val methodId: String,
    /** Devin accepts `_meta.api_key` to authenticate without browser login. */
    val _meta: JsonObject? = null,
)

/**
 * `devin acp --agent-type` spawn modes the bridge can host on a dedicated
 * per-profile process (W8.3; `_meta["devingo/agentType"]`). Default rides
 * the shared resident process — its wire value is also accepted.
 */
enum class AgentType(val wire: String) {
    Default("default"),
    Review("review"),
    Codemap("codemap"),
    Summarizer("summarizer"),
}

@Serializable
data class SessionNewParams(
    val cwd: String,
    val mcpServers: List<JsonObject> = emptyList(),
    /**
     * `_devingo` spawn profile (W8.3): `devingo/agentType` +
     * `devingo/refusalFallback` — the bridge routes the request to a
     * per-profile `devin acp` process. Ignored by agent-side devin.
     */
    val _meta: JsonObject? = null,
)

@Serializable
data class SessionLoadParams(
    val sessionId: String,
    val cwd: String,
    val mcpServers: List<JsonObject> = emptyList(),
    /**
     * Same spawn profile as [SessionNewParams._meta] (W8.3): on a
     * hub-unknown sid (idle evict / bridge restart) the bridge needs the
     * profile to re-spawn the right proc — omitting it silently lands the
     * resumed session on the resident default agent.
     */
    val _meta: JsonObject? = null,
)

@Serializable
data class SessionNewResult(
    /** May be absent in `session/load` replies — the caller knows the id. */
    val sessionId: String = "",
    val modes: SessionModes? = null,
    val models: SessionModels? = null,
    /**
     * Devin's unified config surface: `session/new` and `session/load`
     * return the available modes and models as configOptions entries with
     * category "mode" / "model", superseding the ACP `modes`/`models` fields.
     */
    val configOptions: List<ConfigOption> = emptyList(),
) {
    fun resolvedModes(): SessionModes? =
        modes ?: configOption("mode")?.let { opt ->
            SessionModes(
                currentModeId = opt.currentValue,
                availableModes = opt.options.map {
                    SessionMode(id = it.value, name = it.name, description = it.description)
                },
            )
        }

    fun resolvedModels(): SessionModels? =
        models ?: configOption("model")?.let { opt ->
            SessionModels(
                currentModelId = opt.currentValue,
                availableModels = opt.options.map {
                    SessionModel(modelId = it.value, name = it.name, description = it.description)
                },
            )
        }

    private fun configOption(id: String): ConfigOption? =
        configOptions.firstOrNull { it.id == id || it.category == id }
}

/** A configurable session option (modes, models, …) from `configOptions`. */
@Serializable
data class ConfigOption(
    val id: String,
    val name: String? = null,
    val category: String? = null,
    val type: String? = null,
    val description: String? = null,
    val currentValue: String? = null,
    val options: List<ConfigOptionChoice> = emptyList(),
)

@Serializable
data class ConfigOptionChoice(
    val value: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class SessionModes(
    val currentModeId: String? = null,
    val availableModes: List<SessionMode> = emptyList(),
)

@Serializable
data class SessionMode(
    val id: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class SessionModels(
    val currentModelId: String? = null,
    val availableModels: List<SessionModel> = emptyList(),
)

@Serializable
data class SessionModel(
    val modelId: String,
    val name: String,
    val description: String? = null,
)

@Serializable
data class PromptParams(
    val sessionId: String,
    val prompt: List<ContentBlock>,
)

@Serializable
data class ContentBlock(
    val type: String,
    /** text blocks. */
    val text: String? = null,
    /** image/audio blocks: base64 payload plus its media type. */
    val data: String? = null,
    val mimeType: String? = null,
    /** resource_link blocks. */
    val uri: String? = null,
    val name: String? = null,
    /**
     * Mention metadata on resource_link blocks — devin reads
     * `cognition.ai/resource-link-type` / `mention-path` / `mention-text`
     * here to resolve `@kind:ref` tokens (A7.3).
     */
    val _meta: JsonObject? = null,
)

@Serializable
data class PromptResult(
    val stopReason: String? = null,
    val _meta: JsonObject? = null,
)

@Serializable
data class CancelParams(val sessionId: String)

@Serializable
data class SetModeParams(val sessionId: String, val modeId: String)

@Serializable
data class SetModelParams(val sessionId: String, val modelId: String)

@Serializable
data class SessionDeleteParams(val sessionId: String)

/**
 * `session/list` params (M9.1): [contentSearch] maps to devin's
 * `content_search` and turns on full-text matching; [withMessageExcerpts]
 * asks for the matched transcript snippets back on each session
 * (`with_message_excerpts` on the wire — both snake_case field names are
 * visible in the agent binary's struct dump, acptest/devin-strings.txt).
 */
@Serializable
data class SessionListParams(
    val cwd: String? = null,
    val content_search: String? = null,
    val with_message_excerpts: Boolean? = null,
)

/** Devin's unified config setter (`configId` covers "mode"/"model"/…). */
@Serializable
data class SetConfigOptionParams(
    val sessionId: String,
    val configId: String,
    val value: String,
)

/** Devin extension: `_cognition.ai/session/rename`. */
@Serializable
data class RenameSessionParams(val sessionId: String, val title: String)

/**
 * One entry of `session/list`. Devin returns the richer fields
 * (lock/plan/user-message flags) flat on each session object; verified
 * against the 3000.12.x agent.
 */
data class AcpSession(
    val sessionId: String,
    val title: String? = null,
    val cwd: String? = null,
    val isLocked: Boolean = false,
    val lockHolderPid: Long? = null,
    val lockHolderClient: String? = null,
    val planFilePath: String? = null,
    val hasUserMessages: Boolean = false,
    val lastActivity: String? = null,
    /** Hub overlay on `_devingo/sessions`: a turn is in flight. */
    val running: Boolean = false,
    /** Hub overlay on `_devingo/sessions`: an approval is unanswered. */
    val pendingPermission: Boolean = false,
    /** Content-search snippets (`with_message_excerpts` reply, M9.1). */
    val excerpts: List<SessionExcerpt> = emptyList(),
)

/** One matched transcript line under a `session/list` content search. */
data class SessionExcerpt(
    val text: String,
    /** True when the match sits on a user message (the "你: " label). */
    val isUser: Boolean = false,
)

/** `_devingo/attach` result (gateway-private; see bridge/hub.go). */
@Serializable
data class AttachResult(
    val sessionId: String = "",
    /**
     * True when the journal no longer reaches the requested `afterSeq`
     * (truncated ring, or the session isn't hosted) — the caller must fall
     * back to `session/load`.
     */
    val needLoad: Boolean = false,
    /** Hub-side truth: a turn is believed in flight right now. */
    val running: Boolean = false,
    /** Journal high-water mark at attach time — replay frames carry `_seq`s
     * up to here; anything above is live. */
    val lastSeq: Long = 0,
    /** An approval is still pending; its request re-delivers after attach. */
    val pendingPermission: Boolean = false,
    /** The session's last `session/new`/`session/load` result, when cached. */
    val session: SessionNewResult? = null,
)

@Serializable
data class RevertListStepsParams(val sessionId: String)

/**
 * `_cognition.ai/revert/forkFromStep` — fork a NEW session branching off
 * [stepId]. The binary's param table shows `stepId`/`forkTargetNodeId`/
 * `userMessageId`/`summary`; stepId is what listSteps hands back.
 */
@Serializable
data class RevertForkParams(
    val sessionId: String,
    val stepId: String,
)

/** One entry of `_cognition.ai/revert/listSteps` (parsed loosely). */
data class RevertStep(
    val stepId: String,
    val summary: String? = null,
    val kind: String? = null,
    val userMessageId: String? = null,
    val stepNumber: Int? = null,
)

/**
 * `_cognition.ai/revert/preview` — one file the revert would roll back.
 * Devin's payload nests the counters under `diffStats`; the action kind is
 * `restore`/`delete`/`recreate` (internally-tagged FileRevertKindPayload).
 */
data class RevertPreviewFile(
    val path: String,
    val action: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
)

/** `_cognition.ai/revert/preview` result (loose decode — keys vary). */
data class RevertPreview(
    val files: List<RevertPreviewFile> = emptyList(),
    /** `irreversibleWarnings` — changes the engine can't undo (flattened). */
    val warnings: List<String> = emptyList(),
    /** `conflicts` — files whose content drifted from the recorded hash. */
    val conflicts: List<String> = emptyList(),
)

/** `_cognition.ai/revert/execute` result (loose decode). */
data class RevertExecuteResult(
    /** `revertHistoryRewound` — history was truncated in place. */
    val historyRewound: Boolean,
    /** Present when the agent answered the revert by forking instead. */
    val forkedSessionId: String? = null,
)

// Agent -> client
@Serializable
data class SessionUpdateParams(
    val sessionId: String,
    val update: JsonObject,
)

@Serializable
data class RequestPermissionParams(
    val sessionId: String,
    val toolCall: ToolCallInfo,
    val options: List<PermissionOption> = emptyList(),
)

@Serializable
data class ToolCallInfo(
    val toolCallId: String,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val rawInput: JsonElement? = null,
    val content: JsonElement? = null,
    val locations: JsonElement? = null,
    /** Devin extras — `terminal_exit.exit_code` on exec updates. */
    val _meta: JsonObject? = null,
)

/** One entry of `_cognition.ai/{rules,skills,plans}/list` (loose decode, A7.3). */
data class MentionResource(
    val id: String,
    val name: String,
    val description: String? = null,
)

/** A slash command advertised via `available_commands_update`. */
data class AvailableCommand(
    val name: String,
    val description: String? = null,
    val inputHint: String? = null,
    /** Devin extras carried in the command's `_meta` object. */
    val category: String? = null,
    val icon: String? = null,
)

@Serializable
data class PermissionOption(
    val optionId: String,
    val name: String,
    val kind: String? = null,
)

object AcpMethods {
    const val INITIALIZE = "initialize"
    const val AUTHENTICATE = "authenticate"
    const val SESSION_NEW = "session/new"
    const val SESSION_LOAD = "session/load"
    const val SESSION_PROMPT = "session/prompt"
    const val SESSION_CANCEL = "session/cancel"
    const val SESSION_SET_MODE = "session/set_mode"
    const val SESSION_SET_MODEL = "session/set_model"
    const val SESSION_SET_CONFIG_OPTION = "session/set_config_option"
    const val SESSION_DELETE = "session/delete"
    const val SESSION_LIST = "session/list"
    const val SESSION_RENAME = "_cognition.ai/session/rename"
    const val SESSION_SHARE = "_cognition.ai/session/share"
    const val SESSION_UPDATE = "session/update"
    const val SESSION_REQUEST_PERMISSION = "session/request_permission"
    const val SESSION_CLOSE = "session/close"

    // Agent->client elicitation (A7.2): `session/elicitation` is the draft
    // name some agents still send for the same create request.
    const val ELICITATION_CREATE = "elicitation/create"
    const val SESSION_ELICITATION = "session/elicitation"
    const val ELICITATION_COMPLETE = "elicitation/complete"
    const val SESSION_ELICITATION_COMPLETE = "session/elicitation/complete"

    // Devin @-mention resource lists (A7.3): rules/skills/plans feed the
    // composer's non-file mention categories.
    const val RULES_LIST = "_cognition.ai/rules/list"
    const val SKILLS_LIST = "_cognition.ai/skills/list"
    const val PLANS_LIST = "_cognition.ai/plans/list"

    // Devin subagent control surface (A7.4) — negotiated via
    // clientCapabilities._meta["cognition.ai/subagentSupport"].
    const val SUBAGENT_CANCEL = "_cognition.ai/subagent/cancel"
    const val SUBAGENT_BACKGROUND = "_cognition.ai/subagent/background"
    const val SUBAGENT_FOREGROUND = "_cognition.ai/subagent/foreground"

    // Devin secret ask (A7.6): agent->client card request (envVarName/
    // secretType/saveScope _meta), answered with {action:"save"|"decline"}.
    const val SECRET_CREATE = "_cognition.ai/secret/create"

    // Devin terminal lifecycle (A7.6) — negotiated via
    // clientCapabilities._meta["cognition.ai/terminalLifecycle"]. Started/
    // completed are agent->client notifications for shell-manager
    // terminals (user `!` commands + background shells); kill is the
    // client->agent stop call.
    const val TERMINAL_COMMAND_STARTED = "_cognition.ai/terminal/commandStarted"
    const val TERMINAL_COMMAND_COMPLETED = "_cognition.ai/terminal/commandCompleted"
    const val TERMINAL_KILL_BACKGROUND_SHELL =
        "_cognition.ai/terminal/killBackgroundShell"

    // Devin revert extension (negotiated via clientCapabilities._meta).
    const val REVERT_LIST_STEPS = "_cognition.ai/revert/listSteps"
    const val REVERT_FORK_FROM_STEP = "_cognition.ai/revert/forkFromStep"
    const val REVERT_PREVIEW = "_cognition.ai/revert/preview"
    const val REVERT_EXECUTE = "_cognition.ai/revert/execute"
    const val REVERT_STEPS_UPDATED = "_cognition.ai/revert/stepsUpdated"
    /** Agent->client: history was rewound by a (peer or local) in-place revert. */
    const val REVERT_HISTORY_REWOUND = "_cognition.ai/revert/historyRewound"
}

// JSON-RPC error code used by ACP when the agent requires authenticate first.
const val ACP_ERROR_AUTH_REQUIRED = -32000
const val JSONRPC_METHOD_NOT_FOUND = -32601
