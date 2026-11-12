package jp.personal.voicealarm.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.data.AlarmGroup
import jp.personal.voicealarm.data.AppDatabase
import jp.personal.voicealarm.data.CustomHoliday
import jp.personal.voicealarm.logic.NextAlarmCalculator
import jp.personal.voicealarm.scheduler.AlarmScheduler
import jp.personal.voicealarm.scheduler.AlarmSkipper
import jp.personal.voicealarm.widget.VoiceAlarmWidgetProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class AlarmUiState(
    val alarms: List<Alarm> = emptyList(),
    val groups: List<AlarmGroup> = emptyList(),
    val holidays: List<CustomHoliday> = emptyList(),
    /** alarmId -> 次回プレビューテキスト */
    val previews: Map<Long, String> = emptyMap(),
    /** alarmId -> 次回日時（並び・ウィジェット連携用） */
    val nextTimes: Map<Long, LocalDateTime> = emptyMap()
)

class AlarmViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val alarmDao = db.alarmDao()
    private val groupDao = db.groupDao()
    private val holidayDao = db.holidayDao()

    val uiState: StateFlow<AlarmUiState> = combine(
        alarmDao.observeAll(), groupDao.observeAll(), holidayDao.observeAll()
    ) { alarms, groups, holidays ->
        val groupsById = groups.associateBy { it.id }
        val customs = holidays.map { LocalDate.ofEpochDay(it.epochDay) }.toSet()
        val now = LocalDateTime.now()
        val results = alarms.mapNotNull { alarm ->
            val result = NextAlarmCalculator.calculateNext(
                alarm, alarm.groupId?.let { groupsById[it] }, customs, now
            )
            result?.let { alarm.id to it }
        }.toMap()
        AlarmUiState(
            alarms = alarms,
            groups = groups,
            holidays = holidays,
            previews = results.mapValues { NextAlarmCalculator.previewText(it.value) } +
                alarms.filter { it.id !in results }.associate { it.id to "次回なし" },
            nextTimes = results.mapValues { it.value.dateTime }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlarmUiState())

    private fun afterChange(alarmId: Long? = null) {
        viewModelScope.launch {
            if (alarmId != null) {
                AlarmScheduler.rescheduleAlarm(getApplication(), alarmId)
            } else {
                AlarmScheduler.rescheduleAll(getApplication())
            }
            VoiceAlarmWidgetProvider.requestUpdate(getApplication())
        }
    }

    fun saveAlarm(alarm: Alarm) {
        viewModelScope.launch {
            val id = if (alarm.id == 0L) alarmDao.insert(alarm) else {
                alarmDao.update(alarm); alarm.id
            }
            afterChange(id)
        }
    }

    suspend fun getAlarm(id: Long): Alarm? = alarmDao.getById(id)

    fun deleteAlarm(alarm: Alarm) {
        viewModelScope.launch {
            AlarmScheduler.cancel(getApplication(), alarm.id)
            alarmDao.delete(alarm)
            afterChange()
        }
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean) {
        viewModelScope.launch {
            alarmDao.setEnabled(alarm.id, enabled)
            afterChange(alarm.id)
        }
    }

    /** 表示中の次回1回分をスキップ予約に追加する（同じアラームで何日分でも追加できる）。 */
    fun skipNextOccurrence(alarm: Alarm) {
        viewModelScope.launch {
            // AlarmSkipper 側で保存・再スケジュール・ウィジェット更新まで行う
            AlarmSkipper.skipNextOccurrence(getApplication(), alarm.id)
        }
    }

    /** そのアラームの未来のスキップ予約をすべて取り消す。 */
    fun clearFutureSkips(alarm: Alarm) {
        viewModelScope.launch {
            AlarmSkipper.clearFutureSkips(getApplication(), alarm.id)
        }
    }

    fun saveGroup(group: AlarmGroup) {
        viewModelScope.launch {
            if (group.id == 0L) groupDao.insert(group) else groupDao.update(group)
            // グループの休日設定は所属アラーム全員の次回に影響する
            afterChange()
        }
    }

    fun deleteGroup(group: AlarmGroup) {
        viewModelScope.launch {
            groupDao.delete(group)
            afterChange()
        }
    }

    fun addCustomHoliday(date: LocalDate, memo: String) {
        viewModelScope.launch {
            holidayDao.insert(CustomHoliday(epochDay = date.toEpochDay(), memo = memo))
            afterChange()
        }
    }

    fun removeCustomHoliday(date: LocalDate) {
        viewModelScope.launch {
            holidayDao.deleteByEpochDay(date.toEpochDay())
            afterChange()
        }
    }
}
