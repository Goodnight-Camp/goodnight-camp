package com.example.pleostest.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SleepRecordDao {
    @Insert
    fun insert(record: SleepRecord)

    @Query("SELECT * FROM sleep_records ORDER BY createdAt DESC LIMIT 5")
    fun getRecent(): List<SleepRecord>

    @Query("DELETE FROM sleep_records WHERE id = :id")
    fun deleteById(id: Int)

    @Query("DELETE FROM sleep_records")
    fun deleteAll()
}
