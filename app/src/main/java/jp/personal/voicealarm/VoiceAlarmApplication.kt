package jp.personal.voicealarm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class VoiceAlarmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_RINGING,
            getString(R.string.channel_ringing_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_ringing_description)
            setBypassDnd(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RINGING = "ringing"
    }
}
