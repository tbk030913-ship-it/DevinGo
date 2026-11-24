package com.devingo.app.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One `cognition.ai/mentionTypes` entry — an @-mention source the agent supports. */
data class MentionType(
    val type: String,
    val label: String? = null,
    val icon: String? = null,
)

/**
 * Typed view over the `initialize` result's `agentCapabilities._meta`. Devin
 * echoes the negotiated feature flags under `cognition.ai/<name>` keys
 * (probe: acptest/probe-logs/01-init-all.log); a missing key means
 * unsupported, so every flag defaults false. Flags not modelled here stay
 * reachable through [raw].
 */
data class AgentCaps(
    val mentionTypes: List<MentionType> = emptyList(),
    val revert: Boolean = false,
    val revertByUserMessageId: Boolean = false,
    val revertHistoryRewound: Boolean = false,
    val sessionRename: Boolean = false,
    val sessionShare: Boolean = false,
    val sessionListContentSearch: Boolean = false,
    val userShellCommand: Boolean = false,
    val subagentSupport: Boolean = false,
    val terminalLifecycle: Boolean = false,
    val userEdits: Boolean = false,
    val midTurnPrompts: Boolean = false,
    val ruleMentions: Boolean = false,
    val megaplan: Boolean = false,
    val persistOnCreate: Boolean = false,
    val editableCommands: Boolean = false,
    val sessionDefaultConfigOptions: Boolean = false,
    val userConfig: Boolean = false,
    val multiRootWorkspace: Boolean = false,
    /** `session/list` sort keys the agent accepts (e.g. "updated_at"). */
    val sessionListOrderBy: List<String> = emptyList(),
    /** Path of the agent's MCP config file — from the result's own `_meta`. */
    val mcpConfigPath: String? = null,
    /** The raw `agentCapabilities` object — future flags read through here. */
    val raw: JsonObject? = null,
) {
    companion object {
        fun from(init: InitializeResult): AgentCaps {
            val caps = init.agentCapabilities
            val meta = caps?.get("_meta") as? JsonObject
            fun flag(name: String) = meta?.boolField("cognition.ai/$name") == true
            val mentions = (meta?.get("cognition.ai/mentionTypes") as? JsonArray)
                ?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    MentionType(
                        type = o.strField("type") ?: return@mapNotNull null,
                        label = o.strField("label"),
                        icon = o.strField("icon"),
                    )
                }
                .orEmpty()
            val orderBy = (meta?.get("cognition.ai/sessionListOrderBy") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                .orEmpty()
            return AgentCaps(
                mentionTypes = mentions,
                revert = flag("revert"),
                revertByUserMessageId = flag("revertByUserMessageId"),
                revertHistoryRewound = flag("revertHistoryRewound"),
                sessionRename = flag("sessionRename"),
                sessionShare = flag("sessionShare"),
                sessionListContentSearch = flag("sessionListContentSearch"),
                userShellCommand = flag("userShellCommand"),
                subagentSupport = flag("subagentSupport"),
                terminalLifecycle = flag("terminalLifecycle"),
                userEdits = flag("userEdits"),
                midTurnPrompts = flag("midTurnPrompts"),
                ruleMentions = flag("ruleMentions"),
                megaplan = flag("megaplan"),
                persistOnCreate = flag("persistOnCreate"),
                editableCommands = flag("editableCommands"),
                sessionDefaultConfigOptions = flag("sessionDefaultConfigOptions"),
                userConfig = flag("userConfig"),
                multiRootWorkspace = flag("multiRootWorkspace"),
                sessionListOrderBy = orderBy,
                mcpConfigPath = init._meta?.strField("mcpConfigPath"),
                raw = caps,
            )
        }
    }
}
