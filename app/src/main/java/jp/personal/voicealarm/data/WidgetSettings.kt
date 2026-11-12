package jp.personal.voicealarm.data

import android.content.Context

/**
 * ウィジェットの背景色プリセット。
 * onColor はメイン文字色、onColorSub は補助文字色（背景色に合わせて自動で決まる）。
 */
enum class WidgetColor(
    val label: String,
    val bgColor: Int,
    val onColor: Int,
    val onColorSub: Int
) {
    WHITE("ホワイト", 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF666666.toInt()),
    BLACK("ブラック", 0xFF202124.toInt(), 0xFFF8F9FA.toInt(), 0xFFBDC1C6.toInt()),
    GRAY("グレー", 0xFF5F6368.toInt(), 0xFFFFFFFF.toInt(), 0xFFE8EAED.toInt()),
    INDIGO("インディゴ", 0xFF3949AB.toInt(), 0xFFFFFFFF.toInt(), 0xFFC5CAE9.toInt()),
    AMBER("アンバー", 0xFFFFB300.toInt(), 0xFF3E2723.toInt(), 0xFF6D4C41.toInt()),
    TEAL("ティール", 0xFF00897B.toInt(), 0xFFFFFFFF.toInt(), 0xFFB2DFDB.toInt());

    companion object {
        fun fromName(name: String?, default: WidgetColor): WidgetColor =
            entries.firstOrNull { it.name == name } ?: default
    }
}

/** ウィジェットの外観設定のスナップショット（プロバイダが描画に使う）。 */
data class WidgetAppearance(
    val color: WidgetColor,
    val radiusDp: Int,
    val opacityPercent: Int
)

/**
 * ホーム画面ウィジェットの見た目（アプリ全体で1つ、SharedPreferences）。
 * 色・角丸・不透明度を設定画面から変えられる。変更したら
 * VoiceAlarmWidgetProvider.requestUpdate で即反映する。
 */
object WidgetSettings {
    private const val PREFS = "widget_settings"
    private const val KEY_COLOR = "widget_color"
    private const val KEY_RADIUS_DP = "widget_radius_dp"
    private const val KEY_OPACITY = "widget_opacity_percent"

    val DEFAULT_COLOR = WidgetColor.WHITE
    const val DEFAULT_RADIUS_DP = 16
    const val DEFAULT_OPACITY = 100

    /** 角丸の選択肢（dp → 表示ラベル） */
    val RADIUS_OPTIONS: List<Pair<Int, String>> = listOf(
        0 to "なし", 8 to "小", 16 to "中", 24 to "大", 32 to "特大"
    )

    fun color(context: Context): WidgetColor =
        WidgetColor.fromName(prefs(context).getString(KEY_COLOR, null), DEFAULT_COLOR)

    fun radiusDp(context: Context): Int =
        prefs(context).getInt(KEY_RADIUS_DP, DEFAULT_RADIUS_DP)

    /** 背景の不透明度（%）。100 = ベタ塗り、小さいほど透ける。 */
    fun opacityPercent(context: Context): Int =
        prefs(context).getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(20, 100)

    fun snapshot(context: Context): WidgetAppearance =
        WidgetAppearance(color(context), radiusDp(context), opacityPercent(context))

    fun setColor(context: Context, color: WidgetColor) {
        prefs(context).edit().putString(KEY_COLOR, color.name).apply()
    }

    fun setRadiusDp(context: Context, dp: Int) {
        prefs(context).edit().putInt(KEY_RADIUS_DP, dp).apply()
    }

    fun setOpacityPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_OPACITY, percent.coerceIn(20, 100)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
