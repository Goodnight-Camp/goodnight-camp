package com.example.pleostest.sdk

/**
 * 지난 밤 1회 기록 — 보정 계산용.
 * [endSocPct]가 null이면 기상 시 배터리를 못 받은 밤.
 */
data class NightSample(
    val startSocPct: Int,
    val endSocPct: Int?,
    val hours: Double,
    /** 그 밤에 기본 모델이 예측한 소비량(보정 전) */
    val predictedKwh: Double,
)

/** 내 차 실측으로 구한 보정 계수. [nights] = 계산에 쓴 밤 수 */
data class HvacCalibration(val factor: Double, val nights: Int) {
    companion object {
        @JvmField val NONE = HvacCalibration(1.0, 0)
    }
}

/**
 * 밤새 공조 소비량 모델.
 *
 * Pleos SDK는 공조 소비 전력·예측을 제공하지 않으므로(Vehicle SDK 2.3.2 HVAC = 상태 조회·제어만)
 * 앱이 추정한다.
 *
 * 1) 기본 모델 — 실내외 온도차에 비례
 *    - 난방: 0.3kW + 0.06kW/°C × (설정 − 외기) − 0.08kW × 추가 인원(체온)
 *    - 냉방: 0.3kW + 0.03kW/°C × (외기 − 설정) + 0.04kW × 추가 인원
 *    - 0.2 ~ 4.0kW로 제한
 *    난방 계수는 공개 실측(테슬라 혹한 차박, 실내 20°C 유지: −13°C 1.45 / −17°C 2.4 / −22°C 3.8 kWh·h)
 *    범위에 들도록 잡은 값. 냉방 계수는 실측 근거가 없는 가정치(밤엔 일사 부하가 없고
 *    냉방 효율이 난방보다 높다고 보고 절반) → 2)의 보정으로 차량별 오차를 흡수한다.
 *
 * 2) 실측 보정 — 지난 밤들의 (시작 − 종료)% × 용량 ÷ 예측 소비량의 중앙값.
 *    기록이 적을 땐 1.0 쪽으로 당긴다: 1 + (중앙값 − 1) × n ÷ (n + 2). 0.5 ~ 2.5로 제한.
 *    배터리 %는 Pleos Vehicle SDK의 실측값(1% 단위, 77.4kWh 기준 0.77kWh)이라
 *    소비가 수 kWh 이상인 2시간 이상의 밤만 쓴다.
 */
object OvernightHvac {

    private const val BASE_KW = 0.3
    private const val HEAT_KW_PER_C = 0.06
    private const val COOL_KW_PER_C = 0.03
    private const val HEAT_PERSON_KW = -0.08
    private const val COOL_PERSON_KW = 0.04
    private const val MIN_KW = 0.2
    private const val MAX_KW = 4.0

    private const val MIN_HOURS = 2.0
    private const val SHRINK_PRIOR_NIGHTS = 2
    private const val MIN_FACTOR = 0.5
    private const val MAX_FACTOR = 2.5

    /** 보정 전 기본 모델의 평균 소비 전력(kW) */
    @JvmStatic
    fun baseKw(outsideC: Double, setC: Int, guests: Int): Double {
        val extra = (guests - 1).coerceAtLeast(0)
        val kw = when {
            outsideC < setC -> BASE_KW + HEAT_KW_PER_C * (setC - outsideC) + HEAT_PERSON_KW * extra
            outsideC > setC -> BASE_KW + COOL_KW_PER_C * (outsideC - setC) + COOL_PERSON_KW * extra
            else -> BASE_KW
        }
        return kw.coerceIn(MIN_KW, MAX_KW)
    }

    /** 그 밤의 실제 소비량(kWh). 측정에 쓸 수 없는 밤이면 null */
    @JvmStatic
    fun actualKwh(night: NightSample, capacityKwh: Double): Double? {
        val end = night.endSocPct ?: return null
        if (end >= night.startSocPct) return null          // 충전됐거나 변화 없음
        if (night.hours < MIN_HOURS) return null           // 1% 해상도로는 너무 짧음
        if (night.predictedKwh <= 0.0) return null
        return (night.startSocPct - end) / 100.0 * capacityKwh
    }

    @JvmStatic
    fun calibrate(nights: List<NightSample>, capacityKwh: Double): HvacCalibration {
        val ratios = nights.mapNotNull { n -> actualKwh(n, capacityKwh)?.let { it / n.predictedKwh } }.sorted()
        if (ratios.isEmpty()) return HvacCalibration.NONE
        val mid = ratios.size / 2
        val median = if (ratios.size % 2 == 1) ratios[mid] else (ratios[mid - 1] + ratios[mid]) / 2
        val n = ratios.size
        val shrunk = 1.0 + (median - 1.0) * n / (n + SHRINK_PRIOR_NIGHTS)
        return HvacCalibration(shrunk.coerceIn(MIN_FACTOR, MAX_FACTOR), n)
    }
}
