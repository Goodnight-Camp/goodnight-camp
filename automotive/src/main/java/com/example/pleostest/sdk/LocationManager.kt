package com.example.pleostest.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import com.example.pleostest.util.GNCLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GNCLocation(val latitude: Double, val longitude: Double)

class LocationManager(private val context: Context) {

    private val fusedLocation = FusedLocation(context.applicationContext)
    private var pleosListener: FusedLocationListener? = null

    private var lastGeocodedLat = Double.NaN
    private var lastGeocodedLng = Double.NaN

    private val androidLocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    private var androidListener: LocationListener? = null

    fun initialize() {
        if (fusedLocation.isSupported()) {
            fusedLocation.initialize()
            GNCLog.i("[LocationManager] Pleos FusedLocation 초기화 성공")
        } else {
            GNCLog.w("[LocationManager] Pleos FusedLocation 미지원 — Android GPS 사용 예정")
        }
    }

    @SuppressLint("MissingPermission")
    fun startUpdates(onLocation: (GNCLocation) -> Unit, onError: (Exception) -> Unit) {
        if (fusedLocation.isSupported()) {
            pleosListener?.let { fusedLocation.unregisterFusedLocationCallback(it) }
            pleosListener = object : FusedLocationListener {
                override fun onChanged(location: ai.pleos.playground.location.fused.model.LocationData?) {
                    location?.let { onLocation(GNCLocation(it.latitude, it.longitude)) }
                }
                override fun onError(exception: Exception) {
                    GNCLog.e("[LocationManager] Pleos 위치 오류: ${exception.message}")
                    onError(exception)
                }
            }
            try {
                fusedLocation.registerFusedLocationCallback(pleosListener!!)
                GNCLog.i("[LocationManager] Pleos 위치 콜백 등록 완료")
                return
            } catch (e: SecurityException) {
                GNCLog.w("[LocationManager] Pleos 권한 없음 — Android GPS로 전환")
                pleosListener = null
            }
        }
        startAndroidFallback(onLocation, onError)
    }

    @SuppressLint("MissingPermission")
    private fun startAndroidFallback(onLocation: (GNCLocation) -> Unit, onError: (Exception) -> Unit) {
        androidListener?.let { androidLocationManager.removeUpdates(it) }
        androidListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                onLocation(GNCLocation(loc.latitude, loc.longitude))
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            val provider = when {
                androidLocationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                    android.location.LocationManager.GPS_PROVIDER
                androidLocationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                    android.location.LocationManager.NETWORK_PROVIDER
                else -> {
                    GNCLog.w("[LocationManager] 사용 가능한 위치 프로바이더 없음")
                    onError(Exception("위치 프로바이더 없음"))
                    return
                }
            }
            androidLocationManager.requestLocationUpdates(provider, 5000L, 10f, androidListener!!)
            GNCLog.i("[LocationManager] Android GPS 시작 ($provider)")
        } catch (e: SecurityException) {
            GNCLog.e("[LocationManager] Android 위치 권한 없음: ${e.message}")
            onError(e)
        }
    }

    fun stopUpdates() {
        pleosListener?.let { fusedLocation.unregisterFusedLocationCallback(it) }
        pleosListener = null
        androidListener?.let { androidLocationManager.removeUpdates(it) }
        androidListener = null
    }

    fun release() {
        stopUpdates()
        fusedLocation.release()
    }

    // 좌표 → 지명 변환. 500m 이상 이동 시에만 재조회.
    fun reverseGeocode(lat: Double, lng: Double, onResult: (String) -> Unit) {
        GNCLog.i("[LocationManager] 역지오코딩 요청 — lat=$lat, lng=$lng")

        if (!lastGeocodedLat.isNaN()) {
            val dist = FloatArray(1)
            Location.distanceBetween(lastGeocodedLat, lastGeocodedLng, lat, lng, dist)
            if (dist[0] < 500f) {
                GNCLog.i("[LocationManager] 이동 거리 ${dist[0].toInt()}m < 500m, 재조회 생략")
                return
            }
        }
        lastGeocodedLat = lat
        lastGeocodedLng = lng

        Thread {
            try {
                val url = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&accept-language=ko")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "GoodNightCamp/1.0 (Android Automotive)")
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val body = conn.inputStream.bufferedReader().readText()
                GNCLog.i("[LocationManager] Nominatim 응답: $body")

                val addr = JSONObject(body).optJSONObject("address")
                if (addr == null) {
                    GNCLog.w("[LocationManager] address 필드 없음")
                    onResult("위치 불명")
                    return@Thread
                }

                // 한국 행정구역: city/county(시·군·구) + city_district/suburb(구·동·읍·면)
                val city = addr.optString("city").ifEmpty { null }
                    ?: addr.optString("county").ifEmpty { null }
                    ?: addr.optString("town").ifEmpty { null }
                val district = addr.optString("city_district").ifEmpty { null }
                    ?: addr.optString("suburb").ifEmpty { null }
                    ?: addr.optString("village").ifEmpty { null }
                val state = addr.optString("state").ifEmpty { null }

                GNCLog.i("[LocationManager] city=$city, district=$district, state=$state")

                val name = when {
                    city != null && district != null -> "$city · $district"
                    city != null -> city
                    state != null -> state
                    else -> "위치 불명"
                }
                GNCLog.i("[LocationManager] 최종 표시명: $name")
                onResult(name)
            } catch (e: Exception) {
                GNCLog.e("[LocationManager] Nominatim 오류: ${e.message}")
                onResult("위치 불명")
            }
        }.start()
    }
}
