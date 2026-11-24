package com.devingo.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devingo.app.acp.GitBranches
import com.devingo.app.acp.GitOpResult
import com.devingo.app.acp.GitStatus
import com.devingo.app.acp.GitStatusFile
import com.devingo.app.ui.theme.DevinGoColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowDownToLine
import me.rerere.hugeicons.stroke.ArrowUpToLine
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Diff
import me.rerere.hugeicons.stroke.GitBranch
import me.rerere.hugeicons.stroke.GitCommit
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.RefreshCw
import me.rerere.hugeicons.stroke.Sparkles

// ---- W8.2: Git quick-ops sheet -----------------------------------------------
//
// One DevinSheet hosting three pages — 总览 (branch/ahead/behind/entries),
// 提交改动 (file checkboxes + message + default-branch guard) and 分支
// (switch/create) — fed by the bridge's /git/* endpoints through the
// SessionStore lambdas. Ops re-fetch the status in place; failures land as
// inline error text — the sheet never crashes out.
//
// t3code reference: ThreadGitControls + GitOverviewSheet/GitCommitSheet/
// GitBranchesSheet folded into one bottom-sheet navigation.

/** Which page the sheet opens on — ReviewSheet's Git icon lands on Commit. */
enum class GitSheetPage { Overview, Commit, Branches }

@Composable
fun GitSheet(
    initialPage: GitSheetPage,
    fetchStatus: suspend () -> GitStatus,
    fetchBranches: suspend () -> GitBranches,
    onCheckout: suspend (branch: String?, createBranch: String?) -> GitOpResult,
    onCommit: suspend (files: List<String>, message: String, createBranch: String?) -> GitOpResult,
    onPush: suspend () -> GitOpResult,
    onPull: suspend () -> GitOpResult,
    /** 「让 Devin 生成 Commit Message」 — sends the request into this session. */
    onAskDevinMessage: (prompt: String) -> Boolean,
    onOpenReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf(initialPage) }
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var branches by remember { mutableStateOf<GitBranches?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var opError by remember { mutableStateOf<String?>(null) }
    // Two-step confirm — "push" | "pull" (the overview's danger rows).
    var confirmSync by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        scope.launch {
            try {
                status = fetchStatus()
                statusError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusError = e.message ?: "无法读取 Git 状态"
            }
        }
    }

    fun refreshBranches() {
        scope.launch {
            try {
                branches = fetchBranches()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                opError = e.message
            }
        }
    }

    /** One mutating op: busy latch, inline error, status refresh on success. */
    fun runOp(
        success: (GitOpResult) -> String,
        block: suspend () -> GitOpResult,
        afterSuccess: () -> Unit = {},
    ) {
        if (busy) return
        busy = true
        opError = null
        notice = null
        scope.launch {
            try {
                notice = success(block())
                afterSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                opError = e.message ?: "操作失败"
            } finally {
                busy = false
            }
            refreshStatus()
        }
    }

    LaunchedEffect(Unit) { refreshStatus() }
    // The branches page fetches lazily — most visits never need the list.
    LaunchedEffect(page) {
        if (page == GitSheetPage.Branches && branches == null) refreshBranches()
    }

    DevinSheet(
        title = when (page) {
            GitSheetPage.Overview -> "Git"
            GitSheetPage.Commit -> "提交改动"
            GitSheetPage.Branches -> "分支"
        },
        onDismiss = onDismiss,
        modifier = Modifier.fillMaxHeight(),
    ) {
        if (page != GitSheetPage.Overview) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { page = GitSheetPage.Overview }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft, null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("返回", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        when (page) {
            GitSheetPage.Overview -> GitOverviewPage(
                status = status,
                statusError = statusError,
                busy = busy,
                notice = notice,
                opError = opError,
                confirmSync = confirmSync,
                onRetry = { refreshStatus() },
                onOpenReview = onOpenReview,
                onOpenCommit = { page = GitSheetPage.Commit },
                onOpenBranches = { page = GitSheetPage.Branches },
                onSyncRequest = { confirmSync = it },
                onSyncCancel = { confirmSync = null },
                onSyncConfirm = { kind ->
                    confirmSync = null
                    when (kind) {
                        "push" -> runOp(
                            { r -> r.output.ifBlank { "已推送" }.lines().lastOrNull() ?: "已推送" },
                            onPush,
                        )
                        else -> runOp(
                            { r -> r.output.ifBlank { "已拉取" }.lines().lastOrNull() ?: "已拉取" },
                            onPull,
                        )
                    }
                },
            )
            GitSheetPage.Commit -> GitCommitPage(
                status = status,
                statusError = statusError,
                busy = busy,
                notice = notice,
                opError = opError,
                onRetry = { refreshStatus() },
                onAskDevinMessage = { prompt ->
                    notice = if (onAskDevinMessage(prompt)) {
                        "已发送给 Devin，回复会出现在会话中"
                    } else {
                        "发送失败"
                    }
                },
                onSubmit = { files, message, createBranch ->
                    // Navigate only on success — a failed commit keeps the
                    // typed message and file selection for the retry.
                    runOp(
                        { r -> "已提交 ${r.commit}".trim() },
                        { onCommit(files, message, createBranch) },
                        { page = GitSheetPage.Overview },
                    )
                },
            )
            GitSheetPage.Branches -> GitBranchesPage(
                branches = branches,
                dirtyCount = status?.files?.size ?: 0,
                busy = busy,
                notice = notice,
                opError = opError,
                onRetry = { refreshBranches() },
                onCheckout = { branch, create ->
                    runOp(
                        { "已切换到 ${it.branch}" },
                        { onCheckout(branch, create) },
                        { refreshBranches() },
                    )
                },
            )
        }
    }
}

// ---- 总览 ---------------------------------------------------------------------

@Composable
private fun GitOverviewPage(
    status: GitStatus?,
    statusError: String?,
    busy: Boolean,
    notice: String?,
    opError: String?,
    confirmSync: String?,
    onRetry: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenCommit: () -> Unit,
    onOpenBranches: () -> Unit,
    onSyncRequest: (String) -> Unit,
    onSyncCancel: () -> Unit,
    onSyncConfirm: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            status == null && statusError == null -> Row(
                Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(20.dp), strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant,
                )
            }
            statusError != null -> InlineRetry(
                text = statusError,
                onRetry = onRetry,
            )
            status == null || !status.isRepo -> Text(
                "当前工作区不是 Git 仓库",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            else -> {
                // ---- summary card ------------------------------------------------
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cs.surfaceContainerLowest,
                    border = BorderStroke(Hairline, cs.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                HugeIcons.GitBranch, null,
                                modifier = Modifier.size(16.dp),
                                tint = cs.onSurface,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                status.branch.ifBlank { "—" },
                                style = monoText(MaterialTheme.typography.bodyLarge)
                                    .copy(fontWeight = FontWeight.W600),
                                color = cs.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (status.isDefaultBranch) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "默认分支",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DevinGoColors.warning,
                                )
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                if (status.files.isEmpty()) "工作区干净"
                                else "${status.files.size} 处改动",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                            if (status.insertions > 0 || status.deletions > 0) {
                                Text(
                                    "+${status.insertions} −${status.deletions}",
                                    style = monoText(MaterialTheme.typography.labelSmall),
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            if (status.ahead > 0) {
                                Text(
                                    "↑${status.ahead}",
                                    style = monoText(MaterialTheme.typography.labelSmall),
                                    color = DevinGoColors.diffAdd,
                                )
                            }
                            if (status.behind > 0) {
                                Text(
                                    "↓${status.behind}",
                                    style = monoText(MaterialTheme.typography.labelSmall),
                                    color = DevinGoColors.diffRemove,
                                )
                            }
                        }
                    }
                }
                FeedbackLines(notice, opError)
                // ---- entries ------------------------------------------------------
                GitEntryRow(
                    icon = HugeIcons.Diff,
                    title = "查看未提交改动",
                    subtitle = if (status.files.isEmpty()) "无改动"
                    else "${status.files.size} 个文件 · +${status.insertions} −${status.deletions}",
                    enabled = status.files.isNotEmpty(),
                    onClick = onOpenReview,
                )
                GitEntryRow(
                    icon = HugeIcons.GitCommit,
                    title = "提交改动",
                    subtitle = if (status.files.isEmpty()) "无待提交文件"
                    else "${status.files.size} 个文件待提交",
                    enabled = status.files.isNotEmpty(),
                    onClick = onOpenCommit,
                )
                GitEntryRow(
                    icon = HugeIcons.GitFork,
                    title = "分支管理",
                    subtitle = status.branch,
                    onClick = onOpenBranches,
                )
                GitEntryRow(
                    icon = HugeIcons.ArrowUpToLine,
                    title = "推送",
                    subtitle = if (status.ahead > 0) "↑${status.ahead} 个提交待推送" else "推送到远端",
                    onClick = { onSyncRequest("push") },
                )
                GitEntryRow(
                    icon = HugeIcons.ArrowDownToLine,
                    title = "拉取",
                    subtitle = if (status.behind > 0) "↓${status.behind} 个提交待拉取" else "从远端拉取",
                    onClick = { onSyncRequest("pull") },
                )
                if (confirmSync != null) {
                    ConfirmRow(
                        warning = if (confirmSync == "push") {
                            "将本地提交推送到远端仓库，确认继续？"
                        } else {
                            "从远端拉取并快进合并，未提交改动保留，确认继续？"
                        },
                        confirmLabel = if (confirmSync == "push") "确认推送" else "确认拉取",
                        busy = busy,
                        onCancel = onSyncCancel,
                        onConfirm = { onSyncConfirm(confirmSync) },
                    )
                }
            }
        }
    }
}

// ---- 提交改动 -------------------------------------------------------------------

@Composable
private fun GitCommitPage(
    status: GitStatus?,
    statusError: String?,
    busy: Boolean,
    notice: String?,
    opError: String?,
    onRetry: () -> Unit,
    onAskDevinMessage: (prompt: String) -> Unit,
    onSubmit: (files: List<String>, message: String, createBranch: String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val files = status?.files.orEmpty()
    var message by remember { mutableStateOf("") }
    var createBranch by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    // null = "全部选中"（默认）；覆盖集在任意勾选后接管。
    var selectionOverride by remember { mutableStateOf<Set<String>?>(null) }
    val selected = selectionOverride ?: files.mapTo(HashSet()) { it.path }
    val selFiles = files.filter { it.path in selected }
    val selIns = selFiles.sumOf { it.insertions }
    val selDel = selFiles.sumOf { it.deletions }

    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        // 默认分支提醒（t3code 的 amber banner）：勾选后先 `switch -c` 再提交。
        if (status?.isDefaultBranch == true) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = DevinGoColors.warningContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        "你正在默认分支 ${status.branch} 上",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevinGoColors.warning,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Text(
                            "同时创建特性分支",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        DevinSwitch(
                            checked = createBranch,
                            onCheckedChange = { createBranch = it },
                        )
                    }
                    if (createBranch) {
                        OutlinedTextField(
                            value = newBranchName,
                            onValueChange = { newBranchName = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            placeholder = {
                                Text(
                                    "feature/新分支名",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.outline,
                                )
                            },
                            singleLine = true,
                            textStyle = monoText(MaterialTheme.typography.bodyMedium),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = cs.onSurface,
                                unfocusedBorderColor = cs.outlineVariant,
                                focusedContainerColor = cs.surfaceContainerLowest,
                                unfocusedContainerColor = cs.surfaceContainerLowest,
                            ),
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
            placeholder = {
                Text(
                    "Commit message",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.outline,
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.onSurface,
                unfocusedBorderColor = cs.outlineVariant,
                focusedContainerColor = cs.surfaceContainerLowest,
                unfocusedContainerColor = cs.surfaceContainerLowest,
            ),
        )
        Spacer(Modifier.size(8.dp))
        // 生成请求发给当前会话本身 —— Devin 看得到工作区，回复落在会话里。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = {
                    onAskDevinMessage(buildCommitPrompt(selFiles))
                },
                enabled = selFiles.isNotEmpty(),
                shape = CircleShape,
                color = cs.surfaceContainerLowest,
                border = BorderStroke(1.dp, cs.outlineVariant),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        HugeIcons.Sparkles, null,
                        modifier = Modifier.size(14.dp),
                        tint = cs.onSurface,
                    )
                    Text(
                        "让 Devin 生成 Commit Message",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurface,
                    )
                }
            }
        }
        FeedbackLines(notice, opError)
        // ---- file picklist -------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${selFiles.size}/${files.size} 个文件  +$selIns −$selDel",
                style = monoText(MaterialTheme.typography.labelSmall),
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { selectionOverride = null }) {
                Text("全选", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = {
                selectionOverride = files.mapTo(HashSet()) { it.path } - selected
            }) {
                Text("反选", style = MaterialTheme.typography.labelMedium)
            }
        }
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.surfaceContainerLowest),
        ) {
            items(files, key = { it.path }) { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectionOverride =
                                if (f.path in selected) selected - f.path
                                else selected + f.path
                        }
                        .padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = f.path in selected,
                        onCheckedChange = null, // the row handles taps
                        modifier = Modifier.size(40.dp),
                    )
                    GitStatusChip(f.status)
                    Text(
                        f.path,
                        style = monoText(MaterialTheme.typography.bodySmall),
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                    )
                    if (f.insertions > 0) {
                        Text(
                            "+${f.insertions}",
                            style = monoText(MaterialTheme.typography.labelSmall),
                            color = DevinGoColors.diffAdd,
                        )
                    }
                    if (f.deletions > 0) {
                        Text(
                            " −${f.deletions}",
                            style = monoText(MaterialTheme.typography.labelSmall),
                            color = DevinGoColors.diffRemove,
                        )
                    }
                }
            }
            if (files.isEmpty()) {
                item("empty") {
                    // A failed status fetch must not masquerade as a forever
                    // "正在读取…" — the sheet can open straight onto this page
                    // from ReviewSheet (W8.2 quality review I5).
                    when {
                        status == null && statusError != null -> InlineRetry(
                            text = statusError,
                            onRetry = onRetry,
                        )
                        else -> Text(
                            if (status == null) "正在读取…" else "没有待提交的文件",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        val canSubmit = selFiles.isNotEmpty() && !busy &&
            (!createBranch || newBranchName.isNotBlank())
        BlackPillButton(
            text = if (busy) "提交中…" else "提交 ${selFiles.size} 个文件",
            enabled = canSubmit,
            onClick = {
                // 留空 → 本地由 diff 概况生成（Devin 生成的路径走上面的
                // Sparkles 按钮——它的回复进会话，不回填这里）。
                val msg = message.trim().ifBlank { localCommitMessage(selFiles) }
                onSubmit(
                    selFiles.map { it.path },
                    msg,
                    if (createBranch) newBranchName.trim() else null,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The prompt the Sparkles button sends into the live session. */
private fun buildCommitPrompt(selFiles: List<GitStatusFile>): String = buildString {
    append("请为当前工作区已改动的 Git 文件生成一条规范的 commit message")
    append("（Conventional Commits 风格，单行 subject）。")
    append("只回复 message 文本本身——不要包裹引号或代码块、不要附加解释。\n")
    append("改动文件：")
    val listing = selFiles.joinToString(", ") { it.path }
    append(if (listing.length > 800) listing.take(800) + "…" else listing)
}

/** 留空提交时的本地 message（diff 概况版）。 */
private fun localCommitMessage(selFiles: List<GitStatusFile>): String =
    when (selFiles.size) {
        0 -> "chore: workspace update"
        1 -> "chore: update ${selFiles[0].path.substringAfterLast('/')}"
        else -> "chore: update ${selFiles.size} files"
    }

// ---- 分支 ---------------------------------------------------------------------

@Composable
private fun GitBranchesPage(
    branches: GitBranches?,
    dirtyCount: Int,
    busy: Boolean,
    notice: String?,
    opError: String?,
    onRetry: () -> Unit,
    onCheckout: (branch: String?, createBranch: String?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var newBranch by remember { mutableStateOf("") }
    var confirmCheckout by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newBranch,
                onValueChange = { newBranch = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "新建分支名",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.outline,
                    )
                },
                singleLine = true,
                textStyle = monoText(MaterialTheme.typography.bodyMedium),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.onSurface,
                    unfocusedBorderColor = cs.outlineVariant,
                    focusedContainerColor = cs.surfaceContainerLowest,
                    unfocusedContainerColor = cs.surfaceContainerLowest,
                ),
            )
            Surface(
                onClick = {
                    if (newBranch.isNotBlank()) {
                        onCheckout(null, newBranch.trim())
                        newBranch = ""
                    }
                },
                enabled = newBranch.isNotBlank() && !busy,
                shape = CircleShape,
                color = cs.surfaceContainerLowest,
                border = BorderStroke(1.dp, cs.outlineVariant),
            ) {
                Text(
                    "创建",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        FeedbackLines(notice, opError)
        if (confirmCheckout != null) {
            ConfirmRow(
                warning = "工作区有 $dirtyCount 处未提交改动，切换分支可能带来冲突，仍要切换到 $confirmCheckout？",
                confirmLabel = "确认切换",
                busy = busy,
                onCancel = { confirmCheckout = null },
                onConfirm = {
                    onCheckout(confirmCheckout, null)
                    confirmCheckout = null
                },
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
            when {
                branches == null -> item("loading") {
                    // A failed first fetch shows the retry row instead of
                    // spinning forever (W8.2 quality review).
                    if (opError != null) {
                        InlineRetry(text = opError, onRetry = onRetry)
                    } else {
                        Row(
                            Modifier.padding(vertical = 20.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                branches.branches.isEmpty() -> item("empty") {
                    Text(
                        "没有本地分支",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                else -> items(branches.branches, key = { it }) { b ->
                    val current = b == branches.current
                    val isDefault = b == branches.defaultBranch
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !current && !busy) {
                                if (dirtyCount > 0) {
                                    confirmCheckout = b
                                } else {
                                    onCheckout(b, null)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            HugeIcons.GitBranch, null,
                            modifier = Modifier.size(15.dp),
                            tint = if (current) cs.primary else cs.onSurfaceVariant,
                        )
                        Text(
                            b,
                            style = monoText(MaterialTheme.typography.bodyMedium)
                                .copy(
                                    fontWeight = if (current) FontWeight.W600
                                    else FontWeight.Normal,
                                ),
                            color = if (current) cs.onSurface else cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                        )
                        if (isDefault) {
                            Text(
                                "默认",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                        if (current) {
                            Icon(
                                HugeIcons.CheckmarkCircle02, "当前分支",
                                modifier = Modifier.size(16.dp),
                                tint = cs.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- shared bits -----------------------------------------------------------------

/** One tappable overview entry — icon + title/subtitle + trailing chevron. */
@Composable
private fun GitEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerLowest,
        border = BorderStroke(Hairline, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, null,
                modifier = Modifier.size(18.dp),
                tint = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) cs.onSurface
                    else cs.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowRight, null,
                modifier = Modifier.size(18.dp),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}

/** RevertSheet's danger idiom: warning line + 取消/确认 pills. */
@Composable
private fun ConfirmRow(
    warning: String,
    confirmLabel: String,
    busy: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.AlertCircle, null,
                modifier = Modifier.size(14.dp),
                tint = cs.error,
            )
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = cs.error,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinePillButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            )
            DangerPillButton(
                text = confirmLabel,
                loading = busy,
                enabled = !busy,
                onClick = onConfirm,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
            )
        }
    }
}

/** Inline notice (success) + op error lines — errors inform, never crash. */
@Composable
private fun FeedbackLines(notice: String?, opError: String?) {
    val cs = MaterialTheme.colorScheme
    notice?.let {
        Row(
            Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.CheckmarkCircle02, null,
                modifier = Modifier.size(13.dp),
                tint = DevinGoColors.diffAdd,
            )
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    opError?.let {
        Row(
            Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                HugeIcons.AlertCircle, null,
                modifier = Modifier.size(13.dp),
                tint = cs.error,
            )
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = cs.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Status fetch failed — message + retry. */
@Composable
internal fun InlineRetry(text: String, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = cs.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onRetry,
            shape = CircleShape,
            color = cs.surfaceContainerHighest,
        ) {
            Icon(
                HugeIcons.RefreshCw, "重试",
                modifier = Modifier.padding(8.dp).size(14.dp),
                tint = cs.onSurface,
            )
        }
    }
}

/** Porcelain letter chip — the file-row's tiny colored status. */
@Composable
private fun GitStatusChip(status: String) {
    val cs = MaterialTheme.colorScheme
    val color = when (status) {
        "A", "?" -> DevinGoColors.diffAdd
        "D" -> DevinGoColors.diffRemove
        "U" -> cs.error
        else -> cs.onSurfaceVariant
    }
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            status.take(1),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

