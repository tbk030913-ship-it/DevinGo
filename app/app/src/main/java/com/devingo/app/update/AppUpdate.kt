// M10: GitHub Releases self-update — check the public repo's latest release,
// pick the APK matching the device's ABI, download into cacheDir/updates and
// hand off to the system installer. Networking rides the bridge's shared
// OkHttp pool; the pure helpers (version compare, APK pick, URL gate) stay
// Android-free so src/test can exercise them.
package com.devingo.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.devingo.app.acp.bridgeHttpClient
import com.devingo.app.acp.longField
import com.devingo.app.acp.strField
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** GitHub Releases entry points for the public DevinGo repo. */
const val RELEASES_API_URL =
    "https://api.github.com/repos/tbk030913-ship-it/DevinGo/releases/latest"
const val RELEASES_PAGE_URL = "https://github.com/tbk030913-ship-it/DevinGo/releases"

private const val MAX_DOWNLOAD_REDIRECTS = 5

/** Update check/download failures carrying a user-facing message. */
open class UpdateException(message: String) : Exception(message)

/** /releases/latest answered 404 — the repo has no published release yet. */
class NoReleaseException : UpdateException("暂无已发布版本")

/** The download was cancelled (取消按钮或 sheet 关闭). */
class DownloadAborted : CancellationException(null)

/** One asset attached to a GitHub release. */
data class ReleaseAsset(val name: String, val size: Long, val downloadUrl: String)

/** The repo's latest published release. */
data class GithubRelease(
    val tagName: String,
    val name: String,
    val publishedAt: String,
    val body: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
)

private val updateJson = Json { ignoreUnknownKeys = true }

/**
 * Semantic version compare of two dotted-numeric tags (leading `v`/`V`,
 * `-pre`/`+meta` suffixes dropped; missing segments read as 0):
 * `0.10.0 > 0.2.0`. Positive = [remote] is newer.
 */
fun compareVersions(remote: String, local: String): Int {
    val a = versionSegments(remote)
    val b = versionSegments(local)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0L }
        val y = b.getOrElse(i) { 0L }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

private fun versionSegments(v: String): List<Long> =
    v.trim().removePrefix("v").removePrefix("V")
        .substringBefore('-').substringBefore('+')
        .split('.')
        .map { it.takeWhile(Char::isDigit).toLongOrNull() ?: 0L }

/**
 * Pick the installable APK from release [assets]: the first device ABI
 * ([supportedAbis] in `Build.SUPPORTED_ABIS` priority order) whose name
 * appears in a filename, else `universal`, else the sole/first `.apk`.
 * Debug builds are skipped whenever a non-debug APK exists.
 */
fun pickApkAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return null
    val pool = apks.filterNot { it.name.contains("debug", ignoreCase = true) }
        .ifEmpty { apks }
    supportedAbis.forEach { abi ->
        // Delimited match — plain contains() would let a 32-bit "x86"
        // device claim an "x86_64" build it can't install. Gradle splits
        // emit `app-<abi>-<variant>.apk` (or `app-<abi>.apk`).
        pool.firstOrNull {
            it.name.contains("-$abi-", ignoreCase = true) ||
                it.name.endsWith("-$abi.apk", ignoreCase = true)
        }?.let { return it }
    }
    pool.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return it }
    return pool.firstOrNull()
}

/**
 * The download allowlist: https only, host `github.com` or under
 * `*.githubusercontent.com` (the release CDN a `browser_download_url`
 * 302s to). Every redirect hop is re-checked against this gate.
 */
fun isAllowedDownloadUrl(url: HttpUrl): Boolean {
    if (!url.isHttps) return false
    val host = url.host.lowercase()
    return host == "github.com" || host.endsWith(".githubusercontent.com")
}

/**
 * `GET /releases/latest`. Throws [NoReleaseException] on 404, an
 * [UpdateException] with a Chinese message on rate-limit/network/other
 * failures — the sheet shows it verbatim.
 */
suspend fun fetchLatestRelease(currentVersionName: String): GithubRelease =
    withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(RELEASES_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DevinGo/$currentVersionName")
            .build()
        val body = try {
            bridgeHttpClient.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> resp.body?.string().orEmpty()
                    404 -> throw NoReleaseException()
                    403 -> throw UpdateException("GitHub 接口限流，请稍后再试")
                    else -> throw UpdateException("检查更新失败（HTTP ${resp.code}）")
                }
            }
        } catch (e: IOException) {
            throw UpdateException("网络连接失败，请检查网络后重试")
        }
        try {
            parseRelease(body)
        } catch (e: Exception) {
            throw UpdateException("返回数据异常")
        }
    }

private fun parseRelease(body: String): GithubRelease {
    val obj = updateJson.parseToJsonElement(body).jsonObject
    val assets = (obj["assets"] as? JsonArray)?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val name = o.strField("name") ?: return@mapNotNull null
        val url = o.strField("browser_download_url") ?: return@mapNotNull null
        ReleaseAsset(name, o.longField("size") ?: 0L, url)
    } ?: emptyList()
    return GithubRelease(
        tagName = obj.strField("tag_name").orEmpty(),
        name = obj.strField("name").orEmpty(),
        publishedAt = obj.strField("published_at").orEmpty(),
        body = obj.strField("body").orEmpty(),
        htmlUrl = obj.strField("html_url").orEmpty(),
        assets = assets,
    )
}

/**
 * Download [asset] into `cacheDir/updates/` — old APKs are swept first, the
 * stream lands in a `.tmp` file that is renamed into place on completion
 * (a crash never leaves a half APK). Redirects are walked by hand so every
 * hop must still satisfy [isAllowedDownloadUrl]. [onCall] exposes the live
 * OkHttp call for the cancel button; [onProgress] reports bytes. Throws
 * [DownloadAborted] on cancel, [UpdateException] otherwise.
 */
suspend fun downloadApk(
    context: Context,
    asset: ReleaseAsset,
    onCall: (Call) -> Unit = {},
    onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
): File = withContext(Dispatchers.IO) {
    val client = bridgeHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    // AtomicReference: the completion hook reads it off-thread.
    val activeCall = AtomicReference<Call?>()
    val cancelHook = coroutineContext.job.invokeOnCompletion {
        activeCall.get()?.cancel()
    }
    try {
        var url = asset.downloadUrl
        var resp: okhttp3.Response? = null
        var hops = 0
        while (resp == null) {
            val httpUrl = url.toHttpUrlOrNull() ?: throw UpdateException("下载地址无效")
            if (!isAllowedDownloadUrl(httpUrl)) {
                throw UpdateException("下载地址未指向 GitHub，已中止")
            }
            val call = client.newCall(Request.Builder().url(httpUrl).build())
            activeCall.set(call)
            onCall(call)
            // Cancelled between hops or before execute() — the hook only
            // sees the call once it's registered.
            if (!isActive) {
                call.cancel()
                throw DownloadAborted()
            }
            val r = try {
                call.execute()
            } catch (e: IOException) {
                if (call.isCanceled()) throw DownloadAborted()
                throw UpdateException("网络连接失败，请检查网络后重试")
            }
            if (r.isRedirect) {
                val next = r.header("Location")
                r.close()
                if (next == null || ++hops > MAX_DOWNLOAD_REDIRECTS) {
                    throw UpdateException("下载重定向异常")
                }
                url = next
            } else {
                resp = r
            }
        }
        resp.use { r ->
            if (!r.isSuccessful) throw UpdateException("下载失败（HTTP ${r.code}）")
            val body = r.body ?: throw UpdateException("下载内容为空")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, asset.name + ".tmp")
            val out = File(dir, asset.name)
            try {
                body.byteStream().use { ins ->
                    tmp.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        val total = body.contentLength()
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
            } catch (e: IOException) {
                tmp.delete()
                if (activeCall.get()?.isCanceled() == true || !isActive) {
                    throw DownloadAborted()
                }
                throw UpdateException("下载中断，请重试")
            }
            if (!tmp.renameTo(out)) {
                tmp.delete()
                throw UpdateException("安装包写入失败")
            }
            out
        }
    } finally {
        cancelHook.dispose()
    }
}

/**
 * Package-name check on the downloaded APK — refuses to hand the installer
 * an archive that isn't this app (a tampered or wrong asset).
 */
fun verifyApkPackage(context: Context, file: File): Boolean {
    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageArchiveInfo(
            file.absolutePath, PackageManager.PackageInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
    }
    return info?.packageName == context.packageName
}

/** System-installer intent for a downloaded APK (FileProvider-shared). */
fun installApkIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** 「安装未知应用」settings page for this app — granting resumes the install. */
fun unknownSourcesIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )
