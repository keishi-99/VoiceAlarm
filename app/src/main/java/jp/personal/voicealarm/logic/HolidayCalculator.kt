package jp.personal.voicealarm.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日本の国民の祝日をルールベースで計算する。
 *
 * 対応範囲: 2025〜2027年を主な対象とし、計算ルール自体は 2000〜2099 年で
 * 概ね正しく動作する想定。ただし以下は対象外・簡略化している:
 * - 皇室行事等によるその年限定の特別な祝日・休日（例: 即位関連）
 * - オリンピック等による祝日の移動（例: 2020/2021年の海の日等の特例）
 * - 春分・秋分は天文計算の近似式（1980年基準の常用式）を使用
 *
 * 実装するルール:
 * - 固定日: 元日(1/1), 建国記念(2/11), 天皇誕生日(2/23), 昭和の日(4/29),
 *   憲法記念(5/3), みどりの日(5/4), こどもの日(5/5), 山の日(8/11),
 *   文化の日(11/3), 勤労感謝(11/23)
 * - ハッピーマンデー: 成人の日(1月第2月曜), 海の日(7月第3月曜),
 *   敬老の日(9月第3月曜), スポーツの日(10月第2月曜)
 * - 春分の日・秋分の日: 近似式
 * - 振替休日: 祝日が日曜の場合、その後で祝日でない最初の日を振替休日とする
 * - 国民の休日: 前後を祝日に挟まれた平日を休日とする
 */
object HolidayCalculator {

    private val cache = mutableMapOf<Int, Map<LocalDate, String>>()

    fun holidaysOfYear(year: Int): Map<LocalDate, String> =
        cache.getOrPut(year) { computeYear(year) }

    fun isJapaneseHoliday(date: LocalDate): Boolean =
        holidaysOfYear(date.year).containsKey(date)

    fun holidayName(date: LocalDate): String? = holidaysOfYear(date.year)[date]

    fun holidaysOfMonth(yearMonth: YearMonth): Map<LocalDate, String> =
        holidaysOfYear(yearMonth.year).filterKeys { YearMonth.from(it) == yearMonth }

    private fun computeYear(year: Int): Map<LocalDate, String> {
        val base = linkedMapOf<LocalDate, String>()

        fun add(month: Int, day: Int, name: String) {
            base[LocalDate.of(year, month, day)] = name
        }
        fun addHappyMonday(month: Int, nth: Int, name: String) {
            base[nthMonday(year, month, nth)] = name
        }

        add(1, 1, "元日")
        addHappyMonday(1, 2, "成人の日")
        add(2, 11, "建国記念の日")
        add(2, 23, "天皇誕生日")
        add(3, vernalEquinoxDay(year), "春分の日")
        add(4, 29, "昭和の日")
        add(5, 3, "憲法記念日")
        add(5, 4, "みどりの日")
        add(5, 5, "こどもの日")
        addHappyMonday(7, 3, "海の日")
        add(8, 11, "山の日")
        addHappyMonday(9, 3, "敬老の日")
        add(9, autumnalEquinoxDay(year), "秋分の日")
        addHappyMonday(10, 2, "スポーツの日")
        add(11, 3, "文化の日")
        add(11, 23, "勤労感謝の日")

        val result = base.toMutableMap()

        // 国民の休日: 前日と翌日が祝日の平日（判定は振替より先に行うのが実務的。
        // 典型例は9月の敬老の日と秋分の日に挟まれた日など）
        // 翌年の1/1が関係する12/31等は扱わない（実例がないため）。
        var d = LocalDate.of(year, 1, 2)
        val end = LocalDate.of(year, 12, 30)
        while (!d.isAfter(end)) {
            if (!result.containsKey(d) &&
                result.containsKey(d.minusDays(1)) &&
                result.containsKey(d.plusDays(1))
            ) {
                // 挟まれた日が日曜等でも国民の休日になり得るが、平日のみを対象とする簡略化
                if (d.dayOfWeek != DayOfWeek.SUNDAY && d.dayOfWeek != DayOfWeek.SATURDAY) {
                    result[d] = "国民の休日"
                }
            }
            d = d.plusDays(1)
        }

        // 振替休日: 祝日（国民の休日を含む）が日曜なら、直後の祝日でない日を振替とする
        val sundayHolidays = result.keys.filter { it.dayOfWeek == DayOfWeek.SUNDAY }.sorted()
        for (holiday in sundayHolidays) {
            var substitute = holiday.plusDays(1)
            while (result.containsKey(substitute)) {
                substitute = substitute.plusDays(1)
            }
            // 年をまたぐ振替（12/31が日曜で祝日等）は実例がないため同年内のみ保持するが、
            // 計算自体は保持してよい。ただし他年のキャッシュと混ざらないよう当年分だけ返す。
            if (substitute.year == year) {
                result[substitute] = "振替休日"
            }
        }

        return result.toMap()
    }

    private fun nthMonday(year: Int, month: Int, nth: Int): LocalDate {
        var date = LocalDate.of(year, month, 1)
        while (date.dayOfWeek != DayOfWeek.MONDAY) date = date.plusDays(1)
        return date.plusWeeks((nth - 1).toLong())
    }

    /** 春分の日の近似式（1980〜2099で使用される常用式） */
    fun vernalEquinoxDay(year: Int): Int {
        val v = 20.8431 + 0.242194 * (year - 1980) - ((year - 1980) / 4)
        return v.toInt()
    }

    /** 秋分の日の近似式 */
    fun autumnalEquinoxDay(year: Int): Int {
        val v = 23.2488 + 0.242194 * (year - 1980) - ((year - 1980) / 4)
        return v.toInt()
    }
}
