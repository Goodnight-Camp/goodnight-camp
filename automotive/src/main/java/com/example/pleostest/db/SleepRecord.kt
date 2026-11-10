package com.example.pleostest.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_records")
data class SleepRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val destinationName: String,
    val destinationDistanceKm: Int,
    /** 목적지 좌표 — (0,0)이면 미상 (v1에서 올라온 기록 포함) */
    val destinationLat: Double = 0.0,
    val destinationLng: Double = 0.0,
    val wakeTime: String,
    val setTemperatureDeg: Int,
    val marginBatteryPct: Int,
    val guestCount: Int
)
