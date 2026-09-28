package dev.ryunosuke.island.ui.app

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ryunosuke.island.IslandApp
import dev.ryunosuke.island.builtin.TimerPicker
import dev.ryunosuke.island.data.IslandSettings
import dev.ryunosuke.island.island.ClockCommand
import dev.ryunosuke.island.island.DemoController
import dev.ryunosuke.island.island.IslandAlert
import dev.ryunosuke.island.island.TimeFormat
import dev.ryunosuke.island.overlay.IslandOverlayService
import dev.ryunosuke.island.source.BatterySaver
import dev.ryunosuke.island.source.IslandNotificationListener
import dev.ryunosuke.island.source.ShizukuShell
import dev.ryunosuke.island.source.StatusBarGap
import dev.ryunosuke.island.ui.island.IslandColors
import dev.ryunosuke.island.ui.island.IslandMetrics
import dev.ryunosuke.island.ui.island.IslandText
import dev.ryunosuke.island.ui.island.content.rememberElapsedClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppTheme { MainScreen() } }
    }
}

private data class Access(
    val accessibility: Boolean,
    val listener: Boolean,
    val bluetooth: Boolean,
    val notifications: Boolean,
    val microphone: Boolean,
    val secureSettings: Boolean,
    val shizuku: ShizukuShell.Status,
)

private fun readAccess(context: Context): Access {
    val nm = context.getSystemService(NotificationManager::class.java)
    return Access(
        accessibility = ShizukuSetup.isAccessibilityEnabled(context),
        listener = nm.isNotificationListenerAccessGranted(ComponentName(context, IslandNotificationListener::class.java)),
        bluetooth = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
        notifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        secureSettings = BatterySaver.canToggle(context),
        shizuku = ShizukuShell.status(context),
    )
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val g = IslandApp.graph
    val settings by g.settings.collectAsState()
    val scope = rememberCoroutineScope()
    fun update(f: (IslandSettings) -> IslandSettings) = scope.launch { g.settingsStore.update(f) }

    // 設定画面から戻ってきたら権限の状態を読み直す
    var access by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(readAccess(context)) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                access = readAccess(context)
                delay(1_500)
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = readAccess(context)
    }
    // 縦向きの画面の幅（島は横向きでは隠す）
    val screenDp = context.resources.displayMetrics.let { minOf(it.widthPixels, it.heightPixels) / it.density }

    LazyColumn(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Island", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Pixel 6a のカメラ位置に Dynamic Island を出します",
                color = IslandColors.Secondary, fontSize = 14.sp,
            )
        }

        item {
            Section("セットアップ") {
                val missing = !access.accessibility || !access.listener || !access.bluetooth || !access.notifications ||
                    (settings.audioWaveform && !access.microphone) || !access.secureSettings
                ShizukuRow(access.shizuku, missing) { ShizukuSetup.grant(context, settings.audioWaveform) }
                AccessRow(
                    "アクセシビリティ", "島を画面の最上部に描くのに使います", access.accessibility,
                ) { openAccessibility(context) }
                AccessRow(
                    "通知へのアクセス", "音楽・通話・タイマー・ナビを読み取ります", access.listener,
                ) { openListener(context) }
                AccessRow(
                    "Bluetooth", "イヤホンがつながったときに名前と電池を出します", access.bluetooth,
                ) { permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
                AccessRow(
                    "通知", "画面が消えているときに内蔵タイマーの終了を知らせます", access.notifications,
                ) { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                if (settings.audioWaveform) {
                    AccessRow(
                        "マイク", "音楽の波形を実際の音に合わせます（出ている音の強さを見るだけで、録音はしません）", access.microphone,
                    ) { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
                }
                val directSaver = access.shizuku == ShizukuShell.Status.Ready
                AccessRow(
                    "省電力の切り替え",
                    when {
                        directSaver -> "電池残量低下の表示をタップすると、バッテリー セーバーをすぐ入れ・切りします（Shizuku を使うので、手動で入れたものも切れます）"
                        access.secureSettings -> "電池残量低下の表示をタップすると、バッテリー セーバーをすぐオンにします"
                        else -> "PC から下のコマンドを一度実行する（または上の Shizuku でまとめて許可する）と、電池残量低下の表示のタップでバッテリー セーバーをすぐオンにできます（無ければ設定画面を開きます）"
                    },
                    access.secureSettings || directSaver,
                    fixLabel = "コピー",
                ) { copy(context, BatterySaver.GRANT_COMMAND) }
                if (!access.secureSettings && !directSaver) {
                    Text(
                        BatterySaver.GRANT_COMMAND,
                        color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2E)).padding(8.dp),
                    )
                }
                if (!access.accessibility) {
                    Text(
                        "アクセシビリティのスイッチが灰色で押せないときは、アプリ情報の右上 ⋮ から「制限付き設定を許可」を選んでから戻ってください（Shizuku があれば、上の「まとめて許可」で済みます）。",
                        color = IslandColors.Secondary, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { openAppInfo(context) }) { Text("アプリ情報を開く") }
                }
            }
        }

        item { BuiltinClock() }

        item {
            Section("デモ") {
                Text("本物の出来事を待たずに見た目と操作を試せます。長押しで展開、左右スワイプでしまいます。しまったあとは、残った島をタップ（または左右にスワイプ）すると戻ります。", color = IslandColors.Secondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                DemoButtons()
            }
        }

        item {
            Section("位置と大きさ") {
                Text(
                    "カメラの穴にぴったり重なるように合わせます。0（左端）は自動です。" +
                        "広がったときの幅は待機時の幅より狭くならず、中身がカメラの穴に被らない幅は残します。",
                    color = IslandColors.Secondary, fontSize = 13.sp,
                )
                SliderRow("高さ", settings.heightDp, 0f..56f, "dp") { v -> update { it.copy(heightDp = v) } }
                SliderRow("待機時の幅", settings.centerWidthDp, 0f..200f, "dp") { v -> update { it.copy(centerWidthDp = v) } }
                AutoSizeRow(
                    "広がったときの幅",
                    settings.compactWidthDp,
                    IslandMetrics.idleWidthDp(screenDp, settings).coerceAtMost(screenDp - 1f)..screenDp,
                ) { v -> update { it.copy(compactWidthDp = v) } }
                AutoSizeRow(
                    "展開したときの幅",
                    settings.expandedWidthDp,
                    IslandMetrics.MIN_EXPANDED_WIDTH_DP.coerceAtMost(screenDp - 1f)..screenDp,
                ) { v -> update { it.copy(expandedWidthDp = v) } }
                SliderRow("左右", settings.offsetXDp, -40f..40f, "dp") { v -> update { it.copy(offsetXDp = v) } }
                SliderRow("上下", settings.offsetYDp, -20f..20f, "dp") { v -> update { it.copy(offsetYDp = v) } }
                TextButton(onClick = {
                    update {
                        it.copy(
                            heightDp = 0f, centerWidthDp = 0f, compactWidthDp = 0f, expandedWidthDp = 0f,
                            offsetXDp = 0f, offsetYDp = 0f,
                        )
                    }
                }) { Text("自動に戻す") }
            }
        }

        item {
            Section("ステータスバー（root）") {
                val gap by g.statusBarGap.state.collectAsState()
                Text(
                    "root で起動した Shizuku があれば、ステータスバーの時計と通知アイコンを島の左、電池などを島の右に寄せて、" +
                        "島の下に隠れないようにできます（展開したときは隠れます）。" +
                        "切り替えたり幅を変えたりすると、開いているアプリの画面が一度作り直されます。",
                    color = IslandColors.Secondary, fontSize = 13.sp,
                )
                SwitchRow("時計とアイコンを島の外に出す", settings.shiftStatusBar) { v -> update { it.copy(shiftStatusBar = v) } }
                if (settings.shiftStatusBar) {
                    // 変えるたびにアプリの画面が作り直されるので、指を離したときだけ変える
                    AutoSizeRow(
                        "空ける幅",
                        settings.statusBarGapDp,
                        IslandMetrics.idleWidthDp(screenDp, settings).coerceAtMost(screenDp * 0.75f - 1f)..screenDp * 0.75f,
                        commitOnRelease = true,
                    ) { v -> update { it.copy(statusBarGapDp = v) } }
                    Text(
                        "自動は、音楽などのコンパクトがちょうど入る幅です（島の幅を変えると空きも合わせて変わります）。" +
                            "2 つ同時の右の丸や、タイマーのように文字の多いものは、右のアイコンに少し被ります。",
                        color = IslandColors.Secondary, fontSize = 12.sp,
                    )
                }
                statusBarGapText(gap, settings.shiftStatusBar)?.let {
                    Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                }
                if (settings.shiftStatusBar) {
                    Text(
                        "アンインストールする前にスイッチを切ってください（切らずに消したときは、入れ直して Shizuku の使用を許可すると元に戻ります）。",
                        color = IslandColors.Secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item {
            Section("出すもの") {
                SwitchRow("音楽・動画", settings.media) { v -> update { it.copy(media = v) } }
                SwitchRow("通話", settings.calls) { v -> update { it.copy(calls = v) } }
                SwitchRow("時計アプリのタイマー・ストップウォッチ・アラーム", settings.clockMirror) { v -> update { it.copy(clockMirror = v) } }
                SwitchRow("ナビ", settings.navigation) { v -> update { it.copy(navigation = v) } }
                SwitchRow("その他の進行中のもの（DL・配達・録画など）", settings.otherLive) { v -> update { it.copy(otherLive = v) } }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = Color(0xFF2C2C2E))
                SwitchRow("充電", settings.charging) { v -> update { it.copy(charging = v) } }
                SwitchRow("バッテリー残量低下", settings.lowBattery) { v -> update { it.copy(lowBattery = v) } }
                SwitchRow("サイレント・バイブの切り替え", settings.ringer) { v -> update { it.copy(ringer = v) } }
                SwitchRow("おやすみモード", settings.dnd) { v -> update { it.copy(dnd = v) } }
                SwitchRow("イヤホンの接続", settings.bluetooth) { v -> update { it.copy(bluetooth = v) } }
                SwitchRow("ロック解除", settings.unlock) { v -> update { it.copy(unlock = v) } }
            }
        }

        item {
            Section("振る舞い") {
                SwitchRow("何もないときも島を出す（iPhone と同じ）", settings.idleVisible) { v -> update { it.copy(idleVisible = v) } }
                SpeedRow(settings.animationSpeed) { v -> update { it.copy(animationSpeed = v) } }
                SwitchRow("音楽の波形を実際の音に合わせる（低い音が左、高い音が右）", settings.audioWaveform) { v ->
                    update { it.copy(audioWaveform = v) }
                    if (v && !access.microphone) permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
                SwitchRow("全画面のアプリでは隠す", settings.hideFullscreen) { v -> update { it.copy(hideFullscreen = v) } }
                SwitchRow("横向きでは隠す", settings.hideLandscape) { v -> update { it.copy(hideLandscape = v) } }
                SwitchRow("通知シェードを開いたら隠す", settings.hideShade) { v -> update { it.copy(hideShade = v) } }
                SliderRow(
                    "一時停止した音楽を隠すまで", settings.mediaPausedTimeoutSec.toFloat(), 5f..600f, "秒",
                ) { v -> update { it.copy(mediaPausedTimeoutSec = v.toInt()) } }
            }
        }

        item { ExcludedApps(settings) { v -> update { it.copy(excludedPackages = v) } } }
    }
}

// ---- 内蔵ストップウォッチ・タイマー ----

@Composable
private fun BuiltinClock() {
    val clock = IslandApp.graph.clock
    val sw by clock.stopwatch.collectAsState()
    val timer by clock.timer.collectAsState()
    val now by rememberElapsedClock(50, sw.running || timer.running)
    Section("ストップウォッチ・タイマー") {
        Text("クイック設定にも「ストップウォッチ」「タイマー」のタイルがあります。", color = IslandColors.Secondary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                TimeFormat.stopwatch(sw.elapsed(maxOf(now, SystemClock.elapsedRealtime()))),
                style = IslandText.bigTime.copy(fontSize = 34.sp),
                modifier = Modifier.weight(1f),
            )
            if (sw.active) {
                OutlinedButton(onClick = {
                    clock.handle(if (sw.running) ClockCommand.StopwatchLap else ClockCommand.StopwatchReset)
                }) { Text(if (sw.running) "ラップ" else "リセット") }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = { clock.handle(ClockCommand.StopwatchToggle) }) {
                Text(if (sw.running) "一時停止" else if (sw.active) "再開" else "開始")
            }
        }
        if (sw.laps.isNotEmpty()) {
            Text(
                sw.laps.mapIndexed { i, t -> "ラップ ${i + 1}  ${TimeFormat.stopwatch(t)}" }.reversed().take(5).joinToString("\n"),
                color = IslandColors.Secondary, style = IslandText.tabular, fontSize = 13.sp,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF2C2C2E))
        if (timer.active) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (timer.ringing) "終了" else TimeFormat.countdown(timer.remaining(maxOf(now, SystemClock.elapsedRealtime()))),
                    style = IslandText.bigTime.copy(fontSize = 34.sp, color = IslandColors.Orange),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    clock.handle(if (timer.ringing) ClockCommand.TimerStopRinging else ClockCommand.TimerCancel)
                }) { Text(if (timer.ringing) "停止" else "キャンセル") }
                if (!timer.ringing) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { clock.handle(ClockCommand.TimerToggle) }) { Text(if (timer.running) "一時停止" else "再開") }
                }
            }
        } else {
            TimerPicker(clock.lastTimerMs, onStart = { clock.startTimer(it) }, onCancel = null)
        }
    }
}

// ---- デモ ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoButtons() {
    val g = IslandApp.graph
    val types = listOf(
        "音楽" to DemoController.Type.Media,
        "着信" to DemoController.Type.IncomingCall,
        "通話中" to DemoController.Type.OngoingCall,
        "タイマー" to DemoController.Type.Timer,
        "ストップウォッチ" to DemoController.Type.Stopwatch,
        "アラーム" to DemoController.Type.Alarm,
        "ナビ" to DemoController.Type.Navigation,
        "ダウンロード" to DemoController.Type.Download,
        "画面録画" to DemoController.Type.Recording,
    )
    val alerts = listOf(
        "充電" to IslandAlert.Charging(76),
        "残量低下" to IslandAlert.LowBattery(20),
        "サイレント" to IslandAlert.Ringer(android.media.AudioManager.RINGER_MODE_SILENT),
        "着信音" to IslandAlert.Ringer(android.media.AudioManager.RINGER_MODE_NORMAL),
        "おやすみ" to IslandAlert.Dnd(true),
        "イヤホン" to IslandAlert.Device("AirPods Pro", 82, wired = false),
        "ロック解除" to IslandAlert.Unlock,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, t) in types) FilledTonalButton(onClick = { g.demo.show(t) }) { Text(label) }
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, a) in alerts) OutlinedButton(onClick = { g.arbiter.postAlert(a) }) { Text(label) }
    }
    TextButton(onClick = { g.demo.clear() }) { Text("デモを消す") }
}

// ---- 除外アプリ ----

@Composable
private fun ExcludedApps(settings: IslandSettings, onChange: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val seen by IslandApp.graph.notifications.seenLivePackages.collectAsState()
    val all = (seen + settings.excludedPackages).sorted()
    Section("その他の進行中のもの：出さないアプリ") {
        if (all.isEmpty()) {
            Text("進行中の通知を出したアプリがここに並びます。", color = IslandColors.Secondary, fontSize = 13.sp)
        }
        for (pkg in all) {
            val label = remember(pkg) {
                runCatching {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = pkg in settings.excludedPackages,
                    onCheckedChange = { on -> onChange(if (on) settings.excludedPackages + pkg else settings.excludedPackages - pkg) },
                )
                Text(label, color = Color.White)
            }
        }
    }
}

// ---- 部品 ----

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1C1C1E))
            .padding(16.dp),
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** Shizuku（なくても使える）。あれば下の許可をまとめて付けられ、省電力を島から直接入れ・切りできる */
@Composable
private fun ShizukuRow(status: ShizukuShell.Status, missing: Boolean, onGrant: () -> Unit) {
    val context = LocalContext.current
    AccessRow(
        "Shizuku（なくても使えます）",
        when (status) {
            ShizukuShell.Status.NotInstalled ->
                "入れると、下の許可を PC なしでまとめて付けられ、手動で入れた省電力も島から切れるようになります"
            ShizukuShell.Status.NotRunning ->
                "Shizuku のアプリで起動してください（root なしでは、端末を再起動するたびに起動し直します）"
            ShizukuShell.Status.NoPermission ->
                "Island に Shizuku の使用を許可すると、省電力を島から直接入れ・切りでき、足りない許可もまとめて付けます"
            ShizukuShell.Status.Ready ->
                if (missing) "下の足りない許可をまとめて付けられます"
                else "省電力を島から直接入れ・切りし、Recents で払ったアプリの音楽をすぐ消します"
        },
        status == ShizukuShell.Status.Ready && !missing,
        fixLabel = when (status) {
            ShizukuShell.Status.NotInstalled -> "入手"
            ShizukuShell.Status.NotRunning -> "開く"
            ShizukuShell.Status.NoPermission -> "許可"
            ShizukuShell.Status.Ready -> "まとめて許可"
        },
    ) {
        when (status) {
            ShizukuShell.Status.NotInstalled, ShizukuShell.Status.NotRunning -> ShizukuShell.openManager(context)
            ShizukuShell.Status.NoPermission, ShizukuShell.Status.Ready -> onGrant()
        }
    }
}

@Composable
private fun AccessRow(title: String, detail: String, ok: Boolean, fixLabel: String = "許可", onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(10.dp).clip(CircleShape).background(if (ok) IslandColors.Green else IslandColors.Red),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text(detail, color = IslandColors.Secondary, fontSize = 12.sp)
        }
        if (!ok) FilledTonalButton(onClick = onFix) { Text(fixLabel) }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableIntStateOf(value.toInt()) }
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (local == 0 && range.start == 0f && unit == "dp") "自動" else "$local $unit", color = IslandColors.Secondary, fontSize = 14.sp)
        }
        Slider(
            value = local.toFloat(),
            onValueChange = {
                local = it.toInt()
                onChange(local.toFloat())
            },
            valueRange = range,
        )
    }
}

/**
 * 0 を「自動」とする大きさのスライダー。左端のひと区切りが自動で、そのすぐ右から [range] の dp になる
 * （下限より小さい値は意味がないので、0〜下限をスライダーに入れない）。
 * [commitOnRelease] なら、動かしている間は表示だけ変えて、指を離したときに [onChange] を呼ぶ
 */
@Composable
private fun AutoSizeRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    commitOnRelease: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val auto = range.start - (range.endInclusive - range.start) * 0.08f
    var local by remember(value, range) {
        androidx.compose.runtime.mutableFloatStateOf(if (value <= 0f) auto else value.coerceIn(range))
    }
    fun valueOf(v: Float) = if (v < range.start) 0f else v.roundToInt().toFloat()
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (local < range.start) "自動" else "${local.roundToInt()} dp", color = IslandColors.Secondary, fontSize = 14.sp)
        }
        Slider(
            value = local,
            onValueChange = {
                local = it
                if (!commitOnRelease) onChange(valueOf(it))
            },
            onValueChangeFinished = { if (commitOnRelease) onChange(valueOf(local)) },
            valueRange = auto..range.endInclusive,
        )
    }
}

/** ステータスバーの空きの今の状態。言うことが無ければ null */
private fun statusBarGapText(state: StatusBarGap.State, on: Boolean): String? = when (state) {
    StatusBarGap.State.Off -> if (on) "準備しています…" else null
    StatusBarGap.State.NoIsland -> "アクセシビリティの Island がオンで、画面が縦向きのときに空けます"
    StatusBarGap.State.NoShizuku -> "上の Shizuku が動いていて、Island に使う許可が出ているときに空けます"
    StatusBarGap.State.NeedsRoot ->
        "Shizuku が root なし（ワイヤレスデバッグ）で動いています。Shizuku のアプリで、root で起動し直してください"
    StatusBarGap.State.Applying -> "空けています…"
    is StatusBarGap.State.Applied -> "真ん中を ${state.widthDp} dp 空けています"
    is StatusBarGap.State.Failed -> "空けられませんでした（${state.reason}）。スイッチを入れ直すと、もう一度試します"
    StatusBarGap.State.CannotRestore -> "Shizuku が動いていないので、まだ元に戻せていません（Shizuku を起動すると戻します）"
}

/** 島の動きの速さ。1.0 が iOS 26 と同じ */
@Composable
private fun SpeedRow(value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { androidx.compose.runtime.mutableFloatStateOf(value) }
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text("アニメーションの速さ", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            val label = "%.1f×".format(local)
            Text(if (local == 1f) "$label（iOS 26 と同じ）" else label, color = IslandColors.Secondary, fontSize = 14.sp)
        }
        Slider(
            value = local,
            onValueChange = {
                // 0.1 刻み
                local = (it * 10).roundToInt() / 10f
                onChange(local)
            },
            valueRange = 0.5f..2f,
            steps = 14,
        )
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("adb", text))
}

// ---- 設定画面へ ----

private fun openAccessibility(context: Context) {
    val component = ComponentName(context, IslandOverlayService::class.java).flattenToString()
    val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
    runCatching { context.startActivity(detail) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

private fun openListener(context: Context) {
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, IslandNotificationListener::class.java).flattenToString(),
        )
    runCatching { context.startActivity(detail) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
