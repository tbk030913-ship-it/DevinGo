package com.devingo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.acp.AcpSession
import com.devingo.app.acp.AgentCaps
import com.devingo.app.acp.AvailableCommand
import com.devingo.app.acp.FsSearchItem
import com.devingo.app.acp.MentionResource
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AtSign
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.hugeicons.stroke.FileText
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.Map

// ---- unified `/` + `@` popover (A7.3) -----------------------------------------
//
// One surface replaces N6.2's CommandSuggestions + FileMentionList, ported
// from t3code's ComposerCommandPopover/trigger model:
//  - `/` at message start → the agent's available_commands grouped by
//    category (row = icon | name+inputHint | description).
//  - `@` at any word boundary → a kind chip row (全部/文件/目录/Git/Rules/
//    Skills/Plans/会话) over concurrent results: bridge /fs/search for
//    files+dirs, _cognition.ai/{rules,skills,plans}/list, the cached
//    session list, and a static git-diff entry. A pick rewrites the trigger
//    range as `@{kind}:{ref} ` — SessionStore turns it into a mention
//    resource_link at send.

/** The trigger token before the caret: `@[query]` or `/[query]`. */
internal data class ComposerTrigger(
    val kind: Kind,
    /** The partial query after the trigger char ("" for a bare `@`/`/`). */
    val query: String,
    /** Index of the trigger char in the text — the replacement start. */
    val start: Int,
) {
    enum class Kind { Mention, Slash }
}

/**
 * t3's detectComposerTrigger reduced to `@` and `/`: the token is the
 * whitespace-delimited word ending at the caret. `@a:b` is a kinded token
 * being typed manually — no popover. `/` only fires at message start
 * (provider commands are message-leading), and a second `/` or `\` in the
 * word means it's a path, not a command.
 */
internal fun detectComposerTrigger(text: String, caret: Int): ComposerTrigger? {
    val c = caret.coerceIn(0, text.length)
    var start = c
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    val token = text.substring(start, c)
    return when {
        token.startsWith("@") -> {
            val q = token.substring(1)
            if (q.contains(':')) {
                null
            } else {
                ComposerTrigger(ComposerTrigger.Kind.Mention, q, start)
            }
        }
        token.startsWith("/") -> {
            val q = token.substring(1)
            if (start != 0 || q.contains('/') || q.contains('\\')) {
                null
            } else {
                ComposerTrigger(ComposerTrigger.Kind.Slash, q, start)
            }
        }
        else -> null
    }
}

/** Picker kinds in chip order — the subset we can actually list items for. */
private val MENTION_KIND_ORDER = listOf(
    "file", "directory", "git", "rule", "skill", "plan", "conversation",
)

/**
 * The mention categories the popover offers: file/directory always;
 * git/conversation once the agent negotiates the mention protocol (their
 * tokens are harmless either way); rule/skill/plan only when the agent
 * advertises them in `mentionTypes` (A7.3). An unnegotiated agent — empty
 * mentionTypes — gets file/directory only.
 */
internal fun enabledMentionKinds(caps: AgentCaps): List<String> {
    val advertised = caps.mentionTypes.mapTo(HashSet()) { it.type }
    return MENTION_KIND_ORDER.filter { k ->
        when (k) {
            "file", "directory" -> true
            "git", "conversation" -> advertised.isNotEmpty()
            else -> k in advertised
        }
    }
}

internal fun mentionKindLabel(type: String): String = when (type) {
    "file" -> "文件"
    "directory" -> "目录"
    "git" -> "Git"
    "rule" -> "Rules"
    "skill" -> "Skills"
    "plan" -> "Plans"
    "conversation" -> "会话"
    "terminal" -> "终端"
    "codemap" -> "Codemaps"
    else -> type
}

/** Fuzzy score like the bridge's /fs/search: -1 no match, lower is better. */
internal fun scoreQueryMatch(label: String, query: String): Int {
    if (query.isEmpty()) return 0
    val l = label.lowercase()
    val q = query.lowercase()
    return when {
        l == q -> 0
        l.startsWith(q) -> 1
        l.contains(q) -> 2
        isSubsequence(q, l) -> 3
        else -> -1
    }
}

private fun isSubsequence(needle: String, haystack: String): Boolean {
    var i = 0
    for (c in haystack) {
        if (i < needle.length && c == needle[i]) i++
    }
    return i == needle.length
}

private data class PickerRow(
    /** The literal inserted at the trigger range on pick. */
    val insert: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    /** File/dir rows render the relPath in the mono font. */
    val mono: Boolean = false,
    val description: String? = null,
    /** Trailing mono text — a command's inputHint or a session's activity. */
    val hint: String? = null,
)

private data class PickerSection(val header: String?, val rows: List<PickerRow>)

/** Per-kind row caps when 全部 is selected vs. a single-kind chip. */
private const val CAP_ALL_FILES = 8
private const val CAP_ALL_DIRS = 4
private const val CAP_ALL_OTHERS = 5
private const val CAP_SINGLE = 30
private const val SEARCH_DEBOUNCE_MS = 140L

@Composable
internal fun ComposerCommandPopover(
    trigger: ComposerTrigger,
    sessionId: String?,
    commands: List<AvailableCommand>,
    caps: AgentCaps,
    sessions: List<AcpSession>,
    onSearch: suspend (String) -> List<FsSearchItem>,
    onMentionResources: suspend (String) -> List<MentionResource>,
    onPick: (insert: String, rangeStart: Int) -> Unit,
) {
    when (trigger.kind) {
        ComposerTrigger.Kind.Slash -> SlashPopover(
            trigger = trigger,
            commands = commands,
            onPick = onPick,
        )
        ComposerTrigger.Kind.Mention -> MentionPopover(
            trigger = trigger,
            sessionId = sessionId,
            caps = caps,
            sessions = sessions,
            onSearch = onSearch,
            onMentionResources = onMentionResources,
            onPick = onPick,
        )
    }
}

// ---- `/` commands --------------------------------------------------------------

/** The command's lucide-ish icon name → the nearest HugeIcon. */
private fun commandIcon(icon: String?): ImageVector = when (icon) {
    "book" -> HugeIcons.Book01
    "zap" -> HugeIcons.Flash
    "file-text" -> HugeIcons.FileText
    "map" -> HugeIcons.Map
    else -> HugeIcons.CommandLine
}

@Composable
private fun SlashPopover(
    trigger: ComposerTrigger,
    commands: List<AvailableCommand>,
    onPick: (String, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val sections = remember(commands, trigger.query) {
        val q = trigger.query
        commands.mapNotNull { c ->
            val name = c.name.removePrefix("/")
            val s = scoreQueryMatch(name, q)
            if (q.isNotEmpty() && s < 0) return@mapNotNull null
            Triple(c, name, s)
        }.groupBy(
            { it.first.category },
            { (c, name, s) ->
                PickerRow(
                    insert = "/$name ",
                    label = "/$name",
                    icon = commandIcon(c.icon),
                    accent = DevinGoColors.link,
                    mono = true,
                    description = c.description,
                    hint = c.inputHint,
                ) to s
            },
        ).map { (cat, rows) ->
            PickerSection(
                header = cat?.takeIf { it.isNotBlank() },
                rows = rows.sortedWith(
                    compareBy({ it.second }, { it.first.label }),
                ).map { it.first },
            )
        }
    }
    if (sections.isEmpty()) {
        PickerHintRow("没有匹配命令")
    } else {
        PickerList(sections, trigger.query, onPick = { onPick(it, trigger.start) })
    }
}

// ---- `@` mentions --------------------------------------------------------------

@Composable
private fun MentionPopover(
    trigger: ComposerTrigger,
    sessionId: String?,
    caps: AgentCaps,
    sessions: List<AcpSession>,
    onSearch: suspend (String) -> List<FsSearchItem>,
    onMentionResources: suspend (String) -> List<MentionResource>,
    onPick: (String, Int) -> Unit,
) {
    val kinds = remember(caps) { enabledMentionKinds(caps) }
    // Keyed on the bound session like the old file lister — a session
    // switch must not show the previous workspace's results.
    var selectedKind by remember(sessionId) { mutableStateOf<String?>(null) }
    var fs by remember(sessionId) { mutableStateOf<List<FsSearchItem>?>(null) }
    var fsFailed by remember(sessionId) { mutableStateOf(false) }
    // rule/skill/plan lists are complete catalogs — fetched once, then the
    // query filters client-side.
    var resources by remember(sessionId) {
        mutableStateOf<Map<String, List<MentionResource>>>(emptyMap())
    }

    val query = trigger.query
    val needFs = selectedKind == null ||
        selectedKind == "file" || selectedKind == "directory"
    LaunchedEffect(sessionId, selectedKind, query, needFs) {
        if (!needFs) return@LaunchedEffect
        if (query.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
        try {
            fs = onSearch(query)
            fsFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fs = emptyList()
            fsFailed = true
        }
    }
    val resKinds = kinds.filter { it == "rule" || it == "skill" || it == "plan" }
    LaunchedEffect(sessionId, selectedKind, resKinds) {
        for (k in resKinds) {
            if ((selectedKind == null || selectedKind == k) && k !in resources) {
                launch {
                    val list = try {
                        onMentionResources(k)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    resources = resources + (k to list)
                }
            }
        }
    }

    Column {
        KindChipRow(
            kinds = kinds,
            selected = selectedKind,
            onSelect = { selectedKind = if (selectedKind == it) null else it },
        )
        val sections = buildSections(
            query = query,
            selectedKind = selectedKind,
            kinds = kinds,
            fs = fs.orEmpty(),
            resources = resources,
            sessions = sessions,
            sessionId = sessionId,
        )
        when {
            sections.isNotEmpty() -> PickerList(
                sections, query,
                onPick = { onPick(it, trigger.start) },
            )
            needFs && fs == null && !fsFailed ->
                PickerHintRow("正在搜索工作区…")
            fsFailed && (selectedKind == null || selectedKind == "file" ||
                selectedKind == "directory") ->
                PickerHintRow("无法搜索工作区文件")
            selectedKind != null && selectedKind in resKinds &&
                selectedKind !in resources ->
                PickerHintRow("正在加载…")
            else -> PickerHintRow("没有匹配项")
        }
    }
}

/** Build the sectioned row list for the current query + chip selection. */
private fun buildSections(
    query: String,
    selectedKind: String?,
    kinds: List<String>,
    fs: List<FsSearchItem>,
    resources: Map<String, List<MentionResource>>,
    sessions: List<AcpSession>,
    sessionId: String?,
): List<PickerSection> {
    val all = selectedKind == null
    val sections = mutableListOf<PickerSection>()
    fun accentIcon(kind: String): Pair<Color, ImageVector> {
        val (argb, icon) = chipPresentation(kind)
        return Color(argb) to icon
    }
    fun fsRows(dir: Boolean, cap: Int): List<PickerRow> {
        val (color, icon) = accentIcon(if (dir) "directory" else "file")
        return fs.asSequence()
            .filter { it.isDir == dir }
            .take(cap)
            .map { item ->
                PickerRow(
                    insert = "@${if (dir) "directory" else "file"}:${item.relPath} ",
                    label = if (dir) item.relPath + "/" else item.relPath,
                    icon = icon,
                    accent = color,
                    mono = true,
                )
            }
            .toList()
    }
    if (all || selectedKind == "file") {
        fsRows(dir = false, cap = if (all) CAP_ALL_FILES else CAP_SINGLE)
            .takeIf { it.isNotEmpty() }
            ?.let { sections += PickerSection("文件", it) }
    }
    if (all || selectedKind == "directory") {
        fsRows(dir = true, cap = if (all) CAP_ALL_DIRS else CAP_SINGLE)
            .takeIf { it.isNotEmpty() }
            ?.let { sections += PickerSection("目录", it) }
    }
    if ("git" in kinds && (all || selectedKind == "git")) {
        val matches = query.isEmpty() ||
            scoreQueryMatch("diff", query) >= 0 ||
            scoreQueryMatch("git", query) >= 0 ||
            "未提交".contains(query.lowercase()) ||
            "变更".contains(query.lowercase())
        if (matches) {
            val (color, icon) = accentIcon("git")
            sections += PickerSection(
                "Git",
                listOf(
                    PickerRow(
                        insert = "@git:diff ",
                        label = "未提交变更",
                        icon = icon,
                        accent = color,
                        description = "工作区的 git diff",
                    ),
                ),
            )
        }
    }
    for (k in listOf("rule", "skill", "plan")) {
        if (k !in kinds || !(all || selectedKind == k)) continue
        val rows = resources[k].orEmpty()
            .mapNotNull { r ->
                val s = scoreQueryMatch(r.name, query)
                if (query.isNotEmpty() && s < 0) return@mapNotNull null
                r to s
            }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
            .take(if (all) CAP_ALL_OTHERS else CAP_SINGLE)
            .map { (r, _) ->
                val (color, icon) = accentIcon(k)
                PickerRow(
                    insert = "@$k:${r.id} ",
                    label = r.name,
                    icon = icon,
                    accent = color,
                    description = r.description,
                )
            }
        if (rows.isNotEmpty()) {
            sections += PickerSection(mentionKindLabel(k), rows)
        }
    }
    if ("conversation" in kinds && (all || selectedKind == "conversation")) {
        val (color, icon) = accentIcon("conversation")
        val rows = sessions.asSequence()
            .filter { it.sessionId != sessionId }
            .mapNotNull { s ->
                val label = s.title?.takeIf { it.isNotBlank() }
                    ?: s.sessionId.take(8)
                val sScore = if (query.isEmpty()) {
                    0
                } else {
                    scoreQueryMatch(label, query).let {
                        if (it < 0) scoreQueryMatch(s.sessionId, query) else it
                    }
                }
                if (query.isNotEmpty() && sScore < 0) return@mapNotNull null
                s to Pair(label, sScore)
            }
            .sortedBy { it.second.second }
            .take(if (all) CAP_ALL_OTHERS else CAP_SINGLE)
            .map { (s, labelScore) ->
                PickerRow(
                    insert = "@conversation:${s.sessionId} ",
                    label = labelScore.first,
                    icon = icon,
                    accent = color,
                    hint = s.lastActivity,
                )
            }
            .toList()
        if (rows.isNotEmpty()) {
            sections += PickerSection("会话", rows)
        }
    }
    return sections
}

// ---- shared rows ---------------------------------------------------------------

/** The kind filter chips — 全部 plus one per enabled category. */
@Composable
private fun KindChipRow(
    kinds: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            KindChip(
                label = "全部",
                icon = HugeIcons.AtSign,
                accent = cs.primary,
                selected = selected == null,
                onClick = { selected?.let(onSelect) },
            )
        }
        items(kinds, key = { it }) { k ->
            val (argb, icon) = chipPresentation(k)
            KindChip(
                label = mentionKindLabel(k),
                icon = icon,
                accent = Color(argb),
                selected = selected == k,
                onClick = { onSelect(k) },
            )
        }
    }
    HorizontalDivider(
        color = cs.outlineVariant,
        thickness = Hairline,
        modifier = Modifier.padding(horizontal = 10.dp),
    )
}

@Composable
private fun KindChip(
    label: String,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.14f) else cs.surfaceContainer,
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(Hairline, accent.copy(alpha = 0.6f))
        } else {
            androidx.compose.foundation.BorderStroke(Hairline, cs.outlineVariant)
        },
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                icon, null,
                modifier = Modifier.size(12.dp),
                tint = if (selected) accent else cs.onSurfaceVariant,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The sectioned row list — header + icon|label|description rows. */
@Composable
private fun PickerList(
    sections: List<PickerSection>,
    query: String,
    onPick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.heightIn(max = 300.dp)) {
        sections.forEach { section ->
            if (section.header != null) {
                item(key = "h:${section.header}") {
                    Text(
                        section.header,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(
                            start = 14.dp, top = 8.dp, bottom = 2.dp,
                        ),
                    )
                }
            }
            items(
                section.rows,
                key = { "${section.header}:${it.insert}" },
            ) { row ->
                PickerRow(row, query, onPick)
            }
        }
    }
}

@Composable
private fun PickerRow(
    row: PickerRow,
    query: String,
    onPick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clickable { onPick(row.insert) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            row.icon, null,
            modifier = Modifier.size(15.dp),
            tint = row.accent,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            highlightQuery(row.label, query, cs.primary),
            style = if (row.mono) {
                monoText()
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        if (!row.description.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                row.description,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (!row.hint.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                row.hint,
                style = monoText(MaterialTheme.typography.labelSmall),
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The single status row (loading / failed / empty). */
@Composable
private fun PickerHintRow(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Bold-accent the query's matched characters: the contiguous hit when the
 * label contains it, else the subsequence positions (fuzzy).
 */
private fun highlightQuery(text: String, query: String, accent: Color): AnnotatedString {
    val b = AnnotatedString.Builder(text)
    if (query.isEmpty()) return b.toAnnotatedString()
    val l = text.lowercase()
    val q = query.lowercase()
    val i = l.indexOf(q)
    val style = SpanStyle(color = accent, fontWeight = FontWeight.Bold)
    if (i >= 0) {
        b.addStyle(style, i, i + q.length)
    } else {
        var qi = 0
        for (j in l.indices) {
            if (qi < q.length && l[j] == q[qi]) {
                b.addStyle(style, j, j + 1)
                qi++
            }
        }
    }
    return b.toAnnotatedString()
}
