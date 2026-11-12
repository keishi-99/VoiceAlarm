package jp.personal.voicealarm.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import jp.personal.voicealarm.data.AppDatabase
import jp.personal.voicealarm.logic.NextAlarmCalculator
import jp.personal.voicealarm.receiver.AlarmReceiver
import jp.personal.voicealarm.widget.VoiceAlarmWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * AlarmManager への登録を一元管理する。
 * 鳴動は setAlarmClock を使う（最も優先度が高く、時計アイコン表示もされる）。
 * フォールバックとして canScheduleExactAlarms() を確認し、不可なら
 * setAndAllowWhileIdle を使う（数分ずれる可能性があるため、設定画面で許可を案内する）。
 */
object AlarmScheduler {

    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_IS_SNOOZE = "extra_is_snooze"
    /** その鳴動までにスヌーズした累計回数（上限判定に使う）。通常鳴動は0 */
    const val EXTRA_SNOOZE_COUNT = "extra_snooze_count"

    private fun pendingIntent(
        context: Context,
        alarmId: Long,
        isSnooze: Boolean = false,
        snoozeCount: Int = 0
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_IS_SNOOZE, isSnooze)
            putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
            // スヌーズと通常で別 PendingIntent にする
            action = if (isSnooze) "jp.personal.voicealarm.SNOOZE.$alarmId" else "jp.personal.voicealarm.ALARM.$alarmId"
        }
        return PendingIntent.getBroadcast(
            context,
            (alarmId * 10 + if (isSnooze) 1 else 0).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleAt(
        context: Context,
        alarmId: Long,
        trigger: LocalDateTime,
        isSnooze: Boolean = false,
        snoozeCount: Int = 0
    ) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val triggerMillis = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pendingIntent(context, alarmId, isSnooze, snoozeCount)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else true

        if (isSnooze) {
            // スヌーズは正確さ優先で setExactAndAllowWhileIdle
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            }
            return
        }
        val showIntent = PendingIntent.getActivity(
            context, alarmId.toInt(),
            Intent(context, jp.personal.voicealarm.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (canExact) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerMillis, showIntent), pi)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
        }
    }

    fun cancel(context: Context, alarmId: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(pendingIntent(context, alarmId, false))
        manager.cancel(pendingIntent(context, alarmId, true))
    }

    fun scheduleSnooze(context: Context, alarmId: Long, snoozeMinutes: Int, snoozeCount: Int = 1) {
        scheduleAt(
            context, alarmId, LocalDateTime.now().plusMinutes(snoozeMinutes.toLong()),
            isSnooze = true, snoozeCount = snoozeCount
        )
    }

    /** DB の内容に基づいて1つのアラームの次回を再計算して登録する。 */
    suspend fun rescheduleAlarm(context: Context, alarmId: Long) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val alarm = db.alarmDao().getById(alarmId)
        if (alarm == null || !alarm.enabled) {
            cancel(context, alarmId)
            return@withContext
        }
        val groups = db.groupDao().getAll().associateBy { it.id }
        val customs = db.holidayDao().getAll().map { LocalDate.ofEpochDay(it.epochDay) }.toSet()
        val next = NextAlarmCalculator.calculateNext(
            alarm, alarm.groupId?.let { groups[it] }, customs, LocalDateTime.now()
        )
        cancel(context, alarmId)
        if (next != null) scheduleAt(context, alarmId, next.dateTime)
        VoiceAlarmWidgetProvider.requestUpdate(context)
    }

    /** 全有効アラームを再登録（再起動時・時刻変更時・アプリ更新時）。 */
    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val groups = db.groupDao().getAll().associateBy { it.id }
        val customs = db.holidayDao().getAll().map { LocalDate.ofEpochDay(it.epochDay) }.toSet()
        val now = LocalDateTime.now()
        for (alarm in db.alarmDao().getEnabled()) {
            val next = NextAlarmCalculator.calculateNext(
                alarm, alarm.groupId?.let { groups[it] }, customs, now
            )
            cancel(context, alarm.id)
            if (next != null) scheduleAt(context, alarm.id, next.dateTime)
        }
        VoiceAlarmWidgetProvider.requestUpdate(context)
    }
}
