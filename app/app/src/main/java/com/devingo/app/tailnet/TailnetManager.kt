package com.devingo.app.tailnet

import android.content.Context
import android.net.Uri
import android.os.Build
import com.devingo.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Node state surfaced to the settings page and the Home header dot. */
sealed interface TailnetState {
    data object Off : TailnetState
    data object Unsupported : TailnetState
    data object Starting : TailnetState
    data class NeedsLogin(val authUrl: String?) : TailnetState
    data object NeedsMachineAuth : TailnetState
    data class Running(val ip: String, val dnsName: String?, val tailnet: String?) : TailnetState
    data class Failed(val message: String) : TailnetState
}

/**
 * Owns the embedded tsnet node lifecycle and tailnet host forwarding.
 *
 * The node is process-global, started from MainActivity.onCreate when the
 * switch is on. resolveHost() is the only thing the UI layer touches for
 * routing: prefs keeps the ORIGINAL bridge host everywhere.
 */
object TailnetManager {
    private lateinit var app: Context
    private var prefs: Prefs? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** Serializes native start/stop/logout so rapid toggles can't interleave. */
    private val lifecycleMutex = Mutex()

    /** target ("host:port") → loopback port; listeners die with the node. */
    private val forwardCache = ConcurrentHashMap<String, Int>()

    private val _state = MutableStateFlow<TailnetState>(TailnetState.Off)
    val state: StateFlow<TailnetState> = _state.asStateFlow()

    fun init(context: Context, prefs: Prefs) {
        app = context.applicationContext
        this.prefs = prefs
        if (prefs.tailnetEnabled) start()
    }

    fun start() {
        if (!TailnetNative.loaded) {
            _state.value = TailnetState.Unsupported
            return
        }
        scope.launch {
            lifecycleMutex.withLock {
                val s = _state.value
                if (s is TailnetState.Starting || s is TailnetState.Running ||
                    s is TailnetState.NeedsLogin || s is TailnetState.NeedsMachineAuth
                ) {
                    return@withLock // already live; native start is idempotent anyway
                }
                forwardCache.clear()
                _state.value = TailnetState.Starting
                val err = runCatching {
                    TailnetNative.start(stateDir(), cacheDir(), hostname())
                }.getOrElse { it.message ?: "原生启动失败" }
                if (err.isNotEmpty()) {
                    _state.value = TailnetState.Failed(err)
                    return@withLock
                }
                pollLoop()
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        forwardCache.clear()
        _state.value = TailnetState.Off
        if (TailnetNative.loaded) {
            scope.launch {
                lifecycleMutex.withLock { runCatching { TailnetNative.stop() } }
            }
        }
    }

    /** Wipe the tailnet login, then restart so the flow returns to 需要登录. */
    fun logout() {
        pollJob?.cancel()
        pollJob = null
        forwardCache.clear()
        scope.launch {
            lifecycleMutex.withLock { runCatching { TailnetNative.logout() } }
            _state.value = TailnetState.Off
            start()
        }
    }

    private fun pollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var interactive = false // saw a state that isn't startup-in-progress
            val deadline = System.currentTimeMillis() + 30_000
            while (isActive) {
                val st = fetchState()
                if (!isActive) return@launch // a cancelled poll must not revive state
                when (st) {
                    is TailnetState.Running -> {
                        _state.value = st
                        interactive = true
                        delay(15_000)
                    }
                    TailnetState.Unsupported, is TailnetState.Failed -> {
                        _state.value = st
                        return@launch
                    }
                    else -> {
                        _state.value = st
                        if (st is TailnetState.NeedsLogin ||
                            st is TailnetState.NeedsMachineAuth
                        ) {
                            interactive = true
                        }
                        if (!interactive && System.currentTimeMillis() > deadline) {
                            _state.value = TailnetState.Failed("启动超时")
                            return@launch
                        }
                        delay(1_000)
                    }
                }
            }
        }
    }

    private fun fetchState(): TailnetState {
        val o = runCatching { JSONObject(TailnetNative.status()) }
            .getOrElse { return TailnetState.Failed("状态读取失败") }
        val error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" }
        return when (o.optString("state")) {
            "Running" -> {
                val ips = o.optJSONArray("ips")
                TailnetState.Running(
                    ip = (0 until (ips?.length() ?: 0)).asSequence()
                        .map { ips!!.getString(it) }
                        .firstOrNull { it.startsWith("100.") }
                        ?: (ips?.getString(0) ?: ""),
                    dnsName = o.optString("dnsName").ifBlank { null },
                    tailnet = o.optString("tailnet").ifBlank { null },
                )
            }
            "NeedsLogin" -> TailnetState.NeedsLogin(validAuthUrl(o.optString("authUrl")))
            "NeedsMachineAuth" -> TailnetState.NeedsMachineAuth
            "Stopped", "NoState" -> TailnetState.Starting
            else -> error?.let { TailnetState.Failed(it) } ?: TailnetState.Starting
        }
    }

    /** Only the real Tailscale login host may be handed to the browser. */
    private fun validAuthUrl(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() && it != "null" }?.let { u ->
            val uri = runCatching { Uri.parse(u) }.getOrNull()
            u.takeIf { uri != null && uri.scheme == "https" && uri.host == "login.tailscale.com" }
        }

    /**
     * Routes a bridge host through the tailnet when the switch is on and the
     * host is a tailnet address (100.64.0.0/10 IPv4 or *.ts.net). Anything
     * else passes through unchanged so non-tailnet setups are unaffected.
     */
    suspend fun resolveHost(host: String): String {
        if (prefs?.tailnetEnabled != true || !hostNeedsTailnet(host)) return host
        val h = hostPart(host)
        val target = "$h:${host.substringAfterLast(':', "").toIntOrNull() ?: 8790}"

        waitForRunning()
        val local = forwardCache[target] ?: run {
            val p = withContext(Dispatchers.IO) {
                runCatching { TailnetNative.forward(target) }.getOrDefault(-1)
            }
            if (p <= 0) throw IllegalStateException("Tailscale 未连接")
            forwardCache.putIfAbsent(target, p) ?: p
        }
        return "127.0.0.1:$local"
    }

    private suspend fun waitForRunning() {
        if (_state.value is TailnetState.Running) return
        if (_state.value is TailnetState.Off && prefs?.tailnetEnabled == true) start()
        withTimeoutOrNull(10_000) {
            _state.first {
                it is TailnetState.Running || it is TailnetState.Failed ||
                    it is TailnetState.NeedsLogin || it is TailnetState.Unsupported ||
                    it is TailnetState.NeedsMachineAuth || it is TailnetState.Off
            }
        }
        if (_state.value !is TailnetState.Running) {
            throw IllegalStateException("Tailscale 未连接")
        }
    }

    /** True when the raw prefs host value is a tailnet destination. */
    fun hostNeedsTailnet(host: String): Boolean = isTailnetHost(hostPart(host))

    private fun hostPart(host: String): String = host.substringBeforeLast(':')

    /** Tailnet destinations only: CGNAT IPv4 or a MagicDNS *.ts.net name. */
    private fun isTailnetHost(h: String): Boolean {
        if (h.endsWith(".ts.net")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return octets.none { it !in 0..255 } && octets[0] == 100 && octets[1] in 64..127
    }

    private fun stateDir(): String =
        File(app.noBackupFilesDir, "tailnet").apply { mkdirs() }.absolutePath

    private fun cacheDir(): String =
        File(app.cacheDir, "tailnet").apply { mkdirs() }.absolutePath

    /** Tailscale hostname: "devingo-<model>", lowercase, 40 chars max. */
    private fun hostname(): String =
        ("devingo-" + Build.MODEL.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-"))
            .take(40)
}
