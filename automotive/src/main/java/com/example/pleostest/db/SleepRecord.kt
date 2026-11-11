package com.example.pleostest.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.pleostest.sdk.NightSample

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
    val guestCount: Int,
    // ── v3: 밤새 공조 소비 실측 보정용 ──
    /** 잠들 때 차량 배터리 % (미수신이면 null → 보정에 안 씀) */
    val startSocPct: Int? = null,
    /** 계획 당시 외기 온도 °C */
    val outsideTempC: Double? = null,
    /** 보정 전 기본 모델의 평균 공조 소비 전력 kW (실제 잔 시간을 곱해 예측 kWh로 환산) */
    val predictedHvacKw: Double = 0.0,
    /** [일어날게요] 시점 차량 배터리 % */
    val endSocPct: Int? = null,
    val endedAt: Long? = null,
) {
    /** 보정에 쓸 표본. 시작·종료가 모두 기록된 밤만 */
    fun toNightSample(): NightSample? {
        val start = startSocPct ?: return null
        val ended = endedAt ?: return null
        val hours = (ended - createdAt) / 3_600_000.0
        return NightSample(
            startSocPct = start,
            endSocPct = endSocPct,
            hours = hours,
            predictedKwh = predictedHvacKw * hours,
        )
    }
}
