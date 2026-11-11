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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.example.pleostest.R
import com.example.pleostest.db.SleepDatabase
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.sdk.HomeRouteResult
import com.example.pleostest.sdk.NaviManager
import com.example.pleostest.sdk.WakeAction
import com.example.pleostest.sdk.WakeSequenceStatus
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

    private val sequenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = renderWakeSequence()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 아침 화면은 밝은 아침 팔레트(values/colors.xml) — 앱 나머지는 밤 팔레트
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake)

        GNCLog.i("[WakeActivity] 기상 화면 시작 — wakeTime=${GlobalVariables.wakeTime}, battery=${GlobalVariables.curBattery}, neededForHome=${GlobalVariables.neededForHomePct}%")
        naviManager.initialize()
        setupPhaseA()
        setupPhaseB()
        // 정각 알림·음성 안내는 CarDataService(기상 시퀀스)가 이 화면을 띄우면서 함께 처리

        // 캠핑 화면의 [차박 종료]로 들어오면 알람 단계 없이 바로 종료 화면(출발 준비)
        // (아침 테마 적용으로 액티비티가 한 번 다시 만들어질 수 있어 savedInstanceState 여부와 무관하게 판단)
        if (intent.getBooleanExtra(EXTRA_END_CAMPING, false)) {
            GNCLog.i("[WakeActivity] 차박 종료로 진입 → 종료 화면")
            findViewById<View>(R.id.gc_header_b).findViewById<TextView>(R.id.gc_header_subtitle).text =
                getString(R.string.gc_wake_end_subtitle)
            showPhaseB()
        }

        // 종료 화면에서 뒤로 → 캠핑을 마치고 첫 화면(오늘 밤 설정)으로
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findViewById<View>(R.id.gc_phase_b).visibility == View.VISIBLE) goToStart() else finish()
            }
        })
    }

    private fun goToStart() {
        GNCLog.i("[WakeActivity] 종료 화면 닫기 → 첫 화면")
        GlobalVariables.isCampingMode = false
        GlobalVariables.campingStartMin = -1
        startActivity(Intent(this, SetUpActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        /** 캠핑 화면의 [차박 종료]에서 열림 → 바로 종료 화면 */
        const val EXTRA_END_CAMPING = "end_camping"
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(dataReceiver,
            IntentFilter(CarDataService.ACTION_DATA_UPDATED),
            RECEIVER_NOT_EXPORTED)
        registerReceiver(sequenceReceiver,
            IntentFilter(CarDataService.ACTION_WAKE_SEQUENCE_UPDATED),
            RECEIVER_NOT_EXPORTED)
        refreshStats()
        renderWakeSequence()
        refreshHeaderLocation()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
        unregisterReceiver(sequenceReceiver)
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

        // 일출 카드의 큰 시각·날짜
        val cal = Calendar.getInstance()
        findViewById<TextView>(R.id.gc_wake_a_time).text =
            String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        findViewById<TextView>(R.id.gc_wake_a_date).text = android.text.format.DateFormat.format(
            android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "yyyyMMMMdEEEE"), cal)

        // 5분만 더 → 기상 시퀀스 스누즈(조명 다시 은은하게, 5분 뒤 다시 깨움) + 수면 오버레이 복귀
        findViewById<TextView>(R.id.gc_action_snooze).setOnClickListener {
            CarDataService.send(this, CarDataService.ACTION_WAKE_SNOOZE)
            GlobalVariables.showSleepOverlayImmediately = true
            GNCLog.i("[WakeActivity] 5분 더 → 기상 시퀀스 스누즈, CampingActivity 수면 오버레이 즉시 표시")
            startActivity(Intent(this, CampingActivity::class.java))
            finish()
        }

        findViewById<TextView>(R.id.gc_action_wake_up).setOnClickListener {
            GNCLog.i("[WakeActivity] 일어날게요 탭 → 기상 시퀀스 정리 + Phase B 전환")
            CarDataService.send(this, CarDataService.ACTION_WAKE_FINISH)
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
        // 헤더 뒤로 버튼 = 종료 화면 닫고 첫 화면으로
        headerB.findViewById<View>(R.id.gc_header_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { goToStart() }
        }

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
        recordWakeUp()
        populatePhaseB()
    }

    /**
     * 기상 시 실제 배터리를 수면 기록에 남긴다 → 다음 밤 계획의 공조 소비 보정에 사용.
     * 앱이 밤사이 재시작돼도 DB의 미완료 기록(24시간 이내)을 찾아 마무리한다.
     */
    private fun recordWakeUp() {
        val endSoc = GlobalFunction.batteryPctOrNull()
        if (endSoc == null) {
            GNCLog.w("[WakeActivity] 기상 배터리 미수신 → 이번 밤은 보정에 쓰지 않음")
            return
        }
        val dao = SleepDatabase.getInstance(this).sleepRecordDao()
        val now = System.currentTimeMillis()
        Thread {
            val night = dao.getOpenNight(since = now - 24 * 3_600_000L)
            if (night == null) {
                GNCLog.d("[WakeActivity] 마무리할 수면 기록 없음 (이미 기록됨 또는 시작 배터리 미상)")
                return@Thread
            }
            dao.finishNight(night.id, endSoc, now)
            val hours = (now - night.createdAt) / 3_600_000.0
            GNCLog.i("[WakeActivity] 수면 기록 마무리 — id=${night.id}, 배터리 ${night.startSocPct}% → ${endSoc}%, ${"%.1f".format(hours)}시간, 예측 공조 ${night.predictedHvacKw}kW")
        }.start()
    }

    private fun populatePhaseB() {
        val tempLabel = "${GlobalVariables.setTemperatureDeg}°C"
        val currentPct = GlobalFunction.batteryPct()
        // 잠들 때와 지금의 실제 배터리 차이. 둘 중 하나라도 모르면 밤 계획의 예측값
        val startSoc = GlobalVariables.sleepStartSocPct
        val nowSoc = GlobalFunction.batteryPctOrNull()
        val overnightPct = if (startSoc >= 0 && nowSoc != null) (startSoc - nowSoc).coerceAtLeast(0)
                           else GlobalVariables.overnightUsePct
        GNCLog.i("[WakeActivity] 밤새 사용량 — ${if (startSoc >= 0 && nowSoc != null) "실측 $startSoc% → $nowSoc%" else "예측값"} = $overnightPct%")
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

        // 어젯밤 기록 — 잠든 시각(이대로 잘게요) → 지금, 잔 시간, 설정 온도
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMin = GlobalVariables.campingStartMin
        findViewById<TextView>(R.id.gc_wake_sleep_range).text =
            if (startMin >= 0) "${GlobalFunction.minutesToHHmm(startMin)} → $timeStr" else timeStr
        val sleptMin = if (startMin >= 0) (nowMin - startMin + 24 * 60) % (24 * 60) else -1
        findViewById<TextView>(R.id.gc_wake_sleep_duration).text =
            if (sleptMin >= 0) getString(R.string.gc_duration_hm, sleptMin / 60, sleptMin % 60) else "-"
        findViewById<TextView>(R.id.gc_wake_set_temp).text = tempLabel

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
    // 기상 시퀀스 — 차가 깨우면서 한 일
    // ────────────────────────────────────────────────────────────────

    private fun renderWakeSequence() {
        val status = CarDataService.wakeStatus
        val card = findViewById<View>(R.id.gc_card_wake_sequence)
        if (status.items.isEmpty()) {
            card.visibility = View.INVISIBLE   // 자리는 유지 (버튼이 위로 딸려 올라가지 않게)
            return
        }
        card.visibility = View.VISIBLE
        val entries = status.items
            .filter { it.action != WakeAction.ALARM }
            .map { "${stateMark(it.state)} ${getString(actionLabel(it.action))}${stateSuffix(it.state)}" }
        // 한 줄에 하나 — 카드 높이를 채우고 차량 거리에서도 읽히게
        val text = StringBuilder(entries.joinToString("\n"))
        if (status.eco) text.append("\n").append(getString(R.string.gc_wake_seq_eco))
        findViewById<TextView>(R.id.gc_wake_sequence_list).text = text
    }

    private fun stateMark(state: WakeSequenceStatus.State) = when (state) {
        WakeSequenceStatus.State.DONE -> "✓"
        WakeSequenceStatus.State.PENDING, WakeSequenceStatus.State.RUNNING -> "…"
        WakeSequenceStatus.State.UNSUPPORTED -> "—"
        WakeSequenceStatus.State.DENIED, WakeSequenceStatus.State.FAILED -> "✕"
    }

    private fun stateSuffix(state: WakeSequenceStatus.State) = when (state) {
        WakeSequenceStatus.State.UNSUPPORTED -> " " + getString(R.string.gc_wake_seq_unsupported)
        WakeSequenceStatus.State.DENIED -> " " + getString(R.string.gc_wake_seq_denied)
        WakeSequenceStatus.State.FAILED -> " " + getString(R.string.gc_wake_seq_failed)
        else -> ""
    }

    private fun actionLabel(action: WakeAction): Int = when (action) {
        WakeAction.DEFROST_ON -> R.string.gc_wake_act_defrost
        WakeAction.MIRROR_HEAT_ON -> R.string.gc_wake_act_mirror_heat
        WakeAction.STEERING_HEAT_ON -> R.string.gc_wake_act_steering_heat
        WakeAction.SEAT_HEAT_ON -> R.string.gc_wake_act_seat_heat
        WakeAction.SEAT_VENT_ON -> R.string.gc_wake_act_seat_vent
        WakeAction.LIGHT_RAMP -> R.string.gc_wake_act_light
        WakeAction.FRESH_AIR -> R.string.gc_wake_act_fresh_air
        WakeAction.WINDOW_CRACK -> R.string.gc_wake_act_window_open
        WakeAction.WINDOW_CLOSE -> R.string.gc_wake_act_window_close
        WakeAction.DEFROST_OFF -> R.string.gc_wake_act_defrost_off
        WakeAction.MIRROR_UNFOLD -> R.string.gc_wake_act_mirror_unfold
        WakeAction.ALARM -> R.string.gc_wake_act_alarm
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
