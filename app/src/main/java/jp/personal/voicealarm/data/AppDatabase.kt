package jp.personal.voicealarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Alarm::class, AlarmGroup::class, CustomHoliday::class, RingHistory::class],
    // v2: Alarm.skipNext (Boolean) を廃止し、skipDatesCsv (日付集合) に変更（導入前につき Migration なし）。
    // v3: Alarm.snoozeMaxCount を追加。既存インストール向けに Migration で列だけ足す。
    // v4: 鳴動履歴テーブル（ring_history）を追加。
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun groupDao(): AlarmGroupDao
    abstract fun holidayDao(): CustomHolidayDao
    abstract fun ringHistoryDao(): RingHistoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 → v3：alarms に snoozeMaxCount 列（既定0=無制限）を追加するだけの移行。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE alarms ADD COLUMN snoozeMaxCount INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v3 → v4：鳴動履歴テーブルを追加するだけの移行。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ring_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "alarmId INTEGER NOT NULL, " +
                        "label TEXT NOT NULL, " +
                        "alarmTime TEXT NOT NULL, " +
                        "event TEXT NOT NULL, " +
                        "eventTimeMillis INTEGER NOT NULL)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voice_alarm.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
