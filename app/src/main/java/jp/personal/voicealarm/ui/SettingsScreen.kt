package jp.personal.voicealarm.ui

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.personal.voicealarm.data.BackupManager
import jp.personal.voicealarm.util.PermissionChecks
import jp.personal.voicealarm.data.RingingButtonSize
import jp.personal.voicealarm.data.RingingUiSettings
import jp.personal.voicealarm.data.WidgetColor
import jp.personal.voicealarm.data.WidgetSettings
import jp.personal.voicealarm.widget.VoiceAlarmWidgetProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    // バックアップの書き出し/読み込み（ファイル選択は端末の画面に任せる）
    val scope = rememberCoroutineScope()
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = BackupManager.exportTo(context, uri)
                Toast.makeText(
                    context,
                    if (ok) "バックアップを書き出しました" else "書き出しに失敗しました",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }
    if (pendingImportUri != null) {
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("バックアップを読み込みますか？") },
            text = {
                Text("今のアラーム・グループ・休日は全て消えて、ファイルの内容に置き換わります。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = pendingImportUri
                    pendingImportUri = null
                    if (uri != null) {
                        scope.launch {
                            val ok = BackupManager.importFrom(context, uri)
                            Toast.makeText(
                                context,
                                if (ok) "バックアップを読み込みました"
                                else "読み込みに失敗しました（ファイルが壊れている可能性があります）",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }) { Text("読み込む") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("キャンセル") }
            }
        )
    }
    // refreshKey を読むことで再コンポーズ時に状態を再評価する
    val exactGranted = remember(refreshKey) { PermissionChecks.canScheduleExact(context) }
    val batteryExempt = remember(refreshKey) { PermissionChecks.isBatteryExempt(context) }
    val fullScreenGranted =
        remember(refreshKey) { PermissionChecks.canUseFullScreenIntent(context) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("設定・許可") },
            navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("正確なアラームの許可")
            Text(if (exactGranted) "状態: 許可されています" else "状態: 許可されていません（時刻がずれる可能性があります）")
            if (!exactGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }) { Text("許可画面を開く") }
            }
            Spacer(Modifier.height(24.dp))
            Text("電池の最適化")
            Text(if (batteryExempt) "状態: 最適化から除外されています" else "状態: 除外されていません（鳴らない原因になります）")
            if (!batteryExempt) {
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("除外をリクエスト") }
            }
            Spacer(Modifier.height(24.dp))
            Text("通知の許可（Android 13以降）")
            Text("鳴動画面を表示するために必要です。許可していない場合はアプリ情報から許可してください。")
            OutlinedButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }) { Text("アプリ情報を開く") }
            Spacer(Modifier.height(24.dp))
            Text("鳴動画面の全画面表示（Android 14以降）")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Text(
                    if (fullScreenGranted) "状態: 許可されています"
                    else "状態: 許可されていません（ロック画面に通知しか出ず、鳴動画面が開かない可能性があります）"
                )
                if (!fullScreenGranted) {
                    Button(onClick = {
                        val intent =
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                data = Uri.parse("package:${context.packageName}")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                            )
                        }
                    }) { Text("許可画面を開く") }
                }
            } else {
                Text("この端末（Android 13以前）では追加の許可は不要です")
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { refreshKey++ }) { Text("状態を再確認") }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // 鳴動画面のボタンサイズ（アプリ全体の設定。次に鳴る時から反映される）
            var snoozeSize by remember { mutableStateOf(RingingUiSettings.snoozeSize(context)) }
            var stopSize by remember { mutableStateOf(RingingUiSettings.stopSize(context)) }
            Text("鳴動画面のボタン", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "鳴動画面のボタンの大きさをボタンごとに選べます。次にアラームが鳴る時から反映されます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text("スヌーズボタン")
            Spacer(Modifier.height(8.dp))
            ButtonSizeSelector(
                selected = snoozeSize,
                onSelect = {
                    snoozeSize = it
                    RingingUiSettings.setSnoozeSize(context, it)
                }
            )
            Spacer(Modifier.height(16.dp))
            Text("停止ボタン")
            Spacer(Modifier.height(8.dp))
            ButtonSizeSelector(
                selected = stopSize,
                onSelect = {
                    stopSize = it
                    RingingUiSettings.setStopSize(context, it)
                }
            )
            Spacer(Modifier.height(20.dp))
            var stopLongPress by remember {
                mutableStateOf(RingingUiSettings.stopRequiresLongPress(context))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("停止は長押しで止める")
                    Text(
                        "オンにすると、停止ボタンは長押しでないと止まらなくなります（誤タップ防止）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = stopLongPress,
                    onCheckedChange = {
                        stopLongPress = it
                        RingingUiSettings.setStopRequiresLongPress(context, it)
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
            var snoozeLongPress by remember {
                mutableStateOf(RingingUiSettings.snoozeRequiresLongPress(context))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("スヌーズも長押しにする")
                    Text(
                        "オンにすると、スヌーズボタンも長押しでないと効かなくなります。長押しの時間は停止とは別に設定できます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = snoozeLongPress,
                    onCheckedChange = {
                        snoozeLongPress = it
                        RingingUiSettings.setSnoozeRequiresLongPress(context, it)
                    }
                )
            }
            if (stopLongPress) {
                Spacer(Modifier.height(12.dp))
                Text("停止の長押し時間")
                Spacer(Modifier.height(8.dp))
                var stopHoldSeconds by remember {
                    mutableStateOf(RingingUiSettings.stopHoldSeconds(context))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sec in 1..3) {
                        FilterChip(
                            selected = stopHoldSeconds == sec,
                            onClick = {
                                stopHoldSeconds = sec
                                RingingUiSettings.setStopHoldSeconds(context, sec)
                            },
                            label = { Text("${sec}秒") }
                        )
                    }
                }
            }
            if (snoozeLongPress) {
                Spacer(Modifier.height(12.dp))
                Text("スヌーズの長押し時間")
                Spacer(Modifier.height(8.dp))
                var snoozeHoldSeconds by remember {
                    mutableStateOf(RingingUiSettings.snoozeHoldSeconds(context))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sec in 1..3) {
                        FilterChip(
                            selected = snoozeHoldSeconds == sec,
                            onClick = {
                                snoozeHoldSeconds = sec
                                RingingUiSettings.setSnoozeHoldSeconds(context, sec)
                            },
                            label = { Text("${sec}秒") }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            var volumeAction by remember {
                mutableStateOf(RingingUiSettings.volumeButtonAction(context))
            }
            Text("音量ボタンの動作")
            Text(
                "鳴動画面が表示されている間に音量ボタン（上下どちらでも）を押したときの動作です。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf(
                    RingingUiSettings.VolumeButtonAction.NONE to "なし",
                    RingingUiSettings.VolumeButtonAction.STOP to "停止",
                    RingingUiSettings.VolumeButtonAction.SNOOZE to "スヌーズ"
                )
                options.forEach { (action, label) ->
                    FilterChip(
                        selected = volumeAction == action,
                        onClick = {
                            volumeAction = action
                            RingingUiSettings.setVolumeButtonAction(context, action)
                        },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ウィジェットの見た目（色・角丸・不透明度）。変更したら即ウィジェットへ反映する
            var widgetColor by remember { mutableStateOf(WidgetSettings.color(context)) }
            var widgetRadius by remember { mutableStateOf(WidgetSettings.radiusDp(context)) }
            var widgetOpacity by remember { mutableStateOf(WidgetSettings.opacityPercent(context)) }
            fun refreshWidget() = VoiceAlarmWidgetProvider.requestUpdate(context)
            Text("ウィジェット", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "ホーム画面ウィジェットの背景の色・角丸・不透明度を変えられます。文字色は背景に合わせて自動で切り替わります。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Text("背景色")
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WidgetColor.entries.forEach { c ->
                    FilterChip(
                        selected = widgetColor == c,
                        onClick = {
                            widgetColor = c
                            WidgetSettings.setColor(context, c)
                            refreshWidget()
                        },
                        label = { Text(c.label) },
                        leadingIcon = {
                            Box(
                                Modifier.size(14.dp)
                                    .background(Color(c.bgColor), CircleShape)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        CircleShape
                                    )
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("角丸")
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WidgetSettings.RADIUS_OPTIONS.forEach { (radiusDp, radiusLabel) ->
                    FilterChip(
                        selected = widgetRadius == radiusDp,
                        onClick = {
                            widgetRadius = radiusDp
                            WidgetSettings.setRadiusDp(context, radiusDp)
                            refreshWidget()
                        },
                        label = { Text(radiusLabel) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("背景の不透明度: ${widgetOpacity}%")
            Slider(
                value = widgetOpacity.toFloat(),
                onValueChange = { widgetOpacity = it.toInt() },
                onValueChangeFinished = {
                    WidgetSettings.setOpacityPercent(context, widgetOpacity)
                    refreshWidget()
                },
                valueRange = 20f..100f,
                steps = 7
            )
            Text(
                "100%でベタ塗り、小さいほど壁紙が透けて見えます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // バックアップ（書き出し/読み込み）
            Text("バックアップ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "アラーム・グループ・カスタム休日をファイルに書き出して保存できます。読み込むと今のデータは全てファイルの内容に置き換わります（鳴動履歴は対象外）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("voice-alarm-backup.json") }
                ) {
                    Text("書き出し")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) }
                ) {
                    Text("読み込み")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ButtonSizeSelector(
    selected: RingingButtonSize,
    onSelect: (RingingButtonSize) -> Unit
) {
    // 選択肢が多いので、入りきらない分は次の行に折り返す
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RingingButtonSize.entries.forEach { size ->
            FilterChip(
                selected = size == selected,
                onClick = { onSelect(size) },
                label = { Text("${size.label} (${size.heightDp})") }
            )
        }
    }
}


