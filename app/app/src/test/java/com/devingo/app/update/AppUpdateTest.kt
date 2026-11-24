// JVM tests for the update checker's pure helpers (M10): semantic version
// compare, ABI-matched APK selection and the download-URL allowlist.
package com.devingo.app.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    private fun asset(name: String, size: Long = 1L) =
        ReleaseAsset(name, size, "https://github.com/x/$name")

    @Test
    fun compareVersions_numericSegments() {
        assertTrue(compareVersions("0.10.0", "0.2.0") > 0)
        assertTrue(compareVersions("0.2.1", "0.2.0") > 0)
        assertTrue(compareVersions("v0.3.0", "0.2.9") > 0)
        assertTrue(compareVersions("V1.0.0", "0.99.9") > 0)
        assertTrue(compareVersions("0.2.0", "0.2.0") == 0)
        assertTrue(compareVersions("0.2", "0.2.0") == 0)
        assertTrue(compareVersions("0.2.0", "0.2.1") < 0)
        assertTrue(compareVersions("0.9.9", "0.10.0") < 0)
        // Pre-release/build suffixes don't shift the numeric compare.
        assertTrue(compareVersions("0.3.0-beta", "0.2.9") > 0)
        assertTrue(compareVersions("0.3.0+build5", "0.3.0") == 0)
    }

    @Test
    fun pickApkAsset_matchesDeviceAbiFirst() {
        val assets = listOf(
            asset("app-x86_64-release.apk"),
            asset("app-arm64-v8a-release.apk"),
            asset("app-universal-release.apk"),
        )
        assertEquals("app-arm64-v8a-release.apk",
            pickApkAsset(assets, listOf("arm64-v8a"))?.name)
        assertEquals("app-x86_64-release.apk",
            pickApkAsset(assets, listOf("x86_64"))?.name)
        // SUPPORTED_ABIS order wins over asset order.
        assertEquals("app-x86_64-release.apk",
            pickApkAsset(assets, listOf("x86_64", "arm64-v8a"))?.name)
        assertEquals("app-arm64-v8a-release.apk",
            pickApkAsset(assets, listOf("arm64-v8a", "x86_64"))?.name)
    }

    @Test
    fun pickApkAsset_universalThenFallback() {
        val assets = listOf(
            asset("app-arm64-v8a-release.apk"),
            asset("app-universal-release.apk"),
        )
        // Unknown ABI → universal.
        assertEquals("app-universal-release.apk",
            pickApkAsset(assets, listOf("armeabi-v7a"))?.name)
        // A 32-bit x86 device must not claim the x86_64 build — the ABI
        // token is matched delimited, not as a substring.
        assertEquals("app-universal-release.apk",
            pickApkAsset(listOf(asset("app-x86_64-release.apk"),
                asset("app-universal-release.apk")), listOf("x86"))?.name)
        // No universal, no ABI hit → first apk.
        assertEquals("app-arm64-v8a-release.apk",
            pickApkAsset(listOf(asset("app-arm64-v8a-release.apk")),
                listOf("x86_64"))?.name)
        // Non-apk assets ignored; empty → null.
        assertNull(pickApkAsset(
            listOf(asset("checksums.txt"), asset("bundle.aab")),
            listOf("arm64-v8a")))
        assertNull(pickApkAsset(emptyList(), listOf("arm64-v8a")))
    }

    @Test
    fun pickApkAsset_prefersNonDebug() {
        val assets = listOf(
            asset("app-arm64-v8a-debug.apk"),
            asset("app-arm64-v8a-release.apk"),
        )
        assertEquals("app-arm64-v8a-release.apk",
            pickApkAsset(assets, listOf("arm64-v8a"))?.name)
        // Only debug APKs → still installable.
        assertEquals("app-arm64-v8a-debug.apk",
            pickApkAsset(listOf(asset("app-arm64-v8a-debug.apk")),
                listOf("arm64-v8a"))?.name)
    }

    @Test
    fun isAllowedDownloadUrl_githubOnlyOverHttps() {
        assertTrue(isAllowedDownloadUrl(
            "https://github.com/tbk030913-ship-it/DevinGo/releases/download/v0.3.0/app-arm64-v8a.apk"
                .toHttpUrl()))
        assertTrue(isAllowedDownloadUrl(
            "https://objects.githubusercontent.com/github-production-release-asset/x".toHttpUrl()))
        assertTrue(isAllowedDownloadUrl(
            "https://release-assets.githubusercontent.com/x".toHttpUrl()))
        // http, look-alikes and suffix tricks stay out.
        assertFalse(isAllowedDownloadUrl("http://github.com/x".toHttpUrl()))
        assertFalse(isAllowedDownloadUrl("https://evilgithub.com/x".toHttpUrl()))
        assertFalse(isAllowedDownloadUrl("https://github.com.evil.com/x".toHttpUrl()))
        assertFalse(isAllowedDownloadUrl("https://api.github.com/x".toHttpUrl()))
        assertFalse(isAllowedDownloadUrl(
            "https://githubusercontent.com.evil.com/x".toHttpUrl()))
    }
}
