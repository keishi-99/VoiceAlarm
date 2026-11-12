package jp.personal.voicealarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import jp.personal.voicealarm.scheduler.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AlarmScheduler.rescheduleAll(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
