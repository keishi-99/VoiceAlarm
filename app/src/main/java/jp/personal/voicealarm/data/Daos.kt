package jp.personal.voicealarm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun observeAll(): Flow<List<Alarm>>

    @Query("SELECT * FROM alarms")
    suspend fun getAll(): List<Alarm>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): Alarm?

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabled(): List<Alarm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alarm: Alarm): Long

    @Update
    suspend fun update(alarm: Alarm)

    @Delete
    suspend fun delete(alarm: Alarm)

    @Query("UPDATE alarms SET skipDatesCsv = :csv WHERE id = :id")
    suspend fun setSkipDatesCsv(id: Long, csv: String)

    @Query("UPDATE alarms SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM alarms")
    suspend fun deleteAll()
}

@Dao
interface AlarmGroupDao {
    @Query("SELECT * FROM alarm_groups ORDER BY name")
    fun observeAll(): Flow<List<AlarmGroup>>

    @Query("SELECT * FROM alarm_groups")
    suspend fun getAll(): List<AlarmGroup>

    @Query("SELECT * FROM alarm_groups WHERE id = :id")
    suspend fun getById(id: Long): AlarmGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: AlarmGroup): Long

    @Update
    suspend fun update(group: AlarmGroup)

    @Delete
    suspend fun delete(group: AlarmGroup)

    @Query("DELETE FROM alarm_groups")
    suspend fun deleteAll()
}

@Dao
interface CustomHolidayDao {
    @Query("SELECT * FROM custom_holidays ORDER BY epochDay")
    fun observeAll(): Flow<List<CustomHoliday>>

    @Query("SELECT * FROM custom_holidays")
    suspend fun getAll(): List<CustomHoliday>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(holiday: CustomHoliday)

    @Delete
    suspend fun delete(holiday: CustomHoliday)

    @Query("DELETE FROM custom_holidays WHERE epochDay = :epochDay")
    suspend fun deleteByEpochDay(epochDay: Long)

    @Query("DELETE FROM custom_holidays")
    suspend fun deleteAll()
}

@Dao
interface RingHistoryDao {
    @Insert
    suspend fun insert(entry: RingHistory)

    @Query("SELECT * FROM ring_history ORDER BY eventTimeMillis DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<RingHistory>

    @Query("DELETE FROM ring_history")
    suspend fun deleteAll()

    /** 直近200件だけ残して古いものを消す（履歴が際限なく増えないように）。 */
    @Query(
        "DELETE FROM ring_history WHERE id NOT IN " +
            "(SELECT id FROM ring_history ORDER BY eventTimeMillis DESC, id DESC LIMIT 200)"
    )
    suspend fun prune()
}
