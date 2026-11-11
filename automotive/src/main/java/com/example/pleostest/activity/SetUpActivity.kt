package com.example.pleostest.activity

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.pleostest.R
import com.example.pleostest.db.SleepDatabase
import com.example.pleostest.db.SleepRecord
import com.example.pleostest.dialog.DestinationSearchDialog
import com.example.pleostest.dialog.GcTimePickerDialog
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.sdk.LocationManager
import com.example.pleostest.sdk.NaviManager
import com.example.pleostest.service.CarDataService
import com.example.pleostest.util.GNCLog

class SetUpActivity : BaseActivity() {

    val locationManager: LocationManager by lazy { LocationManager(this) }
    val naviManager: NaviManager by lazy { NaviManager(this) }

    private val vehicleDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CarDataService.ACTION_DATA_UPDATED) {
                refreshVehicleStats()
            }
        }
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            GNCLog.i("[SetUpActivity] 위치 권한 허용 → 위치 업데이트 시작")
            startLocationUpdates()
        } else {
            GNCLog.w("[SetUpActivity] 위치 권한 거부 — 헤더 위치 표시 불가")
        }
        requestNotificationPermission()
    }

    // 기상 알림용 — 위치 권한 요청이 끝난 뒤 이어서 (동시에 두 다이얼로그를 띄우지 않음)
    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        GNCLog.i("[SetUpActivity] 알림 권한 ${if (granted) "허용" else "거부 — 기상 시엔 화면·음성으로만 깨움"}")
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            GNCLog.i("[SetUpActivity] 위치 권한 이미 허용 → 위치 업데이트 시작")
            startLocationUpdates()
            requestNotificationPermission()
        } else {
            GNCLog.i("[SetUpActivity] 위치 권한 미허용 → 권한 요청")
            locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        registerReceiver(
            vehicleDataReceiver,
            IntentFilter(CarDataService.ACTION_DATA_UPDATED), RECEIVER_NOT_EXPORTED
        )
        GNCLog.d("[SetUpActivity] CarDataService 브로드캐스트 수신 등록")

        naviManager.initialize()
        naviManager.onDestination = { name, km, lat, lng ->
            GNCLog.i("[SetUpActivity] NaviManager 목적지 갱신 → name=$name, km=$km")
            GlobalVariables.destinationName = name
            GlobalVariables.destinationDistanceKm = km
            GlobalVariables.destinationLat = lat
            GlobalVariables.destinationLng = lng
            runOnUiThread { updateDestinationRow() }
        }

        populateViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        val hasBattery = GlobalVariables.curBattery != "-"
        val hasTemp = GlobalVariables.outTemperature != "-"
        GNCLog.d("[SetUpActivity] onResume — battery=${GlobalVariables.curBattery}, temp=${GlobalVariables.outTemperature}")
        if (hasBattery || hasTemp) {
            GNCLog.d("[SetUpActivity] 차량 데이터 존재 → 즉시 갱신")
            refreshVehicleStats()
        }
        updateDestinationRow()   // 밤 계획에서 내비 예측으로 목적지·거리가 바뀌었을 수 있음
        loadSleepRecords()
        refreshHeaderLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(vehicleDataReceiver)
        locationManager.release()
        naviManager.release()
    }

    // ────────────────────────────────────────────────────────────────
    // 초기 화면 채우기
    // ────────────────────────────────────────────────────────────────

    private fun populateViews() {
        // 헤더 부제
        headerView().findViewById<TextView>(R.id.gc_header_subtitle).text =
            getString(R.string.gc_setup_subtitle)

        //thread 돌려서 데이터를 집어 넣어줘여하는데
        // 배터리 지표
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_battery),
            GlobalFunction.batteryValueStr(),
            "%",
            getString(R.string.gc_label_battery)
        )

        // 바깥 온도 지표
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_outside),
            GlobalFunction.temperatureValueStr(),
            "°C",
            getString(R.string.gc_label_outside)
        )

        // 오늘 밤 설정 행들
        updateWakeTimeDisplay()
        updateDestinationRow()
        updateTemperatureDisplay()
        updateReserveDisplay()
        updateGuestsDisplay()
    }

    // ────────────────────────────────────────────────────────────────
    // 리스너 설정
    // ────────────────────────────────────────────────────────────────

    private fun setupListeners() {

        // 일어날 시간 — 행 탭 시 TimePicker
        findViewById<LinearLayout>(R.id.gc_row_wake_time).setOnClickListener { showWakeTimePicker() }

        // 목적지 변경 → 검색 다이얼로그
        findViewById<TextView>(R.id.gc_destination_change).setOnClickListener {
            GNCLog.i("[SetUpActivity] 목적지 변경 버튼 탭")
            DestinationSearchDialog(this) { name, km, lat, lng ->
                GNCLog.i("[SetUpActivity] 목적지 선택 완료 → name=$name, km=$km (이전: ${GlobalVariables.destinationName})")
                GlobalVariables.destinationName = name
                GlobalVariables.destinationDistanceKm = km
                GlobalVariables.destinationLat = lat
                GlobalVariables.destinationLng = lng
                updateDestinationRow()
            }.show()
        }

        // 원하는 온도 ±1°C
        findViewById<ImageButton>(R.id.gc_temperature_minus).setOnClickListener { adjustTemperature(-1) }
        findViewById<ImageButton>(R.id.gc_temperature_plus).setOnClickListener { adjustTemperature(+1) }

        // 여유 배터리 세그먼트
        findViewById<RadioGroup>(R.id.gc_reserve_segment).setOnCheckedChangeListener { _, checkedId ->
            val old = GlobalVariables.marginBatteryPct
            GlobalVariables.marginBatteryPct = when (checkedId) {
                R.id.gc_reserve_5 -> 5
                R.id.gc_reserve_15 -> 15
                else -> 10
            }
            GNCLog.i("[SetUpActivity] 여유 배터리 변경: ${old}% → ${GlobalVariables.marginBatteryPct}%")
            updateReserveDisplay()
        }

        // 자는 인원 ±1명 (최소 1명)
        findViewById<ImageButton>(R.id.gc_guests_minus).setOnClickListener { adjustGuests(-1) }
        findViewById<ImageButton>(R.id.gc_guests_plus).setOnClickListener { adjustGuests(+1) }

        // 밤 계획 만들기
        findViewById<TextView>(R.id.gc_action_make_plan).setOnClickListener {
            makePlan()
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 값 조정
    // ────────────────────────────────────────────────────────────────

    private fun showWakeTimePicker() {
        GNCLog.i("[SetUpActivity] 기상 시간 행 탭 — 현재: ${GlobalVariables.wakeTime}")
        val parts = GlobalVariables.wakeTime.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        GcTimePickerDialog(this, hour, minute) { h, m ->
            val newTime = String.format("%02d:%02d", h, m)
            GNCLog.i("[SetUpActivity] 기상 시간 변경: ${GlobalVariables.wakeTime} → $newTime")
            GlobalVariables.wakeTime = newTime
            updateWakeTimeDisplay()
        }.show()
    }

    private fun adjustTemperature(delta: Int) {
        val old = GlobalVariables.setTemperatureDeg
        GlobalVariables.setTemperatureDeg = (old + delta).coerceIn(16, 28)
        GNCLog.i("[SetUpActivity] 온도 조정 ${if (delta > 0) "+" else ""}$delta°C: ${old}°C → ${GlobalVariables.setTemperatureDeg}°C")
        updateTemperatureDisplay()
    }

    private fun adjustGuests(delta: Int) {
        val old = GlobalVariables.guestCount
        GlobalVariables.guestCount = (old + delta).coerceIn(1, 9)
        GNCLog.i("[SetUpActivity] 인원 조정 ${if (delta > 0) "+" else ""} ${old}명 → ${GlobalVariables.guestCount}명")
        updateGuestsDisplay()
    }

    // ────────────────────────────────────────────────────────────────
    // 디스플레이 갱신
    // ────────────────────────────────────────────────────────────────

    private fun updateWakeTimeDisplay() {
        findViewById<TextView>(R.id.gc_wake_time_value).text = GlobalVariables.wakeTime
        val sunrise = GlobalVariables.sunriseTime.takeIf { it != "-" }
        findViewById<TextView>(R.id.gc_wake_time_sunrise).text =
            if (sunrise != null) getString(R.string.gc_setting_sunrise, sunrise) else ""
    }

    private fun updateDestinationRow() {
        findViewById<TextView>(R.id.gc_destination_value).text = GlobalVariables.destinationName
        val km = GlobalVariables.destinationDistanceKm
        findViewById<TextView>(R.id.gc_destination_value_distance).text =
            if (km > 0) "$km ${getString(R.string.gc_unit_km)}" else ""
    }

    private fun updateTemperatureDisplay() {
        val deg = GlobalVariables.setTemperatureDeg
        findViewById<TextView>(R.id.gc_temperature_value).text =
            "$deg°C"
    }

    private fun updateGuestsDisplay() {
        findViewById<TextView>(R.id.gc_guests_value).text =
            GlobalVariables.guestCount.toString()
    }

    private fun updateReserveDisplay() {
        val pct = GlobalVariables.marginBatteryPct
        findViewById<TextView>(R.id.gc_reserve_value).text =
            "$pct%"

        // 세그먼트 UI를 현재 값에 동기화 (리스너 루프 방지용 조건 체크)
        val seg = findViewById<RadioGroup>(R.id.gc_reserve_segment)
        val targetId = when (pct) {
            5 -> R.id.gc_reserve_5
            15 -> R.id.gc_reserve_15
            else -> R.id.gc_reserve_10
        }
        if (seg.checkedRadioButtonId != targetId) seg.check(targetId)
    }

    // ────────────────────────────────────────────────────────────────
    // 밤 계획 만들기 → PlanActivity 이동
    // ────────────────────────────────────────────────────────────────

    private fun makePlan() {
        GNCLog.i("[SetUpActivity] 밤 계획 만들기 → PlanActivity 이동")
        GNCLog.i("[SetUpActivity] 현재 설정: wakeTime=${GlobalVariables.wakeTime}, temp=${GlobalVariables.setTemperatureDeg}°C, guests=${GlobalVariables.guestCount}명, reserve=${GlobalVariables.marginBatteryPct}%, dest=${GlobalVariables.destinationName}(${GlobalVariables.destinationDistanceKm}km)")
        startActivity(Intent(this, PlanActivity::class.java))
    }

    // ────────────────────────────────────────────────────────────────
    // 최근 수면 기록
    // ────────────────────────────────────────────────────────────────

    private fun loadSleepRecords() {
        GNCLog.d("[SetUpActivity] 수면 기록 DB 조회 시작")
        val db = SleepDatabase.getInstance(this)
        Thread {
            val records = db.sleepRecordDao().getRecent()
            GNCLog.i("[SetUpActivity] 수면 기록 조회 완료 — ${records.size}개")
            records.forEachIndexed { i, r ->
                GNCLog.d("[SetUpActivity] 기록[$i] dest=${r.destinationName}, wakeTime=${r.wakeTime}, temp=${r.setTemperatureDeg}°C, guests=${r.guestCount}명, createdAt=${r.createdAt}")
            }
            runOnUiThread { populateSleepRecords(records) }
        }.start()
    }

    private fun populateSleepRecords(records: List<SleepRecord>) {
        val scrollView = findViewById<View>(R.id.gc_sleep_records_scroll)
        val container = findViewById<LinearLayout>(R.id.gc_sleep_records_container)
        val emptyView = findViewById<TextView>(R.id.gc_sleep_records_empty)
        container.removeAllViews()

        if (records.isEmpty()) {
            scrollView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            return
        }

        scrollView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE

        records.forEachIndexed { index, record ->
            if (index > 0) {
                val divider = View(this)
                divider.setBackgroundColor(
                    android.graphics.Color.parseColor("#1AFFFFFF"))
                val divParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 2)
                container.addView(divider, divParams)
            }

            val row = layoutInflater.inflate(R.layout.gc_item_sleep_record, container, false)

            row.findViewById<TextView>(R.id.gc_record_destination).text = record.destinationName
            row.findViewById<TextView>(R.id.gc_record_detail).text =
                "기상 ${record.wakeTime}  ·  ${record.setTemperatureDeg}°C  ·  ${record.guestCount}명"

            row.setOnClickListener { applyRecord(record) }

            row.findViewById<View>(R.id.gc_record_delete).setOnClickListener {
                GNCLog.i("[SetUpActivity] 수면 기록 삭제 요청 — id=${record.id}, dest=${record.destinationName}")
                val db = SleepDatabase.getInstance(this)
                Thread {
                    db.sleepRecordDao().deleteById(record.id)
                    GNCLog.i("[SetUpActivity] 수면 기록 삭제 완료 — id=${record.id}")
                    runOnUiThread { loadSleepRecords() }
                }.start()
            }

            container.addView(row)
        }
    }

    private fun applyRecord(record: SleepRecord) {
        GNCLog.i("[SetUpActivity] 수면 기록 적용 — id=${record.id}, dest=${record.destinationName}, wakeTime=${record.wakeTime}, temp=${record.setTemperatureDeg}°C, guests=${record.guestCount}명, reserve=${record.marginBatteryPct}%")
        GlobalVariables.destinationName = record.destinationName
        GlobalVariables.destinationDistanceKm = record.destinationDistanceKm
        GlobalVariables.destinationLat = record.destinationLat
        GlobalVariables.destinationLng = record.destinationLng
        GlobalVariables.wakeTime = record.wakeTime
        GlobalVariables.setTemperatureDeg = record.setTemperatureDeg
        GlobalVariables.marginBatteryPct = record.marginBatteryPct
        GlobalVariables.guestCount = record.guestCount
        // 설정 행 갱신
        updateWakeTimeDisplay()
        updateDestinationRow()
        updateTemperatureDisplay()
        updateReserveDisplay()
        updateGuestsDisplay()
        GNCLog.d("[SetUpActivity] 기록 적용 후 UI 갱신 완료")
    }

    private fun startLocationUpdates() {
        locationManager.initialize()
        locationManager.startUpdates(
            onLocation = { loc ->
                GlobalVariables.currentLat = loc.latitude
                GlobalVariables.currentLng = loc.longitude
                locationManager.reverseGeocode(loc.latitude, loc.longitude) { name ->
                    GlobalVariables.locationName = name
                    runOnUiThread { updateLocationDisplay(name) }
                }
            },
            onError = { }
        )
    }

    private fun updateLocationDisplay(name: String) {
        headerView().findViewById<TextView>(R.id.gc_context_location).text = name
    }

    private fun refreshVehicleStats() {
        GNCLog.d("[SetUpActivity] 차량 지표 갱신 — battery=${GlobalVariables.curBattery}, temp=${GlobalVariables.outTemperature}")
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_battery),
            GlobalFunction.batteryValueStr(),
            "%",
            getString(R.string.gc_label_battery)
        )
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_outside),
            GlobalFunction.temperatureValueStr(),
            "°C",
            getString(R.string.gc_label_outside)
        )
    }

    private fun headerView(): View = findViewById(R.id.gc_header)
}
