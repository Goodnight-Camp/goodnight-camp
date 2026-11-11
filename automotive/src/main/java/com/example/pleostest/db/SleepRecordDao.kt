package com.example.pleostest.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SleepRecordDao {
    @Insert
    fun insert(record: SleepRecord): Long

    @Query("SELECT * FROM sleep_records ORDER BY createdAt DESC LIMIT 5")
    fun getRecent(): List<SleepRecord>

    /** 공조 소비 보정용 — 시작·종료 배터리가 모두 있는 최근 밤 */
    @Query("SELECT * FROM sleep_records WHERE startSocPct IS NOT NULL AND endSocPct IS NOT NULL ORDER BY createdAt DESC LIMIT :limit")
    fun getMeasuredNights(limit: Int): List<SleepRecord>

    /** 아직 기상 기록이 없는 가장 최근 밤 ([since] 이후 시작) */
    @Query("SELECT * FROM sleep_records WHERE startSocPct IS NOT NULL AND endedAt IS NULL AND createdAt >= :since ORDER BY createdAt DESC LIMIT 1")
    fun getOpenNight(since: Long): SleepRecord?

    @Query("UPDATE sleep_records SET endSocPct = :endSocPct, endedAt = :endedAt WHERE id = :id")
    fun finishNight(id: Int, endSocPct: Int, endedAt: Long)

    @Query("DELETE FROM sleep_records WHERE id = :id")
    fun deleteById(id: Int)

    @Query("DELETE FROM sleep_records")
    fun deleteAll()
}
