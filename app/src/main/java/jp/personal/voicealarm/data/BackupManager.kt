package jp.personal.voicealarm.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import jp.personal.voicealarm.scheduler.AlarmScheduler
import jp.personal.voicealarm.widget.VoiceAlarmWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * バックアップ（書き出し/読み込み）。
 * アラーム・グループ・カスタム休日を JSON でファイルに保存し、復元できるようにする。
 * 復元は「今のデータを全て消して、ファイルの内容で置き換える」方式。
 * グループとアラームの ID はファイルの値をそのまま使うため、所属関係は崩れない。
 * 鳴動履歴は対象外。
 */
object BackupManager {

    private const val FORMAT_VERSION = 1

    /** 現在のデータを JSON 文字列にする。 */
    suspend fun buildJson(context: Context): String = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)

        val groups = JSONArray()
        for (g in db.groupDao().getAll()) {
            groups.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("skipJapaneseHolidays", g.skipJapaneseHolidays)
                    .put("skipCustomHolidays", g.skipCustomHolidays)
            )
        }
        root.put("groups", groups)

        val holidays = JSONArray()
        for (h in db.holidayDao().getAll()) {
            holidays.put(
                JSONObject()
                    .put("epochDay", h.epochDay)
                    .put("memo", h.memo)
            )
        }
        root.put("holidays", holidays)

        val alarms = JSONArray()
        for (a in db.alarmDao().getAll()) {
            alarms.put(
                JSONObject()
                    .put("id", a.id)
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("label", a.label)
                    .put("enabled", a.enabled)
                    .put("repeatDays", a.repeatDays)
                    .put("groupId", a.groupId ?: JSONObject.NULL)
                    .put("soundUri", a.soundUri ?: JSONObject.NULL)
                    .put("soundName", a.soundName)
                    .put("voiceVolume", a.voiceVolume.toDouble())
                    .put("seVolume", a.seVolume.toDouble())
                    .put("fadeIn", a.fadeIn)
                    .put("vibration", a.vibration)
                    .put("snoozeMinutes", a.snoozeMinutes)
                    .put("snoozeMaxCount", a.snoozeMaxCount)
                    .put("skipDatesCsv", a.skipDatesCsv)
                    .put("specificDateEpochDay", a.specificDateEpochDay ?: JSONObject.NULL)
            )
        }
        root.put("alarms", alarms)
        root.toString(2)
    }

    /** JSON を指定 URI のファイルへ書き出す。成功したら true。 */
    suspend fun exportTo(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = buildJson(context)
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 指定 URI のバックアップファイルを読み込んで復元する。
     * パースを全て終えてから DB を置き換えるので、壊れたファイルでは既存データを消さない。
     * 成功したら true。復元後は再スケジュールとウィジェット更新まで行う。
     */
    suspend fun importFrom(context: Context, uri: Uri): Boolean {
        val text = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            } catch (_: Exception) {
                null
            }
        } ?: return false

        val parsed = try {
            parseJson(text)
        } catch (_: Exception) {
            null
        } ?: return false

        val db = AppDatabase.getInstance(context)
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.alarmDao().deleteAll()
                db.groupDao().deleteAll()
                db.holidayDao().deleteAll()
                for (g in parsed.groups) db.groupDao().insert(g)
                for (h in parsed.holidays) db.holidayDao().insert(h)
                for (a in parsed.alarms) db.alarmDao().insert(a)
            }
        }
        AlarmScheduler.rescheduleAll(context)
        VoiceAlarmWidgetProvider.requestUpdate(context)
        return true
    }

    private data class Parsed(
        val groups: List<AlarmGroup>,
        val holidays: List<CustomHoliday>,
        val alarms: List<Alarm>
    )

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else optLong(key)

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key)

    private fun parseJson(text: String): Parsed {
        val root = JSONObject(text)
        val groups = mutableListOf<AlarmGroup>()
        val groupArray = root.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until groupArray.length()) {
            val o = groupArray.getJSONObject(i)
            groups.add(
                AlarmGroup(
                    id = o.optLong("id"),
                    name = o.optString("name"),
                    skipJapaneseHolidays = o.optBoolean("skipJapaneseHolidays"),
                    skipCustomHolidays = o.optBoolean("skipCustomHolidays")
                )
            )
        }
        val holidays = mutableListOf<CustomHoliday>()
        val holidayArray = root.optJSONArray("holidays") ?: JSONArray()
        for (i in 0 until holidayArray.length()) {
            val o = holidayArray.getJSONObject(i)
            holidays.add(
                CustomHoliday(
                    epochDay = o.optLong("epochDay"),
                    memo = o.optString("memo")
                )
            )
        }
        val alarms = mutableListOf<Alarm>()
        val alarmArray = root.optJSONArray("alarms") ?: JSONArray()
        for (i in 0 until alarmArray.length()) {
            val o = alarmArray.getJSONObject(i)
            alarms.add(
                Alarm(
                    id = o.optLong("id"),
                    hour = o.optInt("hour"),
                    minute = o.optInt("minute"),
                    label = o.optString("label"),
                    enabled = o.optBoolean("enabled", true),
                    repeatDays = o.optString("repeatDays"),
                    groupId = o.nullableLong("groupId"),
                    soundUri = o.nullableString("soundUri"),
                    soundName = o.optString("soundName", "デフォルト"),
                    voiceVolume = o.optDouble("voiceVolume", 0.8).toFloat(),
                    seVolume = o.optDouble("seVolume", 1.0).toFloat(),
                    fadeIn = o.optBoolean("fadeIn"),
                    vibration = o.optBoolean("vibration", true),
                    snoozeMinutes = o.optInt("snoozeMinutes", 5),
                    snoozeMaxCount = o.optInt("snoozeMaxCount", 0),
                    skipDatesCsv = o.optString("skipDatesCsv"),
                    specificDateEpochDay = o.nullableLong("specificDateEpochDay")
                )
            )
        }
        return Parsed(groups, holidays, alarms)
    }
}
