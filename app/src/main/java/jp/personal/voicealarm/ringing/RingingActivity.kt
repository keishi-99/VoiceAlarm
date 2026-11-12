package jp.personal.voicealarm.ringing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.data.RingingUiSettings
import jp.personal.voicealarm.logic.NextAlarmCalculator
import jp.personal.voicealarm.ui.theme.VoiceAlarmTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

class RingingActivity : ComponentActivity() {

    private var now by mutableStateOf(LocalDateTime.now())
    /** テストモード時だけ使うプレイヤー（通常時は RingingService 側が鳴らす） */
    private var testPlayer: VoiceAlarmPlayer? = null
    private var isTestMode: Boolean = false
    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // テストモードでは本物の鳴動終了ブロードキャストに反応しないよう、レシーバを登録しない
        val testMode = intent?.getBooleanExtra(EXTRA_TEST_MODE, false) ?: false
        if (!testMode) {
            ContextCompat.registerReceiver(
                this, finishReceiver, IntentFilter(RingingService.ACTION_FINISH_RINGING),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // 鳴動画面に出す情報はサービスが Intent に載せて渡す（未指定時は既定値）
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        val snoozeMinutes = intent?.getIntExtra(EXTRA_SNOOZE_MINUTES, 5) ?: 5
        val fromSnooze = intent?.getBooleanExtra(EXTRA_FROM_SNOOZE, false) ?: false
        val snoozeCount = intent?.getIntExtra(EXTRA_SNOOZE_COUNT, 0) ?: 0
        val snoozeMaxCount = intent?.getIntExtra(EXTRA_SNOOZE_MAX_COUNT, 0) ?: 0
        isTestMode = testMode
        // ボタンの大きさ・長押し設定はアプリ全体設定から読む（設定画面で変更、次回鳴動から反映）
        val snoozeButtonHeightDp = RingingUiSettings.snoozeSize(this).heightDp
        val stopButtonHeightDp = RingingUiSettings.stopSize(this).heightDp
        val stopRequiresLongPress = RingingUiSettings.stopRequiresLongPress(this)
        val snoozeRequiresLongPress = RingingUiSettings.snoozeRequiresLongPress(this)
        val stopHoldMs = RingingUiSettings.stopHoldSeconds(this) * 1000L
        val snoozeHoldMs = RingingUiSettings.snoozeHoldSeconds(this) * 1000L

        setContent {
            VoiceAlarmTheme {
                RingingScreen(
                    now = now,
                    label = label,
                    snoozeMinutes = snoozeMinutes,
                    fromSnooze = fromSnooze,
                    testMode = testMode,
                    snoozeButtonHeightDp = snoozeButtonHeightDp,
                    stopButtonHeightDp = stopButtonHeightDp,
                    stopRequiresLongPress = stopRequiresLongPress,
                    snoozeRequiresLongPress = snoozeRequiresLongPress,
                    stopHoldMs = stopHoldMs,
                    snoozeHoldMs = snoozeHoldMs,
                    snoozeCount = snoozeCount,
                    snoozeMaxCount = snoozeMaxCount,
                    onStop = { performStop() },
                    onSnooze = { performSnooze() }
                )
            }
        }
        if (testMode) {
            // 編集画面から渡された音設定で、その場でプレイヤーを鳴らす
            val testAlarm = Alarm(
                hour = 0,
                minute = 0,
                label = label,
                soundUri = intent?.getStringExtra(EXTRA_SOUND_URI),
                voiceVolume = intent?.getFloatExtra(EXTRA_VOICE_VOLUME, 0.8f) ?: 0.8f,
                seVolume = intent?.getFloatExtra(EXTRA_SE_VOLUME, 1.0f) ?: 1.0f,
                fadeIn = intent?.getBooleanExtra(EXTRA_FADE_IN, false) ?: false,
                vibration = intent?.getBooleanExtra(EXTRA_VIBRATION, true) ?: true,
                snoozeMinutes = snoozeMinutes
            )
            testPlayer = VoiceAlarmPlayer(this, testAlarm) { finish() }.also { it.start() }
        }
        // 表示時刻をざっくり更新（正確な秒表示はしない）
        window.decorView.postDelayed(object : Runnable {
            override fun run() {
                now = LocalDateTime.now()
                if (!isFinishing) window.decorView.postDelayed(this, 10_000)
            }
        }, 10_000)
    }

    /** 停止ボタンと同じ動作（音量ボタンで止める場合もここを通る）。 */
    private fun performStop() {
        if (isTestMode) {
            // テストでは本物のアラーム状態に触れず、その場で止めて閉じる
            testPlayer?.stop()
        } else {
            startService(RingingService.stopIntent(this))
        }
        finish()
    }

    /** スヌーズボタンと同じ動作（音量ボタンからのスヌーズでも使う）。 */
    private fun performSnooze() {
        if (isTestMode) {
            // テストで本物のスヌーズ予約は作らない（押したらテスト終了）
            testPlayer?.stop()
        } else {
            startService(RingingService.snoozeIntent(this))
        }
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 音量ボタン（上下どちらでも）に、設定で選んだ動作（停止/スヌーズ）を割り当てる。
        // 鳴動画面が表示されている間だけ効く
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            when (RingingUiSettings.volumeButtonAction(this)) {
                RingingUiSettings.VolumeButtonAction.STOP -> {
                    performStop()
                    return true
                }
                RingingUiSettings.VolumeButtonAction.SNOOZE -> {
                    performSnooze()
                    return true
                }
                RingingUiSettings.VolumeButtonAction.NONE -> Unit
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        testPlayer?.stop()
        testPlayer = null
        try { unregisterReceiver(finishReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LABEL = "jp.personal.voicealarm.ringing.LABEL"
        const val EXTRA_SNOOZE_MINUTES = "jp.personal.voicealarm.ringing.SNOOZE_MINUTES"
        const val EXTRA_FROM_SNOOZE = "jp.personal.voicealarm.ringing.FROM_SNOOZE"
        /** この鳴動までにスヌーズした累計回数と、アラームに設定された上限（0=無制限） */
        const val EXTRA_SNOOZE_COUNT = "jp.personal.voicealarm.ringing.SNOOZE_COUNT"
        const val EXTRA_SNOOZE_MAX_COUNT = "jp.personal.voicealarm.ringing.SNOOZE_MAX_COUNT"
        /** テスト再生モード（編集画面のテスト再生から開くとき） */
        const val EXTRA_TEST_MODE = "jp.personal.voicealarm.ringing.TEST_MODE"
        const val EXTRA_SOUND_URI = "jp.personal.voicealarm.ringing.SOUND_URI"
        const val EXTRA_VOICE_VOLUME = "jp.personal.voicealarm.ringing.VOICE_VOLUME"
        const val EXTRA_SE_VOLUME = "jp.personal.voicealarm.ringing.SE_VOLUME"
        const val EXTRA_FADE_IN = "jp.personal.voicealarm.ringing.FADE_IN"
        const val EXTRA_VIBRATION = "jp.personal.voicealarm.ringing.VIBRATION"
    }
}

@Composable
fun RingingScreen(
    now: LocalDateTime,
    label: String,
    snoozeMinutes: Int,
    fromSnooze: Boolean,
    testMode: Boolean = false,
    snoozeButtonHeightDp: Int,
    stopButtonHeightDp: Int,
    stopRequiresLongPress: Boolean = false,
    snoozeRequiresLongPress: Boolean = false,
    stopHoldMs: Long = 1000L,
    snoozeHoldMs: Long = 1000L,
    snoozeCount: Int = 0,
    snoozeMaxCount: Int = 0,
    onStop: () -> Unit,
    onSnooze: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    // 夜明けのグラデーション（テーマ色ベース。ダークモードでも眩しくならない配色になる）
    val background = Brush.verticalGradient(
        listOf(colors.primaryContainer, colors.surface)
    )
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(background)
    ) {
        // 画面が小さい端末でボタンが上部（時刻表示）を潰さないよう、
        // 1つのボタンの高さは画面の高さの42%を上限とする（両ボタン最大でも約半分〜8割に収まる）
        val heightCapDp = (maxHeight.value * 0.42f).toInt().coerceAtLeast(48)
        val snoozeHeight = minOf(snoozeButtonHeightDp, heightCapDp)
        val stopHeight = minOf(stopButtonHeightDp, heightCapDp)
        Column(
            // 端末のナビゲーションバー（戻るボタン等）と下部ボタンが被らないようインセット分を空ける
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 上部：どのアラームが鳴っているか
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Alarm,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = colors.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    label.ifBlank { "アラーム" },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    String.format(
                        "%d月%d日(%s)",
                        now.monthValue, now.dayOfMonth,
                        NextAlarmCalculator.weekdayJa(now.dayOfWeek.value)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant
                )
                if (testMode || fromSnooze) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            if (testMode) "テスト再生中" else "スヌーズ中",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    String.format("%d:%02d", now.hour, now.minute),
                    style = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Bold),
                    color = colors.onSurface
                )
            }

            // 下部：大きさは設定でボタンごとに変更可能。文字とアイコンも高さに連動して大きくする
            val snoozeTextStyle = when {
                snoozeHeight >= 128 -> MaterialTheme.typography.headlineSmall
                snoozeHeight >= 80 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }
            val snoozeIconSize = when {
                snoozeHeight >= 128 -> 32.dp
                snoozeHeight >= 80 -> 26.dp
                else -> 22.dp
            }
            // スヌーズ上限（0=無制限）がある場合、既に上限まで使っていたらスヌーズは出さない
            val snoozeAllowed = snoozeMaxCount <= 0 || snoozeCount < snoozeMaxCount
            if (!snoozeAllowed) {
                Text(
                    "スヌーズは上限回数に達しました",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant
                )
            } else if (snoozeRequiresLongPress) {
                // 設定で長押し式にしている場合は、設定秒数押し続けないと効かないボタンにする
                HoldToConfirmButton(
                    heightDp = snoozeHeight,
                    textStyle = snoozeTextStyle,
                    iconSize = snoozeIconSize,
                    holdDurationMs = snoozeHoldMs,
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    icon = Icons.Default.Snooze,
                    text = "${snoozeMinutes}分間スヌーズ（長押し）",
                    onAction = onSnooze
                )
            } else {
                FilledTonalButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth().height(snoozeHeight.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.secondaryContainer,
                        contentColor = colors.onSecondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Snooze,
                        contentDescription = null,
                        modifier = Modifier.size(snoozeIconSize)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("${snoozeMinutes}分間スヌーズ", style = snoozeTextStyle)
                }
            }
            Spacer(Modifier.height(16.dp))
            val stopTextStyle = when {
                stopHeight >= 160 -> MaterialTheme.typography.headlineMedium
                stopHeight >= 96 -> MaterialTheme.typography.headlineSmall
                else -> MaterialTheme.typography.titleLarge
            }
            val stopIconSize = when {
                stopHeight >= 160 -> 36.dp
                stopHeight >= 96 -> 30.dp
                else -> 26.dp
            }
            if (stopRequiresLongPress) {
                // 設定で長押し式にしている場合は、設定秒数押し続けないと止まらないボタンにする
                HoldToConfirmButton(
                    heightDp = stopHeight,
                    textStyle = stopTextStyle,
                    iconSize = stopIconSize,
                    holdDurationMs = stopHoldMs,
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    icon = Icons.Default.Stop,
                    text = "停止（長押し）",
                    onAction = onStop
                )
            } else {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(stopHeight.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    )
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(stopIconSize)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("停止", style = stopTextStyle)
                }
            }
        }
    }
}

/**
 * 長押しで確定するボタン（誤タップ防止）。停止・スヌーズ共通。
 * 押している間は進捗バーが満ちていき、[holdDurationMs] 押し続けると [onAction] が呼ばれる。
 * 途中で離すと進捗はリセットされ、何も起きない。
 */
@Composable
private fun HoldToConfirmButton(
    heightDp: Int,
    textStyle: TextStyle,
    iconSize: Dp,
    holdDurationMs: Long,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    text: String,
    onAction: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .semantics { contentDescription = text }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    // onPress のレシーバ（PressGestureScope）は CoroutineScope ではないため、
                    // coroutineScope で囲い、その中で進捗アニメーションを並行して走らせる
                    val heldFull = coroutineScope {
                        val animJob = launch {
                            progress.animateTo(
                                1f,
                                animationSpec = tween(
                                    durationMillis = holdDurationMs.toInt(),
                                    easing = LinearEasing
                                )
                            )
                        }
                        // 制限時間まで離されなければ「押し切った」とみなす。
                        // tryAwaitRelease は離されると true、キャンセルされると false を返し、
                        // 時間切れなら withTimeoutOrNull が null を返す（＝押し切った）。
                        val full = withTimeoutOrNull(holdDurationMs) {
                            tryAwaitRelease()
                        } == null
                        animJob.cancel()
                        full
                    }
                    if (heldFull) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAction()
                    }
                    progress.snapTo(0f)
                })
            },
        contentAlignment = Alignment.Center
    ) {
        // 進捗表示：左から半透明が満ちていく
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(contentColor.copy(alpha = 0.28f))
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = contentColor
            )
            Spacer(Modifier.size(8.dp))
            Text(text, style = textStyle, color = contentColor)
        }
    }
}
