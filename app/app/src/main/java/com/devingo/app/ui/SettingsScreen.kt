package com.devingo.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devingo.app.ChatViewModel
import com.devingo.app.Notifier
import com.devingo.app.Prefs
import com.devingo.app.tailnet.TailnetManager
import com.devingo.app.tailnet.TailnetState
import com.devingo.app.ui.theme.DevinGoColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

/**
 * Settings page (spec §G): connection fields, notifications, background
 * keep-alive and Android Live Updates. Opens over Home; Back returns.
 */
@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    prefs: Prefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var editField by remember { mutableStateOf<EditField?>(null) }
    var ecoSheet by remember { mutableStateOf<EcoSheet?>(null) }
    var updateSheet by remember { mutableStateOf(false) }
    var logoutConfirm by remember { mutableStateOf(false) }

    // System states re-read on every resume.
    var notifEnabled by remember { mutableStateOf(false) }
    var notifPermGranted by remember { mutableStateOf(true) }
    var batteryUnrestricted by remember { mutableStateOf(false) }
    var promotedGranted by remember { mutableStateOf(false) }
    var promotedSupported by remember { mutableStateOf(false) }
    fun refresh() {
        val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        notifEnabled = nm.areNotificationsEnabled()
        notifPermGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        batteryUnrestricted = runCatching {
            (context.getSystemService(android.content.Context.POWER_SERVICE)
                    as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
        promotedSupported = Build.VERSION.SDK_INT >= 36
        promotedGranted = if (Build.VERSION.SDK_INT >= 36) {
            nm.canPostPromotedNotifications()
        } else false
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        refresh()
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh() }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top bar: back chevron + centered title. Content caps at 720dp.
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回",
                    modifier = Modifier.size(24.dp),
                    tint = cs.onSurface,
                )
            }
            Text(
                "设置",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ---- 连接 -------------------------------------------------------------
            SectionHeader("连接", Modifier.padding(start = 20.dp))
            val activeHost by prefs.hostFlow.collectAsState()
            SettingRow(
                "Bridge 地址",
                subtitle = activeHost.ifBlank { "未设置" },
                onClick = { editField = EditField.Host },
            )
            SettingRow(
                "Token",
                subtitle = masked(prefs.token),
                onClick = { editField = EditField.Token },
            )
            SettingRow(
                "默认工作目录",
                subtitle = prefs.cwd.ifBlank { "bridge 默认目录" },
                onClick = { editField = EditField.Cwd },
            )
            SettingRow(
                "Devin API Key",
                subtitle = masked(prefs.apiKey),
                onClick = { editField = EditField.ApiKey },
            )
            SettingRow(
                "策略拒答回退模型",
                subtitle = prefs.refusalFallbackModel
                    .ifBlank { "未设置 · 主模型被拒答时不降级" } + " · 新会话生效",
                onClick = { editField = EditField.RefusalFallback },
            )

            // ---- Tailscale ----------------------------------------------------------
            SectionHeader("Tailscale", Modifier.padding(start = 20.dp))
            val tailnetState by TailnetManager.state.collectAsState()
            SettingRow(
                "内置 Tailscale",
                subtitle = "无需安装 Tailscale App，手机直接加入你的 tailnet",
                onClick = null,
                trailing = {
                    DevinSwitch(
                        checked = prefs.tailnetEnabled,
                        onCheckedChange = { on ->
                            prefs.tailnetEnabled = on
                            if (on) TailnetManager.start() else TailnetManager.stop()
                        },
                    )
                },
            )
            if (prefs.tailnetEnabled) {
                when (val st = tailnetState) {
                    is TailnetState.Running -> SettingRow(
                        listOf("已连接", st.ip, st.tailnet)
                            .filterNotNull().joinToString(" · "),
                        onClick = null,
                    )
                    is TailnetState.NeedsLogin -> SettingRow(
                        "需要登录",
                        onClick = null,
                        trailing = {
                            BlackPillButton(
                                "登录 Tailscale",
                                onClick = {
                                    st.authUrl?.let {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(it)),
                                        )
                                    }
                                },
                                enabled = st.authUrl != null,
                            )
                        },
                    )
                    is TailnetState.NeedsMachineAuth -> SettingRow(
                        "等待管理员批准此设备",
                        onClick = null,
                    )
                    is TailnetState.Failed -> SettingRow(
                        "失败 · ${st.message}",
                        onClick = { TailnetManager.start() },
                        trailing = { SettingValue("重试") },
                    )
                    is TailnetState.Unsupported -> SettingRow(
                        "不支持此设备",
                        onClick = null,
                    )
                    else -> SettingRow(
                        "启动中…",
                        onClick = null,
                        trailing = {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = cs.onSurfaceVariant,
                            )
                        },
                    )
                }
                if (tailnetState is TailnetState.Running ||
                    tailnetState is TailnetState.NeedsMachineAuth
                ) {
                    SettingRow(
                        "退出 Tailscale 登录",
                        onClick = { logoutConfirm = true },
                    )
                }
                Text(
                    "电脑需安装并登录同一个 Tailscale 账号；Bridge 地址填电脑的 " +
                        "Tailscale 地址（如 100.x.x.x:8790）或 MagicDNS 名称" +
                        "（<电脑名>.ts.net:8790）",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                // Loopback forwarders are reachable by other apps on the phone.
                if (prefs.token.isBlank()) {
                    Text(
                        "建议给 bridge 设置 Token：本机其他应用也能访问转发端口",
                        style = MaterialTheme.typography.bodySmall,
                        color = DevinGoColors.warning,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }

            // ---- 通知 -------------------------------------------------------------
            SectionHeader("通知", Modifier.padding(start = 20.dp))
            val systemOn = notifEnabled && notifPermGranted
            SettingRow(
                "系统通知",
                subtitle = null,
                onClick = {
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !notifPermGranted -> notifLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                        !notifEnabled -> context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(
                                    Settings.EXTRA_APP_PACKAGE,
                                    context.packageName,
                                ),
                        )
                    }
                },
                trailing = {
                    if (systemOn) {
                        Text(
                            "已开启",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    } else {
                        SettingValue("去开启")
                    }
                },
            )
            SettingRow(
                "权限请求提醒",
                subtitle = "Devin 等你授权时提醒",
                onClick = null,
                enabled = systemOn,
                modifier = Modifier.alpha(if (systemOn) 1f else 0.5f),
                trailing = {
                    DevinSwitch(
                        checked = prefs.notifyPermission,
                        onCheckedChange = { prefs.notifyPermission = it },
                        enabled = systemOn,
                    )
                },
            )
            SettingRow(
                "本轮完成提醒",
                subtitle = "一轮对话结束时提醒",
                onClick = null,
                enabled = systemOn,
                modifier = Modifier.alpha(if (systemOn) 1f else 0.5f),
                trailing = {
                    DevinSwitch(
                        checked = prefs.notifyTurnDone,
                        onCheckedChange = { prefs.notifyTurnDone = it },
                        enabled = systemOn,
                    )
                },
            )
            SettingRow(
                "仅在后台时提醒",
                subtitle = "前台使用时不打扰",
                onClick = null,
                enabled = systemOn,
                modifier = Modifier.alpha(if (systemOn) 1f else 0.5f),
                trailing = {
                    DevinSwitch(
                        checked = prefs.notifyOnlyInBackground,
                        onCheckedChange = { prefs.notifyOnlyInBackground = it },
                        enabled = systemOn,
                    )
                },
            )

            // ---- 后台保活 ---------------------------------------------------------
            SectionHeader("后台保活", Modifier.padding(start = 20.dp))
            SettingRow(
                "保持后台连接",
                subtitle = "会话打开时以前台服务保持 WebSocket 连接",
                onClick = null,
                trailing = {
                    DevinSwitch(
                        checked = prefs.keepAlive,
                        onCheckedChange = { prefs.keepAlive = it },
                    )
                },
            )
            SettingRow(
                "电池优化",
                subtitle = null,
                onClick = {
                    if (!batteryUnrestricted) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
                trailing = {
                    if (batteryUnrestricted) {
                        Text(
                            "已不受限制",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    } else {
                        SettingValue("去设置")
                    }
                },
            )
            SettingRow(
                "自启动与后台运行",
                subtitle = "部分国产系统需在应用详情里手动允许",
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )

            // ---- 实时活动 ----------------------------------------------------------
            SectionHeader("实时活动", Modifier.padding(start = 20.dp))
            SettingRow(
                "状态栏实时进度",
                subtitle = if (prefs.keepAlive) {
                    "Devin 工作时在状态栏/锁屏显示进度"
                } else {
                    "需要先开启保持后台连接"
                },
                onClick = null,
                enabled = prefs.keepAlive,
                modifier = Modifier.alpha(if (prefs.keepAlive) 1f else 0.5f),
                trailing = {
                    DevinSwitch(
                        checked = prefs.liveUpdates,
                        onCheckedChange = { prefs.liveUpdates = it },
                        enabled = prefs.keepAlive,
                    )
                },
            )
            SettingRow(
                "系统权限",
                subtitle = if (!promotedSupported) {
                    "需要 Android 16 及以上，当前以普通常驻通知显示"
                } else null,
                onClick = {
                    if (promotedSupported && !promotedGranted) {
                        context.startActivity(
                            Intent(
                                "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS",
                            ).putExtra(
                                "android.provider.extra.APP_PACKAGE",
                                context.packageName,
                            ),
                        )
                    }
                },
                enabled = promotedSupported && !promotedGranted,
                trailing = {
                    when {
                        !promotedSupported -> Unit
                        promotedGranted -> Text(
                            "已允许",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                        else -> SettingValue("去开启")
                    }
                },
            )

            // ---- 生态（M9.2）---------------------------------------------------------
            SectionHeader("生态", Modifier.padding(start = 20.dp))
            val mcpServers by vm.mcpServers.collectAsState()
            SettingRow(
                "MCP 服务",
                subtitle = mcpServers?.let { list ->
                    val on = list.count { it.enabled != false }
                    "${list.size} 个服务器 · $on 个已启用"
                },
                onClick = { ecoSheet = EcoSheet.Mcp },
            )
            SettingRow(
                "Rules · Skills · Plugins",
                onClick = { ecoSheet = EcoSheet.Lists },
            )
            SettingRow(
                "环境诊断",
                onClick = { ecoSheet = EcoSheet.Doctor },
            )

            // ---- 关于 ---------------------------------------------------------------
            SectionHeader("关于", Modifier.padding(start = 20.dp))
            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(
                        context.packageName, 0,
                    ).versionName
                }.getOrNull() ?: "—"
            }
            SettingRow("版本", subtitle = versionName, onClick = null)
            SettingRow(
                "检查更新",
                onClick = { updateSheet = true },
            )
        }
    }

    if (updateSheet) {
        UpdateSheet(onDismiss = { updateSheet = false })
    }

    editField?.let { field ->
        EditFieldSheet(
            field = field,
            prefs = prefs,
            onDismiss = { editField = null },
        )
    }
    when (ecoSheet) {
        EcoSheet.Mcp -> McpServersSheet(vm, onDismiss = { ecoSheet = null })
        EcoSheet.Lists -> EcoListSheet(vm, onDismiss = { ecoSheet = null })
        EcoSheet.Doctor -> DoctorSheet(vm, onDismiss = { ecoSheet = null })
        null -> Unit
    }
    if (logoutConfirm) {
        AlertDialog(
            onDismissRequest = { logoutConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    logoutConfirm = false
                    TailnetManager.logout()
                }) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = { logoutConfirm = false }) { Text("取消") }
            },
            title = { Text("退出 Tailscale 登录") },
            text = { Text("此设备将从 tailnet 中移除登录，下次使用内置 Tailscale 时需要重新授权。") },
        )
    }
}

private fun masked(value: String): String =
    if (value.isBlank()) "未设置" else "••••••••"

private enum class EditField { Host, Token, Cwd, ApiKey, RefusalFallback }

/** Which M9.2 ecosystem sheet is open over settings. */
private enum class EcoSheet { Mcp, Lists, Doctor }

/** Single-field editor sheet; secrets get a visibility toggle. */
@Composable
private fun EditFieldSheet(field: EditField, prefs: Prefs, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val (title, initial, secret) = when (field) {
        EditField.Host -> Triple("Bridge 地址", prefs.host, false)
        EditField.Token -> Triple("Token", prefs.token, true)
        EditField.Cwd -> Triple("默认工作目录", prefs.cwd, false)
        EditField.ApiKey -> Triple("Devin API Key", prefs.apiKey, true)
        EditField.RefusalFallback -> Triple(
            "策略拒答回退模型（如 opus）",
            prefs.refusalFallbackModel,
            false,
        )
    }
    var value by remember(field) { mutableStateOf(initial) }
    var showSecret by remember { mutableStateOf(false) }
    var fieldError by remember(field) { mutableStateOf<String?>(null) }
    DevinSheet(title = title, onDismiss = onDismiss) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; fieldError = null },
            isError = fieldError != null,
            supportingText = {
                fieldError?.let { Text(it, color = cs.error) }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (secret && !showSecret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = cs.onSurface,
                unfocusedBorderColor = cs.outlineVariant,
                cursorColor = cs.onSurface,
            ),
            trailingIcon = if (secret) {
                {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) HugeIcons.ViewOff else HugeIcons.View,
                            if (showSecret) "隐藏" else "显示",
                            modifier = Modifier.size(20.dp),
                            tint = cs.onSurfaceVariant,
                        )
                    }
                }
            } else {
                null
            },
        )
        Spacer(Modifier.height(16.dp))
        BlackPillButton(
            text = "保存",
            onClick = {
                var ok = true
                when (field) {
                    // Manual entries join the candidate list (C5).
                    EditField.Host -> prefs.noteHostCandidate(value.trim())
                    EditField.Token -> prefs.token = value.trim()
                    EditField.Cwd -> prefs.cwd = value.trim()
                    EditField.ApiKey -> prefs.apiKey = value.trim()
                    // Bridge validates the same charset and -32602s a bad
                    // name — catch it here so a typo doesn't turn every
                    // session/new into a silent protocol error (W8.3 M6).
                    EditField.RefusalFallback -> {
                        val v = value.trim()
                        if (v.isEmpty() ||
                            v.matches(Regex("^[A-Za-z0-9][A-Za-z0-9 ._:\\-+/]{0,63}(,[A-Za-z0-9][A-Za-z0-9 ._:\\-+/]{0,63})*$"))) {
                            prefs.refusalFallbackModel = v
                        } else {
                            fieldError = "可逗号分隔多个模型，每项限字母数字与 ._:-+/，≤64 字符"
                            ok = false
                        }
                    }
                }
                if (ok) onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
