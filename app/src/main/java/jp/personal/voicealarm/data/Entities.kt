package jp.personal.voicealarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * アラーム本体。
 * repeatDays は DayOfWeek.value (1=月 .. 7=日) をカンマ区切りで保持する。
 * 空文字 = 繰り返しなし（1回だけ）。その場合 specificDateEpochDay があればその日、
 * なければ「次に来る当該時刻（過ぎていれば翌日）」に鳴る。
 * skipDatesCsv は「次回スキップ」で飛ばす日付（LocalDate.toEpochDay()）を
 * カンマ区切りで保持する。空文字 = スキップ予約なし。日付で持つことで、
 * 同じアラームの複数回分（例：月曜と火曜）をまとめてスキップできる。
 */
@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val enabled: Boolean = true,
    val repeatDays: String = "",
    val groupId: Long? = null,
    val soundUri: String? = null,
    val soundName: String = "デフォルト",
    val voiceVolume: Float = 0.8f,
    val seVolume: Float = 1.0f,
    val fadeIn: Boolean = false,
    val vibration: Boolean = true,
    val snoozeMinutes: Int = 5,
    /** スヌーズの最大回数。0 = 無制限。上限に達すると鳴動画面にスヌーズが出なくなる */
    @ColumnInfo(defaultValue = "0")
    val snoozeMaxCount: Int = 0,
    /** スキップする日付の集合（LocalDate.toEpochDay() のカンマ区切り）。空 = 予約なし */
    val skipDatesCsv: String = "",
    val specificDateEpochDay: Long? = null
) {
    fun repeatDaySet(): Set<Int> =
        repeatDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    /** スキップ予約された日付の集合を返す。壊れた値は無視する。 */
    fun skipDateSet(): Set<LocalDate> =
        skipDatesCsv.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .map { LocalDate.ofEpochDay(it) }
            .toSet()

    /** 今日以降のスキップ予約だけを返す（一覧・ウィジェットの表示用）。 */
    fun futureSkipDates(today: LocalDate = LocalDate.now()): List<LocalDate> =
        skipDateSet().filter { !it.isBefore(today) }.sorted()

    companion object {
        /** 日付の集合から skipDatesCsv 形式の文字列を作る（ソート済み・重複なし）。 */
        fun skipDatesCsvOf(dates: Set<LocalDate>): String =
            dates.map { it.toEpochDay() }.sorted().joinToString(",")
    }
}

@Entity(tableName = "alarm_groups")
data class AlarmGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val skipJapaneseHolidays: Boolean = false,
    val skipCustomHolidays: Boolean = false
)

@Entity(tableName = "custom_holidays")
data class CustomHoliday(
    /** LocalDate.toEpochDay() */
    @PrimaryKey val epochDay: Long,
    val memo: String = ""
)

/**
 * 鳴動履歴（いつ・どのアラームが・どうなったか）。
 * ラベルと時刻は鳴動時点のスナップショット（元アラームを編集・削除しても履歴は残る）。
 * event は表示文そのもの（例：鳴動／スヌーズ（5分後）／停止／自動停止）。
 */
@Entity(tableName = "ring_history")
data class RingHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: Long,
    val label: String = "",
    /** 鳴動したアラームの時刻（"07:00" 形式） */
    val alarmTime: String = "",
    val event: String,
    val eventTimeMillis: Long
)
