package com.example.pleostest.sdk

import com.example.pleostest.util.GNCLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/**
 * 시간별 기온 예보 — Open-Meteo (무료, API 키 없음, https://open-meteo.com).
 *
 * - 위치는 소수 둘째 자리(약 1km)로 반올림해서만 보낸다. 그 외 정보는 보내지 않는다.
 * - 캠핑장은 통신이 약할 수 있어 짧은 타임아웃 후 실패하면 null → 호출자는 지금 기온 기준으로 계산.
 * - 같은 위치(약 1km 이내)는 1시간 동안 캐시.
 */
object WeatherForecast {

    private const val TIMEOUT_MS = 5_000
    private const val CACHE_MS = 60 * 60_000L

    @Volatile private var cache: Triple<Double, Double, Pair<Long, List<HourlyTemp>>>? = null

    /** 백그라운드 스레드에서 받아 [onResult]로 전달 (실패 시 null). 호출 스레드에서 콜백되지 않음에 주의 */
    fun fetch(lat: Double, lng: Double, onResult: (List<HourlyTemp>?) -> Unit) {
        if (lat == 0.0 && lng == 0.0) {
            GNCLog.d("[WeatherForecast] 위치 미상 → 예보 생략")
            onResult(null)
            return
        }
        val rLat = Math.round(lat * 100) / 100.0
        val rLng = Math.round(lng * 100) / 100.0
        cache?.let { (cLat, cLng, entry) ->
            if (abs(cLat - rLat) < 0.011 && abs(cLng - rLng) < 0.011 &&
                System.currentTimeMillis() - entry.first < CACHE_MS) {
                onResult(entry.second)
                return
            }
        }
        Thread {
            val result = try {
                val url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                        "&hourly=temperature_2m&timeformat=unixtime&timezone=UTC&forecast_days=2",
                    rLat, rLng)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                try {
                    if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                    parse(conn.inputStream.bufferedReader().use { it.readText() })
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                GNCLog.w("[WeatherForecast] 예보 수신 실패 → 지금 기온 기준: ${e.javaClass.simpleName} ${e.message}")
                null
            }
            if (result != null) {
                cache = Triple(rLat, rLng, System.currentTimeMillis() to result)
                GNCLog.i("[WeatherForecast] 예보 ${result.size}시간 수신 (위치 %.2f, %.2f)".format(rLat, rLng))
            }
            onResult(result)
        }.start()
    }

    /** Open-Meteo 응답 → 시간별 기온 (timeformat=unixtime: 초 단위) */
    fun parse(json: String): List<HourlyTemp>? {
        val hourly = JSONObject(json).optJSONObject("hourly") ?: return null
        val times = hourly.optJSONArray("time") ?: return null
        val temps = hourly.optJSONArray("temperature_2m") ?: return null
        val out = ArrayList<HourlyTemp>(times.length())
        for (i in 0 until minOf(times.length(), temps.length())) {
            if (temps.isNull(i)) continue
            out += HourlyTemp(times.getLong(i) * 1000L, temps.getDouble(i).toFloat())
        }
        return out.takeIf { it.size >= 2 }
    }
}
