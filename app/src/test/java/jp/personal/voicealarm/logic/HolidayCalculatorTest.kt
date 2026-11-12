package jp.personal.voicealarm.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidayCalculatorTest {

    @Test fun fixedHolidays2025() {
        assertTrue(HolidayCalculator.isJapaneseHoliday(LocalDate.of(2025, 1, 1)))
        assertEquals("憲法記念日", HolidayCalculator.holidayName(LocalDate.of(2025, 5, 3)))
        assertTrue(HolidayCalculator.isJapaneseHoliday(LocalDate.of(2025, 11, 3)))
        assertFalse(HolidayCalculator.isJapaneseHoliday(LocalDate.of(2025, 11, 4)))
    }

    @Test fun happyMondays2025() {
        // 成人の日: 2025年1月第2月曜 = 1/13
        assertEquals("成人の日", HolidayCalculator.holidayName(LocalDate.of(2025, 1, 13)))
        // 海の日: 2025年7月第3月曜 = 7/21
        assertEquals("海の日", HolidayCalculator.holidayName(LocalDate.of(2025, 7, 21)))
        // 敬老の日: 2025年9月第3月曜 = 9/15
        assertEquals("敬老の日", HolidayCalculator.holidayName(LocalDate.of(2025, 9, 15)))
    }

    @Test fun equinox2025() {
        // 2025年 春分の日 3/20, 秋分の日 9/23
        assertEquals("春分の日", HolidayCalculator.holidayName(LocalDate.of(2025, 3, 20)))
        assertEquals("秋分の日", HolidayCalculator.holidayName(LocalDate.of(2025, 9, 23)))
    }

    @Test fun substituteHoliday() {
        // 2025年2月23日(日) 天皇誕生日 -> 2月24日(月) 振替休日
        assertEquals(DayOfWeekForTest.SUNDAY, LocalDate.of(2025, 2, 23).dayOfWeek.value % 7)
        assertEquals("振替休日", HolidayCalculator.holidayName(LocalDate.of(2025, 2, 24)))
        // 2025年5月4日(日) みどりの日 -> 5月6日(火) が振替 (5/5はこどもの日)
        assertEquals("振替休日", HolidayCalculator.holidayName(LocalDate.of(2025, 5, 6)))
    }

    @Test fun citizensHoliday2026September() {
        // 2026年: 敬老の日 9/21(月), 秋分の日 9/23(水) -> 9/22(火) 国民の休日
        assertEquals("敬老の日", HolidayCalculator.holidayName(LocalDate.of(2026, 9, 21)))
        assertEquals("秋分の日", HolidayCalculator.holidayName(LocalDate.of(2026, 9, 23)))
        assertEquals("国民の休日", HolidayCalculator.holidayName(LocalDate.of(2026, 9, 22)))
    }

    @Test fun goldenWeek2027SpotCheck() {
        assertTrue(HolidayCalculator.isJapaneseHoliday(LocalDate.of(2027, 5, 3)))
        assertTrue(HolidayCalculator.isJapaneseHoliday(LocalDate.of(2027, 5, 5)))
        assertFalse(HolidayCalculator.isJapaneseHoliday(LocalDate.of(2027, 5, 6)))
    }

    // dayOfWeek.value: Mon=1..Sun=7 なので、日曜チェック用の小さな補助
    private object DayOfWeekForTest { const val SUNDAY = 0 }
}
