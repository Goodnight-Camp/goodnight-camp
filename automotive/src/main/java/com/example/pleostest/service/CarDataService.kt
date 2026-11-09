package com.example.pleostest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.example.pleostest.R
import com.example.pleostest.activity.WakeActivity
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.sdk.VehicleManager
import com.example.pleostest.util.GNCLog
import java.util.Calendar

class CarDataService : Service() {

    companion object {
        const val ACTION_DATA_UPDATED = "com.example.pleostest.VEHICLE_DATA_UPDATED"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gc_vehicle_data"
    }

    private lateinit var vehicleManager: VehicleManager

    private val workerThread = HandlerThread("vehicle-poll")
    private lateinit var pollHandler: Handler

    private val pollRunnable = object : Runnable {
        override fun run() {
            vehicleManager.fetch()
            checkWakeTime()
            pollHandler.postDelayed(this, 5 * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        workerThread.start()
        pollHandler = Handler(workerThread.looper)

        vehicleManager = VehicleManager(this)
        vehicleManager.onBattery = { pct ->
            GNCLog.d("[CarDataService] 배터리 갱신: $pct% → broadcast")
            GlobalVariables.curBattery = "$pct%"
            broadcast()
        }
        vehicleManager.onTemperature = { celsius ->
            GNCLog.d("[CarDataService] 외부 온도 갱신: ${Math.round(celsius)}°C → broadcast")
            GlobalVariables.outTemperature = "${Math.round(celsius)}°C"
            broadcast()
        }
        vehicleManager.onEfficiency = { kmPerKwh ->
            GNCLog.d("[CarDataService] 효율 갱신: $kmPerKwh km/kWh")
            GlobalVariables.efficiencyKmPerKwh = kmPerKwh
        }
        vehicleManager.onCapacity = { wh ->
            GNCLog.d("[CarDataService] 배터리 용량 갱신: $wh Wh")
            GlobalVariables.batteryCapacityWh = wh
        }
        vehicleManager.onError = { e ->
            GNCLog.e("[CarDataService] 차량 데이터 오류: ${e.message}")
        }
        vehicleManager.initialize()

        // 즉시 1회 조회 후 60초마다 반복
        pollHandler.post(pollRunnable)

        GNCLog.i("[CarDataService] 시작 (폴링 간격: ${GlobalVariables.POLL_INTERVAL_MS / 1000}초)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacks(pollRunnable)
        workerThread.quitSafely()
        vehicleManager.release()
        GNCLog.i("[CarDataService] 종료")
    }

    private fun checkWakeTime() {
        if (!GlobalVariables.isCampingMode) return
        if (GlobalVariables.wakeAlarmTriggered) return
        if (System.currentTimeMillis() < GlobalVariables.snoozeUntilMs) {
            GNCLog.d("[CarDataService] 기상 체크 — 스누즈 중 (마감: ${GlobalVariables.snoozeUntilMs})")
            return
        }
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val wakeMin = GlobalFunction.hhmmToMinutes(GlobalVariables.wakeTime)
        GNCLog.d("[CarDataService] 기상 시간 체크 — 현재=${nowMin}분, 기상=${wakeMin}분")
        if (nowMin >= wakeMin) {
            GNCLog.i("[CarDataService] 기상 시간 도달 → WakeActivity 시작")
            GlobalVariables.wakeAlarmTriggered = true
            startActivity(Intent(this, WakeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
    }

    private fun broadcast() {
        sendBroadcast(Intent(ACTION_DATA_UPDATED).apply { setPackage(packageName) })
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "차량 데이터", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.gc_app_name))
            .setContentText("차량 데이터 수집 중")
            .setSmallIcon(R.mipmap.ic_goodnight_camp)
            .setOngoing(true)
            .build()
    }
}
