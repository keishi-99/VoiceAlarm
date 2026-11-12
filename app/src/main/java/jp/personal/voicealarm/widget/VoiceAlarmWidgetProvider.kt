package jp.personal.voicealarm.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import jp.personal.voicealarm.MainActivity
import jp.personal.voicealarm.R
import jp.personal.voicealarm.data.AppDatabase
import jp.personal.voicealarm.data.WidgetAppearance
import jp.personal.voicealarm.data.WidgetSettings
import jp.personal.voicealarm.logic.NextAlarmCalculator
import jp.personal.voicealarm.scheduler.AlarmSkipper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ホーム画面ウィジェット（RemoteViews）。
 * - 有効な全アラームの中で最も近い次回日時とラベルを表示する
 * - 「次回をスキップ」ボタン: その最も近いアラームの次回の日付をスキップ予約に追加し、
 *   再スケジュール＋ウィジェット更新を行う（スキップ後はその次のアラームを表示する。
 *   表示更新後にもう一度タップすれば、同じアラームの次の日もスキップできる）
 *   誤連打防止のため、スキップ直後の短時間の連続操作は無視する
 * - 本体タップでアプリを開く
 */
open class VoiceAlarmWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateAllWidgets(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
        // サイズが変わったら背景画像（角丸の形）を新しいサイズで描き直す
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateAllWidgets(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_SKIP_NEXT) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    skipNearest(context.applicationContext)
                } finally {
                    pending.finish()
                }
            }
        } else if (intent.action == ACTION_WIDGET_REFRESH) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    updateAllWidgets(context.applicationContext)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_SKIP_NEXT = "jp.personal.voicealarm.ACTION_WIDGET_SKIP_NEXT"
        const val ACTION_WIDGET_REFRESH = "jp.personal.voicealarm.ACTION_WIDGET_REFRESH"

        // 誤連打防止: スキップ操作を受け付けたら、この時間内の次のスキップ操作は無視する。
        // 1回タップしたつもりが、表示が切り替わる前の誤タップや二重配信で
        // 「見ていない別のアラーム」までスキップしてしまう事故を防ぐため。
        // 意図的に続けてスキップしたい場合は、表示が更新されてから（この時間を過ぎてから）
        // もう一度タップすればよい。
        private const val SKIP_DEBOUNCE_MS = 3_000L
        private const val SKIP_PREFS = "widget_skip_guard"
        private const val KEY_LAST_SKIP_AT = "last_skip_at_ms"
        private val skipLock = Any()

        /**
         * スキップの実行権を取る。デバウンス時間内に呼ばれた場合は false を返し、
         * 呼び出し側は何もしない。時刻の記録までをロック内で行い、並行配信でも
         * 二重に通過しないようにする。
         */
        private fun claimSkipSlot(context: Context): Boolean {
            val prefs = context.getSharedPreferences(SKIP_PREFS, Context.MODE_PRIVATE)
            val nowMs = System.currentTimeMillis()
            synchronized(skipLock) {
                val lastMs = prefs.getLong(KEY_LAST_SKIP_AT, 0L)
                if (nowMs - lastMs < SKIP_DEBOUNCE_MS) return false
                // commit() で同期書き込みし、別プロセスからの連続配信でも直後に読めるようにする
                prefs.edit().putLong(KEY_LAST_SKIP_AT, nowMs).commit()
                return true
            }
        }

        /** ウィジェットの種類（通常／小）。レイアウトとルートID、日時の書式が違う。 */
        private class WidgetKind(
            val providerClass: Class<out AppWidgetProvider>,
            val layoutRes: Int,
            val rootId: Int,
            /** 小さい方は幅が狭いので日時を「11/24(火) 7:00」形式にする */
            val compact: Boolean
        )

        private val KINDS: List<WidgetKind>
            get() = listOf(
                WidgetKind(
                    VoiceAlarmWidgetProvider::class.java,
                    R.layout.widget_layout, R.id.widget_root, compact = false
                ),
                WidgetKind(
                    VoiceAlarmWidgetProviderSmall::class.java,
                    R.layout.widget_layout_small, R.id.widget_root_small, compact = true
                )
            )

        fun requestUpdate(context: Context) {
            // 両サイズとも更新する（置いていない方へのブロードキャストは無視される）
            for (kind in KINDS) {
                val intent = Intent(context, kind.providerClass)
                    .setAction(ACTION_WIDGET_REFRESH)
                context.sendBroadcast(intent)
            }
        }

        suspend fun skipNearest(context: Context) {
            if (!claimSkipSlot(context)) {
                // 誤連打と判断: スキップは実行せず、表示だけ最新に保つ
                updateAllWidgets(context)
                return
            }
            // 表示中の最も近い次回の「日付」をスキップ予約に追加する（一覧と共通処理）。
            // 表示が更新された後にもう一度タップすれば、その次の日も追加できる。
            AlarmSkipper.skipNearestOccurrence(context)
            updateAllWidgets(context)
        }

        suspend fun updateAllWidgets(context: Context) = withContext(Dispatchers.IO) {
            val manager = AppWidgetManager.getInstance(context)

            val db = AppDatabase.getInstance(context)
            val groups = db.groupDao().getAll().associateBy { it.id }
            val customs = db.holidayDao().getAll().map { LocalDate.ofEpochDay(it.epochDay) }.toSet()
            val nearest = NextAlarmCalculator.nearestNext(
                db.alarmDao().getEnabled(), groups, customs, LocalDateTime.now()
            )

            val appearance = WidgetSettings.snapshot(context)
            for (kind in KINDS) {
                val component = ComponentName(context, kind.providerClass)
                val ids = manager.getAppWidgetIds(component)
                for (id in ids) {
                    val views = RemoteViews(context.packageName, kind.layoutRes)
                // 外観（背景の色・角丸・不透明度と、それに合わせた文字色）を反映する
                views.setImageViewBitmap(
                    R.id.widget_background,
                    renderBackground(context, manager, id, appearance)
                )
                views.setTextColor(R.id.widget_title_text, appearance.color.onColorSub)
                views.setTextColor(R.id.widget_next_text, appearance.color.onColor)
                views.setTextColor(R.id.widget_date_text, appearance.color.onColor)
                views.setTextColor(R.id.widget_time_text, appearance.color.onColor)
                views.setTextColor(R.id.widget_next_label, appearance.color.onColorSub)
                // スキップボタンも背景画像＋文字色で、ウィジェット背景と透過の効き方を揃える
                views.setImageViewBitmap(
                    R.id.widget_skip_background,
                    renderSkipBackground(appearance, if (kind.compact) 160 else 336)
                )
                views.setTextColor(R.id.widget_skip_button, appearance.color.onColor)
                if (nearest == null) {
                    views.setTextViewText(
                        R.id.widget_next_text,
                        if (kind.compact) "アラーム未設定" else "アラームは未設定です"
                    )
                    // 二段表示：日にち側に未設定文言を出し、時間側は空にする
                    views.setTextViewText(
                        R.id.widget_date_text,
                        if (kind.compact) "アラーム未設定" else "アラームは未設定です"
                    )
                    views.setTextViewText(R.id.widget_time_text, "")
                    // ラベル行を空にするとその分の高さが潰れるため、文言を入れて領域を残す
                    views.setTextViewText(R.id.widget_next_label, "次回はありません")
                    views.setBoolean(R.id.widget_skip_container, "setEnabled", false)
                    views.setBoolean(R.id.widget_skip_button, "setEnabled", false)
                    views.setFloat(R.id.widget_skip_container, "setAlpha", 0.45f)
                } else {
                    val dt = nearest.second.dateTime
                    views.setTextViewText(
                        R.id.widget_next_text,
                        if (kind.compact) {
                            // 小さい方：MM/DD＋曜日＋時刻（例：11/24(火) 7:00）
                            String.format(
                                "%d/%02d(%s) %d:%02d",
                                dt.monthValue, dt.dayOfMonth,
                                NextAlarmCalculator.weekdayJa(dt.dayOfWeek.value),
                                dt.hour, dt.minute
                            )
                        } else {
                            String.format(
                                "%d月%d日(%s) %02d:%02d",
                                dt.monthValue, dt.dayOfMonth,
                                NextAlarmCalculator.weekdayJa(dt.dayOfWeek.value),
                                dt.hour, dt.minute
                            )
                        }
                    )
                    // 両サイズとも二段表示：1段目＝日にち、2段目＝時間。
                    // 日にちの書式はサイズで変える（小さい方は MM/DD、通常は ○月○日）
                    views.setTextViewText(
                        R.id.widget_date_text,
                        if (kind.compact) {
                            String.format(
                                "%d/%02d(%s)",
                                dt.monthValue, dt.dayOfMonth,
                                NextAlarmCalculator.weekdayJa(dt.dayOfWeek.value)
                            )
                        } else {
                            String.format(
                                "%d月%d日(%s)",
                                dt.monthValue, dt.dayOfMonth,
                                NextAlarmCalculator.weekdayJa(dt.dayOfWeek.value)
                            )
                        }
                    )
                    views.setTextViewText(
                        R.id.widget_time_text,
                        if (kind.compact) {
                            String.format("%d:%02d", dt.hour, dt.minute)
                        } else {
                            String.format("%02d:%02d", dt.hour, dt.minute)
                        }
                    )
                    views.setTextViewText(
                        R.id.widget_next_label,
                        nearest.first.label.ifBlank { "アラーム" } +
                            if (nearest.first.futureSkipDates().isNotEmpty()) "（スキップ予約あり）" else ""
                    )
                    views.setBoolean(R.id.widget_skip_container, "setEnabled", true)
                    views.setBoolean(R.id.widget_skip_button, "setEnabled", true)
                    views.setFloat(R.id.widget_skip_container, "setAlpha", 1f)

                    val skipIntent = Intent(context, kind.providerClass)
                        .setAction(ACTION_WIDGET_SKIP_NEXT)
                    val skipPi = PendingIntent.getBroadcast(
                        context, 9001, skipIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    // コンテナと文字のどちらをタップしても効くよう両方に設定する
                    views.setOnClickPendingIntent(R.id.widget_skip_container, skipPi)
                    views.setOnClickPendingIntent(R.id.widget_skip_button, skipPi)
                }
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openPi = PendingIntent.getActivity(
                    context, 9000, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                    views.setOnClickPendingIntent(kind.rootId, openPi)
                    manager.updateAppWidget(id, views)
                }
            }
        }

        /**
         * 背景（単色＋角丸＋不透明度）を、そのウィジェットの実サイズに合わせて画像化する。
         * RemoteViews では Drawable を直接差し替えられないため、この方式を取る。
         * 描画密度は転送量と粗さのバランスで最大2.5倍に抑える。
         */
        private fun renderBackground(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            appearance: WidgetAppearance
        ): Bitmap {
            val options = manager.getAppWidgetOptions(widgetId)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140)
            val scale = context.resources.displayMetrics.density.coerceAtMost(2.5f)
            val widthPx = (widthDp * scale).toInt().coerceIn(64, 1200)
            val heightPx = (heightDp * scale).toInt().coerceIn(64, 1200)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val bg = appearance.color.bgColor
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(
                    appearance.opacityPercent * 255 / 100,
                    Color.red(bg), Color.green(bg), Color.blue(bg)
                )
            }
            val radiusPx = appearance.radiusDp * scale
            canvas.drawRoundRect(
                RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
                radiusPx, radiusPx, paint
            )
            return bitmap
        }

        /**
         * スキップボタンの背景（ピル型）。
         * 色は文字色系の半透明で、濃さはウィジェット背景の不透明度に比例させる
         * （背景を透かすほどボタンも一緒に透ける）。実サイズへは fitXY で伸縮されるため、
         * 基準のアスペクト比で描く。
         */
        private fun renderSkipBackground(appearance: WidgetAppearance, widthPx: Int): Bitmap {
            val heightPx = 144
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val on = appearance.color.onColor
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(
                    64 * appearance.opacityPercent / 100,
                    Color.red(on), Color.green(on), Color.blue(on)
                )
            }
            canvas.drawRoundRect(
                RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
                heightPx / 2f, heightPx / 2f, paint
            )
            return bitmap
        }
    }
}

/**
 * 小さいウィジェット（横長・3×1想定）用プロバイダ。
 * 処理は通常サイズと共通で、レイアウトと配置サイズだけが違う
 * （更新は companion 側で両方まとめて行う）。
 */
class VoiceAlarmWidgetProviderSmall : VoiceAlarmWidgetProvider()
