package jp.personal.voicealarm.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import jp.personal.voicealarm.R
import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.data.AlarmGroup
import jp.personal.voicealarm.ringing.RingingActivity

data class SoundChoice(val name: String, val uri: String?)

/** content:// URI の表示名（ファイル名）を取得する。取れない場合は汎用の名前。 */
fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    return try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "ファイル音源"
    } catch (_: Exception) {
        "ファイル音源"
    }
}

/**
 * 音源選択メニューでの試し聴き用プレイヤー。同時に鳴るのは1つだけ。
 * 本番の鳴動に近い音量感で確認できるよう、アラーム用途の属性で再生する。
 */
private class SoundPreviewer(private val context: android.content.Context) {
    private var ringtone: Ringtone? = null

    /** 再生を開始する。成功したら true。 */
    fun play(uri: Uri): Boolean {
        stop()
        return try {
            val rt = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return false
            rt.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                rt.isLooping = true
            }
            rt.play()
            ringtone = rt
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
            // 停止失敗は無視する
        }
        ringtone = null
    }
}

/** DatePicker（UTC基準）と LocalDate の相互変換。 */
private fun localDateToUtcMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMillisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** 日付指定（1回だけ）の表示文。例：2026年11月24日(火) */
fun specificDateText(date: LocalDate): String = String.format(
    "%d年%d月%d日(%s)",
    date.year, date.monthValue, date.dayOfMonth,
    jp.personal.voicealarm.logic.NextAlarmCalculator.weekdayJa(date.dayOfWeek.value)
)

/** 繰り返し曜日の要約（編集画面の表示用）。 */
fun repeatSummaryText(days: Set<Int>): String {
    if (days.isEmpty()) return "1回だけ"
    if (days == setOf(1, 2, 3, 4, 5, 6, 7)) return "毎日"
    if (days == setOf(1, 2, 3, 4, 5)) return "平日"
    if (days == setOf(6, 7)) return "週末"
    val names = listOf("月", "火", "水", "木", "金", "土", "日")
    return days.sorted().joinToString("・") { names[it - 1] }
}

/**
 * 音量確認用の SE 試し聴きプレイヤー（MediaPlayer）。
 * 指定した音量でループ再生し、再生中の音量変更（スライダー操作）にも追従する。
 */
private class SePreviewPlayer(private val context: android.content.Context) {
    private var player: MediaPlayer? = null

    fun play(uri: Uri, volume: Float): Boolean {
        stop()
        return try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            p.setDataSource(context.applicationContext, uri)
            p.isLooping = true
            p.setVolume(volume, volume)
            p.prepare()
            p.start()
            player = p
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setVolume(volume: Float) {
        try {
            player?.setVolume(volume, volume)
        } catch (_: Exception) {
            // 再生が終わっている場合は無視する
        }
    }

    fun stop() {
        try {
            player?.stop()
        } catch (_: Exception) {
            // 停止失敗は無視する
        }
        try {
            player?.release()
        } catch (_: Exception) {
            // 解放失敗は無視する
        }
        player = null
    }
}

/**
 * 音量確認用のボイス試し聴き（TTS）。
 * 本番と同じ形式のサンプル文を、指定したボイス音量で読み上げる。
 */
private class VoicePreviewer(context: android.content.Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.JAPANESE
                ready = true
            }
        }
    }

    fun speakSample(volume: Float) {
        val engine = tts ?: return
        if (!ready) return
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        engine.speak("7時00分です", TextToSpeech.QUEUE_FLUSH, params, "volume_preview")
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // 終了失敗は無視する
        }
        tts = null
    }
}

/**
 * アプリ内蔵の音源：デフォルト（端末の既定音）＋ res/raw の合成 WAV。
 * 内蔵 WAV は android.resource:// URI として保存・再生する。
 */
fun builtinSoundChoices(context: android.content.Context): List<SoundChoice> {
    val pkg = context.packageName
    return listOf(
        SoundChoice("デフォルト", null),
        SoundChoice("内蔵ビープ", "android.resource://$pkg/${R.raw.builtin_beep}"),
        SoundChoice("内蔵ベル", "android.resource://$pkg/${R.raw.builtin_bell}"),
        SoundChoice("内蔵電子音", "android.resource://$pkg/${R.raw.builtin_electronic}")
    )
}

/**
 * 端末の音源一覧を種類別に取得する（RingtoneManager の TYPE_RINGTONE /
 * TYPE_ALARM / TYPE_NOTIFICATION）。取得に失敗した場合は空リスト。
 */
fun loadDeviceSounds(context: android.content.Context, type: Int): List<SoundChoice> {
    val result = mutableListOf<SoundChoice>()
    try {
        val manager = RingtoneManager(context).apply { setType(type) }
        val cursor = manager.cursor
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                ?: "音源${cursor.position + 1}"
            val uri = manager.getRingtoneUri(cursor.position).toString()
            result.add(SoundChoice(title, uri))
        }
        cursor.close()
    } catch (_: Exception) {
        // 空のまま返す（選択メニューでは「見つかりませんでした」表示になる）
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlarmEditScreen(
    initial: Alarm?,
    groups: List<AlarmGroup>,
    onSave: (Alarm) -> Unit,
    onDelete: (Alarm) -> Unit,
    onDuplicate: (Alarm) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // 時刻は専用数字キーパッドで「HHMM」の4桁を左から順に入力する（xx:xx 表示）。
    // 桁ごとに範囲チェックするので、25時や60分はそもそも入力できない
    var timeDigits by remember {
        mutableStateOf(
            String.format("%02d%02d", initial?.hour ?: 7, initial?.minute ?: 0)
        )
    }

    // 時刻の入力は「選択」ボタンから開くメニュー（ダイアログ）内で行い、
    // 下書きを編集して「決定」で確定する（繰り返し・音源と同じ方式）
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var selectedDays by remember { mutableStateOf(initial?.repeatDaySet() ?: setOf(1, 2, 3, 4, 5)) }
    // 日付指定（1回だけ）。繰り返し曜日とは排他：日付が入っているときは曜日を見ない
    var specificDate by remember {
        mutableStateOf(initial?.specificDateEpochDay?.let { LocalDate.ofEpochDay(it) })
    }
    var groupId by remember { mutableStateOf(initial?.groupId) }
    // 音源はカテゴリ別に読み込み、選択中の音源は1つだけ保持する。
    // 保存済みの音源（音楽ファイル含む）は名前とURIが DB にあるので、そのまま初期値にできる
    val builtinSounds = remember { builtinSoundChoices(context) }
    val ringtoneSounds = remember { loadDeviceSounds(context, RingtoneManager.TYPE_RINGTONE) }
    val alarmSounds = remember { loadDeviceSounds(context, RingtoneManager.TYPE_ALARM) }
    val notificationSounds = remember { loadDeviceSounds(context, RingtoneManager.TYPE_NOTIFICATION) }
    var selectedSound by remember {
        mutableStateOf(
            SoundChoice(
                initial?.soundName
                    ?: if (initial?.soundUri == null) "デフォルト" else "ファイル音源",
                initial?.soundUri
            )
        )
    }
    var showSoundPicker by remember { mutableStateOf(false) }
    var pickerCategory by remember { mutableStateOf<String?>(null) }
    // 試し聴き：同時に鳴るのは1つだけ。画面を離れたら必ず止める
    val previewer = remember { SoundPreviewer(context) }
    var previewKey by remember { mutableStateOf<String?>(null) }
    fun stopPreview() {
        previewer.stop()
        previewKey = null
    }
    fun togglePreview(choice: SoundChoice) {
        val key = choice.uri ?: "__default__"
        if (previewKey == key) {
            stopPreview()
        } else {
            val uri = choice.uri?.let { Uri.parse(it) }
                ?: Settings.System.DEFAULT_ALARM_ALERT_URI
                ?: Settings.System.DEFAULT_RINGTONE_URI
            if (uri != null && previewer.play(uri)) {
                previewKey = key
            } else {
                stopPreview()
            }
        }
    }
    // 繰り返し・グループも「選択ボタン→メニュー」方式にするための状態。
    // 繰り返しは複数選択なので、下書き（draft）を「決定」で確定する
    var showTimePicker by remember { mutableStateOf(false) }
    var timeDraft by remember { mutableStateOf(timeDigits) }
    // 音量・スヌーズも「選択→メニュー」方式にするためのダイアログ状態（下書きは開くときにコピー）
    var showVoiceVolumePicker by remember { mutableStateOf(false) }
    var voiceVolumeDraft by remember { mutableFloatStateOf(0f) }
    var showSeVolumePicker by remember { mutableStateOf(false) }
    var seVolumeDraft by remember { mutableFloatStateOf(0f) }
    var showSnoozePicker by remember { mutableStateOf(false) }
    var snoozeDraft by remember { mutableStateOf("") }
    var snoozeMaxDraft by remember { mutableStateOf("") }
    var showRepeatPicker by remember { mutableStateOf(false) }
    var repeatDraft by remember { mutableStateOf(selectedDays) }
    var specificDateDraft by remember { mutableStateOf(specificDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    // 音量の試し聴き：SE はループ再生（スライダー操作に音量が追従）、ボイスはサンプル読み上げ
    val sePreviewer = remember { SePreviewPlayer(context) }
    var sePreviewing by remember { mutableStateOf(false) }
    val voicePreviewer = remember { VoicePreviewer(context) }
    fun stopSePreview() {
        sePreviewer.stop()
        sePreviewing = false
    }
    DisposableEffect(Unit) {
        onDispose {
            previewer.stop()
            sePreviewer.stop()
            voicePreviewer.shutdown()
        }
    }
    // 端末の音楽ファイルを音源として取り込む。読み取り許可を永続化し、
    // 再起動後や鳴動サービスからでも再生できるようにする
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // 許可が取れなくても、その場の再生は可能な範囲で続行する
            }
            selectedSound = SoundChoice(queryDisplayName(context, uri), uri.toString())
        }
    }
    var voiceVolume by remember { mutableFloatStateOf(initial?.voiceVolume ?: 0.8f) }
    var seVolume by remember { mutableFloatStateOf(initial?.seVolume ?: 1.0f) }
    var fadeIn by remember { mutableStateOf(initial?.fadeIn ?: false) }
    var vibration by remember { mutableStateOf(initial?.vibration ?: true) }
    var snoozeText by remember { mutableStateOf((initial?.snoozeMinutes ?: 5).toString()) }
    var snoozeMaxText by remember { mutableStateOf((initial?.snoozeMaxCount ?: 0).toString()) }

    fun buildAlarm(): Alarm {
        val choice = selectedSound
        // 途中までしか入力されていない場合は右を 0 で埋めて解釈する（例："073"→07:30）
        val paddedTime = timeDigits.padEnd(4, '0')
        return Alarm(
            id = initial?.id ?: 0,
            hour = paddedTime.substring(0, 2).toInt().coerceIn(0, 23),
            minute = paddedTime.substring(2, 4).toInt().coerceIn(0, 59),
            label = label,
            enabled = initial?.enabled ?: true,
            repeatDays = selectedDays.sorted().joinToString(","),
            groupId = groupId,
            soundUri = choice.uri,
            soundName = choice.name,
            voiceVolume = voiceVolume,
            seVolume = seVolume,
            fadeIn = fadeIn,
            vibration = vibration,
            snoozeMinutes = snoozeText.toIntOrNull()?.coerceIn(1, 60) ?: 5,
            snoozeMaxCount = snoozeMaxText.toIntOrNull()?.coerceIn(0, 99) ?: 0,
            skipDatesCsv = initial?.skipDatesCsv ?: "",
            specificDateEpochDay = specificDate?.toEpochDay()
        )
    }

    // 音源選択メニュー：まず種類（カテゴリ）を選び、その中の音源一覧から1つ選ぶ。
    // 「音楽ファイル」だけは端末のファイル選択画面へ遷移する
    if (showSoundPicker) {
        val categorySounds = when (pickerCategory) {
            "内蔵音源" -> builtinSounds
            "着信音" -> ringtoneSounds
            "アラーム音" -> alarmSounds
            "通知音" -> notificationSounds
            else -> emptyList()
        }
        AlertDialog(
            onDismissRequest = {
                stopPreview()
                showSoundPicker = false
                pickerCategory = null
            },
            title = { Text(pickerCategory ?: "音源を選ぶ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (pickerCategory == null) {
                        listOf("内蔵音源", "着信音", "アラーム音", "通知音", "音楽ファイル")
                            .forEach { category ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        stopPreview()
                                        if (category == "音楽ファイル") {
                                            showSoundPicker = false
                                            filePicker.launch(arrayOf("audio/*"))
                                        } else {
                                            pickerCategory = category
                                        }
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        category,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                    } else {
                        if (categorySounds.isEmpty()) {
                            Text("この種類の音源は見つかりませんでした")
                        }
                        categorySounds.forEach { choice ->
                            val key = choice.uri ?: "__default__"
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    stopPreview()
                                    selectedSound = choice
                                    showSoundPicker = false
                                    pickerCategory = null
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedSound.uri == choice.uri,
                                    onClick = null
                                )
                                Text(
                                    choice.name,
                                    Modifier.padding(start = 8.dp).weight(1f)
                                )
                                // 試し聴き：再生中は停止ボタンに変わる
                                IconButton(onClick = { togglePreview(choice) }) {
                                    Icon(
                                        if (previewKey == key) Icons.Default.Stop
                                        else Icons.Default.PlayArrow,
                                        contentDescription =
                                            if (previewKey == key) "停止" else "試し聴き"
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    stopPreview()
                    if (pickerCategory != null) pickerCategory = null
                    else showSoundPicker = false
                }) { Text(if (pickerCategory != null) "種類に戻る" else "閉じる") }
            }
        )
    }

    // 繰り返し選択メニュー：曜日は複数選択なので、下書きを「決定」で確定する
    if (showRepeatPicker) {
        AlertDialog(
            onDismissRequest = { showRepeatPicker = false },
            title = { Text("繰り返しを選ぶ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = repeatDraft.size == 7 && specificDateDraft == null,
                            onClick = {
                                repeatDraft = (1..7).toSet()
                                specificDateDraft = null
                            },
                            label = { Text("毎日") }
                        )
                        FilterChip(
                            selected = repeatDraft == setOf(1, 2, 3, 4, 5) &&
                                specificDateDraft == null,
                            onClick = {
                                repeatDraft = setOf(1, 2, 3, 4, 5)
                                specificDateDraft = null
                            },
                            label = { Text("平日") }
                        )
                        FilterChip(
                            selected = repeatDraft == setOf(6, 7) && specificDateDraft == null,
                            onClick = {
                                repeatDraft = setOf(6, 7)
                                specificDateDraft = null
                            },
                            label = { Text("週末") }
                        )
                        FilterChip(
                            selected = repeatDraft.isEmpty() && specificDateDraft == null,
                            onClick = {
                                repeatDraft = emptySet()
                                specificDateDraft = null
                            },
                            label = { Text("1回だけ") }
                        )
                    }
                    val names = listOf("月", "火", "水", "木", "金", "土", "日")
                    for (i in 1..7) {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                repeatDraft =
                                    if (i in repeatDraft) repeatDraft - i else repeatDraft + i
                                specificDateDraft = null
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = i in repeatDraft, onCheckedChange = null)
                            Text(names[i - 1] + "曜日", Modifier.padding(start = 8.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    // 日付指定（1回だけ）：選ぶと曜日指定は解除され、その日だけ鳴る
                    Column {
                        Text("日付を指定（1回だけ）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            specificDateDraft?.let { specificDateText(it) } ?: "未指定",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(onClick = { showDatePicker = true }) {
                                Text(if (specificDateDraft == null) "日付を選ぶ" else "日付を変更")
                            }
                            if (specificDateDraft != null) {
                                TextButton(onClick = { specificDateDraft = null }) {
                                    Text("指定を解除")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedDays = repeatDraft
                    specificDate = specificDateDraft
                    showRepeatPicker = false
                }) { Text("決定") }
            },
            dismissButton = {
                TextButton(onClick = { showRepeatPicker = false }) { Text("キャンセル") }
            }
        )
    }

    // 日付ピッカー（繰り返しメニューの上に重ねて開く）。過去の日は選べない
    if (showDatePicker) {
        val todayUtcMillis = localDateToUtcMillis(LocalDate.now())
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis =
                localDateToUtcMillis(specificDateDraft ?: LocalDate.now()),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= todayUtcMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = datePickerState.selectedDateMillis
                    if (picked != null) {
                        specificDateDraft = utcMillisToLocalDate(picked)
                        // 日付を指定したら繰り返し曜日は解除する（排他）
                        repeatDraft = emptySet()
                    }
                    showDatePicker = false
                }) { Text("決定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("キャンセル") }
            }
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    }

    // 時刻ピッカー（専用数字キーパッド）。下書きを編集し、「決定」で確定する
    if (showTimePicker) {
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("時刻") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeDisplayText(timeDraft)
                    Text(
                        "数字キーで4桁入力（例：7時30分 → 0730）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    TimeKeypad(
                        onDigit = { timeDraft = appendTimeDigit(timeDraft, it) },
                        onBackspace = { timeDraft = timeDraft.dropLast(1) },
                        onClear = { timeDraft = "" }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    timeDigits = timeDraft
                    showTimePicker = false
                }) { Text("決定") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("キャンセル") }
            }
        )
    }

    // ボイス音量メニュー（スライダー＋試し聴き）。下書きを編集し、「決定」で確定する
    if (showVoiceVolumePicker) {
        AlertDialog(
            onDismissRequest = { showVoiceVolumePicker = false },
            title = { Text("ボイス音量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${(voiceVolumeDraft * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Slider(
                        value = voiceVolumeDraft,
                        onValueChange = { voiceVolumeDraft = it },
                        valueRange = 0f..1f
                    )
                    OutlinedButton(
                        onClick = { voicePreviewer.speakSample(voiceVolumeDraft) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ボイスをこの音量で試し聴き")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    voiceVolume = voiceVolumeDraft
                    showVoiceVolumePicker = false
                }) { Text("決定") }
            },
            dismissButton = {
                TextButton(onClick = { showVoiceVolumePicker = false }) { Text("キャンセル") }
            }
        )
    }

    // SE音量メニュー（スライダー＋試し聴き）。閉じるときは必ず試し聴きを止める
    if (showSeVolumePicker) {
        AlertDialog(
            onDismissRequest = {
                stopSePreview()
                showSeVolumePicker = false
            },
            title = { Text("SE音量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${(seVolumeDraft * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Slider(
                        value = seVolumeDraft,
                        onValueChange = {
                            seVolumeDraft = it
                            // 試し聴き中は、スライダーの動きに再生音量をその場で追従させる
                            if (sePreviewing) sePreviewer.setVolume(it)
                        },
                        valueRange = 0f..1f
                    )
                    OutlinedButton(
                        onClick = {
                            if (sePreviewing) {
                                stopSePreview()
                            } else {
                                val uri = selectedSound.uri?.let { Uri.parse(it) }
                                    ?: Settings.System.DEFAULT_ALARM_ALERT_URI
                                    ?: Settings.System.DEFAULT_RINGTONE_URI
                                if (uri != null && sePreviewer.play(uri, seVolumeDraft)) {
                                    sePreviewing = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (sePreviewing) "SEの試し聴きを止める" else "SEをこの音量で試し聴き")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    stopSePreview()
                    seVolume = seVolumeDraft
                    showSeVolumePicker = false
                }) { Text("決定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    stopSePreview()
                    showSeVolumePicker = false
                }) { Text("キャンセル") }
            }
        )
    }

    // スヌーズメニュー（間隔・最大回数）。下書きを編集し、「決定」で確定する
    if (showSnoozePicker) {
        AlertDialog(
            onDismissRequest = { showSnoozePicker = false },
            title = { Text("スヌーズ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = snoozeDraft,
                        onValueChange = { snoozeDraft = it.filter { c -> c.isDigit() } },
                        label = { Text("スヌーズ間隔（分）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = snoozeMaxDraft,
                        onValueChange = {
                            snoozeMaxDraft = it.filter { c -> c.isDigit() }.take(2)
                        },
                        label = { Text("スヌーズ最大回数（0=無制限）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "上限に達すると、鳴動画面にスヌーズボタンが出なくなります。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    snoozeText = snoozeDraft
                    snoozeMaxText = snoozeMaxDraft
                    showSnoozePicker = false
                }) { Text("決定") }
            },
            dismissButton = {
                TextButton(onClick = { showSnoozePicker = false }) { Text("キャンセル") }
            }
        )
    }

    // グループ選択メニュー：単一選択なので、タップで即決定して閉じる
    if (showGroupPicker) {
        AlertDialog(
            onDismissRequest = { showGroupPicker = false },
            title = { Text("グループを選ぶ") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            groupId = null
                            showGroupPicker = false
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = groupId == null, onClick = null)
                        Text("なし", Modifier.padding(start = 8.dp))
                    }
                    groups.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                groupId = g.id
                                showGroupPicker = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = groupId == g.id, onClick = null)
                            Text(g.name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGroupPicker = false }) { Text("閉じる") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "アラームを追加" else "アラームを編集") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }
            )
        },
        // テスト再生と保存はスクロール位置によらず常に一番下に表示する
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    // edge-to-edge 表示では Scaffold のボトムバーにナビゲーションバーの
                    // インセットが自動で付かないため、端末の戻るボタン等と被らないよう明示的に空ける
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // テスト再生は実際の鳴動画面をテストモードで開く。
                    // 編集中の（未保存の）音設定をそのまま渡すので、保存前でも本番と同じ鳴り方・見た目を確認できる。
                    // 鳴動画面側の停止／スヌーズはテスト終了として働き、本物のアラームには影響しない。
                    OutlinedButton(
                        onClick = {
                            // 試し聴きが鳴っていたら止めてからテスト再生を開く（裏で鳴り続けないように）
                            stopPreview()
                            stopSePreview()
                            val a = buildAlarm()
                            val intent = Intent(context, RingingActivity::class.java).apply {
                                putExtra(RingingActivity.EXTRA_TEST_MODE, true)
                                putExtra(RingingActivity.EXTRA_LABEL, a.label)
                                putExtra(RingingActivity.EXTRA_SNOOZE_MINUTES, a.snoozeMinutes)
                                putExtra(RingingActivity.EXTRA_SNOOZE_MAX_COUNT, a.snoozeMaxCount)
                                putExtra(RingingActivity.EXTRA_SOUND_URI, a.soundUri)
                                putExtra(RingingActivity.EXTRA_VOICE_VOLUME, a.voiceVolume)
                                putExtra(RingingActivity.EXTRA_SE_VOLUME, a.seVolume)
                                putExtra(RingingActivity.EXTRA_FADE_IN, a.fadeIn)
                                putExtra(RingingActivity.EXTRA_VIBRATION, a.vibration)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("テスト再生") }

                    Button(
                        onClick = { onSave(buildAlarm()) },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EditSection("時刻") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTimeDigits(timeDigits),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        timeDraft = timeDigits
                        showTimePicker = true
                    }) {
                        Text("選択")
                    }
                }
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("ラベル（任意・読み上げません）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            EditSection("繰り返し・グループ") {
                Text("繰り返し", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (specificDate != null) specificDateText(specificDate!!)
                        else repeatSummaryText(selectedDays),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        repeatDraft = selectedDays
                        specificDateDraft = specificDate
                        showRepeatPicker = true
                    }) {
                        Text("選択")
                    }
                }
                Text("グループ", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        groups.firstOrNull { it.id == groupId }?.name ?: "なし",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { showGroupPicker = true }) {
                        Text("選択")
                    }
                }
            }

            EditSection("音") {
                Text("SE（アラーム音）", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        selectedSound.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        stopSePreview()
                        pickerCategory = null
                        showSoundPicker = true
                    }) {
                        Text("選択")
                    }
                }
                Text(
                    "「選択」を押すと、内蔵音源・着信音・アラーム音・通知音・音楽ファイルから選べます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("ボイス音量", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${(voiceVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        voiceVolumeDraft = voiceVolume
                        showVoiceVolumePicker = true
                    }) {
                        Text("選択")
                    }
                }
                Text("SE音量", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${(seVolume * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        stopSePreview()
                        seVolumeDraft = seVolume
                        showSeVolumePicker = true
                    }) {
                        Text("選択")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("フェードイン", Modifier.weight(1f))
                    Switch(checked = fadeIn, onCheckedChange = { fadeIn = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("バイブレーション", Modifier.weight(1f))
                    Switch(checked = vibration, onCheckedChange = { vibration = it })
                }
            }

            EditSection("スヌーズ") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val minutes = snoozeText.toIntOrNull()?.coerceIn(1, 60) ?: 5
                    val maxCount = snoozeMaxText.toIntOrNull()?.coerceIn(0, 99) ?: 0
                    Text(
                        if (maxCount > 0) "${minutes}分ごと・最大${maxCount}回"
                        else "${minutes}分ごと・回数無制限",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        snoozeDraft = snoozeText
                        snoozeMaxDraft = snoozeMaxText
                        showSnoozePicker = true
                    }) {
                        Text("選択")
                    }
                }
            }

            if (initial != null) {
                // 複製：編集中の内容をそのまま新規アラームとして保存する。
                // スキップ予約は引き継がない（複製先はまっさらな状態から始める）
                OutlinedButton(onClick = {
                    val base = buildAlarm()
                    onDuplicate(
                        base.copy(
                            id = 0,
                            label = if (base.label.isBlank()) base.label
                            else "${base.label} のコピー",
                            skipDatesCsv = ""
                        )
                    )
                }) {
                    Text("このアラームを複製")
                }
                OutlinedButton(onClick = { onDelete(initial) }) {
                    Text("このアラームを削除")
                }
            }
        }
    }
}

/**
 * 4桁入力に数字を1桁追加した結果を返す（桁ごとの範囲チェック付き）。
 * 4桁入っている状態で押したら最初から入力し直す
 * （3〜9 は時の十の位に置けないので、0 を補って時の一の位として受け付ける）。
 */
private fun appendTimeDigit(current: String, d: Int): String {
    var buf = current
    if (buf.length >= 4) buf = if (d in 0..2) "" else "0"
    val valid = when (buf.length) {
        0 -> d in 0..2 // 時：十の位
        1 -> if (buf[0] == '2') d in 0..3 else true // 時：一の位（2x時は23まで）
        2 -> d in 0..5 // 分：十の位
        else -> true
    }
    return if (valid) buf + d.toString() else buf
}

/** "0730" → "07:30"（途中までの入力は右を 0 で埋めて解釈）。 */
fun formatTimeDigits(digits: String): String {
    val padded = digits.padEnd(4, '0')
    return padded.substring(0, 2) + ":" + padded.substring(2, 4)
}

/** 時刻の xx:xx 表示。未入力の桁は薄い 0 で出す。 */
@Composable
private fun TimeDisplayText(digits: String) {
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    val display = buildAnnotatedString {
        for (i in 0..3) {
            if (i == 2) append(":")
            val ch = digits.getOrNull(i)
            if (ch != null) {
                append(ch.toString())
            } else {
                withStyle(SpanStyle(color = placeholderColor)) { append("0") }
            }
        }
    }
    Text(
        display,
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

/** 時刻入力用の専用数字キーパッド（1〜9／C・0・⌫ のダイヤル配置）。 */
@Composable
private fun TimeKeypad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (d in row) {
                    TimeKey(modifier = Modifier.weight(1f), onClick = { onDigit(d) }) {
                        Text(d.toString(), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeKey(modifier = Modifier.weight(1f), onClick = onClear) {
                Text("C", style = MaterialTheme.typography.headlineSmall)
            }
            TimeKey(modifier = Modifier.weight(1f), onClick = { onDigit(0) }) {
                Text("0", style = MaterialTheme.typography.headlineSmall)
            }
            TimeKey(modifier = Modifier.weight(1f), onClick = onBackspace) {
                // アイコンは版によって参照方法が変わるため、文字キーで統一する
                Text("⌫", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun TimeKey(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.height(52.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 編集画面のセクションカード（見出し＋中身）。項目のまとまりを目で追いやすくするための枠。 */
@Composable
private fun EditSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
