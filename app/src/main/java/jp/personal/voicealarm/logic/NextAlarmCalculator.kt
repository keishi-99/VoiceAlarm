package jp.personal.voicealarm.logic

import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.data.AlarmGroup
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 次回鳴動日時を求める純粋ロジック（Android / Room に依存しない形でテスト可能）。
 *
 * 仕様書の優先順位:
 *  1. 繰り返し曜日に含まれる候補日か
 *  2. グループの「祝日オフ」が有効で、その日が日本の祝日（振替・国民の休日含む）ならスキップ
 *  3. グループの「カスタム休日オフ」が有効で、その日が登録済みカスタム休日ならスキップ
 *  4. 候補日がアラームのスキップ予約日（skipDateSet）に含まれていればスキップ。
 *     日付指定のため、同じアラームの複数日（例：月曜と火曜）をまとめて飛ばせる。
 *
 * グループに所属しないアラームは休日スキップの対象外（スキップ予約日は対象）。
 */
object NextAlarmCalculator {

    data class NextResult(
        val dateTime: LocalDateTime,
        /** 次回に至るまでに休日等でスキップされた日（表示用） */
        val skippedByHoliday: List<LocalDate>,
        /** 次回に至るまでにスキップ予約で飛ばされた候補日時（表示用） */
        val skippedBySkipDates: List<LocalDateTime>
    )

    fun calculateNext(
        alarm: Alarm,
        group: AlarmGroup?,
        customHolidays: Set<LocalDate>,
        now: LocalDateTime,
        maxSearchDays: Int = 370
    ): NextResult? {
        if (!alarm.enabled) return null

        val repeat = alarm.repeatDaySet()
        val specificDate: LocalDate? = alarm.specificDateEpochDay?.let { LocalDate.ofEpochDay(it) }
        val skipDates = alarm.skipDateSet()
        val skippedHolidays = mutableListOf<LocalDate>()
        val skippedBySkipDates = mutableListOf<LocalDateTime>()
        var oneTimeConsumedFutureCandidateSeen = false

        for (offset in 0..maxSearchDays) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val candidate = date.atTime(alarm.hour, alarm.minute)
            if (!candidate.isAfter(now)) continue

            // 1) 曜日 / 1回だけの扱い
            if (repeat.isNotEmpty()) {
                if (date.dayOfWeek.value !in repeat) continue
            } else {
                // 繰り返しなし
                if (specificDate != null) {
                    if (date != specificDate) continue
                } else {
                    // 日付指定なしの1回だけ: 最初に来る未来の候補だけが対象
                    if (oneTimeConsumedFutureCandidateSeen) continue
                    oneTimeConsumedFutureCandidateSeen = true
                }
            }

            // 2) 日本の祝日
            if (group?.skipJapaneseHolidays == true && HolidayCalculator.isJapaneseHoliday(date)) {
                skippedHolidays.add(date)
                continue
            }
            // 3) カスタム休日
            if (group?.skipCustomHolidays == true && customHolidays.contains(date)) {
                skippedHolidays.add(date)
                continue
            }
            // 4) スキップ予約日（日付指定・複数日可）
            if (date in skipDates) {
                skippedBySkipDates.add(candidate)
                // 1回だけアラームの唯一の候補がスキップ予約日なら、次の候補は存在しない
                if (repeat.isEmpty()) return null
                continue
            }
            return NextResult(candidate, skippedHolidays.toList(), skippedBySkipDates.toList())
        }
        return null
    }

    /** スヌーズや「明日だけ」用: 単純な次回（祝日等を考慮しない生の候補）ではなく、
     *  スキップボタン用に「今の次回」を返すための薄いラッパー。 */
    fun nearestNext(
        alarms: List<Alarm>,
        groupsById: Map<Long, AlarmGroup>,
        customHolidays: Set<LocalDate>,
        now: LocalDateTime
    ): Pair<Alarm, NextResult>? {
        return alarms.asSequence()
            .filter { it.enabled }
            .mapNotNull { alarm ->
                val group = alarm.groupId?.let { groupsById[it] }
                calculateNext(alarm, group, customHolidays, now)?.let { alarm to it }
            }
            .minByOrNull { it.second.dateTime }
    }

    /** 一覧・ウィジェット表示用テキスト */
    fun previewText(result: NextResult?): String {
        if (result == null) return "次回なし"
        val dt = result.dateTime
        val base = String.format(
            "次回: %d月%d日(%s) %02d:%02d",
            dt.monthValue, dt.dayOfMonth, weekdayJa(dt.dayOfWeek.value), dt.hour, dt.minute
        )
        return when {
            result.skippedByHoliday.isNotEmpty() -> {
                val first = result.skippedByHoliday.first()
                base + "（${first.monthValue}月${first.dayOfMonth}日は休日のためスキップ）"
            }
            result.skippedBySkipDates.isNotEmpty() -> {
                val days = result.skippedBySkipDates.joinToString("・") {
                    "${it.monthValue}月${it.dayOfMonth}日"
                }
                base + "（${days}はスキップ済み）"
            }
            else -> base
        }
    }

    fun weekdayJa(dayOfWeekValue: Int): String = when (dayOfWeekValue) {
        1 -> "月"; 2 -> "火"; 3 -> "水"; 4 -> "木"; 5 -> "金"; 6 -> "土"; 7 -> "日"
        else -> ""
    }
}
