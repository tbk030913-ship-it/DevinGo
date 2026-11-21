package com.devingo.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/** What the bridge's /pair QR carries: ordered LAN candidates + tailnet + token. */
internal data class PairInfo(
    val hosts: List<String>,
    val tail: String,
    val token: String,
)

/**
 * Accepts the bridge payload {"v":1,"hosts":[...],"tail":"...","token":"..."}
 * and, as a manual fallback, a bare "host:port" string.
 */
internal fun parsePairCode(text: String): PairInfo? {
    val t = text.trim()
    runCatching { JSONObject(t) }.getOrNull()?.let { o ->
        val hosts = buildList {
            o.optString("host").takeIf { it.isNotBlank() }?.let(::add)
            o.optJSONArray("hosts")?.let { a ->
                for (i in 0 until a.length()) add(a.optString(i))
            }
        }.filter { it.isNotBlank() }.distinct()
        val tail = o.optString("tail", "")
        if (hosts.isEmpty() && tail.isBlank()) return null
        return PairInfo(hosts, tail, o.optString("token", ""))
    }
    val plain = Regex("""^[A-Za-z0-9.\-]+:\d{2,5}$""")
    return t.takeIf { plain.matches(it) }?.let { PairInfo(listOf(it), "", "") }
}

/**
 * Full-screen pairing scanner: camera preview + ML Kit QR detection, with a
 * paste field for devices without a camera or permission. The first decoded
 * code wins and is handed back via [onPaired].
 */
@Composable
internal fun QrPairingDialog(
    onPaired: (PairInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var pasteMode by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF141414)) {
            Box(Modifier.fillMaxSize()) {
                CameraScannerOrFallback(onCode = { c ->
                    parsePairCode(c)?.let(onPaired) ?: run { pasteMode = true; pasteError = true }
                })
                // Centered sight frame + hint.
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(240.dp)
                            .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(20.dp)),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "对准电脑屏幕上的配对二维码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.weight(1.4f))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { pasteMode = !pasteMode }) {
                        Text(if (pasteMode) "使用相机" else "粘贴配对码", color = Color.White)
                    }
                }
                if (pasteMode) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                            .background(Color(0xF21F1F1F), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    ) {
                        OutlinedTextField(
                            value = paste,
                            onValueChange = { paste = it; pasteError = false },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    """{"v":1,"hosts":["192.168.x.x:8790"],…} 或 host:port""",
                                    color = Color.White.copy(alpha = 0.45f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            isError = pasteError,
                            supportingText = if (pasteError) {
                                { Text("不是有效的配对码", color = cs.error) }
                            } else null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            textStyle = MaterialTheme.typography.bodySmall,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color.White,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        BlackPillButton("连接", onClick = {
                            parsePairCode(paste)?.let(onPaired) ?: run { pasteError = true }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraFallbackHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "相机不可用，请改用“粘贴配对码”",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScannerOrFallback(onCode: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        CameraFallbackHint()
        return
    }

    // Any camera failure (init, binding, odd ROM behavior) falls back to the
    // paste path — a scanner must never take the app down.
    var cameraFailed by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        runCatching { PreviewView(context) }.getOrNull()
    }
    if (cameraFailed || previewView == null) {
        CameraFallbackHint()
        return
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    LaunchedEffect(lifecycleOwner) {
        val provider = runCatching {
            withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(context).get()
            }
        }.getOrElse {
            cameraFailed = true
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val scanner = BarcodeScanning.getClient()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var done = false
        analysis.setAnalyzer(executor) { proxy ->
            val media = runCatching { proxy.image }.getOrNull()
            if (media == null || done) {
                proxy.close()
                return@setAnalyzer
            }
            val image = InputImage.fromMediaImage(
                media, proxy.imageInfo.rotationDegrees,
            )
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    val v = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                        ?.rawValue
                    if (!done && !v.isNullOrBlank()) {
                        done = true
                        onCode(v)
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }
        // Some HALs report a null lens facing, which makes the back-camera
        // selector throw instead of just not matching — try front as a
        // fallback before giving up on the camera entirely.
        val selectors = listOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA,
        )
        val bound = selectors.any { selector ->
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            }.isSuccess
        }
        if (!bound) cameraFailed = true
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}
