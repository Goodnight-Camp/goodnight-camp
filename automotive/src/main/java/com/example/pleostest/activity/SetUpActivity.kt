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
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.pleostest.R
import com.example.pleostest.dialog.DestinationSearchDialog
import com.example.pleostest.dialog.GcTimePickerDialog
import com.example.pleostest.global.GlobalFunction
import com.example.pleostest.global.GlobalVariables
import com.example.pleostest.sdk.LocationManager
import com.example.pleostest.sdk.NaviManager
import com.example.pleostest.sdk.WeatherForecast
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

        // 지금 차 상태: 실내 · 바깥 온도 (CarDataService가 갱신하면 refreshVehicleStats로 다시 그림)
        refreshVehicleStats()

        // 오늘 밤 설정 행들
        updateWakeTimeDisplay()
        updateDestinationRow()
        updateReserveDisplay()
        updateGuestsDisplay()
    }

    // ────────────────────────────────────────────────────────────────
    // 리스너 설정
    // ────────────────────────────────────────────────────────────────

    private fun setupListeners() {

        // 내일 아침 카드 탭 → TimePicker
        findViewById<View>(R.id.gc_row_wake_time).setOnClickListener { showWakeTimePicker() }

        // 내일 갈 곳 타일 탭 → 검색 다이얼로그
        findViewById<View>(R.id.gc_destination_change).setOnClickListener {
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

        // 도착할 때 남길 배터리 — 다음 목적지가 집이 아닐 수도 있어 5~50% 자유롭게
        findViewById<SeekBar>(R.id.gc_reserve_slider).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                GlobalVariables.marginBatteryPct = progress
                updateReserveDisplay()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                GNCLog.i("[SetUpActivity] 남길 배터리 변경 → ${GlobalVariables.marginBatteryPct}%")
            }
        })

        // 자는 인원 ±1명 (최소 1명)
        findViewById<ImageButton>(R.id.gc_guests_minus).setOnClickListener { adjustGuests(-1) }
        findViewById<ImageButton>(R.id.gc_guests_plus).setOnClickListener { adjustGuests(+1) }

        // 굿나잇 시작 → 밤 계획
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

    private fun updateGuestsDisplay() {
        findViewById<TextView>(R.id.gc_guests_value).text =
            GlobalVariables.guestCount.toString()
    }

    private fun updateReserveDisplay() {
        val pct = GlobalVariables.marginBatteryPct.coerceIn(RESERVE_MIN, RESERVE_MAX)
        findViewById<TextView>(R.id.gc_reserve_value).text = "$pct%"
        findViewById<TextView>(R.id.gc_reserve_hint).setText(when {
            pct < 8 -> R.string.gc_reserve_hint_low
            pct < 20 -> R.string.gc_reserve_hint_mid
            else -> R.string.gc_reserve_hint_high
        })
        val slider = findViewById<SeekBar>(R.id.gc_reserve_slider)
        if (slider.progress != pct) slider.progress = pct
    }

    // ────────────────────────────────────────────────────────────────
    // 밤 계획 만들기 → PlanActivity 이동
    // ────────────────────────────────────────────────────────────────

    private fun makePlan() {
        GNCLog.i("[SetUpActivity] 밤 계획 만들기 → PlanActivity 이동")
        GNCLog.i("[SetUpActivity] 현재 설정: wakeTime=${GlobalVariables.wakeTime}, 차량 설정 온도=${GlobalVariables.setTemperatureDeg}°C, guests=${GlobalVariables.guestCount}명, reserve=${GlobalVariables.marginBatteryPct}%, dest=${GlobalVariables.destinationName}(${GlobalVariables.destinationDistanceKm}km)")
        startActivity(Intent(this, PlanActivity::class.java))
    }

    private fun startLocationUpdates() {
        locationManager.initialize()
        locationManager.startUpdates(
            onLocation = { loc ->
                GlobalVariables.currentLat = loc.latitude
                GlobalVariables.currentLng = loc.longitude
                // 밤 계획에 쓸 시간별 기온 예보를 미리 받아 둠 (1시간 캐시)
                WeatherForecast.fetch(loc.latitude, loc.longitude) { }
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
        GNCLog.d("[SetUpActivity] 차량 지표 갱신 — 실내=${GlobalVariables.cabinTemperature}, 바깥=${GlobalVariables.outTemperature}")
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_cabin),
            GlobalVariables.cabinTemperature.removeSuffix("°C"),
            "°C",
            getString(R.string.gc_label_cabin)
        )
        GlobalFunction.setStatItem(
            findViewById(R.id.gc_stat_outside),
            GlobalFunction.temperatureValueStr(),
            "°C",
            getString(R.string.gc_label_outside)
        )
    }

    private fun headerView(): View = findViewById(R.id.gc_header)

    companion object {
        private const val RESERVE_MIN = 5
        private const val RESERVE_MAX = 50
    }
}
