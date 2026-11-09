package com.example.pleostest.activity

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.example.pleostest.R
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.ui.BatteryBudgetChartView
import com.example.pleostest.util.GNCLog

class PlanActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plan)

        populateViews()
        setupListeners()
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

        val budget = GlobalFunction.calculateBatteryBudget()
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
            budget.neededForHomePct.toString(), "%", getString(R.string.gc_stat_needed_home)
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
