package com.example.pleostest.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.example.pleostest.R
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.global.GlobalVariables.SLEEP_DELAY_MS
import com.example.pleostest.sdk.WakeAction
import com.example.pleostest.sdk.WakeSequence
import com.example.pleostest.sdk.WakeSequenceStatus
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

    // 기상 시퀀스 진행 → 수면 오버레이 상태 문구 갱신
    private val sequenceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = populateViews()
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
        registerReceiver(sequenceReceiver,
            IntentFilter(CarDataService.ACTION_WAKE_SEQUENCE_UPDATED),
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
        unregisterReceiver(sequenceReceiver)
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
        findViewById<TextView>(R.id.gc_camping_morning_value).text =
            getString(R.string.gc_camping_morning_value, GlobalVariables.morningBatteryPct,
                GlobalVariables.neededForHomePct + GlobalVariables.marginBatteryPct)
        findViewById<TextView>(R.id.gc_camping_dest_value).text =
            "${GlobalVariables.destinationName} ${GlobalVariables.destinationDistanceKm} km"
        populateTimeline()

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
        // 시연용 (디버그 빌드만): "기상까지" 길게 누르면 기상 시퀀스를 15배속으로 — 1분 만에 정각
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            findViewById<View>(R.id.gc_stat_until_wake).setOnLongClickListener {
                GNCLog.i("[CampingActivity] 기상 시퀀스 시연 시작 (15배속)")
                CarDataService.send(this, CarDataService.ACTION_WAKE_DEMO) {
                    putExtra(CarDataService.EXTRA_DEMO_SPEED, 15.0)
                }
                Toast.makeText(this, R.string.gc_wake_demo_started, Toast.LENGTH_SHORT).show()
                true
            }
            // "바깥" 길게 누르면 영하 5°C 겨울 시나리오 (열선·미러 열선 포함)
            findViewById<View>(R.id.gc_stat_outside).setOnLongClickListener {
                GNCLog.i("[CampingActivity] 기상 시퀀스 겨울 시연 시작 (-5°C, 15배속)")
                CarDataService.send(this, CarDataService.ACTION_WAKE_DEMO) {
                    putExtra(CarDataService.EXTRA_DEMO_SPEED, 15.0)
                    putExtra(CarDataService.EXTRA_DEMO_OUTSIDE_C, -5f)
                }
                Toast.makeText(this, R.string.gc_wake_demo_started, Toast.LENGTH_SHORT).show()
                true
            }
        }
        findViewById<TextView>(R.id.gc_action_stop_camping).setOnClickListener {
            // 차박 종료 = 일어난 것과 같음 → 종료 화면(밤새 기록 · 집으로 안내)으로.
            // 기상 시퀀스가 돌던 중이면 정리(바꾼 차 상태 복원), 수면 기록도 마무리된다 (WakeActivity)
            GNCLog.i("[CampingActivity] 차박 종료 버튼 탭 → 종료 화면(출발 준비)")
            GlobalVariables.isCampingMode = false
            GlobalVariables.snoozeUntilMs = 0L
            GlobalVariables.wakeAtMs = 0L
            CarDataService.send(this, CarDataService.ACTION_CAMPING_END)
            startActivity(Intent(this, WakeActivity::class.java).apply {
                putExtra(WakeActivity.EXTRA_END_CAMPING, true)
            })
            finish()
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
        val sequence = CarDataService.wakeStatus
        overlay.findViewById<TextView>(R.id.gc_sleep_status).text =
            if (sequence.active) getString(R.string.gc_sleep_status_waking, currentWakeStep(sequence))
            else getString(R.string.gc_sleep_status,
                "${GlobalVariables.setTemperatureDeg}°C",
                GlobalFunction.batteryPct())
        // 기상 시퀀스가 시작되면 모닥불이 커지며 "곧 깨운다"는 신호
        overlay.findViewById<com.example.pleostest.ui.CampfireView>(R.id.gc_sleep_campfire).intensity =
            if (sequence.active) 1f else 0.5f

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

    /** "아침까지 차가 할 일" — 기상 시퀀스 시간표를 시각별로 묶어 보여주고, 진행되면 체크를 켬 */
    private fun populateTimeline() {
        val container = findViewById<android.widget.LinearLayout>(R.id.gc_camping_timeline)
        val plan = WakeSequence.plan(CarDataService.conditionsNow())
        val status = CarDataService.wakeStatus
        val wakeAt = status.wakeAtMs.takeIf { status.active && it > 0 }
            ?: GlobalVariables.wakeAtMs.takeIf { it > 0 }
            ?: WakeSequence.nextWakeAt(System.currentTimeMillis(), GlobalVariables.wakeTime)
        val actions = plan.map { it.action }.toSet()
        data class Row(val atSec: Int, val icon: Int, val title: Int, val sub: Int, val group: Set<WakeAction>)
        val rows = buildList {
            add(Row(-WakeSequence.DEFROST_LEAD_SEC, R.drawable.gc_ic_window, R.string.gc_tl_defrost,
                if (WakeAction.MIRROR_HEAT_ON in actions) R.string.gc_tl_defrost_sub_mirror else R.string.gc_tl_defrost_sub,
                setOf(WakeAction.DEFROST_ON, WakeAction.MIRROR_HEAT_ON)))
            if (WakeAction.SEAT_HEAT_ON in actions) add(Row(-WakeSequence.COMFORT_LEAD_SEC, R.drawable.gc_ic_flame,
                R.string.gc_tl_heat, R.string.gc_tl_heat_sub, setOf(WakeAction.SEAT_HEAT_ON, WakeAction.STEERING_HEAT_ON)))
            if (WakeAction.SEAT_VENT_ON in actions) add(Row(-WakeSequence.COMFORT_LEAD_SEC, R.drawable.gc_ic_seat,
                R.string.gc_tl_vent_seat, R.string.gc_tl_vent_seat_sub, setOf(WakeAction.SEAT_VENT_ON)))
            add(Row(-WakeSequence.LIGHT_LEAD_SEC, R.drawable.gc_ic_bulb, R.string.gc_tl_light,
                if (WakeAction.WINDOW_CRACK in actions) R.string.gc_tl_light_sub_window else R.string.gc_tl_light_sub,
                setOf(WakeAction.LIGHT_RAMP, WakeAction.FRESH_AIR, WakeAction.WINDOW_CRACK)))
            add(Row(0, R.drawable.gc_ic_sunrise, R.string.gc_tl_alarm, R.string.gc_tl_alarm_sub, setOf(WakeAction.ALARM)))
        }
        container.removeAllViews()
        val inflater = layoutInflater
        val cal = Calendar.getInstance()
        rows.forEachIndexed { i, row ->
            if (i > 0) container.addView(View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.gc_divider))
                setBackgroundColor(getColor(R.color.divider_primary))
            })
            val v = inflater.inflate(R.layout.gc_item_timeline, container, false)
            cal.timeInMillis = wakeAt + row.atSec * 1000L
            v.findViewById<TextView>(R.id.gc_tl_time).text =
                String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            v.findViewById<android.widget.ImageView>(R.id.gc_tl_icon).setImageResource(row.icon)
            v.findViewById<TextView>(R.id.gc_tl_title).setText(row.title)
            v.findViewById<TextView>(R.id.gc_tl_sub).setText(row.sub)
            val states = status.items.filter { it.action in row.group }.map { it.state }
            val tint = when {
                WakeSequenceStatus.State.DONE in states -> R.color.informative_active
                WakeSequenceStatus.State.RUNNING in states -> R.color.informative_night
                else -> R.color.text_quaternary
            }
            v.findViewById<android.widget.ImageView>(R.id.gc_tl_state).imageTintList =
                android.content.res.ColorStateList.valueOf(getColor(tint))
            container.addView(v)
        }
    }

    /** 지금 진행 중이거나 가장 최근에 끝난 기상 단계 이름 */
    private fun currentWakeStep(status: WakeSequenceStatus): String {
        val item = status.items.lastOrNull {
            it.action != WakeAction.ALARM && it.state != WakeSequenceStatus.State.PENDING
        } ?: return getString(R.string.gc_wake_act_waiting)
        return getString(when (item.action) {
            WakeAction.DEFROST_ON, WakeAction.MIRROR_HEAT_ON -> R.string.gc_wake_act_defrost
            WakeAction.STEERING_HEAT_ON, WakeAction.SEAT_HEAT_ON -> R.string.gc_wake_act_seat_heat
            WakeAction.SEAT_VENT_ON -> R.string.gc_wake_act_seat_vent
            WakeAction.LIGHT_RAMP -> R.string.gc_wake_act_light
            WakeAction.FRESH_AIR, WakeAction.WINDOW_CRACK -> R.string.gc_wake_act_fresh_air
            else -> R.string.gc_wake_act_alarm
        })
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
