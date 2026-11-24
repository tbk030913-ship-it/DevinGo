package com.devingo.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devingo.app.BuildConfig
import com.devingo.app.update.DownloadAborted
import com.devingo.app.update.GithubRelease
import com.devingo.app.update.NoReleaseException
import com.devingo.app.update.RELEASES_PAGE_URL
import com.devingo.app.update.ReleaseAsset
import com.devingo.app.update.compareVersions
import com.devingo.app.update.downloadApk
import com.devingo.app.update.fetchLatestRelease
import com.devingo.app.update.installApkIntent
import com.devingo.app.update.pickApkAsset
import com.devingo.app.update.unknownSourcesIntent
import com.devingo.app.update.verifyApkPackage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call

// ---- M10: 检查更新 -----------------------------------------------------------
//
// 设置页「关于」里的自更新 sheet：查 GitHub Releases → 按 ABI 挑 APK →
// 下载到 cacheDir/updates → 直接拉起系统安装器。下载被取消/校验失败/缺
// 「安装未知应用」权限都落在 sheet 内的独立状态，不会闪退或丢现场。

private enum class UpdatePhase {
    Checking, Info, Available, Downloading, AwaitPermission, Ready, Failed
}

@Composable
fun UpdateSheet(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(UpdatePhase.Checking) }
    var infoText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var release by remember { mutableStateOf<GithubRelease?>(null) }
    var apk by remember { mutableStateOf<ReleaseAsset?>(null) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(-1L) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var downloadCall by remember { mutableStateOf<Call?>(null) }

    fun openReleasePage() {
        val url = release?.htmlUrl?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE_URL
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun installOrAsk(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            phase = UpdatePhase.AwaitPermission
            return
        }
        runCatching { context.startActivity(installApkIntent(context, file)) }
            .onSuccess { phase = UpdatePhase.Ready }
            .onFailure {
                errorText = "无法拉起系统安装器"
                phase = UpdatePhase.Failed
            }
    }

    // Returning from the「安装未知应用」page fires ON_RESUME — the download
    // already finished, so a granted switch continues into the installer
    // without the user hunting for the file.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, phase, apkFile) {
        if (phase != UpdatePhase.AwaitPermission) {
            return@DisposableEffect onDispose { }
        }
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME &&
                context.packageManager.canRequestPackageInstalls()
            ) {
                apkFile?.let { installOrAsk(it) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun check() {
        scope.launch {
            phase = UpdatePhase.Checking
            errorText = ""
            try {
                val rel = fetchLatestRelease(BuildConfig.VERSION_NAME)
                release = rel
                if (compareVersions(rel.tagName, BuildConfig.VERSION_NAME) <= 0) {
                    infoText = "当前已是最新版本"
                    phase = UpdatePhase.Info
                } else {
                    apk = pickApkAsset(rel.assets, Build.SUPPORTED_ABIS.toList())
                    phase = UpdatePhase.Available
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoReleaseException) {
                infoText = e.message ?: "暂无已发布版本"
                phase = UpdatePhase.Info
            } catch (e: Exception) {
                errorText = e.message ?: "检查更新失败"
                phase = UpdatePhase.Failed
            }
        }
    }

    fun startDownload() {
        val asset = apk ?: return
        scope.launch {
            phase = UpdatePhase.Downloading
            downloadedBytes = 0
            totalBytes = -1
            try {
                val file = downloadApk(
                    context, asset,
                    onCall = { downloadCall = it },
                    onProgress = { d, t -> downloadedBytes = d; totalBytes = t },
                )
                if (!verifyApkPackage(context, file)) {
                    file.delete()
                    errorText = "安装包校验失败：不是本应用的安装包"
                    phase = UpdatePhase.Failed
                    return@launch
                }
                apkFile = file
                installOrAsk(file)
            } catch (e: DownloadAborted) {
                // Sheet still open → back to the version card; a dismissed
                // sheet has no state left to write.
                if (isActive) phase = UpdatePhase.Available
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorText = e.message ?: "下载失败"
                phase = UpdatePhase.Failed
            } finally {
                downloadCall = null
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    DevinSheet("检查更新", onDismiss = onDismiss, scrollContent = true) {
        when (phase) {
            UpdatePhase.Checking -> Column(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    Modifier.size(24.dp), strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant,
                )
                Text(
                    "正在检查更新…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }

            UpdatePhase.Info -> {
                Text(
                    infoText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                BlackPillButton("完成", onClick = onDismiss)
            }

            UpdatePhase.Available -> {
                val rel = release ?: return@DevinSheet
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "发现新版本 ${rel.tagName}",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (rel.publishedAt.isNotBlank()) {
                        Text(
                            rel.publishedAt.take(10),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                if (rel.body.isNotBlank()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .padding(top = 8.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            rel.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                val asset = apk
                if (asset != null) {
                    Text(
                        "${asset.name} · ${formatSize(asset.size)}",
                        style = monoText(MaterialTheme.typography.bodySmall),
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    BlackPillButton(
                        "立即下载并安装",
                        onClick = { startDownload() },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Text(
                        "该版本未提供可安装的 APK",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                OutlinePillButton(
                    "在浏览器打开 Release",
                    onClick = { openReleasePage() },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            UpdatePhase.Downloading -> {
                val total = totalBytes
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    )
                    Text(
                        "已下载 ${formatSize(downloadedBytes)} / ${formatSize(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    )
                    Text(
                        "已下载 ${formatSize(downloadedBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                OutlinePillButton(
                    "取消下载",
                    onClick = { downloadCall?.cancel() },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            UpdatePhase.AwaitPermission -> {
                Text(
                    "需要「安装未知应用」权限才能继续安装",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    "授权后自动继续安装，无需重新下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                BlackPillButton(
                    "去授权",
                    onClick = {
                        runCatching {
                            context.startActivity(unknownSourcesIntent(context))
                        }
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            UpdatePhase.Ready -> {
                Text(
                    "已打开系统安装界面，按提示完成安装",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    "若系统提示签名不一致，请先卸载当前版本再重新安装",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                BlackPillButton(
                    "重新安装",
                    onClick = { apkFile?.let { installOrAsk(it) } },
                    modifier = Modifier.padding(top = 16.dp),
                )
                OutlinePillButton(
                    "在浏览器打开 Release",
                    onClick = { openReleasePage() },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            UpdatePhase.Failed -> {
                Text(
                    errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                BlackPillButton(
                    "重试",
                    onClick = {
                        if (release != null && apk != null) startDownload() else check()
                    },
                )
                OutlinePillButton(
                    "在浏览器打开 Release",
                    onClick = { openReleasePage() },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 0) "%.1f MB".format(bytes / 1048576f) else "—"
