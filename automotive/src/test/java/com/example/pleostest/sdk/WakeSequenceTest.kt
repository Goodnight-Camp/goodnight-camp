package com.example.pleostest.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WakeSequenceTest {

    private fun plan(outside: Float?, spare: Int? = 30, snooze: Boolean = false) =
        WakeSequence.plan(WakeConditions(outsideC = outside, batterySparePct = spare, snooze = snooze))

    private fun List<WakeStep>.at(action: WakeAction) = firstOrNull { it.action == action }?.atSec

    @Test
    fun `요청한 시간표 - 15분 전 성에 제거, 5분 전 조명과 환기, 정각 알림과 미러`() {
        val p = plan(outside = 8f)
        assertEquals(-15 * 60, p.at(WakeAction.DEFROST_ON))
        assertEquals(-5 * 60, p.at(WakeAction.LIGHT_RAMP))
        assertEquals(-5 * 60, p.at(WakeAction.FRESH_AIR))
        assertEquals(0, p.at(WakeAction.MIRROR_UNFOLD))
        assertEquals(0, p.at(WakeAction.ALARM))
        // 정각엔 성에 제거를 끝내고 알림이 마지막
        assertEquals(0, p.at(WakeAction.DEFROST_OFF))
        assertEquals(WakeAction.ALARM, p.last().action)
    }

    @Test
    fun `시간순으로 정렬돼 있다`() {
        val p = plan(outside = -5f)
        assertEquals(p.sortedBy { it.atSec }.map { it.atSec }, p.map { it.atSec })
    }

    @Test
    fun `추운 날 - 미러 열선, 핸들·시트 열선, 창문은 열지 않음`() {
        val p = plan(outside = -3f)
        assertEquals(-15 * 60, p.at(WakeAction.MIRROR_HEAT_ON))
        assertEquals(-10 * 60, p.at(WakeAction.STEERING_HEAT_ON))
        assertEquals(-10 * 60, p.at(WakeAction.SEAT_HEAT_ON))
        assertNull(p.at(WakeAction.WINDOW_CRACK))
        assertNull(p.at(WakeAction.SEAT_VENT_ON))
    }

    @Test
    fun `선선한 날 - 창문 살짝 열었다가 정각에 닫음, 미러 열선은 안 씀`() {
        val p = plan(outside = 12f)
        assertEquals(-5 * 60, p.at(WakeAction.WINDOW_CRACK))
        assertEquals(0, p.at(WakeAction.WINDOW_CLOSE))
        assertNull(p.at(WakeAction.MIRROR_HEAT_ON))
        assertNull(p.at(WakeAction.SEAT_HEAT_ON))
    }

    @Test
    fun `더운 날 - 시트 통풍, 열선 없음, 30도 넘으면 창문 안 엶`() {
        val p = plan(outside = 31f)
        assertEquals(-10 * 60, p.at(WakeAction.SEAT_VENT_ON))
        assertNull(p.at(WakeAction.SEAT_HEAT_ON))
        assertNull(p.at(WakeAction.WINDOW_CRACK))
    }

    @Test
    fun `바깥 온도를 모르면 온도 조건이 붙은 동작은 하지 않음`() {
        val p = plan(outside = null)
        listOf(WakeAction.MIRROR_HEAT_ON, WakeAction.SEAT_HEAT_ON, WakeAction.STEERING_HEAT_ON,
            WakeAction.SEAT_VENT_ON, WakeAction.WINDOW_CRACK).forEach { assertNull(it.name, p.at(it)) }
        assertEquals(-15 * 60, p.at(WakeAction.DEFROST_ON))
    }

    @Test
    fun `배터리 여유가 적으면 열선은 생략하되 성에 제거는 유지`() {
        val p = plan(outside = -10f, spare = 3)
        assertTrue(WakeSequence.isEco(WakeConditions(-10f, 3)))
        assertNull(p.at(WakeAction.SEAT_HEAT_ON))
        assertNull(p.at(WakeAction.STEERING_HEAT_ON))
        assertNull(p.at(WakeAction.MIRROR_HEAT_ON))
        assertEquals(-15 * 60, p.at(WakeAction.DEFROST_ON))
        assertFalse(WakeSequence.isEco(WakeConditions(-10f, null)))
    }

    @Test
    fun `스누즈 - 조명 램프와 알림만`() {
        val p = plan(outside = -3f, snooze = true)
        assertEquals(listOf(WakeAction.LIGHT_RAMP, WakeAction.ALARM), p.map { it.action })
        assertEquals(-5 * 60, p.at(WakeAction.LIGHT_RAMP))
    }

    @Test
    fun `조명은 5분 전부터 서서히 - 처음엔 아주 천천히`() {
        assertNull(WakeSequence.lightProgress(301.0))
        assertEquals(0.0, WakeSequence.lightProgress(300.0)!!, 1e-9)
        assertEquals(0.25, WakeSequence.lightProgress(150.0)!!, 1e-9)   // 절반 시간에 1/4 밝기 진행
        assertEquals(1.0, WakeSequence.lightProgress(0.0)!!, 1e-9)
        assertEquals(1.0, WakeSequence.lightProgress(-30.0)!!, 1e-9)
    }

    @Test
    fun `조명 밝기는 차량 범위 안에서 은은하게 시작해 60퍼센트까지`() {
        assertEquals(5, WakeSequence.lightLevel(0.0, 0, 100))
        assertEquals(60, WakeSequence.lightLevel(1.0, 0, 100))
        // 범위가 좁은 차(1..10)도 꺼지지 않게 최소 1단계 위
        assertEquals(2, WakeSequence.lightLevel(0.0, 1, 10))
        assertEquals(6, WakeSequence.lightLevel(1.0, 1, 10))
    }

    @Test
    fun `일출 색 - 주황에서 따뜻한 흰색으로`() {
        assertEquals(WakeSequence.SUNRISE_COLOR_START, WakeSequence.sunriseColor(0.0))
        assertEquals(WakeSequence.SUNRISE_COLOR_END, WakeSequence.sunriseColor(1.0))
        val mid = WakeSequence.sunriseColor(0.5)
        assertEquals(0xFF, (mid shr 16) and 0xFF)
        assertTrue(((mid shr 8) and 0xFF) in 0x7A..0xE6)
    }

    @Test
    fun `창문은 이동 범위의 10퍼센트만, 최소 1`() {
        assertEquals(10, WakeSequence.windowCrackPosition(0, 100))
        assertEquals(1, WakeSequence.windowCrackPosition(0, 5))
    }

    @Test
    fun `열선은 중간 단계, 송풍은 한 단계 올리되 절반까지`() {
        assertEquals(2, WakeSequence.heatLevel(0, 3))
        assertEquals(1, WakeSequence.heatLevel(0, 1))
        // 앞좌석처럼 -3..3(음수=냉각)이면 0(꺼짐)이 아니라 가열 2단
        assertEquals(2, WakeSequence.heatLevel(-3, 3))
        assertEquals(4, WakeSequence.freshAirFanSpeed(3, 1, 8))
        assertEquals(5, WakeSequence.freshAirFanSpeed(6, 1, 8))   // 이미 높으면 범위 절반(5단)으로
        assertEquals(2, WakeSequence.freshAirFanSpeed(null, 1, 8))
    }

    @Test
    fun `기상 시각 - 밤 11시에 자면 다음날 아침`() {
        val tz = TimeZone.getTimeZone("Asia/Seoul")
        fun at(d: Int, h: Int, m: Int) = Calendar.getInstance(tz).apply {
            clear(); set(2026, Calendar.NOVEMBER, d, h, m, 0)
        }.timeInMillis
        assertEquals(at(12, 6, 30), WakeSequence.nextWakeAt(at(11, 23, 0), "06:30", tz))
        assertEquals(at(12, 6, 30), WakeSequence.nextWakeAt(at(12, 1, 0), "06:30", tz))
        // 바로 그 분에 잠들면(테스트 등) 오늘 그 시각
        assertEquals(at(12, 6, 30), WakeSequence.nextWakeAt(at(12, 6, 30) + 20_000L, "06:30", tz))
        assertEquals(at(13, 6, 30), WakeSequence.nextWakeAt(at(12, 6, 32), "06:30", tz))
    }
}
