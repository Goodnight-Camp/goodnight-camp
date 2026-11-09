package com.example.pleostest.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import com.example.pleostest.R
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.global.GlobalVariables.SLEEP_DELAY_MS
import com.example.pleostest.service.CarDataService
import com.example.pleostest.util.GNCLog
import java.util.Calendar

class CampingActivity : BaseActivity() {

//    private val SLEEP_DELAY_MS = 5 * 60 * 1000L   // 5분 무동작 → 수면 오버레이
//    private val SLEEP_DELAY_MS = 5 * 1000L   // 테트스 용 5초로
    private val mainHandler = Handler(Looper.getMainLooper())

    // 5분 타이머 → 수면 화면
    private val sleepRunnable = Runnable { showSleepOverlay() }

    // CarDataService 브로드캐스트 수신 → UI 즉시 갱신
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GNCLog.d("[CampingActivity] 차량 데이터 브로드캐스트 수신 — battery=${GlobalVariables.curBattery}, temp=${GlobalVariables.outTemperature}")
            populateViews()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camping)
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(dataReceiver,
            IntentFilter(CarDataService.ACTION_DATA_UPDATED),
            RECEIVER_NOT_EXPORTED)
        GlobalVariables.isCampingMode = true
        GNCLog.i("[CampingActivity] 캠핑 모드 활성 — wakeTime=${GlobalVariables.wakeTime}, temp=${GlobalVariables.setTemperatureDeg}°C, campingStartMin=${GlobalVariables.campingStartMin}")
        populateViews()
        resetSleepTimer()
        refreshHeaderLocation()
        if (GlobalVariables.showSleepOverlayImmediately) {
            GlobalVariables.showSleepOverlayImmediately = false
            GNCLog.i("[CampingActivity] 스누즈 복귀 → 수면 오버레이 즉시 표시")
            showSleepOverlay()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
        mainHandler.removeCallbacks(sleepRunnable)
        GNCLog.i("[CampingActivity] 슬립 타이머 중지")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            GlobalVariables.isCampingMode = false
            GNCLog.i("[CampingActivity] 캠핑 모드 종료")
        }
    }

    // 어느 터치든 수면 타이머 리셋 & 오버레이 숨기기
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        hideSleepOverlay()
        resetSleepTimer()
        return super.dispatchTouchEvent(ev)
    }

    // ────────────────────────────────────────────────────────────────
    // 화면 채우기 (서비스 갱신 시마다 호출)
    // ────────────────────────────────────────────────────────────────

    private fun populateViews() {
        val headerView = findViewById<View>(R.id.gc_header)
        headerView.findViewById<TextView>(R.id.gc_header_subtitle).text =
            getString(R.string.gc_camping_subtitle)

        val tempLabel = "${GlobalVariables.setTemperatureDeg}°C"
        findViewById<TextView>(R.id.gc_camping_headline).text =
            getString(R.string.gc_camping_headline, tempLabel)

        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_battery),
            GlobalFunction.batteryValueStr(), "%", getString(R.string.gc_label_battery)
        )
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_outside),
            GlobalFunction.temperatureValueStr(), "°C", getString(R.string.gc_label_outside)
        )

        val remainMin = remainingMinutes()
        val untilWake = if (remainMin >= 60)
            "${remainMin / 60}시간 ${remainMin % 60}분"
        else "${remainMin}분"
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_until_wake),
            untilWake, "", getString(R.string.gc_stat_until_wake)
        )

        findViewById<TextView>(R.id.gc_camping_hvac_value).text =
            getString(R.string.gc_camping_hvac_active,
                GlobalVariables.setTemperatureDeg.toString())

        // 수면 오버레이가 떠 있으면 그쪽도 갱신
        val overlay = findViewById<View>(R.id.gc_sleep_overlay)
        if (overlay.visibility == View.VISIBLE) updateSleepOverlay(overlay)
    }

    private fun setupListeners() {
        val headerView = findViewById<View>(R.id.gc_header)
        headerView.findViewById<ImageButton>(R.id.gc_header_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }
        findViewById<TextView>(R.id.gc_action_stop_camping).setOnClickListener {
            GNCLog.i("[CampingActivity] 차박 종료 버튼 탭 → 앱 종료")
            GlobalVariables.isCampingMode = false
            finishAffinity()
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 수면 오버레이
    // ────────────────────────────────────────────────────────────────

    private fun resetSleepTimer() {
        mainHandler.removeCallbacks(sleepRunnable)
        mainHandler.postDelayed(sleepRunnable, SLEEP_DELAY_MS)
    }

    private fun showSleepOverlay() {
        GNCLog.i("[CampingActivity] 수면 오버레이 표시 (5분 무동작)")
        val overlay = findViewById<View>(R.id.gc_sleep_overlay)
        overlay.visibility = View.VISIBLE
        updateSleepOverlay(overlay)
    }

    private fun hideSleepOverlay() {
        val overlay = findViewById<View>(R.id.gc_sleep_overlay)
        if (overlay.visibility == View.VISIBLE) {
            GNCLog.d("[CampingActivity] 수면 오버레이 숨김 (터치 감지)")
            overlay.visibility = View.GONE
        }
    }

    private fun updateSleepOverlay(overlay: View) {
        overlay.findViewById<TextView>(R.id.gc_sleep_status).text =
            getString(R.string.gc_sleep_status,
                "${GlobalVariables.setTemperatureDeg}°C",
                GlobalFunction.batteryPct())

        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        overlay.findViewById<TextView>(R.id.gc_sleep_start).text =
            GlobalFunction.minutesToHHmm(nowMin)
        val displayWakeTime = if (GlobalVariables.snoozeUntilMs > System.currentTimeMillis()) {
            val snoozeCal = Calendar.getInstance().apply { timeInMillis = GlobalVariables.snoozeUntilMs }
            GNCLog.d("[CampingActivity] 스누즈 중 — 오버레이 기상 시각 = ${snoozeCal.get(Calendar.HOUR_OF_DAY)}:${snoozeCal.get(Calendar.MINUTE)}")
            String.format("%02d:%02d", snoozeCal.get(Calendar.HOUR_OF_DAY), snoozeCal.get(Calendar.MINUTE))
        } else {
            GlobalVariables.wakeTime
        }
        overlay.findViewById<TextView>(R.id.gc_sleep_wake).text =
            getString(R.string.gc_sleep_wake_label, displayWakeTime)

        // 진행률: 시작(캠핑 돌입) ~ 현재 ~ 기상 시간
        val wakeMin = GlobalFunction.hhmmToMinutes(GlobalVariables.wakeTime)
        val total = if (wakeMin > nowMin) wakeMin - nowMin else 24 * 60 - nowMin + wakeMin
        val elapsed = (GlobalVariables.campingStartMin.takeIf { it >= 0 } ?: nowMin).let { start ->
            if (nowMin >= start) nowMin - start else 24 * 60 - start + nowMin
        }
        val duration = if (wakeMin > (GlobalVariables.campingStartMin.takeIf { it >= 0 } ?: nowMin))
            wakeMin - (GlobalVariables.campingStartMin.takeIf { it >= 0 } ?: nowMin)
        else total
        val progress = if (duration > 0) (elapsed * 1000 / duration).coerceIn(0, 1000) else 0
        overlay.findViewById<ProgressBar>(R.id.gc_sleep_progress).progress = progress
    }

    private fun remainingMinutes(): Int {
        if (GlobalVariables.snoozeUntilMs > System.currentTimeMillis()) {
            val remaining = ((GlobalVariables.snoozeUntilMs - System.currentTimeMillis()) / 60000).toInt()
            GNCLog.d("[CampingActivity] 스누즈 중 — 기상까지 ${remaining}분 남음")
            return remaining.coerceAtLeast(1)
        }
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val wakeMin = GlobalFunction.hhmmToMinutes(GlobalVariables.wakeTime)
        return if (wakeMin > nowMin) wakeMin - nowMin else 24 * 60 - nowMin + wakeMin
    }
}
