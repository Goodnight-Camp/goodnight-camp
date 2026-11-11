package com.example.pleostest.sdk

import kotlin.math.max
import kotlin.math.min

/** 시간별 기온 예보 한 칸 */
data class HourlyTemp(val epochMs: Long, val celsius: Float)

/**
 * 밤사이 배터리 곡선.
 * [xs]: 잠드는 시각 0 → 기상 1 사이의 위치, [pcts]: 그 시점의 예상 배터리 %.
 */
data class OvernightProfile(
    val xs: DoubleArray,
    val pcts: DoubleArray,
    val usedKwh: Double,
    /** 보정 전 기본 모델의 평균 공조 전력(kW) — 수면 기록에 저장해 다음 보정에 사용 */
    val avgBaseKw: Double,
    /** 예보로 계산했는지 (false = 지금 기온이 밤새 그대로라고 가정) */
    val forecastUsed: Boolean,
    /** 계산에 쓴 가장 낮은 기온 */
    val minTempC: Float,
) {
    val usedPct: Double get() = if (pcts.isEmpty()) 0.0 else pcts.first() - pcts.last()
}

/**
 * 밤사이 기온 변화를 반영한 배터리 소모 계산.
 *
 * 15분마다 그 시각의 기온으로 [OvernightHvac.baseKw]를 구해 누적한다 (× 내 차 실측 보정 계수).
 * 예보는 지역 격자값이라 차 센서와 다를 수 있어, 지금 시점은 센서 값에 맞추고
 * 그 차이를 [ANCHOR_DECAY_HOURS]에 걸쳐 0으로 줄여 예보 쪽으로 넘어간다.
 * 예보가 없거나 밤 구간을 덮지 못하면 지금 기온이 그대로라고 본다 (기존 방식).
 */
object OvernightProfiles {

    const val STEP_MINUTES = 15
    const val ANCHOR_DECAY_HOURS = 3.0

    fun build(
        startMs: Long,
        endMs: Long,
        currentPct: Int,
        capacityKwh: Double,
        setTempC: Int,
        guests: Int,
        calibrationFactor: Double,
        sensorTempC: Float,
        forecast: List<HourlyTemp>?,
    ): OvernightProfile {
        val totalMs = max(endMs - startMs, 60_000L)
        val sorted = forecast?.sortedBy { it.epochMs }.orEmpty()
        val usable = sorted.size >= 2 && sorted.first().epochMs <= startMs + HOUR_MS && sorted.last().epochMs >= endMs - HOUR_MS
        val anchor = if (usable) sensorTempC - interpolate(sorted, startMs) else 0f

        val stepMs = STEP_MINUTES * 60_000L
        val xs = ArrayList<Double>()
        val pcts = ArrayList<Double>()
        var t = startMs
        var kwh = 0.0
        var baseKwh = 0.0
        var minTemp = Float.MAX_VALUE
        xs += 0.0
        pcts += currentPct.toDouble()
        while (t < endMs) {
            val next = min(t + stepMs, endMs)
            val mid = (t + next) / 2
            val temp = if (usable) {
                val hoursIn = (mid - startMs) / HOUR_MS.toDouble()
                val decay = max(0.0, 1.0 - hoursIn / ANCHOR_DECAY_HOURS).toFloat()
                interpolate(sorted, mid) + anchor * decay
            } else sensorTempC
            minTemp = min(minTemp, temp)
            val hours = (next - t) / HOUR_MS.toDouble()
            val base = OvernightHvac.baseKw(temp.toDouble(), setTempC, guests)
            baseKwh += base * hours
            kwh += base * calibrationFactor * hours
            xs += (next - startMs) / totalMs.toDouble()
            pcts += max(0.0, currentPct - kwh / capacityKwh * 100.0)
            t = next
        }
        val totalHours = totalMs / HOUR_MS.toDouble()
        return OvernightProfile(
            xs = xs.toDoubleArray(),
            pcts = pcts.toDoubleArray(),
            usedKwh = kwh,
            avgBaseKw = if (totalHours > 0) baseKwh / totalHours else 0.0,
            forecastUsed = usable,
            minTempC = if (minTemp == Float.MAX_VALUE) sensorTempC else minTemp,
        )
    }

    /** 시간별 예보 사이를 직선으로 이어 [ms] 시점 기온 (범위 밖은 끝값) */
    fun interpolate(sorted: List<HourlyTemp>, ms: Long): Float {
        if (ms <= sorted.first().epochMs) return sorted.first().celsius
        if (ms >= sorted.last().epochMs) return sorted.last().celsius
        val i = sorted.indexOfLast { it.epochMs <= ms }
        val a = sorted[i]
        val b = sorted[i + 1]
        val f = (ms - a.epochMs).toFloat() / (b.epochMs - a.epochMs)
        return a.celsius + (b.celsius - a.celsius) * f
    }

    private const val HOUR_MS = 3_600_000L
}
