package jp.personal.voicealarm.scheduler

import android.content.Context
import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.data.AlarmGroup
import jp.personal.voicealarm.data.AppDatabase
import jp.personal.voicealarm.logic.NextAlarmCalculator
import jp.personal.voicealarm.widget.VoiceAlarmWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「次回スキップ」の共通処理（一覧画面とウィジェットで共用）。
 *
 * スキップは日付で記録する（Alarm.skipDatesCsv）:
 * - 1回の操作では、その時点で表示されている次回（= NextAlarmCalculator が返す
 *   次回候補）の日付を1件だけ追加する。
 * - 表示が更新された後にもう一度操作すれば、その次の候補の日付が追加される。
 *   同じアラームでも月曜→火曜のように複数日分を続けてスキップできる。
 * - 過去の日付は対象にしない（過ぎた候補は計算結果に現れないため自然に外れる）。
 */
object AlarmSkipper {

    /**
     * 指定アラームの「現在の次回」の日付をスキップ予約に追加する。
     * 次回候補がない（無効・候補なし）場合は何もせず false を返す。
     */
    suspend fun skipNextOccurrence(context: Context, alarmId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val alarm = db.alarmDao().getById(alarmId) ?: return@withContext false
            val groups = db.groupDao().getAll().associateBy { it.id }
            val customs = db.holidayDao().getAll().map { LocalDate.ofEpochDay(it.epochDay) }.toSet()
            skipNextOccurrence(context, alarm, groups, customs)
        }

    /**
     * 既に読み込み済みのデータを使う版（ウィジェット用）。
     * [alarm] の現在の次回候補の日付をスキップ予約に追加し、再スケジュールする。
     */
    suspend fun skipNextOccurrence(
        context: Context,
        alarm: Alarm,
        groupsById: Map<Long, AlarmGroup>,
        customHolidays: Set<LocalDate>,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
        val next = NextAlarmCalculator.calculateNext(
            alarm, alarm.groupId?.let { groupsById[it] }, customHolidays, now
        ) ?: return false
        val newDates = alarm.skipDateSet() + next.dateTime.toLocalDate()
        val db = AppDatabase.getInstance(context)
        db.alarmDao().setSkipDatesCsv(alarm.id, Alarm.skipDatesCsvOf(newDates))
        AlarmScheduler.rescheduleAlarm(context, alarm.id)
        VoiceAlarmWidgetProvider.requestUpdate(context)
        return true
    }

    /**
     * 最も近い次回アラーム（ウィジェット表示中）の次回をスキップする。
     * ウィジェットのデバウンス判定は呼び出し側（WidgetProvider）が行う。
     */
    suspend fun skipNearestOccurrence(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val groups = db.groupDao().getAll().associateBy { it.id }
            val customs = db.holidayDao().getAll().map { LocalDate.ofEpochDay(it.epochDay) }.toSet()
            val nearest = NextAlarmCalculator.nearestNext(
                db.alarmDao().getEnabled(), groups, customs, LocalDateTime.now()
            ) ?: return@withContext false
            skipNextOccurrence(context, nearest.first, groups, customs)
        }

    /**
     * 指定アラームの未来（今日含む）のスキップ予約をすべて取り消す。
     * 過去分の記録はここでは触らない（鳴動時の整理で消える）。
     */
    suspend fun clearFutureSkips(context: Context, alarmId: Long) =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val alarm = db.alarmDao().getById(alarmId) ?: return@withContext
            val today = LocalDate.now()
            val kept = alarm.skipDateSet().filter { it.isBefore(today) }.toSet()
            if (kept.size != alarm.skipDateSet().size) {
                db.alarmDao().setSkipDatesCsv(alarmId, Alarm.skipDatesCsvOf(kept))
            }
            AlarmScheduler.rescheduleAlarm(context, alarmId)
            VoiceAlarmWidgetProvider.requestUpdate(context)
        }

    /**
     * 過ぎたスキップ予約日（昨日以前）を整理する。鳴動時の後始末用。
     * 未来の予約は一切消さない。変更があった場合のみ保存する。
     */
    suspend fun prunePastSkipDates(context: Context, alarmId: Long) =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val alarm = db.alarmDao().getById(alarmId) ?: return@withContext
            val today = LocalDate.now()
            val kept = alarm.skipDateSet().filter { !it.isBefore(today) }.toSet()
            if (kept.size != alarm.skipDateSet().size) {
                db.alarmDao().setSkipDatesCsv(alarmId, Alarm.skipDatesCsvOf(kept))
            }
        }
}
