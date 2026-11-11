package com.example.pleostest.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.example.pleostest.util.GNCLog

/** 차량 제어 1건의 결과 */
sealed class CarOutcome {
    object Done : CarOutcome() { override fun toString() = "Done" }
    /** 이 차(또는 에뮬레이터)에 해당 기능이 없음 */
    object Unsupported : CarOutcome() { override fun toString() = "Unsupported" }
    /** 앱 권한으로는 제어할 수 없음 */
    data class Denied(val reason: String) : CarOutcome()
    data class Failed(val reason: String) : CarOutcome()
}

/**
 * 기상 시퀀스용 차량 제어 — Pleos Vehicle SDK 래퍼.
 *
 * 차마다 지원 기능·범위(송풍 단계, 열선 단계, 창문 이동 범위 등)가 다르므로
 * [discover]로 capability를 먼저 읽고, 그 범위 안에서만 값을 쓴다.
 * 모든 콜백은 SDK 스레드에서 올 수 있으니 호출자가 자기 스레드로 옮겨야 한다.
 */
@SuppressLint("MissingPermission")
class CarControl(private val context: Context) {

    /** 차량이 알려준 지원 영역·범위. 조회 실패한 항목은 비어 있음 */
    data class Caps(
        val hvacPowerAreas: List<SeatArea> = emptyList(),
        val hvacAutoAreas: List<SeatArea> = emptyList(),
        val recircAreas: List<SeatArea> = emptyList(),
        val tempAreas: Map<SeatArea, AreaBounds<Float>> = emptyMap(),
        val fanSpeed: Map<SeatArea, AreaBounds<Int>> = emptyMap(),
        val fanDirections: Map<SeatArea, List<FanDirectionType>> = emptyMap(),
        val defrostAreas: List<WindowArea> = emptyList(),
        val mirrorHeat: Map<MirrorArea, AreaBounds<Int>> = emptyMap(),
        val steeringHeat: AreaBounds<Int>? = null,
        val seatHeat: Map<SeatArea, AreaBounds<Int>> = emptyMap(),
        val seatVent: Map<SeatArea, AreaBounds<Int>> = emptyMap(),
        val occupancyAreas: List<SeatArea> = emptyList(),
        val ambientBrightness: Map<SeatArea, AreaBounds<Int>> = emptyMap(),
        val ambientColorAreas: List<SeatArea> = emptyList(),
        val ambientSwitch: Map<SeatArea, List<LightSwitch>> = emptyMap(),
        val windowPosition: Map<WindowArea, AreaBounds<Int>> = emptyMap(),
    ) {
        /** 앞좌석 창문만 (환기용) */
        val frontWindows: Map<WindowArea, AreaBounds<Int>>
            get() = windowPosition.filterKeys { a ->
                a.values.any { it == WindowValue.ROW_1_LEFT || it == WindowValue.ROW_1_RIGHT }
            }

        fun summary(): String = buildString {
            append("공조전원=${hvacPowerAreas.size} 오토=${hvacAutoAreas.size} 내외기=${recircAreas.size} ")
            append("온도=${tempAreas.size} 송풍=${fanSpeed.values.firstOrNull()?.let { "${it.minValue}..${it.maxValue}" }} ")
            append("풍향=${fanDirections.values.firstOrNull()} 성에=${defrostAreas.size} 미러열선=${mirrorHeat.size} ")
            append("핸들열선=${steeringHeat?.let { "${it.minValue}..${it.maxValue}" }} ")
            append("시트열선=${seatHeat.size} 시트통풍=${seatVent.size} 착좌=${occupancyAreas.size} ")
            append("앰비언트밝기=${ambientBrightness.values.firstOrNull()?.let { "${it.minValue}..${it.maxValue}" }} ")
            append("앰비언트색=${ambientColorAreas.size} 앰비언트스위치=${ambientSwitch.size} ")
            append("창문=${windowPosition.values.firstOrNull()?.let { "${it.minValue}..${it.maxValue}" }}(${windowPosition.size})")
        }
    }

    /** 시퀀스 시작 전 상태 — 기상 후 원래대로 돌려놓기 위해 */
    data class Snapshot(
        val ambientBrightness: Map<SeatArea, Int> = emptyMap(),
        val ambientColor: Map<SeatArea, Int> = emptyMap(),
        val fanDirection: Map<SeatArea, FanDirectionType> = emptyMap(),
        val fanSpeed: Map<SeatArea, Int> = emptyMap(),
        val recirc: Map<SeatArea, Boolean> = emptyMap(),
        val windowPosition: Map<WindowArea, Int> = emptyMap(),
    )

    private val vehicle = Vehicle(context.applicationContext)
    private val hvac get() = vehicle.getHVAC()
    private val light get() = vehicle.getLight()
    private val seat get() = vehicle.getSeat()
    private val window get() = vehicle.getWindow()

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    fun initialize() {
        vehicle.initialize()
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()),
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                propertyManager = if (ready) c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager else null
            }
        } catch (e: Exception) {
            GNCLog.w("[CarControl] Car 서비스 연결 실패: ${e.message}")
        }
        GNCLog.i("[CarControl] 초기화")
    }

    fun release() {
        car?.disconnect()
        car = null
        propertyManager = null
        vehicle.release()
    }

    // ────────────────────────────────────────────────────────────────
    // capability 조회
    // ────────────────────────────────────────────────────────────────

    fun discover(onDone: (Caps) -> Unit) {
        var caps = Caps()
        val lock = Any()
        val gather = Gather { onDone(synchronized(lock) { caps }) }
        fun <T> probe(name: String, call: ((T) -> Unit, (Exception) -> Unit) -> Unit, apply: (Caps, T) -> Caps) {
            gather.add()
            try {
                call({ v -> synchronized(lock) { caps = apply(caps, v) }; gather.done() },
                     { e -> GNCLog.d("[CarControl] $name 미지원: ${e.message}"); gather.done() })
            } catch (e: Exception) {
                GNCLog.d("[CarControl] $name 조회 불가: ${e.javaClass.simpleName} ${e.message}")
                gather.done()
            }
        }
        probe<List<SeatArea>>("공조 전원", { ok, f -> hvac.checkHvacPowerCapabilities(ok, f) }) { c, v -> c.copy(hvacPowerAreas = v) }
        probe<List<SeatArea>>("공조 오토", { ok, f -> hvac.checkHvacAutoCapabilities(ok, f) }) { c, v -> c.copy(hvacAutoAreas = v) }
        probe<List<SeatArea>>("내외기", { ok, f -> hvac.checkHvacRecirculationCapabilities(ok, f) }) { c, v -> c.copy(recircAreas = v) }
        probe<Map<SeatArea, AreaBounds<Float>>>("설정 온도", { ok, f -> hvac.checkHvacTemperatureCapabilities(ok, f) }) { c, v -> c.copy(tempAreas = v) }
        probe<Map<SeatArea, AreaBounds<Int>>>("송풍", { ok, f -> hvac.checkHvacFanSpeedCapabilities(ok, f) }) { c, v -> c.copy(fanSpeed = v) }
        probe<Map<SeatArea, List<FanDirectionType>>>("풍향", { ok, f -> hvac.checkHvacFanDirectionCapabilities(ok, f) }) { c, v -> c.copy(fanDirections = v) }
        probe<List<WindowArea>>("성에 제거", { ok, f -> hvac.checkHvacDefrosterCapabilities(ok, f) }) { c, v -> c.copy(defrostAreas = v) }
        probe<Map<MirrorArea, AreaBounds<Int>>>("미러 열선", { ok, f -> vehicle.getSideMirror().checkHvacSideMirrorHeatCapabilities(ok, f) }) { c, v -> c.copy(mirrorHeat = v) }
        probe<AreaBounds<Int>>("핸들 열선", { ok, f -> vehicle.getSteeringWheel().checkHvacSteeringWheelHeatCapabilities(ok, f) }) { c, v -> c.copy(steeringHeat = v) }
        probe<Map<SeatArea, AreaBounds<Int>>>("시트 열선", { ok, f -> seat.checkSeatTemperatureCapabilities(ok, f) }) { c, v -> c.copy(seatHeat = v) }
        probe<Map<SeatArea, AreaBounds<Int>>>("시트 통풍", { ok, f -> seat.checkSeatVentilationCapabilities(ok, f) }) { c, v -> c.copy(seatVent = v) }
        probe<List<SeatArea>>("착좌", { ok, f -> seat.checkSeatOccupancyCapabilities(ok, f) }) { c, v -> c.copy(occupancyAreas = v) }
        probe<Map<SeatArea, AreaBounds<Int>>>("앰비언트 밝기", { ok, f -> light.checkAmbientLightBrightnessCapabilities({ _, m -> ok(m) }, f) }) { c, v -> c.copy(ambientBrightness = v) }
        probe<List<SeatArea>>("앰비언트 색", { ok, f -> light.checkAmbientLightBrightnessColorCapabilities({ _, l -> ok(l) }, f) }) { c, v -> c.copy(ambientColorAreas = v) }
        probe<Map<SeatArea, List<LightSwitch>>>("앰비언트 스위치", { ok, f -> light.checkAmbientLightSwitchCapabilities({ _, m -> ok(m) }, f) }) { c, v -> c.copy(ambientSwitch = v) }
        probe<Map<WindowArea, AreaBounds<Int>>>("창문", { ok, f -> window.checkWindowPositionCapabilities(ok, f) }) { c, v -> c.copy(windowPosition = v) }
        gather.seal()
    }

    /** 원래 상태 저장 (실패한 항목은 복원하지 않음) */
    fun snapshot(caps: Caps, onDone: (Snapshot) -> Unit) {
        var snap = Snapshot()
        val lock = Any()
        val gather = Gather { onDone(synchronized(lock) { snap }) }
        fun <T> read(call: ((T) -> Unit, (Exception) -> Unit) -> Unit, apply: (Snapshot, T) -> Snapshot) {
            gather.add()
            try {
                call({ v -> synchronized(lock) { snap = apply(snap, v) }; gather.done() }, { gather.done() })
            } catch (e: Exception) { gather.done() }
        }
        caps.ambientBrightness.keys.forEach { a ->
            read<Int?>({ ok, f -> light.getAmbientLightBrightness(a, ok, f) }) { s, v -> if (v == null) s else s.copy(ambientBrightness = s.ambientBrightness + (a to v)) }
        }
        caps.ambientColorAreas.forEach { a ->
            read<Int?>({ ok, f -> light.getAmbientLightBrightnessColor(a, ok, f) }) { s, v -> if (v == null) s else s.copy(ambientColor = s.ambientColor + (a to v)) }
        }
        caps.fanDirections.keys.forEach { a ->
            read<FanDirectionType?>({ ok, f -> hvac.getHvacFanDirection(a, ok, f) }) { s, v -> if (v == null) s else s.copy(fanDirection = s.fanDirection + (a to v)) }
        }
        caps.fanSpeed.keys.forEach { a ->
            read<Int?>({ ok, f -> hvac.getHvacFanSpeed(a, ok, f) }) { s, v -> if (v == null) s else s.copy(fanSpeed = s.fanSpeed + (a to v)) }
        }
        caps.recircAreas.forEach { a ->
            read<Boolean?>({ ok, f -> hvac.getHvacRecirculationState(a, ok, f) }) { s, v -> if (v == null) s else s.copy(recirc = s.recirc + (a to v)) }
        }
        caps.windowPosition.keys.forEach { a ->
            read<Int?>({ ok, f -> window.getCurrentWindowPosition(a, ok, f) }) { s, v -> if (v == null) s else s.copy(windowPosition = s.windowPosition + (a to v)) }
        }
        gather.seal()
    }

    // ────────────────────────────────────────────────────────────────
    // 동작
    // ────────────────────────────────────────────────────────────────

    /** 성에 제거: 공조 켜고, 설정 온도, 앞·뒤 유리 성에 제거, 바람 방향 유리+발 */
    fun defrostOn(caps: Caps, targetC: Float, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        caps.hvacPowerAreas.forEach { a -> op { ok, f -> hvac.setHvacPower(a, true, { _, _ -> ok() }, f) } }
        caps.tempAreas.forEach { (a, b) ->
            val t = targetC.coerceIn(b.minValue, b.maxValue)
            op { ok, f -> hvac.setHvacTemperature(a, t, TemperatureUnit.CELSIUS, { _, _ -> ok() }, f) }
        }
        caps.defrostAreas.forEach { a -> op(counts = true) { ok, f -> hvac.setHvacDefroster(a, true, { _, _ -> ok() }, f) } }
        caps.fanDirections.forEach { (a, dirs) ->
            val d = listOf(FanDirectionType.DEFROST_AND_FLOOR, FanDirectionType.DEFROST).firstOrNull { it in dirs }
            if (d != null) op(counts = true) { ok, f -> hvac.setHvacFanDirection(a, d, { _, _ -> ok() }, f) }
        }
    }

    /** 성에 제거 마무리: 유리 성에 제거 끄고, 바람 방향 원래대로(없으면 얼굴+발), 공조 오토 */
    fun defrostOff(caps: Caps, snap: Snapshot?, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        caps.defrostAreas.forEach { a -> op(counts = true) { ok, f -> hvac.setHvacDefroster(a, false, { _, _ -> ok() }, f) } }
        caps.fanDirections.forEach { (a, dirs) ->
            val original = snap?.fanDirection?.get(a)?.takeIf { it in dirs && it != FanDirectionType.UNKNOWN &&
                it != FanDirectionType.DEFROST && it != FanDirectionType.DEFROST_AND_FLOOR }
            val d = original ?: FanDirectionType.FACE_AND_FLOOR.takeIf { it in dirs }
            if (d != null) op { ok, f -> hvac.setHvacFanDirection(a, d, { _, _ -> ok() }, f) }
        }
        caps.hvacAutoAreas.forEach { a -> op { ok, f -> hvac.setHvacAuto(a, true, { _, _ -> ok() }, f) } }
    }

    fun mirrorHeat(caps: Caps, on: Boolean, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        caps.mirrorHeat.forEach { (a, b) ->
            val level = if (on) WakeSequence.heatLevel(b.minValue, b.maxValue) else b.minValue
            op(counts = true) { ok, f -> vehicle.getSideMirror().setHvacSideMirrorHeat(a, level, { _, _ -> ok() }, f) }
        }
    }

    fun steeringHeat(caps: Caps, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        caps.steeringHeat?.let { b ->
            val level = WakeSequence.heatLevel(b.minValue, b.maxValue)
            op(counts = true) { ok, f -> vehicle.getSteeringWheel().setHvacSteeringWheelHeat(level, { ok() }, f) }
        }
    }

    /**
     * 시트 열선/통풍: 운전석은 곧 운전할 사람이 앉으니 항상, 나머지는 착좌 센서가 "앉음"일 때만.
     * (차박 중 접어둔 뒷좌석에 열선을 켜면 매트리스 아래가 뜨거워질 수 있음)
     */
    fun seatComfort(caps: Caps, ventilate: Boolean, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        val targets = if (ventilate) caps.seatVent else caps.seatHeat
        GNCLog.d("[CarControl] ${if (ventilate) "시트 통풍" else "시트 열선"} 영역 — " +
            targets.entries.joinToString { (a, b) -> "${a.values.toList()} ${b.minValue}..${b.maxValue}" } +
            " / 착좌 센서 ${caps.occupancyAreas.map { it.values.toList() }}")
        targets.forEach { (a, b) ->
            val level = WakeSequence.heatLevel(b.minValue, b.maxValue)
            val set: ((() -> Unit), (Exception) -> Unit) -> Unit = { ok, f ->
                if (ventilate) seat.setHvacSeatVentilation(a, level, { _, _ -> ok() }, f)
                else seat.setHvacSeatTemperature(a, level, { _, _ -> ok() }, f)
            }
            val isDriver = a.values.contains(SeatValue.ROW_1_LEFT)
            val isFront = a.values.all { it == SeatValue.ROW_1_LEFT || it == SeatValue.ROW_1_RIGHT || it == SeatValue.ROW_1_CENTER }
            when {
                isDriver -> op(counts = true, block = set)
                isFront && caps.occupancyAreas.contains(a) -> op(counts = true) { ok, f ->
                    seat.getCurrentSeatOccupancy(a, { occupied ->
                        if (occupied == true) set(ok, f) else f(Skip())   // 빈 자리 → 결과 집계에서 제외
                    }, f)
                }
                else -> Unit   // 뒷좌석은 접혀 있을 수 있어 건드리지 않음
            }
        }
    }

    /** 외기 유입 + 송풍 한 단계 ↑ */
    fun freshAir(caps: Caps, snap: Snapshot?, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        caps.recircAreas.forEach { a -> op(counts = true) { ok, f -> hvac.setHvacRecirculation(a, false, { _, _ -> ok() }, f) } }
        caps.fanSpeed.forEach { (a, b) ->
            val speed = WakeSequence.freshAirFanSpeed(snap?.fanSpeed?.get(a), b.minValue, b.maxValue)
            op(counts = true) { ok, f -> hvac.setHvacFanSpeed(a, speed, { _, _ -> ok() }, f) }
        }
    }

    /** 외기·송풍 원래대로 */
    fun restoreAir(caps: Caps, snap: Snapshot, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        snap.recirc.forEach { (a, v) -> op(counts = true) { ok, f -> hvac.setHvacRecirculation(a, v, { _, _ -> ok() }, f) } }
        snap.fanSpeed.forEach { (a, v) -> if (caps.fanSpeed.containsKey(a)) op(counts = true) { ok, f -> hvac.setHvacFanSpeed(a, v, { _, _ -> ok() }, f) } }
    }

    /** 앞 창문 살짝 열기 / 닫기 (닫을 땐 원래 위치, 모르면 완전히 닫음) */
    fun frontWindows(caps: Caps, open: Boolean, snap: Snapshot?, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        caps.frontWindows.forEach { (a, b) ->
            val original = snap?.windowPosition?.get(a)?.coerceIn(b.minValue, b.maxValue)
            val crack = WakeSequence.windowCrackPosition(b.minValue, b.maxValue)
            // 이미 그만큼 이상 열려 있으면 그대로 둠 (열린 창문을 "살짝 열기"로 닫아버리지 않게)
            if (open && original != null && original >= crack) {
                op(counts = true) { ok, _ -> ok() }
                return@forEach
            }
            val pos = if (open) crack else original ?: b.minValue
            op(counts = true) { ok, f -> window.setWindowPosition(a, pos, { _, _ -> ok() }, f) }
        }
    }

    /** 앰비언트 조명 켜기 + 밝기 + 색 */
    fun ambient(caps: Caps, level: Int?, color: Int?, turnOn: Boolean, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        if (turnOn) caps.ambientSwitch.forEach { (a, switches) ->
            if (LightSwitch.ON in switches) op { ok, f -> light.setAmbientLightSwitch(a, LightSwitch.ON, { _, _ -> ok() }, f) }
        }
        if (level != null) caps.ambientBrightness.forEach { (a, b) ->
            val v = level.coerceIn(b.minValue, b.maxValue)
            op(counts = true) { ok, f -> light.setAmbientLightBrightness(a, v, { _, _ -> ok() }, f) }
        }
        if (color != null) caps.ambientColorAreas.forEach { a ->
            op { ok, f -> light.setAmbientLightBrightnessColor(a, color, { _, _ -> ok() }, f) }
        }
    }

    fun restoreAmbient(snap: Snapshot, onDone: (CarOutcome) -> Unit) = batch(onDone) {
        snap.ambientBrightness.forEach { (a, v) -> op(counts = true) { ok, f -> light.setAmbientLightBrightness(a, v, { _, _ -> ok() }, f) } }
        snap.ambientColor.forEach { (a, v) -> op { ok, f -> light.setAmbientLightBrightnessColor(a, v, { _, _ -> ok() }, f) } }
    }

    /**
     * 사이드미러 펴기. Pleos SDK에는 미러 접기/펴기 API가 없어 AAOS VHAL MIRROR_FOLD를 직접 쓴다.
     * 이 속성은 android.car.permission.CONTROL_CAR_MIRRORS(signature|privileged)가 필요해
     * 일반 앱 설치로는 [CarOutcome.Denied] — 시스템 앱으로 탑재되거나 OEM이 권한을 줄 때만 동작.
     */
    fun unfoldMirrors(onDone: (CarOutcome) -> Unit) {
        val granted = context.checkSelfPermission(PERMISSION_CONTROL_CAR_MIRRORS) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            onDone(CarOutcome.Denied("$PERMISSION_CONTROL_CAR_MIRRORS 은 시스템 권한"))
            return
        }
        val pm = propertyManager ?: return onDone(CarOutcome.Failed("Car 서비스 미연결"))
        try {
            val config = pm.getCarPropertyConfig(VehiclePropertyIds.MIRROR_FOLD)
                ?: return onDone(CarOutcome.Unsupported)
            config.areaIds.forEach { area -> pm.setBooleanProperty(VehiclePropertyIds.MIRROR_FOLD, area, false) }
            onDone(CarOutcome.Done)
        } catch (e: SecurityException) {
            onDone(CarOutcome.Denied(e.message ?: "SecurityException"))
        } catch (e: Exception) {
            onDone(CarOutcome.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 비동기 묶음 처리
    // ────────────────────────────────────────────────────────────────

    /**
     * 여러 SDK 호출을 묶어 결과 하나로.
     * counts=true인 호출(그 동작의 핵심) 중 하나라도 성공 → Done.
     * 핵심 호출이 하나도 없으면(차가 그 기능을 지원 안 함) → Unsupported.
     */
    private class Batch(private val onDone: (CarOutcome) -> Unit) {
        private var pending = 0
        private var sealed = false
        private var keyOps = 0
        private var keyOk = 0
        private var failure: CarOutcome? = null
        private var finished = false

        fun op(counts: Boolean = false, block: (() -> Unit, (Exception) -> Unit) -> Unit) {
            synchronized(this) { pending++; if (counts) keyOps++ }
            var settled = false
            fun settle(e: Exception?) {
                if (e is Skip) {
                    synchronized(this) { if (settled) return; settled = true; pending--; if (counts) keyOps-- }
                    check()
                    return
                }
                if (e != null) GNCLog.d("[CarControl] 호출 실패 (${if (counts) "핵심" else "보조"}): ${e.javaClass.simpleName} ${e.message}")
                synchronized(this) {
                    if (settled) return
                    settled = true
                    pending--
                    if (e == null) { if (counts) keyOk++ }
                    else if (counts || failure == null) failure = map(e)
                }
                check()
            }
            try { block({ settle(null) }, { e -> settle(e) }) } catch (e: Exception) { settle(e) }
        }

        fun seal() { synchronized(this) { sealed = true }; check() }

        private fun check() {
            val result = synchronized(this) {
                if (!sealed || pending > 0 || finished) return
                finished = true
                when {
                    keyOps == 0 -> CarOutcome.Unsupported
                    keyOk > 0 -> CarOutcome.Done
                    else -> failure ?: CarOutcome.Failed("알 수 없음")
                }
            }
            onDone(result)
        }

        private fun map(e: Exception): CarOutcome = when {
            e is SecurityException -> CarOutcome.Denied(e.message ?: "SecurityException")
            (e.message ?: "").contains("support", ignoreCase = true) -> CarOutcome.Unsupported
            else -> CarOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun batch(onDone: (CarOutcome) -> Unit, build: Batch.() -> Unit) {
        val b = Batch(onDone)
        b.build()
        b.seal()
    }

    /** 조건이 맞지 않아 일부러 하지 않은 호출 (성공도 실패도 아님) */
    private class Skip : Exception()

    /** 비동기 조회 n건이 모두 끝나면 1회 콜백 */
    private class Gather(private val onAll: () -> Unit) {
        private var pending = 0
        private var sealed = false
        private var fired = false
        @Synchronized fun add() { pending++ }
        fun done() { synchronized(this) { pending-- }; fire() }
        fun seal() { synchronized(this) { sealed = true }; fire() }
        private fun fire() {
            synchronized(this) { if (!sealed || pending > 0 || fired) return; fired = true }
            onAll()
        }
    }

    companion object {
        const val PERMISSION_CONTROL_CAR_MIRRORS = "android.car.permission.CONTROL_CAR_MIRRORS"
    }
}
