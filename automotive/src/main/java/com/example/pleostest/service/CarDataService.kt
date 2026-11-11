package com.example.pleostest.service

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.example.pleostest.R
import com.example.pleostest.activity.WakeActivity
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.sdk.CarControl
import com.example.pleostest.sdk.VehicleManager
import com.example.pleostest.sdk.WakeAction
import com.example.pleostest.sdk.WakeConditions
import com.example.pleostest.sdk.WakeSequence
import com.example.pleostest.sdk.WakeSequenceRunner
import com.example.pleostest.sdk.WakeSequenceStatus
import com.example.pleostest.util.GNCLog

class CarDataService : Service() {

    companion object {
        const val ACTION_DATA_UPDATED = "com.example.pleostest.VEHICLE_DATA_UPDATED"
        /** 기상 시퀀스 진행 상황 변경 → 화면 갱신 */
        const val ACTION_WAKE_SEQUENCE_UPDATED = "com.example.pleostest.WAKE_SEQUENCE_UPDATED"

        /** 서비스로 보내는 명령 (같은 앱 안에서만) */
        const val ACTION_WAKE_SNOOZE = "com.example.pleostest.action.WAKE_SNOOZE"
        const val ACTION_WAKE_FINISH = "com.example.pleostest.action.WAKE_FINISH"
        const val ACTION_WAKE_DEMO = "com.example.pleostest.action.WAKE_DEMO"
        /** 차박 종료 — 진행 중인 기상 시퀀스를 중단하고 바꾼 차 상태를 되돌림 */
        const val ACTION_CAMPING_END = "com.example.pleostest.action.CAMPING_END"
        const val EXTRA_DEMO_SPEED = "speed"
        /** 시연용 바깥 온도 (에뮬레이터는 외기온을 바꿀 수 없어 겨울 시나리오를 보여줄 때 사용) */
        const val EXTRA_DEMO_OUTSIDE_C = "outside_c"

        private const val NOTIFICATION_ID = 1
        private const val ALARM_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "gc_vehicle_data"
        private const val ALARM_CHANNEL_ID = "gc_wake_alarm"
        const val SNOOZE_MINUTES = 5

        /** 화면에서 읽는 최신 시퀀스 상태 */
        @Volatile var wakeStatus: WakeSequenceStatus = WakeSequenceStatus()
            private set

        /** 지금 차 상태로 본 기상 시퀀스 조건 — 캠핑 화면 타임라인도 같은 기준을 씀 */
        fun conditionsNow(): WakeConditions {
            val battery = GlobalFunction.batteryPctOrNull()
            return WakeConditions(
                outsideC = GlobalFunction.temperatureValueStr().toFloatOrNull(),
                batterySparePct = battery?.let { it - GlobalVariables.neededForHomePct - GlobalVariables.marginBatteryPct },
                targetTempC = GlobalVariables.setTemperatureDeg.toFloat(),
            )
        }

        fun send(context: Context, action: String, configure: Intent.() -> Unit = {}) {
            context.startService(Intent(context, CarDataService::class.java).setAction(action).apply(configure))
        }
    }

    private lateinit var vehicleManager: VehicleManager
    private lateinit var carControl: CarControl
    private lateinit var wakeRunner: WakeSequenceRunner
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

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
        vehicleManager.onCabinTemperature = { celsius ->
            GlobalVariables.cabinTemperature = "${Math.round(celsius)}°C"
            broadcast()
        }
        vehicleManager.onSetTemperature = { celsius ->
            val deg = Math.round(celsius)
            if (deg != GlobalVariables.setTemperatureDeg) {
                GNCLog.i("[CarDataService] 차 공조 설정 온도 ${deg}°C → 밤 계획·기상 시퀀스에 사용")
                GlobalVariables.setTemperatureDeg = deg
            }
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

        carControl = CarControl(this).also { it.initialize() }
        wakeRunner = WakeSequenceRunner(carControl, object : WakeSequenceRunner.Listener {
            override fun onAlarm() = fireAlarm()
            override fun onStatusChanged(status: WakeSequenceStatus) {
                wakeStatus = status
                sendBroadcast(Intent(ACTION_WAKE_SEQUENCE_UPDATED).apply { setPackage(packageName) })
            }
        })

        // 즉시 1회 조회 후 반복
        pollHandler.post(pollRunnable)

        GNCLog.i("[CarDataService] 시작 (폴링 간격: ${GlobalVariables.POLL_INTERVAL_MS / 1000}초)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WAKE_SNOOZE -> snooze()
            ACTION_CAMPING_END -> {
                GNCLog.i("[CarDataService] 차박 종료 → 기상 시퀀스 중단")
                wakeRunner.cancel()
                getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
                stopSpeaking()
            }
            ACTION_WAKE_FINISH -> {
                GNCLog.i("[CarDataService] 일어났음 → 기상 시퀀스 정리")
                wakeRunner.finish()
                getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
                stopSpeaking()
            }
            ACTION_WAKE_DEMO -> startDemo(
                intent.getDoubleExtra(EXTRA_DEMO_SPEED, 15.0),
                if (intent.hasExtra(EXTRA_DEMO_OUTSIDE_C)) intent.getFloatExtra(EXTRA_DEMO_OUTSIDE_C, 0f) else null,
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacks(pollRunnable)
        workerThread.quitSafely()
        vehicleManager.release()
        wakeRunner.cancel()
        wakeRunner.release()
        carControl.release()
        try { tts?.release() } catch (_: Exception) {}
        GNCLog.i("[CarDataService] 종료")
    }

    // ────────────────────────────────────────────────────────────────
    // 기상 시퀀스
    // ────────────────────────────────────────────────────────────────

    /**
     * 기상 [WakeSequence.DEFROST_LEAD_SEC]초 전이 되면 기상 시퀀스 시작.
     * 그 뒤(조명·환기·정각 알림·스누즈)는 [WakeSequenceRunner]가 진행한다.
     */
    private fun checkWakeTime() {
        if (!GlobalVariables.isCampingMode) {
            if (wakeStatus.active) {
                GNCLog.i("[CarDataService] 캠핑 모드 종료 → 기상 시퀀스 중단")
                wakeRunner.cancel()
            }
            return
        }
        if (wakeStatus.active || GlobalVariables.wakeAlarmTriggered) return
        val wakeAt = GlobalVariables.wakeAtMs.takeIf { it > 0 } ?: return
        val now = System.currentTimeMillis()
        if (now < wakeAt - WakeSequence.DEFROST_LEAD_SEC * 1000L) return
        if (now > wakeAt + 60 * 60_000L) {
            GNCLog.w("[CarDataService] 기상 시각이 1시간 넘게 지남 → 시퀀스 생략")
            return
        }
        GNCLog.i("[CarDataService] 기상 ${(wakeAt - now) / 60_000}분 전 → 기상 시퀀스 시작")
        prepareTts()
        wakeRunner.arm(wakeAt, currentConditions())
    }

    private fun currentConditions(): WakeConditions = conditionsNow()

    private fun snooze() {
        val speed = if (wakeStatus.active) wakeRunner.speed() else 1.0
        GlobalVariables.snoozeUntilMs = System.currentTimeMillis() + (SNOOZE_MINUTES * 60_000L / speed).toLong()
        getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
        stopSpeaking()
        if (wakeStatus.active) {
            wakeRunner.snooze(SNOOZE_MINUTES)
        } else {
            // 서비스가 재시작돼 시퀀스가 없을 때: 짧은 시퀀스로 새로 시작
            wakeRunner.arm(GlobalVariables.snoozeUntilMs, currentConditions().copy(snooze = true))
        }
        GNCLog.i("[CarDataService] 스누즈 ${SNOOZE_MINUTES}분 → ${GlobalVariables.snoozeUntilMs}")
    }

    /** 시연용: 지금부터 15분 뒤를 기상 시각으로, [speed]배속 (15배 → 1분 만에 정각) */
    private fun startDemo(speed: Double, outsideC: Float?) {
        GNCLog.i("[CarDataService] 기상 시퀀스 시연 — ${speed}배속, 바깥 ${outsideC ?: "실측"}°C")
        GlobalVariables.wakeAlarmTriggered = false
        GlobalVariables.snoozeUntilMs = 0L
        prepareTts()
        val wakeAt = System.currentTimeMillis() + WakeSequence.DEFROST_LEAD_SEC * 1000L + 2_000L
        val cond = currentConditions().let { if (outsideC != null) it.copy(outsideC = outsideC) else it }
        wakeRunner.arm(wakeAt, cond, speed)
    }

    /** 정각: 알림 + 음성 + 기상 화면 (러너 스레드에서 호출) */
    private fun fireAlarm() {
        GNCLog.i("[CarDataService] 기상 정각 → 알림·음성·기상 화면")
        GlobalVariables.wakeAlarmTriggered = true
        GlobalVariables.snoozeUntilMs = 0L
        val open = Intent(this, WakeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        showAlarmNotification(open)
        speak(alarmMessage())
        startActivity(open)
    }

    private fun alarmMessage(): String {
        val done = wakeStatus.items
            .filter { it.state == WakeSequenceStatus.State.DONE }
            .map { it.action }.toSet()
        val parts = mutableListOf<String>()
        if (WakeAction.DEFROST_ON in done) parts += getString(R.string.gc_wake_tts_defrost)
        if (WakeAction.FRESH_AIR in done || WakeAction.WINDOW_CRACK in done) parts += getString(R.string.gc_wake_tts_vent)
        if (WakeAction.SEAT_HEAT_ON in done || WakeAction.STEERING_HEAT_ON in done) parts += getString(R.string.gc_wake_tts_heat)
        val sb = StringBuilder(getString(R.string.gc_wake_tts_greeting))
        if (parts.isNotEmpty()) sb.append(' ').append(getString(R.string.gc_wake_tts_done, parts.joinToString(", ")))
        (wakeStatus.outsideC ?: GlobalFunction.temperatureValueStr().toFloatOrNull())?.let {
            sb.append(' ').append(getString(R.string.gc_wake_tts_outside, Math.round(it)))
        }
        return sb.toString()
    }

    private fun showAlarmNotification(open: Intent) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ALARM_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(ALARM_CHANNEL_ID, getString(R.string.gc_wake_alarm_channel),
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pending = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle(getString(R.string.gc_wake_alarm_title))
            .setContentText(getString(R.string.gc_wake_alarm_text, GlobalVariables.wakeTime))
            .setSmallIcon(R.mipmap.ic_goodnight_camp)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(ALARM_NOTIFICATION_ID, notification)
            GNCLog.i("[CarDataService] 기상 알림 표시")
        } catch (e: SecurityException) {
            GNCLog.w("[CarDataService] 알림 권한 없음 — 기상 화면·음성만: ${e.message}")
        }
    }

    // ── 음성 안내 (Pleos TTS, 온디바이스) ──

    private fun prepareTts() {
        if (tts != null) return
        try {
            tts = TextToSpeech(applicationContext, Mode.ON_DEVICE).also { engine ->
                engine.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        ttsReady = true
                        GNCLog.i("[CarDataService] TTS 준비 완료")
                    }
                    override fun onFailure(error: ErrorType) {
                        GNCLog.w("[CarDataService] TTS 초기화 실패: $error")
                    }
                })
            }
        } catch (e: Exception) {
            GNCLog.w("[CarDataService] TTS 사용 불가: ${e.message}")
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            GNCLog.w("[CarDataService] TTS 미준비 — 음성 생략: $text")
            return
        }
        try {
            engine.speak(text, true)
            GNCLog.i("[CarDataService] 음성 안내: $text")
        } catch (e: Exception) {
            GNCLog.w("[CarDataService] 음성 안내 실패: ${e.message}")
        }
    }

    private fun stopSpeaking() {
        try { tts?.stop() } catch (_: Exception) {}
    }

    // ────────────────────────────────────────────────────────────────

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
