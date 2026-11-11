package com.example.pleostest.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OvernightProfileTest {

    private val hour = 3_600_000L
    private val start = 1_000L * hour   // 임의의 정시
    private val end = start + 8 * hour  // 8시간 수면

    private fun build(forecast: List<HourlyTemp>?, sensor: Float = 5f, factor: Double = 1.0) =
        OvernightProfiles.build(start, end, currentPct = 90, capacityKwh = 77.4, setTempC = 20, guests = 1,
            calibrationFactor = factor, sensorTempC = sensor, forecast = forecast)

    /** 시작부터 매시간 [temps] 기온 */
    private fun hourly(vararg temps: Float) = temps.mapIndexed { i, t -> HourlyTemp(start + i * hour, t) }

    @Test
    fun `예보가 없으면 지금 기온이 밤새 그대로 - 기존 직선 계산과 같음`() {
        val p = build(null)
        assertFalse(p.forecastUsed)
        val expected = OvernightHvac.baseKw(5.0, 20, 1) * 8
        assertEquals(expected, p.usedKwh, 1e-9)
        assertEquals(expected / 8, p.avgBaseKw, 1e-9)
        assertEquals(5f, p.minTempC, 0f)
        // 기울기 일정 → 앞 절반과 뒤 절반 소모가 같음
        val mid = p.pcts.size / 2
        assertEquals(p.pcts[0] - p.pcts[mid], p.pcts[mid] - p.pcts.last(), 1e-6)
    }

    @Test
    fun `곡선은 0에서 1까지, 배터리는 줄기만 함`() {
        val p = build(hourly(5f, 4f, 3f, 2f, 1f, 0f, -1f, -2f, -3f))
        assertEquals(0.0, p.xs.first(), 0.0)
        assertEquals(1.0, p.xs.last(), 1e-9)
        for (i in 1 until p.xs.size) {
            assertTrue(p.xs[i] > p.xs[i - 1])
            assertTrue(p.pcts[i] <= p.pcts[i - 1])
        }
        assertEquals(90.0, p.pcts.first(), 0.0)
        assertEquals(33, p.xs.size)   // 8시간 ÷ 15분 = 32칸 + 시작점
    }

    @Test
    fun `새벽으로 갈수록 추워지면 더 많이 쓰고 뒤로 갈수록 가파름`() {
        val constant = build(null, sensor = 5f)
        val cooling = build(hourly(5f, 4f, 3f, 2f, 1f, 0f, -1f, -2f, -3f), sensor = 5f)
        assertTrue(cooling.forecastUsed)
        assertTrue(cooling.usedKwh > constant.usedKwh)
        val mid = cooling.pcts.size / 2
        assertTrue(cooling.pcts[mid] - cooling.pcts.last() > cooling.pcts[0] - cooling.pcts[mid])
        assertEquals(-3f, cooling.minTempC, 0.3f)
    }

    @Test
    fun `예보와 차 센서가 다르면 처음엔 센서 값, 3시간에 걸쳐 예보로 넘어감`() {
        // 예보는 밤새 0°C인데 차 센서는 지금 6°C
        val flat = hourly(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val p = build(flat, sensor = 6f)
        val pureForecast = build(flat, sensor = 0f)
        // 처음 몇 시간은 센서 쪽(따뜻)이라 덜 쓰지만, 3시간 뒤부터는 같은 기울기
        assertTrue(p.usedKwh < pureForecast.usedKwh)
        val i4 = 16  // 4시간 지점
        val slopeLateA = p.pcts[i4] - p.pcts.last()
        val slopeLateB = pureForecast.pcts[i4] - pureForecast.pcts.last()
        assertEquals(slopeLateB, slopeLateA, 1e-6)
    }

    @Test
    fun `예보가 밤 구간을 덮지 못하면 쓰지 않음`() {
        val short = listOf(HourlyTemp(start, 1f), HourlyTemp(start + 2 * hour, 0f))
        assertFalse(build(short).forecastUsed)
        val stale = listOf(HourlyTemp(start - 10 * hour, 1f), HourlyTemp(start - 5 * hour, 0f))
        assertFalse(build(stale).forecastUsed)
    }

    @Test
    fun `실측 보정 계수는 소모량에 그대로 곱해지고 기본 평균 kW는 그대로`() {
        val f = hourly(5f, 4f, 3f, 2f, 1f, 0f, -1f, -2f, -3f)
        val a = build(f, factor = 1.0)
        val b = build(f, factor = 1.5)
        assertEquals(a.usedKwh * 1.5, b.usedKwh, 1e-9)
        assertEquals(a.avgBaseKw, b.avgBaseKw, 1e-12)
    }

    @Test
    fun `시간별 예보 사이는 직선으로 이음`() {
        val f = hourly(0f, 10f)
        assertEquals(5f, OvernightProfiles.interpolate(f, start + hour / 2), 1e-4f)
        assertEquals(0f, OvernightProfiles.interpolate(f, start - hour), 0f)
        assertEquals(10f, OvernightProfiles.interpolate(f, start + 5 * hour), 0f)
    }
}
