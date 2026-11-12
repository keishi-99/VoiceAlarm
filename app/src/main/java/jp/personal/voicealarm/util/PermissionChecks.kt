package jp.personal.voicealarm.util

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/** アラームが確実に鳴るために必要な許可の状態チェック（設定画面と一覧バナーで共用）。 */
object PermissionChecks {

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java)
        return manager.canScheduleExactAlarms()
    }

    fun isBatteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Android 14以降で、ロック画面上に鳴動画面を直接開く許可があるか。13以前は常時可。 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm?.canUseFullScreenIntent() ?: true
    }

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 外れている重要な許可の表示名リスト（空なら全て問題なし）。 */
    fun missingCriticalPermissions(context: Context): List<String> {
        val missing = mutableListOf<String>()
        if (!canScheduleExact(context)) missing.add("正確なアラーム")
        if (!isBatteryExempt(context)) missing.add("電池の最適化の除外")
        if (!notificationsEnabled(context)) missing.add("通知")
        if (!canUseFullScreenIntent(context)) missing.add("全画面表示")
        return missing
    }
}
