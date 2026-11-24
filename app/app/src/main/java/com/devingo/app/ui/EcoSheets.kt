package com.devingo.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.devingo.app.ChatViewModel
import com.devingo.app.acp.EcoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- M9.2: ecosystem sheets ---------------------------------------------------
//
// MCP 服务 (live list + enable/disable + OAuth sign-in), Rules/Skills/Plugins
// (read-only CLI viewers) and 环境诊断 (`devin doctor`). Every row's data comes
// from the bridge's `devin <x>` shell-outs — the wire shapes are tolerant
// (`items` when JSON mode parsed, raw `output` text otherwise).

/** Status text that suggests the server wants OAuth — shows its 登录 button. */
private fun EcoItem.needsAuth(): Boolean {
    val s = "$status $detail"
    return listOf("auth", "oauth", "unauthorized", "未认证", "未授权", "登录")
        .any { s.contains(it, ignoreCase = true) }
}

private fun EcoItem.statusLabel(): String = when {
    enabled == false -> "已禁用"
    status.isNotBlank() -> status
    else -> ""
}

/** MCP 服务 — `devin mcp list` rows with enable/disable switches. */
@Composable
fun McpServersSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val servers by vm.mcpServers.collectAsState()
    val authReq by vm.mcpAuthRequest.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var busyName by remember { mutableStateOf<String?>(null) }
    var signIn by remember { mutableStateOf<Pair<String, String?>?>(null) }

    LaunchedEffect(Unit) { vm.refreshMcpServers() }

    DevinSheet("MCP 服务", onDismiss = onDismiss, scrollContent = true) {
        // Agent-side OAuth ask (…/mcp/authorizationRequired) surfaces here.
        authReq?.let { req ->
            SettingRow(
                "「${req.serverName.ifBlank { "服务器" }}」需要授权",
                subtitle = "Devin 发起了 OAuth 登录请求",
                onClick = {
                    signIn = req.serverName to req.authorizationUrl
                    vm.dismissMcpAuthRequest()
                },
                trailing = { SettingValue("去登录") },
            )
        }
        when (val list = servers) {
            null -> Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(24.dp), strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant,
                )
            }
            else -> {
                if (list.isEmpty()) {
                    Text(
                        "暂无 MCP 服务器",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
                list.forEach { srv ->
                    val enabled = srv.enabled != false
                    SettingRow(
                        title = srv.name,
                        subtitle = listOf(srv.statusLabel(), srv.detail)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ").ifBlank { null },
                        onClick = null,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (srv.needsAuth()) {
                                    TextButton(onClick = {
                                        signIn = srv.name to null
                                    }) {
                                        Text("登录", color = cs.primary)
                                    }
                                }
                                if (busyName == srv.name) {
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = cs.onSurfaceVariant,
                                    )
                                } else {
                                    DevinSwitch(
                                        checked = enabled,
                                        enabled = busyName == null,
                                        onCheckedChange = { on ->
                                            busyName = srv.name
                                            error = null
                                            scope.launch {
                                                error = vm.setMcpServerEnabled(
                                                    srv.name, on,
                                                )
                                                busyName = null
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = cs.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    signIn?.let { (name, url) ->
        McpSignInSheet(
            vm = vm,
            serverName = name,
            initialAuthUrl = url,
            onFinished = {
                vm.refreshMcpServers()
                signIn = null
            },
            onDismiss = { signIn = null },
        )
    }
}

private sealed interface SignInState {
    data object Loading : SignInState
    /** OAuth URL known — open it in a browser, then paste the callback. */
    data class Ready(val authUrl: String) : SignInState
    data object Done : SignInState
    data class Failed(val message: String) : SignInState
}

/**
 * OAuth sign-in for one MCP server (t3code McpSignInSheet): `devin mcp
 * login` prints the authorization URL, the phone opens it in a browser,
 * the user pastes the final localhost callback URL back, and the bridge
 * delivers it to the parked login process.
 */
@Composable
fun McpSignInSheet(
    vm: ChatViewModel,
    serverName: String,
    initialAuthUrl: String?,
    onFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SignInState>(SignInState.Loading) }
    var pasted by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(serverName) {
        if (initialAuthUrl != null) {
            state = SignInState.Ready(initialAuthUrl)
            return@LaunchedEffect
        }
        try {
            var st = vm.startMcpLogin(serverName)
            // The URL can take a few seconds — keep polling until it shows
            // or the login itself exits.
            var waited = 0
            while (st.authUrl == null && !st.done && waited < 20) {
                delay(1000)
                st = vm.mcpLoginStatus(serverName) ?: break
                waited++
            }
            state = when {
                st.done && st.error == null -> SignInState.Done
                st.done -> SignInState.Failed(
                    st.error ?: st.output.ifBlank { "登录进程已退出" },
                )
                st.authUrl != null -> SignInState.Ready(st.authUrl!!)
                else -> SignInState.Failed("未获取到授权链接")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = SignInState.Failed(e.message ?: "登录失败")
        }
    }

    DevinSheet(
        "登录 $serverName",
        onDismiss = onDismiss,
        scrollContent = true,
    ) {
        when (val s = state) {
            SignInState.Loading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(24.dp), strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant,
                )
            }
            SignInState.Done -> {
                Text(
                    "已完成授权",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                BlackPillButton("完成", onClick = onFinished)
            }
            is SignInState.Failed -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                BlackPillButton("关闭", onClick = onDismiss)
            }
            is SignInState.Ready -> {
                BlackPillButton(
                    "在浏览器中打开授权页",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(s.authUrl)),
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "授权完成后，把浏览器地址栏里 localhost 开头的回调链接粘贴到下面",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("http://localhost:…/callback?code=…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cs.outline,
                        unfocusedBorderColor = cs.outlineVariant,
                    ),
                )
                BlackPillButton(
                    "提交回调链接",
                    onClick = {
                        submitting = true
                        scope.launch {
                            try {
                                val res = vm.submitMcpAuthCallback(
                                    serverName, pasted.trim(),
                                )
                                // stdin 方式立即进入收尾；fetch 方式等 login
                                // 进程退出后再收尾。
                                var st = vm.mcpLoginStatus(serverName)
                                var waited = 0
                                while (st != null && !st.done && waited < 10) {
                                    delay(1000)
                                    st = vm.mcpLoginStatus(serverName)
                                    waited++
                                }
                                if (res.ok || st?.done == true) {
                                    state = SignInState.Done
                                } else {
                                    state = SignInState.Failed(
                                        res.body.ifBlank { "回调未被接受" }
                                            .take(300),
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                state = SignInState.Failed(
                                    e.message ?: "提交失败",
                                )
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    loading = submitting,
                    enabled = pasted.startsWith("http") && !submitting,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

// ---- Skills / Rules / Plugins --------------------------------------------------

private enum class EcoKind(val path: String, val label: String) {
    Skills("skills", "Skills"),
    Rules("rules", "Rules"),
    Plugins("plugins", "Plugins"),
}

/**
 * Read-only browser for `devin skills|rules|plugins list` — three tabs, tap a
 * row for `devin <x> show` detail (skills/rules; plugins have no show).
 */
@Composable
fun EcoListSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(EcoKind.Skills) }
    var items by remember { mutableStateOf<List<EcoItem>?>(null) }
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    // name → detail text while `devin show` is in flight/landed.
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(kind) {
        items = null
        error = null
        detail = null
        try {
            val list = vm.fetchEcoList(kind.path)
            items = list.items
            raw = list.output
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        }
    }

    DevinSheet("Rules · Skills · Plugins", onDismiss = onDismiss) {
        // Tab row — text chips over the same row-title baseline.
        Row(Modifier.padding(bottom = 8.dp)) {
            EcoKind.entries.forEach { k ->
                val active = k == kind
                Text(
                    k.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (active) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (active) cs.surfaceContainerHighest
                            else cs.surfaceContainerLowest,
                        )
                        .clickable { kind = k }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                error != null -> Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.error,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                detail != null -> {
                    SettingRow(
                        "返回列表",
                        onClick = { detail = null },
                    )
                    Text(
                        detail!!.second.ifBlank { "（无内容）" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = cs.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                items == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp), strokeWidth = 2.dp,
                        color = cs.onSurfaceVariant,
                    )
                }
                items!!.isEmpty() && raw.isBlank() -> Text(
                    "暂无条目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
                items!!.isEmpty() -> Text(
                    // JSON mode didn't parse — show the CLI's table text.
                    raw,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = cs.onSurface,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                else -> items!!.forEach { item ->
                    SettingRow(
                        title = item.name,
                        subtitle = listOf(item.status, item.detail, item.path)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ").ifBlank { null },
                        onClick = if (kind == EcoKind.Plugins) null else {
                            {
                                val name = item.name
                                detail = name to "加载中…"
                                scope.launch {
                                    detail = name to try {
                                        vm.fetchEcoShow(kind.path, name)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        e.message ?: "加载失败"
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---- 环境诊断 ---------------------------------------------------------------------

/** `devin doctor` output — a plain scrollable dump of the checks. */
@Composable
fun DoctorSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            text = vm.fetchDoctor()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "诊断失败"
        }
    }
    DevinSheet("环境诊断", onDismiss = onDismiss, scrollContent = true) {
        when {
            error != null -> Text(
                error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.error,
                modifier = Modifier.padding(vertical = 20.dp),
            )
            text == null -> Row(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(24.dp), strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant,
                )
            }
            else -> Text(
                text!!.ifBlank { "（无输出）" },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = cs.onSurface,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
