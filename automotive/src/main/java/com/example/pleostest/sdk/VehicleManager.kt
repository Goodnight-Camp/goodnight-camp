package com.example.pleostest.sdk

import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.vehicle.Vehicle
import com.example.pleostest.util.GNCLog

class VehicleManager(private val context: Context) {

    private val vehicle = Vehicle(context.applicationContext)

    private var car: Car? = null
    private var carPropertyManager: CarPropertyManager? = null

    var onBattery: ((Int) -> Unit)? = null
    var onTemperature: ((Float) -> Unit)? = null
    var onEfficiency: ((Float) -> Unit)? = null   // km/kWh
    var onCapacity: ((Float) -> Unit)? = null     // Wh
    var onError: ((Exception) -> Unit)? = null

    fun initialize() {
        vehicle.initialize()
        GNCLog.i("[VehicleManager] Vehicle SDK 초기화")

        car = Car.createCar(
            context,
            Handler(Looper.getMainLooper()),
            Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT
        ) { connectedCar, ready ->
            if (ready) {
                carPropertyManager =
                    connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                GNCLog.i("[VehicleManager] CarPropertyManager 연결 완료")
            } else {
                GNCLog.w("[VehicleManager] Car 서비스 미연결")
            }
        }
    }

    // 1회성 조회 — HandlerThread에서 호출됨
    @SuppressLint("MissingPermission")
    fun fetch() {
        fetchBattery()
        fetchTemperature()
        fetchEfficiencyAndCapacity()
    }

    @SuppressLint("MissingPermission")
    private fun fetchBattery() {
        try {
            vehicle.getEvBattery().getEvBatteryStateOfCharge(
                onComplete = { pct ->
                    val value = pct ?: return@getEvBatteryStateOfCharge
                    GNCLog.i("[VehicleManager] 배터리 $value%")
                    onBattery?.invoke(value)
                },
                onFailed = { e ->
                    GNCLog.e("[VehicleManager] 배터리 조회 오류: ${e.message}")
                    onError?.invoke(e)
                }
            )
        } catch (e: SecurityException) {
            GNCLog.w("[VehicleManager] 배터리 권한 없음: ${e.message}")
            onError?.invoke(e)
        }
    }

    private fun fetchTemperature() {
        val manager = carPropertyManager
        if (manager == null) {
            GNCLog.w("[VehicleManager] CarPropertyManager 미준비 — 온도 조회 건너뜀")
            return
        }
        try {
            val celsius = manager.getFloatProperty(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, 0)
            GNCLog.i("[VehicleManager] 외부 온도 ${celsius}°C")
            onTemperature?.invoke(celsius)
        } catch (e: SecurityException) {
            GNCLog.w("[VehicleManager] 외부 온도 권한 없음: ${e.message}")
            onError?.invoke(e)
        } catch (e: Exception) {
            GNCLog.e("[VehicleManager] 외부 온도 조회 오류: ${e.message}")
            onError?.invoke(e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchEfficiencyAndCapacity() {
        try {
            vehicle.getEvBattery().getSinceChargeEvEfficiency(
                onComplete = { eff ->
                    val value = eff ?: return@getSinceChargeEvEfficiency
                    GNCLog.i("[VehicleManager] 충전 후 효율 ${value} km/kWh")
                    onEfficiency?.invoke(value)
                },
                onFailed = { e -> GNCLog.d("[VehicleManager] 효율 미지원 (기본값 사용): ${e.message}") }
            )
        } catch (e: SecurityException) {
            GNCLog.w("[VehicleManager] 효율 권한 없음: ${e.message}")
        }

        try {
            @Suppress("DEPRECATION")
            vehicle.getEvBattery().getEvBatteryCapacity(
                onComplete = { cap ->
                    val value = cap ?: return@getEvBatteryCapacity
                    GNCLog.i("[VehicleManager] 배터리 용량 ${value} Wh")
                    onCapacity?.invoke(value)
                },
                onFailed = { e -> GNCLog.d("[VehicleManager] 용량 미지원 (기본값 사용): ${e.message}") }
            )
        } catch (e: SecurityException) {
            GNCLog.w("[VehicleManager] 용량 권한 없음: ${e.message}")
        }
    }

    fun release() {
        onBattery = null
        onTemperature = null
        onEfficiency = null
        onCapacity = null
        onError = null
        car?.disconnect()
        car = null
        carPropertyManager = null
        vehicle.release()
        GNCLog.i("[VehicleManager] 해제")
    }
}
