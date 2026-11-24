package com.devingo.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Last-used connection settings, persisted between launches.
 *
 * Secrets (bridge token, Devin API key) are stored AES/GCM-encrypted with a
 * key held in AndroidKeyStore — never in cleartext. Values written before
 * encryption existed are migrated transparently on first read.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("devingo", Context.MODE_PRIVATE)

    /** Bumped on every boolean-pref write so collectors can react live. */
    private val _settingsVersion = MutableStateFlow(0)
    val settingsVersion: StateFlow<Int> = _settingsVersion.asStateFlow()

    private fun bumpSettings() {
        _settingsVersion.value += 1
    }

    /**
     * Live mirror of [host] — kept in sync by a preference listener so
     * writes through ANY Prefs instance (e.g. the connection manager's
     * candidate switch) reach collectors. The listener is held strongly:
     * SharedPreferences only weakly references listeners.
     */
    private val _host = MutableStateFlow(host)
    val hostFlow: StateFlow<String> = _host.asStateFlow()

    private val hostListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "host") _host.value = host
        }

    init {
        sp.registerOnSharedPreferenceChangeListener(hostListener)
    }

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    /**
     * C5: every bridge address worth trying — QR LAN candidates first, the
     * tailnet one last, plus manually entered ones. [host] is the member
     * currently considered active. Falls back to [host] alone on installs
     * that predate the list.
     */
    var hostCandidates: List<String>
        get() {
            val raw = sp.getString("hosts", null)
                ?: return listOf(host).filter { it.isNotBlank() }
            return runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).map { a.optString(it) }
            }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        }
        set(v) = sp.edit()
            .putString("hosts", JSONArray(v.filter { it.isNotBlank() }.distinct()).toString())
            .apply()

    /** Make [h] the active host; a new address joins the candidates first. */
    fun noteHostCandidate(h: String) {
        val cur = hostCandidates
        if (h.isNotBlank() && h !in cur) hostCandidates = listOf(h) + cur
        host = h
    }

    var token: String
        get() = getSecret("token")
        set(v) = putSecret("token", v)

    var cwd: String
        get() = sp.getString("cwd", "") ?: ""
        set(v) = sp.edit().putString("cwd", v).apply()

    var lastSessionId: String
        get() = sp.getString("lastSessionId", "") ?: ""
        set(v) = sp.edit().putString("lastSessionId", v).apply()

    /** Optional Devin API key — skips the per-connection browser login. */
    var apiKey: String
        get() = getSecret("apiKey")
        set(v) = putSecret("apiKey", v)

    var lastSessionCwd: String
        get() = sp.getString("lastSessionCwd", "") ?: ""
        set(v) = sp.edit().putString("lastSessionCwd", v).apply()

    // ---- notification / keep-alive preferences (v2 §L) -----------------------

    /** Notify when a permission request is waiting for a decision. */
    var notifyPermission: Boolean
        get() = sp.getBoolean("notifyPermission", true)
        set(v) { sp.edit().putBoolean("notifyPermission", v).apply(); bumpSettings() }

    /** Notify when a prompt turn finishes. */
    var notifyTurnDone: Boolean
        get() = sp.getBoolean("notifyTurnDone", true)
        set(v) { sp.edit().putBoolean("notifyTurnDone", v).apply(); bumpSettings() }

    /** Only post turn/permission notifications while the app is backgrounded. */
    var notifyOnlyInBackground: Boolean
        get() = sp.getBoolean("notifyOnlyInBackground", true)
        set(v) { sp.edit().putBoolean("notifyOnlyInBackground", v).apply(); bumpSettings() }

    /** Foreground service keeps the ACP socket alive while a session is open. */
    var keepAlive: Boolean
        get() = sp.getBoolean("keepAlive", true)
        set(v) { sp.edit().putBoolean("keepAlive", v).apply(); bumpSettings() }

    /** Ask the system to promote session notifications to Live Updates. */
    var liveUpdates: Boolean
        get() = sp.getBoolean("liveUpdates", true)
        set(v) { sp.edit().putBoolean("liveUpdates", v).apply(); bumpSettings() }

    /** Embedded Tailscale node: tailnet bridge hosts route over the tailnet. */
    var tailnetEnabled: Boolean
        get() = sp.getBoolean("tailnetEnabled", false)
        set(v) { sp.edit().putBoolean("tailnetEnabled", v).apply(); bumpSettings() }

    /**
     * W8.3: model name for `devin acp`'s policy-refusal fallback
     * (`DEVIN_REFUSAL_FALLBACK`) — sent on session/new so the bridge
     * spawns the session's process with it. Blank = no fallback.
     */
    var refusalFallbackModel: String
        get() = sp.getString("refusalFallbackModel", "") ?: ""
        set(v) = sp.edit().putString("refusalFallbackModel", v).apply()

    /**
     * Project-group expansion overrides for the session list. Null until the
     * user toggles a group — before that, HomeScreen computes defaults.
     */
    var expandedProjects: Set<String>?
        get() = sp.getStringSet("expandedProjects", null)?.toSet()
        set(v) {
            if (v == null) {
                sp.edit().remove("expandedProjects").apply()
            } else {
                sp.edit().putStringSet("expandedProjects", v.toSet()).apply()
            }
        }

    private fun getSecret(key: String): String {
        val raw = sp.getString(key, "") ?: ""
        if (raw.isEmpty()) return ""
        if (!raw.startsWith(ENC_PREFIX)) {
            // Legacy cleartext value — migrate it under the key now.
            putSecret(key, raw)
            return raw
        }
        return KeystoreBox.decrypt(raw.removePrefix(ENC_PREFIX)).orEmpty()
    }

    private fun putSecret(key: String, value: String) {
        val stored = if (value.isEmpty()) {
            ""
        } else {
            // If the keystore is unusable (rare), degrade to the previous
            // cleartext behaviour rather than losing the value outright.
            runCatching { ENC_PREFIX + KeystoreBox.encrypt(value) }.getOrDefault(value)
        }
        sp.edit().putString(key, stored).apply()
    }

    private companion object {
        const val ENC_PREFIX = "enc:v1:"
    }
}

/**
 * AES/GCM cipher backed by a key generated inside AndroidKeyStore. The key
 * never leaves the TEE/keystore; no user authentication is required.
 */
private object KeystoreBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "devingo_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey = cached ?: synchronized(this) {
        cached ?: loadOrCreate().also { cached = it }
    }

    private fun loadOrCreate(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Returns Base64(iv ‖ ciphertext‖tag). */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = try {
            cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            evictKey()
            throw e
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Inverse of [encrypt]; null on any decode/decrypt failure. */
    fun decrypt(payload: String): String? = runCatching {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        require(raw.size > GCM_IV_LEN) { "payload too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_LEN),
        )
        String(cipher.doFinal(raw, GCM_IV_LEN, raw.size - GCM_IV_LEN), Charsets.UTF_8)
    }.getOrNull()

    /** Drop the cached handle so a later call reloads (or regenerates) the key. */
    private fun evictKey() = synchronized(this) {
        cached = null
    }
}
