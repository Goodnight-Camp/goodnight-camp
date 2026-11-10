package com.example.pleostest.activity

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.example.pleostest.R
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.sdk.HomeTrip
import com.example.pleostest.sdk.NaviManager
import com.example.pleostest.sdk.TripEstimate
import com.example.pleostest.sdk.TripResult
import com.example.pleostest.ui.BatteryBudgetChartView
import com.example.pleostest.util.GNCLog

class PlanActivity : BaseActivity() {

    private val naviManager: NaviManager by lazy { NaviManager(this) }

    /** 순정 내비 requestTrip으로 받은 "집까지 필요 %". -1 = 아직 없음(앱 추정식 사용) */
    private var naviNeededPct = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan)

        naviManager.initialize()
        populateViews()          // 우선 앱 추정식으로 즉시 표시
        setupListeners()
        requestNaviEstimate()    // 내비 예측이 오면 다시 그림
    }

    override fun onDestroy() {
        super.onDestroy()
        naviManager.release()
    }

    // ────────────────────────────────────────────────────────────────
    // 순정 내비 귀가 예측
    // ────────────────────────────────────────────────────────────────

    private fun requestNaviEstimate() {
        val destination = HomeTrip.placeOf(
            GlobalVariables.destinationName,
            GlobalVariables.destinationLat,
            GlobalVariables.destinationLng
        )
        naviManager.requestTripEstimate(destination, GlobalFunction.batteryPctOrNull()) { result ->
            if (isFinishing || isDestroyed) return@requestTripEstimate
            when (result) {
                is TripResult.Estimated -> applyNaviEstimate(result.estimate, adoptPlace = destination == null)
                else -> GNCLog.w("[PlanActivity] 내비 예측 실패 → 앱 추정식 유지: $result")
            }
        }
    }

    private fun applyNaviEstimate(est: TripEstimate, adoptPlace: Boolean) {
        GNCLog.i("[PlanActivity] 내비 예측 적용 — ${est.place.name}, ${est.distanceKm}km, 도착 예상 ${est.socAtDestPct}%, 필요 ${est.neededPct}%")
        if (adoptPlace) {
            // 목적지를 고른 적 없으면 내비 HOME 즐겨찾기가 귀가 목적지
            GlobalVariables.destinationName = est.place.name
            GlobalVariables.destinationLat = est.place.lat
            GlobalVariables.destinationLng = est.place.lng
        }
        GlobalVariables.destinationDistanceKm = est.distanceKm   // 직선거리×1.3 대신 실제 도로 거리
        naviNeededPct = est.neededPct
        populateViews()
    }

    // ────────────────────────────────────────────────────────────────
    // 화면 채우기
    // ────────────────────────────────────────────────────────────────

    private fun populateViews() {
        // 헤더 부제
        findViewById<View>(R.id.gc_header)
            .findViewById<TextView>(R.id.gc_header_subtitle).text =
            getString(R.string.gc_plan_subtitle)

        val tempLabel = "${GlobalVariables.setTemperatureDeg}°C"
        findViewById<TextView>(R.id.gc_plan_headline).text =
            getString(R.string.gc_plan_headline, tempLabel)

        val budget = GlobalFunction.calculateBatteryBudget(naviNeededPct)
        GNCLog.i("[PlanActivity] 배터리 예산 계산 결과 — current=${budget.currentPct}%, morning=${budget.morningPct}%, overnightUse=${budget.overnightUsePct}%, neededHome=${budget.neededForHomePct}%, reserve=${budget.reservePct}%, viable=${budget.isViable}")

        // GlobalVariables 갱신 (다른 화면에서도 참조)
        GlobalVariables.morningBatteryPct = budget.morningPct
        GlobalVariables.overnightUsePct = budget.overnightUsePct
        GlobalVariables.neededForHomePct = budget.neededForHomePct

        // 요약 지표 4개
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_now),
            GlobalFunction.batteryValueStr(), "%", getString(R.string.gc_stat_now)
        )
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_morning),
            budget.morningPct.toString(), "%", getString(R.string.gc_stat_morning)
        )
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_overnight_use),
            budget.overnightUsePct.toString(), "%", getString(R.string.gc_stat_overnight_use)
        )
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_needed_home),
            budget.neededForHomePct.toString(), "%",
            getString(if (budget.neededFromNavi) R.string.gc_stat_needed_home_navi else R.string.gc_stat_needed_home)
        )

        // 그래프
        findViewById<BatteryBudgetChartView>(R.id.gc_battery_chart).setData(budget)

        // 화면 꺼지는 시간: 기상 7시간 전
        val wakeMin = GlobalFunction.hhmmToMinutes(GlobalVariables.wakeTime)
        val screenOffTime = GlobalFunction.minutesToHHmm(
            (wakeMin - 7 * 60 + 24 * 60) % (24 * 60)
        )
        findViewById<TextView>(R.id.gc_care_screen_value).text = "${screenOffTime}에 꺼짐"
    }

    private fun setupListeners() {
        // 헤더 뒤로가기
        val headerView = findViewById<View>(R.id.gc_header)
        headerView.findViewById<ImageButton>(R.id.gc_header_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }

        // 수정 → 뒤로 가기
        findViewById<TextView>(R.id.gc_action_edit).setOnClickListener {
            GNCLog.i("[PlanActivity] 수정 버튼 탭 → SetUpActivity로 복귀")
            finish()
        }

        // 이대로 잘게요 → CampingActivity (시작 시각 기록 + 수면 기록 저장)
        findViewById<TextView>(R.id.gc_action_go_to_sleep).setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            GlobalVariables.campingStartMin =
                cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            GNCLog.i("[PlanActivity] 이대로 잘게요 탭 → campingStartMin=${GlobalVariables.campingStartMin}")
            saveSleepRecord()
            startActivity(android.content.Intent(this, CampingActivity::class.java))
        }
    }

    private fun saveSleepRecord() {
        GNCLog.i("[PlanActivity] 수면 기록 저장 — dest=${GlobalVariables.destinationName}, wakeTime=${GlobalVariables.wakeTime}, temp=${GlobalVariables.setTemperatureDeg}°C, guests=${GlobalVariables.guestCount}명")
        val db = com.example.pleostest.db.SleepDatabase.getInstance(this)
        val record = com.example.pleostest.db.SleepRecord(
            destinationName = GlobalVariables.destinationName,
            destinationDistanceKm = GlobalVariables.destinationDistanceKm,
            destinationLat = GlobalVariables.destinationLat,
            destinationLng = GlobalVariables.destinationLng,
            wakeTime = GlobalVariables.wakeTime,
            setTemperatureDeg = GlobalVariables.setTemperatureDeg,
            marginBatteryPct = GlobalVariables.marginBatteryPct,
            guestCount = GlobalVariables.guestCount
        )
        Thread {
            db.sleepRecordDao().insert(record)
            GNCLog.i("[PlanActivity] 수면 기록 DB 저장 완료")
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshHeaderLocation()
    }
}
