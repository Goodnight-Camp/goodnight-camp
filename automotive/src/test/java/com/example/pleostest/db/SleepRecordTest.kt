package com.example.pleostest.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepRecordTest {

    private fun record(startSoc: Int?, endSoc: Int?, endedAt: Long?, kw: Double = 1.5) = SleepRecord(
        createdAt = 0L,
        destinationName = "집",
        destinationDistanceKm = 24,
        wakeTime = "06:30",
        setTemperatureDeg = 20,
        marginBatteryPct = 10,
        guestCount = 1,
        startSocPct = startSoc,
        predictedHvacKw = kw,
        endSocPct = endSoc,
        endedAt = endedAt,
    )

    @Test
    fun `예측 kWh는 저장된 예측 kW × 실제 잔 시간`() {
        // 8시간 잠 → 1.5kW × 8h = 12kWh (설정한 기상 시각이 아니라 실제 [일어날게요] 기준)
        val s = record(90, 75, endedAt = 8 * 3_600_000L).toNightSample()!!

        assertEquals(8.0, s.hours, 1e-9)
        assertEquals(12.0, s.predictedKwh, 1e-9)
        assertEquals(90, s.startSocPct)
        assertEquals(75, s.endSocPct)
    }

    @Test
    fun `시작 배터리나 기상 기록이 없으면 표본 아님`() {
        assertNull(record(null, 75, endedAt = 1L).toNightSample())
        assertNull(record(90, null, endedAt = null).toNightSample())
    }
}
