package jp.personal.voicealarm.logic

import jp.personal.voicealarm.data.Alarm
import jp.personal.voicealarm.data.AlarmGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class NextAlarmCalculatorTest {

    private fun weekdayAlarm(
        hour: Int = 7, minute: Int = 0, skipDates: Set<LocalDate> = emptySet(), groupId: Long? = 1L
    ) = Alarm(
        id = 1, hour = hour, minute = minute, enabled = true,
        repeatDays = "1,2,3,4,5", groupId = groupId,
        skipDatesCsv = Alarm.skipDatesCsvOf(skipDates)
    )

    private val workGroup = AlarmGroup(
        id = 1, name = "仕事", skipJapaneseHolidays = true, skipCustomHolidays = true
    )
    private val plainGroup = AlarmGroup(id = 2, name = "通常")

    @Test fun nextWeekdaySimple() {
        // 2025-05-09 は金曜日 06:00 -> 当日 07:00
        val now = LocalDateTime.of(2025, 5, 9, 6, 0)
        val result = NextAlarmCalculator.calculateNext(weekdayAlarm(), plainGroup, emptySet(), now)
        assertEquals(LocalDateTime.of(2025, 5, 9, 7, 0), result?.dateTime)
    }

    @Test fun weekendIsSkippedByWeekdayRule() {
        // 金曜 08:00 (過ぎた) -> 次は月曜 2025-05-12
        val now = LocalDateTime.of(2025, 5, 9, 8, 0)
        val result = NextAlarmCalculator.calculateNext(weekdayAlarm(), plainGroup, emptySet(), now)
        assertEquals(LocalDateTime.of(2025, 5, 12, 7, 0), result?.dateTime)
    }

    @Test fun japaneseHolidayIsSkippedWhenGroupSettingOn() {
        // 2025-05-02(金) 08:00 の次: 5/3-5/6 は祝日・振替 -> 5/7(水)
        // ただし 5/5,5/6 等。グループ設定ONの場合のみ
        val now = LocalDateTime.of(2025, 5, 2, 8, 0)
        val result = NextAlarmCalculator.calculateNext(weekdayAlarm(), workGroup, emptySet(), now)
        assertEquals(LocalDateTime.of(2025, 5, 7, 7, 0), result?.dateTime)
        assertTrue(result!!.skippedByHoliday.isNotEmpty())
    }

    @Test fun japaneseHolidayIsNotSkippedWhenGroupSettingOff() {
        val now = LocalDateTime.of(2025, 5, 2, 8, 0)
        val result = NextAlarmCalculator.calculateNext(
            weekdayAlarm(groupId = 2L), plainGroup, emptySet(), now
        )
        // plainGroup は土曜が曜日外なので月曜5/5(祝日だが設定OFFなので鳴る)
        assertEquals(LocalDateTime.of(2025, 5, 5, 7, 0), result?.dateTime)
    }

    @Test fun customHolidayIsSkipped() {
        val now = LocalDateTime.of(2025, 5, 7, 8, 0) // 水曜過ぎ -> 木曜5/8が有給
        val customs = setOf(LocalDate.of(2025, 5, 8))
        val result = NextAlarmCalculator.calculateNext(weekdayAlarm(), workGroup, customs, now)
        assertEquals(LocalDateTime.of(2025, 5, 9, 7, 0), result?.dateTime)
    }

    @Test fun singleSkipDateSkipsOnlyThatDate() {
        // (a) スキップ予約が1日分だけなら、その日だけ飛ばして翌日が次回になる
        val now = LocalDateTime.of(2025, 5, 7, 6, 0) // 水曜当日が候補
        val result = NextAlarmCalculator.calculateNext(
            weekdayAlarm(skipDates = setOf(LocalDate.of(2025, 5, 7))), plainGroup, emptySet(), now
        )
        assertEquals(LocalDateTime.of(2025, 5, 8, 7, 0), result?.dateTime)
        assertEquals(
            listOf(LocalDateTime.of(2025, 5, 7, 7, 0)), result?.skippedBySkipDates
        )
    }

    @Test fun twoSkipDatesOnSameAlarmSkipMonAndTue() {
        // (b) 同じ平日アラームに月曜・火曜の2日分を予約すると、次回は水曜になる
        // 2025-05-12 は月曜、5-13 は火曜、5-14 は水曜（いずれも祝日ではない）
        val now = LocalDateTime.of(2025, 5, 11, 20, 0) // 日曜の夜
        val result = NextAlarmCalculator.calculateNext(
            weekdayAlarm(
                skipDates = setOf(LocalDate.of(2025, 5, 12), LocalDate.of(2025, 5, 13))
            ),
            plainGroup, emptySet(), now
        )
        assertEquals(LocalDateTime.of(2025, 5, 14, 7, 0), result?.dateTime)
        assertEquals(
            listOf(
                LocalDateTime.of(2025, 5, 12, 7, 0),
                LocalDateTime.of(2025, 5, 13, 7, 0)
            ),
            result?.skippedBySkipDates
        )
    }

    @Test fun skipDateForDifferentWeekdayDoesNotAffectImmediateNext() {
        // (c) 候補日と違う日の予約は、直近の次回に影響しない
        val now = LocalDateTime.of(2025, 5, 7, 6, 0) // 水曜当日が候補
        val result = NextAlarmCalculator.calculateNext(
            // 予約は翌週月曜分だけ。当日（水曜）は飛ばされない
            weekdayAlarm(skipDates = setOf(LocalDate.of(2025, 5, 12))), plainGroup, emptySet(), now
        )
        assertEquals(LocalDateTime.of(2025, 5, 7, 7, 0), result?.dateTime)
        assertTrue(result!!.skippedBySkipDates.isEmpty())
    }

    @Test fun disabledAlarmHasNoNext() {
        val alarm = weekdayAlarm().copy(enabled = false)
        assertNull(
            NextAlarmCalculator.calculateNext(alarm, plainGroup, emptySet(), LocalDateTime.of(2025, 5, 7, 6, 0))
        )
    }

    @Test fun oneTimeAlarmTodayAndTomorrow() {
        val alarm = Alarm(id = 9, hour = 9, minute = 30, enabled = true, repeatDays = "")
        val morning = NextAlarmCalculator.calculateNext(alarm, null, emptySet(), LocalDateTime.of(2025, 6, 1, 8, 0))
        assertEquals(LocalDateTime.of(2025, 6, 1, 9, 30), morning?.dateTime)
        val after = NextAlarmCalculator.calculateNext(alarm, null, emptySet(), LocalDateTime.of(2025, 6, 1, 10, 0))
        assertEquals(LocalDateTime.of(2025, 6, 2, 9, 30), after?.dateTime)
    }
}
