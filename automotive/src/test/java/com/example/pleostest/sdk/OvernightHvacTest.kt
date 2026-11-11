package com.example.pleostest.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OvernightHvacTest {

    // ── 기본 모델 ──

    @Test
    fun `난방 — 기본 0_3kW + 온도차 1도당 0_06kW`() {
        // 외기 0°C, 설정 20°C, 1명 → 0.3 + 20×0.06 = 1.5kW
        assertEquals(1.5, OvernightHvac.baseKw(outsideC = 0.0, setC = 20, guests = 1), 1e-9)
    }

    @Test
    fun `난방 — 추가 인원 1명당 0_08kW 감소 (체온)`() {
        assertEquals(1.34, OvernightHvac.baseKw(outsideC = 0.0, setC = 20, guests = 3), 1e-9)
    }

    @Test
    fun `냉방 — 외기가 설정보다 높으면 온도차 1도당 0_03kW`() {
        // 외기 30°C, 설정 20°C, 1명 → 0.3 + 10×0.03 = 0.6kW (기존 식은 냉방을 0으로 봤음)
        assertEquals(0.6, OvernightHvac.baseKw(outsideC = 30.0, setC = 20, guests = 1), 1e-9)
    }

    @Test
    fun `냉방 — 추가 인원 1명당 0_04kW 증가 (체온이 냉방 부하)`() {
        assertEquals(0.68, OvernightHvac.baseKw(outsideC = 30.0, setC = 20, guests = 3), 1e-9)
    }

    @Test
    fun `온도차가 없으면 기본 소비만`() {
        assertEquals(0.3, OvernightHvac.baseKw(outsideC = 20.0, setC = 20, guests = 1), 1e-9)
    }

    @Test
    fun `소비 전력은 0_2 ~ 4_0kW로 제한`() {
        // 0.3 + 1×0.06 − 5×0.08 = −0.04 → 0.2
        assertEquals(0.2, OvernightHvac.baseKw(outsideC = 19.0, setC = 20, guests = 6), 1e-9)
        assertEquals(4.0, OvernightHvac.baseKw(outsideC = -60.0, setC = 28, guests = 1), 1e-9)
    }

    @Test
    fun `공개 실측(테슬라 혹한 차박 1_45 ~ 3_8kWh·h) 범위 안에 든다`() {
        // -13°C/20°C → 1.45kWh/h(히트펌프), -17°C → 2.4, -22°C → 3.8
        for (outside in listOf(-13.0, -17.0, -22.0)) {
            val kw = OvernightHvac.baseKw(outside, 20, 1)
            assertTrue("$outside°C → $kw kW", kw in 1.45..3.8)
        }
    }

    // ── 실측 기반 보정 ──

    private fun night(start: Int, end: Int?, hours: Double = 8.0, predictedKwh: Double = 10.0) =
        NightSample(startSocPct = start, endSocPct = end, hours = hours, predictedKwh = predictedKwh)

    @Test
    fun `실제 소비량 = (시작 − 종료)% × 배터리 용량`() {
        // 77.4kWh × 20% = 15.48kWh
        assertEquals(15.48, OvernightHvac.actualKwh(night(90, 70), capacityKwh = 77.4)!!, 1e-9)
    }

    @Test
    fun `측정에 쓸 수 없는 밤은 제외 — 종료 미기록, 충전됨, 2시간 미만, 예측 0`() {
        assertNull(OvernightHvac.actualKwh(night(90, null), 77.4))
        assertNull(OvernightHvac.actualKwh(night(70, 75), 77.4))
        assertNull(OvernightHvac.actualKwh(night(90, 89, hours = 1.5), 77.4))
        assertNull(OvernightHvac.actualKwh(night(90, 80, predictedKwh = 0.0), 77.4))
    }

    @Test
    fun `기록이 없으면 보정 계수 1_0`() {
        val c = OvernightHvac.calibrate(emptyList(), 77.4)
        assertEquals(1.0, c.factor, 0.0)
        assertEquals(0, c.nights)
    }

    @Test
    fun `기록 1개는 1_0 쪽으로 당겨 반영 — 1 + (비율−1)×n÷(n+2)`() {
        // 실제 15.48 / 예측 10 = 1.548 → 1 + 0.548 × 1/3
        val c = OvernightHvac.calibrate(listOf(night(90, 70)), 77.4)
        assertEquals(1.0 + 0.548 / 3, c.factor, 1e-9)
        assertEquals(1, c.nights)
    }

    @Test
    fun `여러 밤은 비율의 중앙값 사용 (튀는 밤 1개에 흔들리지 않음)`() {
        val nights = listOf(
            night(90, 80, predictedKwh = 7.74),   // 비율 1.0
            night(90, 78, predictedKwh = 7.74),   // 1.2
            night(90, 30, predictedKwh = 7.74),   // 6.0 (이상치)
            night(90, 79, predictedKwh = 7.74),   // 1.1
        )
        val c = OvernightHvac.calibrate(nights, 77.4)
        // 중앙값 (1.1 + 1.2) / 2 = 1.15 → 1 + 0.15 × 4/6
        assertEquals(1.1, c.factor, 1e-9)
        assertEquals(4, c.nights)
    }

    @Test
    fun `보정 계수는 0_5 ~ 2_5로 제한`() {
        val many = List(50) { night(90, 10, predictedKwh = 1.0) }   // 비율 61.9
        assertEquals(2.5, OvernightHvac.calibrate(many, 77.4).factor, 1e-9)
    }

    @Test
    fun `쓸 수 없는 밤은 개수에서 빠진다`() {
        val c = OvernightHvac.calibrate(listOf(night(90, null), night(70, 75)), 77.4)
        assertEquals(0, c.nights)
        assertEquals(1.0, c.factor, 0.0)
    }
}
