package jp.personal.voicealarm.ringing

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import jp.personal.voicealarm.R
import jp.personal.voicealarm.VoiceAlarmApplication
import jp.personal.voicealarm.data.AppDatabase
import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.data.RingHistory
import jp.personal.voicealarm.widget.VoiceAlarmWidgetProvider
import jp.personal.voicealarm.scheduler.AlarmScheduler
import jp.personal.voicealarm.scheduler.AlarmSkipper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 鳴動中フォアグラウンドサービス。
 * フルスクリーンインテント通知で RingingActivity をロック画面上に表示し、
 * VoiceAlarmPlayer で「時刻読み上げ → SE」を繰り返す。
 * 停止／スヌーズ後は次回アラームを再スケジュールする。
 * 鳴動時には、過ぎたスキップ予約日だけを整理する（未来の予約は消さない）。
 */
class RingingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var player: VoiceAlarmPlayer? = null
    private var currentAlarm: Alarm? = null
    /** この鳴動までにスヌーズした累計回数（通常鳴動は0） */
    private var currentSnoozeCount: Int = 0

    /** 待ち行列に入った鳴動リクエスト（同時刻に重なったアラーム用）。 */
    private data class PendingRing(val alarmId: Long, val isSnooze: Boolean, val snoozeCount: Int)

    /**
     * 同時刻に重なったアラームの待ち行列。鳴動中に来たアラームはここに溜めて、
     * 今の鳴動が終わった順に鳴らす（後から来たものに上書きされて消えるのを防ぐ）。
     */
    private val pendingRings = ArrayDeque<PendingRing>()

    /** 鳴動中（開始処理中を含む）かどうか。二重起動とキューの判定に使う。 */
    private var ringingActive = false

    /** 音量ブーストで引き上げる前の端末アラーム音量（インデックス）。ブーストしていない間は null。 */
    private var volumeBoostedFrom: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRinging(rescheduleNext = true, historyEvent = "停止")
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                snooze()
                return START_NOT_STICKY
            }
        }
        val alarmId = intent?.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L) ?: -1L
        if (alarmId < 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val isSnooze = intent?.getBooleanExtra(AlarmScheduler.EXTRA_IS_SNOOZE, false) ?: false
        val snoozeCount =
            if (isSnooze) intent?.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0) ?: 0 else 0

        if (ringingActive) {
            // すでに別のアラームが鳴動中：取りこぼさないよう待ち行列に入れる。
            // 次回分の先行予約とスキップ整理だけは今のうちに済ませておく
            // （実際に鳴らすのは今の鳴動が終わった後。履歴の「鳴動」もその時点で記録する）
            pendingRings.removeAll { it.alarmId == alarmId }
            pendingRings.addLast(PendingRing(alarmId, isSnooze, snoozeCount))
            if (!isSnooze) {
                scope.launch {
                    AlarmSkipper.prunePastSkipDates(this@RingingService, alarmId)
                    AlarmScheduler.rescheduleAlarm(this@RingingService, alarmId)
                }
            }
            return START_STICKY
        }
        ringingActive = true
        startRinging(alarmId, isSnooze, snoozeCount)
        return START_STICKY
    }

    /** 鳴動を開始する（通常の開始と、待ち行列から取り出した次のアラームの両方で使う）。 */
    private fun startRinging(alarmId: Long, isSnooze: Boolean, snoozeCount: Int) {
        currentSnoozeCount = snoozeCount
        scope.launch {
            val db = AppDatabase.getInstance(this@RingingService)
            val alarm = withContext(Dispatchers.IO) { db.alarmDao().getById(alarmId) }
                ?: Alarm(id = alarmId, hour = 7, minute = 0)
            currentAlarm = alarm
            boostAlarmVolumeIfNeeded(alarm)

            startForeground(NOTIFICATION_ID, buildNotification(alarm, isSnooze))
            launchRingingActivity(alarm, isSnooze)

            player?.stop()
            player = VoiceAlarmPlayer(this@RingingService, alarm) {
                // 自動停止（10分）
                stopRinging(rescheduleNext = true, historyEvent = "自動停止（10分）")
            }.also { it.start() }
            recordHistory(alarm, if (isSnooze) "スヌーズで再鳴動" else "鳴動")

            // 鳴動が始まったら、過ぎたスキップ予約日（昨日以前）だけを整理し、
            // スヌーズでない通常鳴動では次回分を先にスケジュールしておく
            // （鳴動中に端末が落ちても次回が失われないように）。
            // 未来のスキップ予約（例：明日もスキップ済み）はここでは消さない。
            if (!isSnooze) {
                AlarmSkipper.prunePastSkipDates(this@RingingService, alarmId)
                AlarmScheduler.rescheduleAlarm(this@RingingService, alarmId)
            }
        }
    }

    /**
     * 現在の鳴動が終わった後の処理。
     * 待ち行列に次のアラームがあれば続けて鳴らし、なければサービスを止める。
     */
    private fun proceedAfterRinging() {
        val next = pendingRings.removeFirstOrNull()
        if (next != null) {
            startRinging(next.alarmId, next.isSnooze, next.snoozeCount)
        } else {
            ringingActive = false
            restoreAlarmVolumeIfBoosted()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * 端末のアラーム音量がアプリ側の設定音量（ボイス/SEの大きい方）より小さい場合、
     * 鳴動中だけ端末音量を最大まで引き上げる。
     * アプリ側の音量は端末音量に掛け算されるため、端末側が下げられていると
     * 設定した大きさで鳴らない（ゼロなら無音になる）のを防ぐのが目的。
     * 引き上げ前の音量は覚えておき、鳴動が全て終わったら元に戻す。
     */
    private fun boostAlarmVolumeIfNeeded(alarm: Alarm) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val maxIndex = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (maxIndex <= 0) return
        val currentIndex = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val configured = maxOf(alarm.voiceVolume, alarm.seVolume)
        if (currentIndex.toFloat() / maxIndex < configured) {
            if (volumeBoostedFrom == null) volumeBoostedFrom = currentIndex
            audio.setStreamVolume(AudioManager.STREAM_ALARM, maxIndex, 0)
        }
    }

    /** 引き上げていた端末のアラーム音量を元に戻す（鳴動が全て終わったときだけ呼ぶ）。 */
    private fun restoreAlarmVolumeIfBoosted() {
        val original = volumeBoostedFrom ?: return
        volumeBoostedFrom = null
        val audio = getSystemService(AudioManager::class.java) ?: return
        audio.setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
    }

    private fun buildNotification(alarm: Alarm, isSnooze: Boolean): Notification {
        val activityIntent = Intent(this, RingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtrasForRingingActivity(alarm, isSnooze)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, alarm.id.toInt(), activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = alarm.label.ifBlank { "アラーム" }
        return NotificationCompat.Builder(this, VoiceAlarmApplication.CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(label)
            .setContentText(String.format("%02d:%02d", alarm.hour, alarm.minute))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setOngoing(true)
            .build()
    }

    private fun launchRingingActivity(alarm: Alarm, isSnooze: Boolean) {
        val intent = Intent(this, RingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtrasForRingingActivity(alarm, isSnooze)
        }
        startActivity(intent)
    }

    /** 鳴動画面に表示用の情報（ラベル・スヌーズ分数・スヌーズ再鳴動かどうか・スヌーズ回数と上限）を載せる。 */
    private fun Intent.putExtrasForRingingActivity(alarm: Alarm, isSnooze: Boolean) {
        putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
        putExtra(RingingActivity.EXTRA_LABEL, alarm.label)
        putExtra(RingingActivity.EXTRA_SNOOZE_MINUTES, alarm.snoozeMinutes)
        putExtra(RingingActivity.EXTRA_FROM_SNOOZE, isSnooze)
        putExtra(RingingActivity.EXTRA_SNOOZE_COUNT, currentSnoozeCount)
        putExtra(RingingActivity.EXTRA_SNOOZE_MAX_COUNT, alarm.snoozeMaxCount)
    }

    /** 鳴動履歴に1件記録する（ラベル・時刻は鳴動時点のスナップショット）。 */
    private fun recordHistory(alarm: Alarm, event: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(this@RingingService).ringHistoryDao()
                dao.insert(
                    RingHistory(
                        alarmId = alarm.id,
                        label = alarm.label,
                        alarmTime = String.format("%02d:%02d", alarm.hour, alarm.minute),
                        event = event,
                        eventTimeMillis = System.currentTimeMillis()
                    )
                )
                dao.prune()
            }
        }
    }

    private fun stopRinging(rescheduleNext: Boolean, historyEvent: String? = null) {
        player?.stop()
        player = null
        val alarm = currentAlarm
        if (historyEvent != null && alarm != null) {
            recordHistory(alarm, historyEvent)
        }
        if (rescheduleNext && alarm != null) {
            scope.launch {
                if (alarm.repeatDaySet().isEmpty()) {
                    // 1回だけアラーム（日付指定・日付なし共通）は、鳴り終わったら自動でオフにする。
                    // 鳴動開始時に翌日分が先行予約されていても、ここで取り消すので鳴り続けない。
                    // スヌーズ中はここを通らないため、スヌーズ後にまた鳴る
                    withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(this@RingingService).alarmDao()
                            .setEnabled(alarm.id, false)
                    }
                    AlarmScheduler.cancel(this@RingingService, alarm.id)
                    VoiceAlarmWidgetProvider.requestUpdate(this@RingingService)
                } else {
                    AlarmScheduler.rescheduleAlarm(this@RingingService, alarm.id)
                }
            }
        }
        sendBroadcast(Intent(ACTION_FINISH_RINGING).setPackage(packageName))
        proceedAfterRinging()
    }

    private fun snooze() {
        val alarm = currentAlarm
        if (alarm == null) {
            stopRinging(rescheduleNext = false)
            return
        }
        // 上限チェック（画面側でもスヌーズを出さないが、二重の安全策）。
        // 上限を超える場合はスヌーズせず停止として扱う
        val nextCount = currentSnoozeCount + 1
        if (alarm.snoozeMaxCount > 0 && nextCount > alarm.snoozeMaxCount) {
            stopRinging(rescheduleNext = true, historyEvent = "停止")
            return
        }
        player?.stop()
        player = null
        recordHistory(alarm, "スヌーズ（${alarm.snoozeMinutes}分後）")
        AlarmScheduler.scheduleSnooze(this, alarm.id, alarm.snoozeMinutes, nextCount)
        sendBroadcast(Intent(ACTION_FINISH_RINGING).setPackage(packageName))
        proceedAfterRinging()
    }

    override fun onDestroy() {
        player?.stop()
        restoreAlarmVolumeIfBoosted()
        pendingRings.clear()
        ringingActive = false
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "jp.personal.voicealarm.ringing.STOP"
        const val ACTION_SNOOZE = "jp.personal.voicealarm.ringing.SNOOZE"
        const val ACTION_FINISH_RINGING = "jp.personal.voicealarm.ringing.FINISH"

        fun stopIntent(context: Context): Intent =
            Intent(context, RingingService::class.java).setAction(ACTION_STOP)

        fun snoozeIntent(context: Context): Intent =
            Intent(context, RingingService::class.java).setAction(ACTION_SNOOZE)
    }
}
