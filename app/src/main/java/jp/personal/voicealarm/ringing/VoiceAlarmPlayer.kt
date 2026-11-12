package jp.personal.voicealarm.ringing

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import jp.personal.voicealarm.data.Alarm
import java.time.LocalTime
import java.util.Locale

/**
 * ボイス（時刻だけ）→ SE のセットを繰り返すプレイヤー。
 *
 * - TTS は日本語、AudioAttributes は USAGE_ALARM / STREAM_ALARM 相当で再生する
 * - TTS 初期化に失敗した場合は SE のみのループにフォールバックする
 * - SE は1セットあたり [SE_DURATION_MS] 再生し、その後また時刻を読み上げる
 * - フェードイン有効時は SE 音量を徐々に上げる
 * - バイブレーション有効時はセット開始時に振動させる
 */
class VoiceAlarmPlayer(
    private val context: Context,
    private val alarm: Alarm,
    private val onAutoStop: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var stopped = false
    private var fadeStep = 0

    private val autoStopRunnable = Runnable {
        stop()
        onAutoStop()
    }
    private val seStopRunnable = Runnable {
        stopPlayerOnly()
        if (!stopped) speakCycle()
    }
    private val fadeRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            fadeStep++
            val ratio = (fadeStep / 10f).coerceAtMost(1f)
            val v = alarm.seVolume * ratio
            try { p.setVolume(v, v) } catch (_: Exception) {}
            if (ratio < 1f && !stopped) handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        stopped = false
        handler.postDelayed(autoStopRunnable, AUTO_STOP_MS)
        vibrate()
        tts = TextToSpeech(context.applicationContext) { status ->
            if (stopped) return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val langResult = t.setLanguage(Locale.JAPAN)
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // Utterance の音量は TTS エンジン依存のため、明示パラメータでも渡す
                ttsReady = langResult != TextToSpeech.LANG_MISSING_DATA &&
                    langResult != TextToSpeech.LANG_NOT_SUPPORTED
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        handler.post { if (!stopped) playSe() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handler.post { if (!stopped) playSe() }
                    }
                })
            } else {
                ttsReady = false
            }
            if (!stopped) speakCycle()
        }
    }

    private fun speakCycle() {
        if (stopped) return
        vibrate()
        if (ttsReady) {
            val now = LocalTime.now()
            val text = String.format(Locale.JAPAN, "%d時%02d分です", now.hour, now.minute)
            val params = android.os.Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, alarm.voiceVolume)
                putInt(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    android.media.AudioManager.STREAM_ALARM
                )
            }
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
            if (result == TextToSpeech.ERROR) playSe()
        } else {
            // TTS が使えない場合は待たずに SE へ
            playSe()
        }
    }

    private fun playSe() {
        if (stopped) return
        stopPlayerOnly()
        try {
            val uri: Uri = alarm.soundUri?.let { Uri.parse(it) }
                ?: Settings.System.DEFAULT_ALARM_ALERT_URI
                ?: Settings.System.DEFAULT_RINGTONE_URI
            val p = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                val initial = if (alarm.fadeIn) 0f else alarm.seVolume
                setVolume(initial, initial)
                prepare()
                start()
            }
            player = p
            fadeStep = 0
            if (alarm.fadeIn) handler.postDelayed(fadeRunnable, 1000)
            handler.postDelayed(seStopRunnable, SE_DURATION_MS)
        } catch (e: Exception) {
            // 音源が壊れている場合は無音ループにならないよう、次の読み上げへ進める
            handler.postDelayed({ if (!stopped) speakCycle() }, 3000)
        }
    }

    private fun stopPlayerOnly() {
        handler.removeCallbacks(seStopRunnable)
        handler.removeCallbacks(fadeRunnable)
        try {
            player?.stop()
        } catch (_: Exception) {}
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
    }

    private fun vibrate() {
        if (!alarm.vibration || stopped) return
        try {
            val vibrator = context.getSystemService(Vibrator::class.java)
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1)
            )
        } catch (_: Exception) {}
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        stopPlayerOnly()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
    }

    companion object {
        private const val UTTERANCE_ID = "voice_alarm_time"
        /** 1セットあたりの SE 再生時間 */
        const val SE_DURATION_MS = 15_000L
        /** 鳴りっぱなし防止の自動停止（10分） */
        const val AUTO_STOP_MS = 10 * 60 * 1000L
    }
}
