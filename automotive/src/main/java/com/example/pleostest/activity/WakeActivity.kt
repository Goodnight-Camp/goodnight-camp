package com.example.pleostest.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.pleostest.R
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.sdk.HomeRouteResult
import com.example.pleostest.sdk.NaviManager
import com.example.pleostest.service.CarDataService
import com.example.pleostest.util.GNCLog
import java.util.Calendar

class WakeActivity : BaseActivity() {

    private val naviManager: NaviManager by lazy { NaviManager(this) }

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            GNCLog.d("[WakeActivity] 차량 데이터 브로드캐스트 수신 — battery=${GlobalVariables.curBattery}")
            refreshStats()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake)

        GNCLog.i("[WakeActivity] 기상 화면 시작 — wakeTime=${GlobalVariables.wakeTime}, battery=${GlobalVariables.curBattery}, neededForHome=${GlobalVariables.neededForHomePct}%")
        naviManager.initialize()
        setupPhaseA()
        setupPhaseB()
        // TODO: SDK TTS 사용 or 알림 노래 → PleosTextToSpeech.getInstance(context).speak("기상 시간입니다.")
        //   SDK 연동 전까지 호출 생략
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(dataReceiver,
            IntentFilter(CarDataService.ACTION_DATA_UPDATED),
            RECEIVER_NOT_EXPORTED)
        refreshStats()
        refreshHeaderLocation()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        naviManager.release()
    }

    // ────────────────────────────────────────────────────────────────
    // Phase A 셋업
    // ────────────────────────────────────────────────────────────────

    private fun setupPhaseA() {
        GNCLog.d("[WakeActivity] Phase A 셋업")

        // 5분만 더 → 스누즈 플래그 + CampingActivity 수면 오버레이 복귀
        findViewById<TextView>(R.id.gc_action_snooze).setOnClickListener {
            GlobalVariables.snoozeUntilMs = System.currentTimeMillis() + 5 * 60 * 1000L
            GlobalVariables.wakeAlarmTriggered = false   // Service가 5분 후 재발동할 수 있도록
            GlobalVariables.showSleepOverlayImmediately = true
            GNCLog.i("[WakeActivity] 5분 더 → snoozeUntilMs=${GlobalVariables.snoozeUntilMs}, wakeAlarmTriggered=false, CampingActivity 수면 오버레이 즉시 표시")
            startActivity(Intent(this, CampingActivity::class.java))
            finish()
        }

        findViewById<TextView>(R.id.gc_action_wake_up).setOnClickListener {
            GNCLog.i("[WakeActivity] 일어날게요 탭 → Phase B 전환")
            showPhaseB()
        }
    }

    // ────────────────────────────────────────────────────────────────
    // Phase B 셋업
    // ────────────────────────────────────────────────────────────────

    private fun setupPhaseB() {
        val headerB = findViewById<View>(R.id.gc_header_b)
        headerB.findViewById<TextView>(R.id.gc_header_subtitle).text =
            getString(R.string.gc_wake_phase_b_subtitle)

        // 집으로 안내 → 순정 내비 HOME 즐겨찾기로 경로 안내 시작
        val navigateHome = findViewById<View>(R.id.gc_action_navigate_home)
        navigateHome.setOnClickListener {
            GNCLog.i("[WakeActivity] 집으로 안내 버튼 탭 → 귀가 경로 요청")
            val started = naviManager.requestHomeRoute { result ->
                navigateHome.isEnabled = true
                onHomeRouteResult(result)
            }
            if (started) navigateHome.isEnabled = false   // 응답 올 때까지 중복 탭 방지
        }

        // 피드백 버튼 (LinearLayout 컨테이너)
        listOf(R.id.gc_feedback_good, R.id.gc_feedback_hot, R.id.gc_feedback_cold)
            .forEach { id ->
                findViewById<View>(id).setOnClickListener { v ->
                    val feedbackMap = mapOf(
                        R.id.gc_feedback_good to "좋았어요",
                        R.id.gc_feedback_hot to "더웠어요",
                        R.id.gc_feedback_cold to "추웠어요"
                    )
                    GNCLog.i("[WakeActivity] 피드백 선택: ${feedbackMap[v.id]}")
                    selectFeedback(v)
                }
            }
    }

    private fun onHomeRouteResult(result: HomeRouteResult) {
        val message = when (result) {
            is HomeRouteResult.Requested -> getString(R.string.gc_route_home_started, result.homeName)
            HomeRouteResult.NoHomeBookmark -> getString(R.string.gc_route_home_no_bookmark)
            HomeRouteResult.Timeout -> getString(R.string.gc_route_home_timeout)
            HomeRouteResult.PermissionDenied -> getString(R.string.gc_route_home_permission)
            is HomeRouteResult.Failed -> getString(R.string.gc_route_home_failed, result.errorCode.name)
        }
        GNCLog.i("[WakeActivity] 귀가 경로 결과 — $result")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showPhaseB() {
        findViewById<View>(R.id.gc_phase_a).visibility = View.GONE
        findViewById<View>(R.id.gc_phase_b).visibility = View.VISIBLE
        populatePhaseB()
    }

    private fun populatePhaseB() {
        val tempLabel = "${GlobalVariables.setTemperatureDeg}°C"
        val currentPct = GlobalFunction.batteryPct()
        val overnightPct = GlobalVariables.overnightUsePct
        GNCLog.i("[WakeActivity] Phase B 채우기 — currentBat=${currentPct}%, overnightUse=${overnightPct}%, dest=${GlobalVariables.destinationName}, dist=${GlobalVariables.destinationDistanceKm}km")

        // 요약 배너
        findViewById<TextView>(R.id.gc_wake_summary).text =
            getString(R.string.gc_wake_summary, overnightPct, tempLabel)

        // 배터리 stat 카드
        findViewById<TextView>(R.id.gc_wake_bat_current).text = currentPct.toString()
        findViewById<TextView>(R.id.gc_wake_bat_overnight).text = overnightPct.toString()

        // 현재 시각 / 날짜
        val cal = Calendar.getInstance()
        val timeStr = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val dayNames = listOf("일", "월", "화", "수", "목", "금", "토")
        val dayOfWeek = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val dateStr = "${cal.get(Calendar.YEAR)}년 ${cal.get(Calendar.MONTH) + 1}월 ${cal.get(Calendar.DAY_OF_MONTH)}일 ${dayOfWeek}요일"
        GNCLog.d("[WakeActivity] 현재 시각: $timeStr, 날짜: $dateStr")
        findViewById<TextView>(R.id.gc_wake_current_time).text = timeStr
        findViewById<TextView>(R.id.gc_wake_current_date).text = dateStr

        // 집으로 안내 거리
        findViewById<TextView>(R.id.gc_wake_route).text =
            "${GlobalVariables.destinationDistanceKm} km"

        // 도착 예상 시각 (평균 80km/h 기준)
        val distKm = GlobalVariables.destinationDistanceKm
        val estMinutes = if (distKm > 0) (distKm * 60 / 80) else 0
        val arrivalCal = Calendar.getInstance().apply { add(Calendar.MINUTE, estMinutes) }
        val h = arrivalCal.get(Calendar.HOUR_OF_DAY)
        val m = arrivalCal.get(Calendar.MINUTE)
        val ampm = if (h < 12) "오전" else "오후"
        val displayH = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        val arrivalTimeStr = "$ampm $displayH:${String.format("%02d", m)}"

        val neededPct = GlobalVariables.neededForHomePct
        val arrivalBatPct = (currentPct - neededPct).coerceAtLeast(0)
        GNCLog.d("[WakeActivity] 도착 예상 — ${arrivalTimeStr}, 잔량 ${arrivalBatPct}%")
        findViewById<TextView>(R.id.gc_wake_arrival_time).text = arrivalTimeStr
        findViewById<TextView>(R.id.gc_wake_dest_name).text = "도착 예상 잔량 ${arrivalBatPct}%"
    }

    // ────────────────────────────────────────────────────────────────
    // 실시간 지표 갱신
    // ────────────────────────────────────────────────────────────────

    private fun refreshStats() {
        val currentPct = GlobalFunction.batteryPct()
        val neededPct = GlobalVariables.neededForHomePct
        val marginPct = (currentPct - neededPct).coerceAtLeast(0)
        val canSnooze = marginPct > 5
        GNCLog.d("[WakeActivity] 배터리 지표 갱신 — current=${currentPct}%, needed=${neededPct}%, margin=${marginPct}%, canSnooze=$canSnooze")

        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_bat_current),
            GlobalFunction.batteryValueStr(), "%",
            getString(R.string.gc_wake_bat_current_label)
        )
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_bat_needed),
            neededPct.toString(), "%",
            getString(R.string.gc_wake_bat_needed_label)
        )
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_bat_margin),
            marginPct.toString(), "%",
            getString(R.string.gc_wake_bat_margin_label)
        )

        val statusText = if (canSnooze)
            getString(R.string.gc_wake_can_snooze)
        else
            getString(R.string.gc_wake_bat_low_warning)
        findViewById<TextView>(R.id.gc_wake_snooze_status).text = statusText
    }

    private fun selectFeedback(selected: View) {
        listOf(R.id.gc_feedback_good, R.id.gc_feedback_hot, R.id.gc_feedback_cold)
            .forEach { id ->
                val container = findViewById<ViewGroup>(id)
                val isSelected = container === selected
                container.background = ContextCompat.getDrawable(
                    this,
                    if (isSelected) R.drawable.gc_bg_tint_active else R.drawable.gc_bg_card
                )
                val textView = container.getChildAt(1) as? TextView
                textView?.setTextColor(ContextCompat.getColor(
                    this,
                    if (isSelected) R.color.informative_active else R.color.text_secondary
                ))
                GNCLog.d("[WakeActivity] 피드백 버튼 id=$id selected=$isSelected")
            }
    }
}
