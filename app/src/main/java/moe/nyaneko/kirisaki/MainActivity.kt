// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private var foreground by mutableStateOf(false)
    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() { foreground = false; super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) {
                Kirisaki(foreground, BuildConfig.DEBUG && intent.getBooleanExtra("preview_animation", false))
            }
        }
    }
}

@Composable private fun Kirisaki(foreground: Boolean, preview: Boolean = false) {
    val context = LocalContext.current
    var record by remember { mutableStateOf(Bundle()) }
    var ipcError by remember { mutableStateOf("") }
    var checkingActivation by remember { mutableStateOf(true) }
    var animating by remember { mutableStateOf(false) }
    val elapsed = remember { Animatable(0f) }
    val progress = SeverTimeline.progress(elapsed.value)
    var replay by remember { mutableIntStateOf(0) }
    LaunchedEffect(foreground, replay) {
        if (!foreground) return@LaunchedEffect
        animating = false
        if (preview) {
            // An explicitly requested debug preview never touches real module/animation records.
            elapsed.snapTo(0f)
            animating = true
            elapsed.animateTo(SeverTimeline.DURATION_MS.toFloat(), tween(SeverTimeline.DURATION_MS, easing = LinearEasing))
            animating = false
            return@LaunchedEffect
        }
        checkingActivation = !record.getBoolean("active")
        val activationCheckStarted = android.os.SystemClock.elapsedRealtime()
        // A foreground request recovers a missed boot-time report without a background service.
        runCatching { StateBridge.request(context) }
        while (true) {
            if (!record.getBoolean("active")) runCatching { StateBridge.request(context) }
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.call(STATE_URI, "read", null, null) ?: Bundle() }
            }
            result.onSuccess { record = it; ipcError = "" }.onFailure { ipcError = "无法读取私有状态：${it.javaClass.simpleName}" }
            checkingActivation = !record.getBoolean("active") && ipcError.isEmpty() &&
                android.os.SystemClock.elapsedRealtime() - activationCheckStarted < 8000
            if (record.getBoolean("precise")) {
                val owner = java.util.UUID.randomUUID().toString()
                val claim = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.call(STATE_URI, "beginAnimation", null,
                        Bundle().apply { putString("owner", owner) }) }.getOrNull()
                }
                if (claim?.getBoolean("play") == true) {
                    var finished = false
                    suspend fun savePosition(release: Boolean) = withContext(Dispatchers.IO) {
                        context.contentResolver.call(STATE_URI, "checkpointAnimation", null, Bundle().apply {
                            putString("owner", owner)
                            putString("boot", claim.getString("boot"))
                            putInt("elapsed", elapsed.value.toInt())
                            putBoolean("finish", finished)
                            putBoolean("release", release)
                        })
                    }
                    try {
                        elapsed.snapTo(claim.getInt("elapsed").toFloat())
                        animating = true
                        coroutineScope {
                            val checkpoints = launch {
                                while (isActive) {
                                    delay(100)
                                    runCatching { savePosition(false) }
                                }
                            }
                            try {
                                val remaining = (SeverTimeline.DURATION_MS - elapsed.value.toInt()).coerceAtLeast(1)
                                elapsed.animateTo(SeverTimeline.DURATION_MS.toFloat(), tween(remaining, easing = LinearEasing))
                                withFrameNanos { } // Commit completion after the final visual frame.
                                finished = true
                            } finally {
                                checkpoints.cancelAndJoin()
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            runCatching { savePosition(true) }
                                .onFailure { ipcError = "无法保存动画进度：${it.javaClass.simpleName}" }
                        }
                        animating = false
                    }
                }
            }
            delay(1000)
        }
    }
    val active = record.getBoolean("active")
    val precise = record.getBoolean("precise")
    val blocked = record.getLong("blocked")
    val error = ipcError.ifEmpty { record.getString("error").orEmpty() }
    val title = when {
        preview -> if (animating) "动画预览" else "Override Severed · 预览"
        error.isNotEmpty() -> "Hook 失败"
        checkingActivation && !active -> "正在检测"
        precise -> "Override Severed"
        blocked > 0 -> "已拦截"
        active -> "等待检测"
        else -> "尚未确认激活"
    }
    val subtitle = when {
        preview -> "模拟数据 · 不代表 Hook 已生效"
        error.isNotEmpty() -> error
        checkingActivation && !active -> "正在等待定位进程响应…"
        precise -> "ColorOS GLA enforcement blocked"
        blocked > 0 -> "严格写入拦截生效 · 尚未定位目标方法"
        active -> "Awaiting detection"
        else -> "尚未收到本次启动的模块响应，将继续检测。"
    }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 28.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Kirisaki", style = MaterialTheme.typography.headlineMedium)
                    Text("切先  /  CUT THE OVERRIDE", color = colors.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 2.sp)
                }
                Text("01", color = colors.outline, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(48.dp))
            Box(Modifier.fillMaxWidth().height(310.dp), contentAlignment = Alignment.Center) {
                if (animating) SeverAnimation(progress,
                    if (preview) "preview.ExampleTarget" else record.getString("class").orEmpty(),
                    if (preview) "enforce(boolean): void" else record.getString("method").orEmpty(), preview)
                else Blade(Modifier.size(160.dp), if (preview || precise || blocked > 0) colors.primary else colors.outline)
            }
            Text(title, modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineMedium)
            Text(subtitle, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (preview) TextButton(onClick = { replay++ }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("重播动画")
            }
            Spacer(Modifier.height(56.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Text("TARGET / 目标", Modifier.padding(top = 22.dp, bottom = 14.dp), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
            if (!record.getString("class").isNullOrEmpty()) {
                TargetLine("Class", record.getString("class").orEmpty())
                TargetLine("Method", record.getString("method").orEmpty())
            } else {
                Text("Awaiting detection", color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            TargetLine("Scope", TARGET_PACKAGE)
            Text("仅需勾选 com.oplus.location", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!active) Text("若长时间未收到响应，请检查 LSPosed 中模块已启用，作用域仅勾选 com.oplus.location。",
                Modifier.padding(top = 10.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (blocked > 0) Text("最近拦截  ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(blocked))}",
                Modifier.padding(top = 16.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (blocked > 0 && record.getBoolean("other_entry")) Text("检测到其他修改入口，已拦截。",
                Modifier.padding(top = 8.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("v${BuildConfig.VERSION_NAME}", Modifier.padding(top = 32.dp, bottom = 24.dp),
                color = colors.outline, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun TargetLine(label: String, value: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable private fun Blade(modifier: Modifier, color: Color) {
    Canvas(modifier.semantics { contentDescription = "切先刀刃" }) { drawBlade(color) }
}
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlade(color: Color) {
    val w = size.width; val h = size.height
    drawPath(Path().apply {
        moveTo(w * .78f, h * .12f); lineTo(w * .61f, h * .57f)
        lineTo(w * .35f, h * .83f); lineTo(w * .30f, h * .78f)
        lineTo(w * .53f, h * .48f); close()
    }, color)
    drawLine(color, Offset(w * .24f, h * .73f), Offset(w * .41f, h * .90f), w * .018f)
    drawLine(color, Offset(w * .29f, h * .84f), Offset(w * .19f, h * .94f), w * .035f)
}

/** Two Canvas clips of the same panel split along the slash; runtime target text travels with them. */
@Composable private fun SeverAnimation(progress: Float, targetClass: String, targetMethod: String, preview: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val panel = colors.surfaceContainerHigh
    val ink = colors.onSurface
    val accent = colors.primary
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "TARGET DETECTED: $targetClass $targetMethod" }) {
        val w = size.width; val h = size.height
        val split = ((progress - .45f) / .30f).coerceIn(0f, 1f)
        val appear = (progress / .15f).coerceIn(0f, 1f)
        val alpha = appear * (1f - split)
        val lift = ((progress - .16f) / .16f).coerceIn(0f, 1f) * 10.dp.toPx()
        val left = w * .025f; val top = h * .12f - lift
        val right = w * .975f; val bottom = h * .88f - lift
        val y1 = h * .66f; val y2 = h * .36f
        val upper = Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, y2); lineTo(0f, y1); close() }
        val lower = Path().apply { moveTo(0f, y1); lineTo(w, y2); lineTo(w, h); lineTo(0f, h); close() }
        fun androidx.compose.ui.graphics.drawscope.DrawScope.panelPart() {
            drawRoundRect(panel.copy(alpha = alpha), Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top), androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb((alpha * 255).toInt(), (ink.red * 255).toInt(), (ink.green * 255).toInt(), (ink.blue * 255).toInt())
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val x = left + 22.dp.toPx()
            val maxWidth = right - x - 16.dp.toPx()
            fun line(text: String, y: Float, sp: Float) {
                paint.textSize = sp.dp.toPx()
                val count = paint.breakText(text, true, maxWidth, null)
                drawContext.canvas.nativeCanvas.drawText(text.take(count), x, top + y.dp.toPx(), paint)
            }
            line(if (preview) "ANIMATION PREVIEW" else "TARGET DETECTED", 32f, 11f)
            line("Class", 73f, 10f); line(targetClass, 96f, 13f)
            line("Method", 132f, 10f); line(targetMethod, 155f, 13f)
            line("network_location_opt_in = 1", 200f, 10f)
        }
        clipPath(upper) { translate(-split * 22.dp.toPx(), -split * 16.dp.toPx()) { panelPart() } }
        clipPath(lower) { translate(split * 22.dp.toPx(), split * 16.dp.toPx()) { panelPart() } }
        val slash = ((progress - .32f) / .15f).coerceIn(0f, 1f)
        if (progress > .32f && progress < .80f) {
            val start = Offset(0f, y1); val end = Offset(w * slash, y1 + (y2 - y1) * slash)
            drawLine(accent.copy(alpha = 1f - split), start, end, 2.dp.toPx())
        }
        val blade = ((progress - .73f) / .22f).coerceIn(0f, 1f)
        if (blade > 0f) {
            val s = min(w, h) * .52f
            translate((w - s) / 2, (h - s) / 2) {
                // Use a local viewport so the final mark matches the resting blade.
                val canvas = drawContext.canvas
                canvas.save(); canvas.scale(s / w, s / h)
                drawBlade(accent.copy(alpha = blade)); canvas.restore()
            }
        }
    }
}
