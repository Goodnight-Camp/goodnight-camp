package com.example.pleostest.sdk

import java.util.Calendar
import java.util.TimeZone

/** 기상 시퀀스에서 차에 내리는 동작 (실행 순서는 [WakeSequence.plan]의 시각이 정함) */
enum class WakeAction {
    /** 앞유리 성에 제거 + 뒷유리 열선 + 바람을 유리·발쪽으로 */
    DEFROST_ON,
    /** 사이드미러 열선 — 미러 성에/이슬 제거 (지원 차량만) */
    MIRROR_HEAT_ON,
    /** 핸들 열선 — 추운 날 출발 대비 */
    STEERING_HEAT_ON,
    /** 사람이 앉아 있는 좌석의 시트 열선 */
    SEAT_HEAT_ON,
    /** 더운 날 시트 통풍 */
    SEAT_VENT_ON,
    /** 앰비언트 조명을 일출처럼 서서히 밝히기 (연속 동작) */
    LIGHT_RAMP,
    /** 외기 유입 + 송풍 한 단계 ↑ — 밤새 쌓인 CO₂·습기 배출 */
    FRESH_AIR,
    /** 앞 창문 살짝 열기 — 바깥이 너무 춥거나 덥지 않을 때만 */
    WINDOW_CRACK,
    /** 열어둔 창문 닫기 */
    WINDOW_CLOSE,
    /** 성에 제거 끝 → 바람 방향 얼굴·발, 공조 오토 */
    DEFROST_OFF,
    /** 접힌 사이드미러 펴기 (VHAL MIRROR_FOLD — 시스템 권한 필요) */
    MIRROR_UNFOLD,
    /** 알림 + 음성 안내 + 기상 화면 */
    ALARM,
}

/** [atSec]: 기상 시각 기준 초. 음수 = 기상 전 */
data class WakeStep(val action: WakeAction, val atSec: Int)

data class WakeConditions(
    /** 바깥 온도 °C (null = 모름 → 온도 조건이 붙은 동작은 보수적으로 판단) */
    val outsideC: Float?,
    /** 지금 배터리 − 귀가 필요 − 도착 여유 (%). null = 모름 */
    val batterySparePct: Int?,
    /** 기상 때 맞출 실내 온도 °C */
    val targetTempC: Float = 22f,
    /** 5분 더 자기 후 다시 깨우는 짧은 시퀀스 */
    val snooze: Boolean = false,
)

object WakeSequence {

    const val DEFROST_LEAD_SEC = 15 * 60
    const val COMFORT_LEAD_SEC = 10 * 60
    const val LIGHT_LEAD_SEC = 5 * 60
    const val VENT_LEAD_SEC = 5 * 60

    /** 이 온도 이하면 핸들·시트 열선 */
    const val HEAT_BELOW_C = 10f
    /** 이 온도 이하면 사이드미러 열선 (미러에 서리) */
    const val MIRROR_HEAT_BELOW_C = 5f
    /** 이 온도 이상이면 시트 통풍 */
    const val SEAT_VENT_ABOVE_C = 26f
    /** 창문 환기는 이 범위의 바깥 온도에서만 — 너무 추우면 외기 모드만 */
    const val WINDOW_MIN_C = 5f
    const val WINDOW_MAX_C = 30f
    /** 귀가 배터리 여유가 이보다 적으면 절약 모드 — 열선류 생략, 성에 제거는 안전상 유지 */
    const val ECO_SPARE_PCT = 5

    /** 앰비언트 조명 밝기 (밝기 범위 대비 비율): 시작은 은은하게, 기상 시각에 최대 */
    const val LIGHT_START_FRACTION = 0.05
    const val LIGHT_END_FRACTION = 0.6
    /** 창문을 여는 정도 (창문 이동 범위 대비 비율) — 손가락 두세 마디 정도 */
    const val WINDOW_CRACK_FRACTION = 0.1

    /** 일출 색: 짙은 주황 → 따뜻한 흰색 (0xRRGGBB) */
    const val SUNRISE_COLOR_START = 0xFF7A1E
    const val SUNRISE_COLOR_END = 0xFFE6C8

    fun isEco(c: WakeConditions): Boolean =
        c.batterySparePct != null && c.batterySparePct < ECO_SPARE_PCT

    /** 기상 시각 기준 시간표. 같은 시각이면 목록 순서대로 실행 */
    fun plan(c: WakeConditions): List<WakeStep> {
        if (c.snooze) {
            // 준비 동작은 이미 끝났으니 조명만 다시 서서히 밝히고 정각에 깨움
            return listOf(
                WakeStep(WakeAction.LIGHT_RAMP, -LIGHT_LEAD_SEC),
                WakeStep(WakeAction.ALARM, 0),
            )
        }
        val eco = isEco(c)
        val t = c.outsideC
        val steps = mutableListOf<WakeStep>()

        // T-15분: 성에 제거는 출발 안전과 직결 → 절약 모드여도 수행
        steps += WakeStep(WakeAction.DEFROST_ON, -DEFROST_LEAD_SEC)
        if (!eco && t != null && t <= MIRROR_HEAT_BELOW_C) {
            steps += WakeStep(WakeAction.MIRROR_HEAT_ON, -DEFROST_LEAD_SEC)
        }

        // T-10분: 손·몸이 닿는 곳 데우기 (온도 모르면 생략 — 여름에 열선 켜는 실수 방지)
        if (!eco && t != null && t <= HEAT_BELOW_C) {
            steps += WakeStep(WakeAction.STEERING_HEAT_ON, -COMFORT_LEAD_SEC)
            steps += WakeStep(WakeAction.SEAT_HEAT_ON, -COMFORT_LEAD_SEC)
        }
        if (!eco && t != null && t >= SEAT_VENT_ABOVE_C) {
            steps += WakeStep(WakeAction.SEAT_VENT_ON, -COMFORT_LEAD_SEC)
        }

        // T-5분: 조명 서서히 + 환기
        steps += WakeStep(WakeAction.LIGHT_RAMP, -LIGHT_LEAD_SEC)
        steps += WakeStep(WakeAction.FRESH_AIR, -VENT_LEAD_SEC)
        val windowOk = t != null && t >= WINDOW_MIN_C && t <= WINDOW_MAX_C
        if (windowOk) steps += WakeStep(WakeAction.WINDOW_CRACK, -VENT_LEAD_SEC)

        // T0: 창문 닫고, 성에 제거 마무리, 미러 펴고, 깨우기
        if (windowOk) steps += WakeStep(WakeAction.WINDOW_CLOSE, 0)
        steps += WakeStep(WakeAction.DEFROST_OFF, 0)
        steps += WakeStep(WakeAction.MIRROR_UNFOLD, 0)
        steps += WakeStep(WakeAction.ALARM, 0)
        return steps
    }

    /** 조명 램프 진행률 0..1 (램프 시작 전 null). 제곱 곡선 — 처음엔 아주 천천히 밝아짐 */
    fun lightProgress(secToWake: Double, leadSec: Int = LIGHT_LEAD_SEC): Double? {
        if (secToWake > leadSec) return null
        val p = ((leadSec - secToWake) / leadSec).coerceIn(0.0, 1.0)
        return p * p
    }

    /** 진행률을 차량 밝기 범위의 값으로 */
    fun lightLevel(progress: Double, min: Int, max: Int): Int {
        val fraction = LIGHT_START_FRACTION + (LIGHT_END_FRACTION - LIGHT_START_FRACTION) * progress
        val level = min + Math.round((max - min) * fraction).toInt()
        return level.coerceIn(min, max).coerceAtLeast(if (max > min) min + 1 else min)
    }

    /** 진행률에 따른 일출 색 (0xRRGGBB) */
    fun sunriseColor(progress: Double): Int {
        val p = progress.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val a = (SUNRISE_COLOR_START shr shift) and 0xFF
            val b = (SUNRISE_COLOR_END shr shift) and 0xFF
            return (a + Math.round((b - a) * p).toInt()) and 0xFF
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** 창문을 살짝 연 위치 (min = 닫힘) */
    fun windowCrackPosition(min: Int, max: Int): Int =
        (min + Math.round((max - min) * WINDOW_CRACK_FRACTION).toInt()).coerceIn(min + 1, max)

    /**
     * 레벨형 열선에서 쓸 단계 — 너무 뜨겁지 않게 중간 단계.
     * 범위가 음수까지면(-3..3, AOSP HVAC_SEAT_TEMPERATURE: 음수=냉각, 양수=가열) 양수 쪽에서만 고른다.
     */
    fun heatLevel(min: Int, max: Int): Int {
        val lo = maxOf(min, 0)
        if (max <= lo) return max
        return (lo + (max - lo + 1) / 2).coerceIn(lo + 1, max)
    }

    /** 송풍 단계: 지금보다 한 단계 높이되, 범위 절반을 넘기지 않음 (소음) */
    fun freshAirFanSpeed(current: Int?, min: Int, max: Int): Int {
        val cap = min + (max - min + 1) / 2
        val target = (current ?: min) + 1
        return target.coerceIn(min, maxOf(cap, min))
    }

    /**
     * [nowMs] 이후 처음 오는 "HH:mm" 시각 (ms). 지금이 정확히 그 분이면 오늘.
     * 23시에 잠들고 06:30에 깨는 경우처럼 날짜가 바뀌는 것을 처리한다.
     */
    fun nextWakeAt(nowMs: Long, hhmm: String, tz: TimeZone = TimeZone.getDefault()): Long {
        val (h, m) = hhmm.split(":").map { it.trim().toInt() }
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis + 60_000L <= nowMs) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }
}
