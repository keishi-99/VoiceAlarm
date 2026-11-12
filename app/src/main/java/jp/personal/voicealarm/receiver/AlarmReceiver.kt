package jp.personal.voicealarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import jp.personal.voicealarm.ringing.RingingService
import jp.personal.voicealarm.scheduler.AlarmScheduler

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (alarmId < 0) return
        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_SNOOZE, false)
        val snoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        val serviceIntent = Intent(context, RingingService::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_IS_SNOOZE, isSnooze)
            putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
