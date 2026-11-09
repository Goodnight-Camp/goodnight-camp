package com.example.pleostest.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_records")
data class SleepRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val destinationName: String,
    val destinationDistanceKm: Int,
    val wakeTime: String,
    val setTemperatureDeg: Int,
    val marginBatteryPct: Int,
    val guestCount: Int
)
