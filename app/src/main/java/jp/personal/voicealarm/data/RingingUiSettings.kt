package jp.personal.voicealarm.data

import android.content.Context

/** 鳴動画面のボタンの大きさ（高さdp）。設定画面でボタンごとに選べる。 */
enum class RingingButtonSize(val heightDp: Int, val label: String) {
    SMALL(56, "小"),
    MEDIUM(64, "中"),
    LARGE(80, "大"),
    EXTRA_LARGE(96, "特大"),
    HUGE(128, "巨大"),
    SUPER(160, "超大"),
    MAX(192, "最大");

    companion object {
        fun fromName(name: String?, default: RingingButtonSize): RingingButtonSize =
            entries.firstOrNull { it.name == name } ?: default
    }
}

/**
 * 鳴動画面まわりの表示設定（アプリ全体で1つ）。
 * アラームごとの設定（音・音量など）は Room の Alarm に持つが、
 * ボタンの大きさは「使う人の手・眠気」に合わせるものなのでアプリ全体設定として
 * SharedPreferences に持つ。
 */
object RingingUiSettings {
    private const val PREFS = "ringing_ui_settings"
    private const val KEY_SNOOZE_SIZE = "snooze_button_size"
    private const val KEY_STOP_SIZE = "stop_button_size"
    private const val KEY_STOP_LONG_PRESS = "stop_requires_long_press"
    private const val KEY_SNOOZE_LONG_PRESS = "snooze_requires_long_press"
    private const val KEY_STOP_HOLD_SECONDS = "stop_hold_seconds"
    private const val KEY_SNOOZE_HOLD_SECONDS = "snooze_hold_seconds"
    private const val KEY_VOLUME_STOP = "stop_on_volume_button"
    private const val KEY_VOLUME_ACTION = "volume_button_action"

    // 既定値：従来の鳴動画面の見た目（スヌーズ64dp／停止76dp相当）に一番近い組み合わせ
    val DEFAULT_SNOOZE_SIZE = RingingButtonSize.MEDIUM
    val DEFAULT_STOP_SIZE = RingingButtonSize.LARGE

    fun snoozeSize(context: Context): RingingButtonSize =
        RingingButtonSize.fromName(
            prefs(context).getString(KEY_SNOOZE_SIZE, null), DEFAULT_SNOOZE_SIZE
        )

    fun stopSize(context: Context): RingingButtonSize =
        RingingButtonSize.fromName(
            prefs(context).getString(KEY_STOP_SIZE, null), DEFAULT_STOP_SIZE
        )

    fun setSnoozeSize(context: Context, size: RingingButtonSize) {
        prefs(context).edit().putString(KEY_SNOOZE_SIZE, size.name).apply()
    }

    fun setStopSize(context: Context, size: RingingButtonSize) {
        prefs(context).edit().putString(KEY_STOP_SIZE, size.name).apply()
    }

    /** 停止ボタンを長押し式にするか（誤タップ防止）。既定はタップで停止。 */
    fun stopRequiresLongPress(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STOP_LONG_PRESS, false)

    fun setStopRequiresLongPress(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_STOP_LONG_PRESS, value).apply()
    }

    /** スヌーズボタンを長押し式にするか（誤タップ防止）。既定はタップでスヌーズ。 */
    fun snoozeRequiresLongPress(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SNOOZE_LONG_PRESS, false)

    fun setSnoozeRequiresLongPress(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SNOOZE_LONG_PRESS, value).apply()
    }

    /** スヌーズの長押し時間（秒）。停止とは別に設定する。1〜3秒、既定は1秒。 */
    fun snoozeHoldSeconds(context: Context): Int =
        prefs(context).getInt(KEY_SNOOZE_HOLD_SECONDS, 1).coerceIn(1, 3)

    fun setSnoozeHoldSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_SNOOZE_HOLD_SECONDS, seconds.coerceIn(1, 3)).apply()
    }

    /** 鳴動中の音量ボタンに割り当てる動作。 */
    enum class VolumeButtonAction { NONE, STOP, SNOOZE }

    /**
     * 音量ボタンの動作（鳴動画面表示中のみ）。既定は「なし」。
     * 旧設定（止めるオン/オフ）がオンだった場合は「停止」として引き継ぐ。
     */
    fun volumeButtonAction(context: Context): VolumeButtonAction {
        val p = prefs(context)
        val stored = p.getString(KEY_VOLUME_ACTION, null)
        if (stored != null) {
            return runCatching { VolumeButtonAction.valueOf(stored) }
                .getOrDefault(VolumeButtonAction.NONE)
        }
        return if (p.getBoolean(KEY_VOLUME_STOP, false)) VolumeButtonAction.STOP
        else VolumeButtonAction.NONE
    }

    fun setVolumeButtonAction(context: Context, action: VolumeButtonAction) {
        prefs(context).edit().putString(KEY_VOLUME_ACTION, action.name).apply()
    }

    /** 停止の長押し時間（秒）。スヌーズとは別に設定する。1〜3秒、既定は1秒。 */
    fun stopHoldSeconds(context: Context): Int =
        prefs(context).getInt(KEY_STOP_HOLD_SECONDS, 1).coerceIn(1, 3)

    fun setStopHoldSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_STOP_HOLD_SECONDS, seconds.coerceIn(1, 3)).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
