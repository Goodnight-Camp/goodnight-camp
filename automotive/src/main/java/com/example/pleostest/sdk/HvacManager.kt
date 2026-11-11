
package com.example.pleostest.sdk

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacAirCleanerStateListener
import ai.pleos.playground.vehicle.listener.HvacAutoStateListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import android.annotation.SuppressLint
import android.content.Context
import com.example.pleostest.util.GNCLog
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pleos Vehicle SDK HVAC 래퍼.
 *
 * 사용 기능 11개: 전원·오토·A/C·내외기·자동내외기·풍량·풍향·설정온도·실내온도·성에제거·공기청정
 * 미사용 3개: 듀얼(좌우 온도 분리)·최대냉방·최대제상 — 차박 수면 용도에 불필요하거나 전력 과다
 *
 * 역할은 4가지뿐이고, "어떤 모드로 돌릴지" 같은 정책은 여기 넣지 않는다 (ClimatePolicy/Controller 담당).
 *  1. 지원 여부 확인  — initialize() 후 기능별 capability 조회 → [supports]
 *  2. 상태 읽기       — [refreshState] → [state]
 *  3. 제어            — setXxx(); 미지원/쓰기 불가 기능은 호출 안 하고 false 반환
 *  4. 리스너          — 상태 변화 구독, 앱이 보낸 명령이 아닌 변화는 [onManualOverride]로 알림
 *
 * 콜백은 SDK IPC 스레드에서 올 수 있으므로 UI 갱신은 호출 측에서 메인 스레드로 넘길 것.
 */
class HvacManager(context: Context) {

    // ────────────────────────────────────────────────────────────────
    // 모델
    // ────────────────────────────────────────────────────────────────

    enum class Feature {
        POWER, AUTO, AC, RECIRCULATION, AUTO_RECIRCULATION,
        FAN_SPEED, FAN_DIRECTION, TEMPERATURE, TEMPERATURE_CURRENT, DEFROSTER, AIR_CLEANER
    }

    /** 기능별 지원 정보. area는 capability가 돌려준 첫 번째 영역을 대표로 사용 */
    data class Support(
        val feature: Feature,
        val accessType: String,                 // NONE / READ / WRITE / READ_WRITE 등 (SDK enum 이름 그대로)
        val seatArea: SeatArea? = null,
        val windowArea: WindowArea? = null,     // DEFROSTER 전용
        val min: Float? = null,                 // FAN_SPEED, TEMPERATURE 전용
        val max: Float? = null,
        val fanDirections: List<FanDirectionType> = emptyList()   // FAN_DIRECTION 전용: 차가 지원하는 풍향 목록
    ) {
        val supported get() = accessType != AccessType.NONE.name
        val readable get() = supported && accessType.contains("READ")
        val writable get() = supported && accessType.contains("WRITE")
    }

    /** 마지막으로 확인된 HVAC 상태 (null = 미지원 또는 아직 모름) */
    data class HvacState(
        val power: Boolean? = null,
        val auto: Boolean? = null,
        val ac: Boolean? = null,
        val recirculation: Boolean? = null,     // true = 내기, false = 외기
        val autoRecirculation: Boolean? = null,
        val fanSpeed: Int? = null,
        val fanDirection: FanDirectionType? = null,
        val targetTempC: Float? = null,
        val cabinTempC: Float? = null,          // 실내 현재 온도 (읽기 전용)
        val defroster: Boolean? = null,
        val airCleaner: Boolean? = null
    )

    // ────────────────────────────────────────────────────────────────
    // 외부 콜백 (VehicleManager와 같은 방식)
    // ────────────────────────────────────────────────────────────────

    var onCapabilitiesReady: ((Map<Feature, Support>) -> Unit)? = null
    var onStateChanged: ((HvacState) -> Unit)? = null
    /** 앱이 명령하지 않은 변화 감지 — 사용자가 직접 공조를 조작한 것으로 보고 자동 제어 양보 판단에 사용 */
    var onManualOverride: ((Feature, Any?) -> Unit)? = null
    var onError: ((Feature?, Exception) -> Unit)? = null

    // ────────────────────────────────────────────────────────────────
    // 내부 상태
    // ────────────────────────────────────────────────────────────────

    private val vehicle = Vehicle(context.applicationContext)
    private lateinit var hvac: HVAC

    private val supportMap = ConcurrentHashMap<Feature, Support>()
    val supports: Map<Feature, Support> get() = supportMap

    @Volatile
    var state = HvacState()
        private set

    /** 앱이 마지막으로 보낸 명령 값과 시각 — 리스너 이벤트가 우리 명령의 결과인지 판별용 */
    private data class Command(val value: Any?, val atMs: Long)
    private val lastCommands = ConcurrentHashMap<Feature, Command>()

    /** 리스너 해제를 위해 등록한 것들을 보관 */
    private val unregisterActions = mutableListOf<() -> Unit>()

    private var initialized = false

    companion object {
        /** set 후 이 시간 안에 같은 값으로 들어온 리스너 이벤트는 "우리 명령의 결과"로 간주 */
        private const val COMMAND_ECHO_WINDOW_MS = 5_000L
        private val CELSIUS = TemperatureUnit.CELSIUS   // ⚠ 이름이 다르면 TemperatureUnit을 Ctrl+클릭해서 섭씨 값으로 교체
    }

    // ────────────────────────────────────────────────────────────────
    // 1. 초기화 + 지원 여부 확인
    // ────────────────────────────────────────────────────────────────

    fun initialize() {
        if (initialized) return
        vehicle.initialize()
        hvac = vehicle.getHVAC()
        initialized = true
        GNCLog.i("[HvacManager] Vehicle SDK 초기화 → capability 조회 시작")
        checkCapabilities()
    }

    @SuppressLint("MissingPermission")
    private fun checkCapabilities() {
        val pending = AtomicInteger(Feature.entries.size)
        val done: () -> Unit = {
            if (pending.decrementAndGet() == 0) {
                logCapabilities()
                onCapabilitiesReady?.invoke(supports)
            }
        }
        // onFailed 시에도 done() 호출 → 하나 실패해도 전체가 멈추지 않게
        fun failed(feature: Feature): (Exception) -> Unit = { e ->
            GNCLog.w("[HvacManager] $feature capability 조회 실패: ${e.message}")
            supportMap[feature] = Support(feature, AccessType.NONE.name)
            done()
        }
        fun seatList(feature: Feature): (CapabilityConfig, List<SeatArea>) -> Unit = { config, areas ->
            supportMap[feature] = Support(feature, config.accessType.name, seatArea = areas.firstOrNull())
            done()
        }

        // 동기 예외(권한 등)가 나도 done()이 호출되도록 기능별로 감싼다
        fun check(feature: Feature, call: () -> Unit) {
            try { call() } catch (e: Exception) { failed(feature)(e) }
        }

        check(Feature.POWER) { hvac.checkHvacPowerCapabilities(seatList(Feature.POWER), failed(Feature.POWER)) }
        check(Feature.AUTO) { hvac.checkHvacAutoCapabilities(seatList(Feature.AUTO), failed(Feature.AUTO)) }
        check(Feature.AC) { hvac.checkHvacAcCapabilities(seatList(Feature.AC), failed(Feature.AC)) }
        check(Feature.RECIRCULATION) { hvac.checkHvacRecirculationCapabilities(seatList(Feature.RECIRCULATION), failed(Feature.RECIRCULATION)) }
        check(Feature.AUTO_RECIRCULATION) { hvac.checkHvacAutoRecirculationCapabilities(seatList(Feature.AUTO_RECIRCULATION), failed(Feature.AUTO_RECIRCULATION)) }
        check(Feature.AIR_CLEANER) { hvac.checkHvacAirCleanerCapabilities(seatList(Feature.AIR_CLEANER), failed(Feature.AIR_CLEANER)) }
        check(Feature.TEMPERATURE_CURRENT) { hvac.checkHvacTemperatureCurrentCapabilities(seatList(Feature.TEMPERATURE_CURRENT), failed(Feature.TEMPERATURE_CURRENT)) }

        check(Feature.FAN_SPEED) {
            hvac.checkHvacFanSpeedCapabilities({ config, bounds ->
                supportMap[Feature.FAN_SPEED] = boundedSupport(Feature.FAN_SPEED, config, bounds)
                done()
            }, failed(Feature.FAN_SPEED))
        }
        check(Feature.FAN_DIRECTION) {
            hvac.checkHvacFanDirectionCapabilities({ config, map ->
                val entry = map.entries.firstOrNull { it.value.isNotEmpty() } ?: map.entries.firstOrNull()
                // 에뮬레이터는 지원 풍향 목록을 비워서 줄 수 있음 → 그럴 땐 SDK에 정의된 전체 값을 후보로 사용
                val dirs = entry?.value?.takeIf { it.isNotEmpty() } ?: enumValues<FanDirectionType>().toList()
                if (entry?.value.isNullOrEmpty()) {
                    GNCLog.w("[HvacManager] FAN_DIRECTION 지원 목록이 비어 있음 → SDK 전체 값으로 대체")
                }
                supportMap[Feature.FAN_DIRECTION] =
                    Support(Feature.FAN_DIRECTION, config.accessType.name, seatArea = entry?.key, fanDirections = dirs)
                done()
            }, failed(Feature.FAN_DIRECTION))
        }
        check(Feature.TEMPERATURE) {
            hvac.checkHvacTemperatureCapabilities({ config, bounds, _ ->
                supportMap[Feature.TEMPERATURE] = boundedSupport(Feature.TEMPERATURE, config, bounds)
                done()
            }, failed(Feature.TEMPERATURE))
        }
        check(Feature.DEFROSTER) {
            hvac.checkHvacDefrosterCapabilities({ config, windows ->
                supportMap[Feature.DEFROSTER] =
                    Support(Feature.DEFROSTER, config.accessType.name, windowArea = windows.firstOrNull())
                done()
            }, failed(Feature.DEFROSTER))
        }
    }

    private fun <T> boundedSupport(
        feature: Feature,
        config: CapabilityConfig,
        bounds: Map<SeatArea, AreaBounds<T>>
    ): Support {
        val (area, range) = bounds.entries.firstOrNull()?.toPair() ?: (null to null)
        return Support(
            feature, config.accessType.name, seatArea = area,
            min = (range?.minValue as? Number)?.toFloat(),
            max = (range?.maxValue as? Number)?.toFloat()
        )
    }

    private fun logCapabilities() {
        Feature.entries.forEach { f ->
            val s = supportMap[f]
            val range = when {
                s?.min != null -> " range=${s.min}~${s.max}"
                !s?.fanDirections.isNullOrEmpty() -> " directions=${s?.fanDirections?.map { it.name }}"
                else -> ""
            }
            GNCLog.i("[HvacManager] capability $f → access=${s?.accessType}$range area=${s?.seatArea?.values?.joinToString { it.name } ?: s?.windowArea?.values?.joinToString { it.name }}")
        }
    }

    fun isWritable(feature: Feature) = supportMap[feature]?.writable == true

    // ────────────────────────────────────────────────────────────────
    // 2. 상태 읽기
    // ────────────────────────────────────────────────────────────────

    /** 읽기 가능한 기능 전부 1회 조회. 결과는 하나씩 도착할 때마다 onStateChanged 호출 */
    @SuppressLint("MissingPermission")
    fun refreshState() {
        if (!initialized) return
        readBool(Feature.POWER) { a, ok, fail -> hvac.getHvacPowerState(a, ok, fail) }
        readBool(Feature.AUTO) { a, ok, fail -> hvac.getHvacAutoState(a, ok, fail) }
        readBool(Feature.AC) { a, ok, fail -> hvac.getHvacAcState(a, ok, fail) }
        readBool(Feature.RECIRCULATION) { a, ok, fail -> hvac.getHvacRecirculationState(a, ok, fail) }
        readBool(Feature.AUTO_RECIRCULATION) { a, ok, fail -> hvac.getHvacAutoRecirculationState(a, ok, fail) }
        readBool(Feature.AIR_CLEANER) { a, ok, fail -> hvac.getHvacAirCleanerState(a, ok, fail) }

        seatAreaFor(Feature.FAN_SPEED, write = false)?.let { area ->
            safeCall(Feature.FAN_SPEED) {
                hvac.getHvacFanSpeed(area, { v -> update(Feature.FAN_SPEED, v) }, readFailed(Feature.FAN_SPEED))
            }
        }
        seatAreaFor(Feature.FAN_DIRECTION, write = false)?.let { area ->
            safeCall(Feature.FAN_DIRECTION) {
                hvac.getHvacFanDirection(area, { v -> update(Feature.FAN_DIRECTION, v) }, readFailed(Feature.FAN_DIRECTION))
            }
        }
        seatAreaFor(Feature.TEMPERATURE, write = false)?.let { area ->
            safeCall(Feature.TEMPERATURE) {
                hvac.getHvacTemperature(area, CELSIUS, { v -> update(Feature.TEMPERATURE, v) }, readFailed(Feature.TEMPERATURE))
            }
        }
        seatAreaFor(Feature.TEMPERATURE_CURRENT, write = false)?.let { area ->
            safeCall(Feature.TEMPERATURE_CURRENT) {
                hvac.getHvacTemperatureCurrent(area, { v -> update(Feature.TEMPERATURE_CURRENT, v) }, readFailed(Feature.TEMPERATURE_CURRENT))
            }
        }
        supportMap[Feature.DEFROSTER]?.takeIf { it.readable }?.windowArea?.let { area ->
            safeCall(Feature.DEFROSTER) {
                hvac.getHvacDefrosterState(area, { v -> update(Feature.DEFROSTER, v) }, readFailed(Feature.DEFROSTER))
            }
        }
    }

    private fun readBool(
        feature: Feature,
        call: (SeatArea, (Boolean?) -> Unit, (Exception) -> Unit) -> Unit
    ) {
        val area = seatAreaFor(feature, write = false) ?: return
        safeCall(feature) { call(area, { v -> update(feature, v) }, readFailed(feature)) }
    }

    private fun readFailed(feature: Feature): (Exception) -> Unit = { e ->
        GNCLog.w("[HvacManager] $feature 조회 실패: ${e.message}")
        onError?.invoke(feature, e)
    }

    // ────────────────────────────────────────────────────────────────
    // 3. 제어 — 반환값: 명령을 보냈으면 true (성공 여부는 콜백/리스너로 확인)
    // ────────────────────────────────────────────────────────────────

    /**
     * 공조 전원. 전원이 꺼져 있으면 온도·풍량 등 다른 항목은 차량에서 "사용 불가"(PropertyNotAvailableException)가 되므로
     * 다른 설정은 반드시 전원이 켜진 뒤에 보낼 것 → [then]에 넣으면 전원 설정 완료 후 실행된다.
     */
    @SuppressLint("MissingPermission")
    fun setPower(on: Boolean, then: (() -> Unit)? = null): Boolean {
        val area = seatAreaFor(Feature.POWER, write = true) ?: return false
        return send(Feature.POWER, on) {
            hvac.setHvacPower(area, on, { _, v ->
                // ⚠ set 콜백의 v는 "요청한 값"일 뿐 실제 적용 여부가 아님 → 실제 상태를 다시 읽어서 확인
                GNCLog.d("[HvacManager] POWER 명령 수락 → $v, 실제 상태 확인 중")
                safeCall(Feature.POWER) {
                    hvac.getHvacPowerState(area, { actual ->
                        update(Feature.POWER, actual)
                        if (actual == on) {
                            GNCLog.i("[HvacManager] POWER 실제 적용 확인 → $actual")
                            if (on) then?.invoke()
                        } else {
                            GNCLog.w("[HvacManager] POWER 명령은 수락됐지만 실제 상태는 $actual — 차량 상태(시동/유틸리티 모드) 확인 필요")
                            onError?.invoke(Feature.POWER, IllegalStateException("HVAC power not applied (actual=$actual)"))
                        }
                    }, readFailed(Feature.POWER))
                }
            }, writeFailed(Feature.POWER))
        }
    }

    @SuppressLint("MissingPermission")
    fun setAuto(on: Boolean) = sendBool(Feature.AUTO, on) { a, v, ok, fail -> hvac.setHvacAuto(a, v, ok, fail) }

    @SuppressLint("MissingPermission")
    fun setAc(on: Boolean) = sendBool(Feature.AC, on) { a, v, ok, fail -> hvac.setHvacAc(a, v, ok, fail) }

    /** fresh = true → 외기 유입(환기), false → 내기 순환 */
    @SuppressLint("MissingPermission")
    fun setFreshAir(fresh: Boolean) =
        sendBool(Feature.RECIRCULATION, !fresh) { a, v, ok, fail -> hvac.setHvacRecirculation(a, v, ok, fail) }

    /** 차량 자체의 공기 상태 기반 자동 내·외기 전환 (앱에서 CO₂를 못 읽으므로 이걸로 대체) */
    @SuppressLint("MissingPermission")
    fun setAutoRecirculation(on: Boolean) =
        sendBool(Feature.AUTO_RECIRCULATION, on) { a, v, ok, fail -> hvac.setHvacAutoRecirculation(a, v, ok, fail) }

    @SuppressLint("MissingPermission")
    fun setAirCleaner(on: Boolean) =
        sendBool(Feature.AIR_CLEANER, on) { a, v, ok, fail -> hvac.setHvacAirCleaner(a, v, ok, fail) }

    /** 차량이 허용하는 범위로 자동 보정해서 설정 */
    @SuppressLint("MissingPermission")
    fun setFanSpeed(level: Int): Boolean {
        val support = supportMap[Feature.FAN_SPEED] ?: return false
        val area = seatAreaFor(Feature.FAN_SPEED, write = true) ?: return false
        val clamped = clamp(level.toFloat(), support).toInt()
        return send(Feature.FAN_SPEED, clamped) {
            hvac.setHvacFanSpeed(area, clamped, { _, v -> GNCLog.d("[HvacManager] FAN_SPEED 설정 완료 → $v") }, writeFailed(Feature.FAN_SPEED))
        }
    }

    /** 최저 풍량 (MINIMAL 모드용) */
    fun setFanSpeedMin(): Boolean =
        supportMap[Feature.FAN_SPEED]?.min?.let { setFanSpeed(it.toInt()) } ?: false

    @SuppressLint("MissingPermission")
    fun setFanDirection(direction: FanDirectionType): Boolean {
        val support = supportMap[Feature.FAN_DIRECTION] ?: return false
        val area = seatAreaFor(Feature.FAN_DIRECTION, write = true) ?: return false
        if (support.fanDirections.isNotEmpty() && direction !in support.fanDirections) {
            GNCLog.w("[HvacManager] FAN_DIRECTION $direction 은 이 차량 미지원 (지원: ${support.fanDirections.map { it.name }})")
            return false
        }
        return send(Feature.FAN_DIRECTION, direction) {
            hvac.setHvacFanDirection(area, direction, { _, v -> GNCLog.d("[HvacManager] FAN_DIRECTION 설정 완료 → $v") }, writeFailed(Feature.FAN_DIRECTION))
        }
    }

    /**
     * 수면용 풍향 — 바람이 얼굴·몸에 직접 닿지 않도록 발밑 방향.
     * FanDirectionType의 실제 값 이름을 몰라도 되게, 차가 지원하는 목록에서 이름으로 고른다:
     *   1순위 FLOOR만 (FACE·DEFROST 미포함)  2순위 FLOOR가 들어간 조합  3순위 FACE 아닌 아무 방향
     */
    fun setSleepFanDirection(): Boolean {
        val dirs = supportMap[Feature.FAN_DIRECTION]?.fanDirections.orEmpty()
            .filterNot { it.name.equals("UNKNOWN", ignoreCase = true) }
        fun has(d: FanDirectionType, key: String) = d.name.contains(key, ignoreCase = true)
        val pick = dirs.firstOrNull { has(it, "FLOOR") && !has(it, "FACE") && !has(it, "DEFROST") }
            ?: dirs.firstOrNull { has(it, "FLOOR") && !has(it, "FACE") }
            ?: dirs.firstOrNull { !has(it, "FACE") }
            ?: return notSupported(Feature.FAN_DIRECTION)
        return setFanDirection(pick)
    }

    @SuppressLint("MissingPermission")
    fun setTargetTemperature(celsius: Float): Boolean {
        val support = supportMap[Feature.TEMPERATURE] ?: return false
        val area = seatAreaFor(Feature.TEMPERATURE, write = true) ?: return false
        val clamped = clamp(celsius, support)
        return send(Feature.TEMPERATURE, clamped) {
            hvac.setHvacTemperature(area, clamped, CELSIUS, { _, v -> GNCLog.d("[HvacManager] TEMPERATURE 설정 완료 → $v°C") }, writeFailed(Feature.TEMPERATURE))
        }
    }

    /** 앞유리 김 서림 제거 — 기상 직전 출발 준비용 */
    @SuppressLint("MissingPermission")
    fun setDefroster(on: Boolean): Boolean {
        val support = supportMap[Feature.DEFROSTER]?.takeIf { it.writable } ?: return notSupported(Feature.DEFROSTER)
        val area = support.windowArea ?: return notSupported(Feature.DEFROSTER)
        return send(Feature.DEFROSTER, on) {
            hvac.setHvacDefroster(area, on, { _, v -> GNCLog.d("[HvacManager] DEFROSTER 설정 완료 → $v") }, writeFailed(Feature.DEFROSTER))
        }
    }

    private fun sendBool(
        feature: Feature,
        value: Boolean,
        call: (SeatArea, Boolean, (SeatArea, Boolean?) -> Unit, (Exception) -> Unit) -> Unit
    ): Boolean {
        val area = seatAreaFor(feature, write = true) ?: return false
        return send(feature, value) {
            call(area, value, { _, v -> GNCLog.d("[HvacManager] $feature 설정 완료 → $v") }, writeFailed(feature))
        }
    }

    private fun send(feature: Feature, value: Any, action: () -> Unit): Boolean {
        if (!initialized) return false
        lastCommands[feature] = Command(value, System.currentTimeMillis())
        GNCLog.i("[HvacManager] $feature ← $value")
        return safeCall(feature, action)
    }

    private fun writeFailed(feature: Feature): (Exception) -> Unit = { e ->
        GNCLog.e("[HvacManager] $feature 설정 실패: ${e.message}")
        lastCommands.remove(feature)
        onError?.invoke(feature, e)
    }

    private fun clamp(value: Float, support: Support): Float {
        val min = support.min ?: return value
        val max = support.max ?: return value
        return value.coerceIn(min, max)
    }

    // ────────────────────────────────────────────────────────────────
    // 4. 리스너
    // ────────────────────────────────────────────────────────────────

    /** 캠핑 모드 시작 시 호출. capability 조회가 끝난 뒤(onCapabilitiesReady 이후)에 불러야 지원 기능만 등록됨 */
    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!initialized || unregisterActions.isNotEmpty()) return

        listen<HvacPowerStateListener>(Feature.POWER, { a, l -> hvac.registerHvacPowerState(a, l) }, { a, l -> hvac.unregisterHvacPowerState(a, l) })
        listen<HvacAutoStateListener>(Feature.AUTO, { a, l -> hvac.registerHvacAutoState(a, l) }, { a, l -> hvac.unregisterHvacAutoState(a, l) })
        listen<HvacAcStateListener>(Feature.AC, { a, l -> hvac.registerHvacAcState(a, l) }, { a, l -> hvac.unregisterHvacAcState(a, l) })
        listen<HvacRecirculationStateListener>(Feature.RECIRCULATION, { a, l -> hvac.registerHvacRecirculationState(a, l) }, { a, l -> hvac.unregisterHvacRecirculationState(a, l) })
        listen<HvacRecirculationStateListener>(Feature.AUTO_RECIRCULATION, { a, l -> hvac.registerHvacAutoRecirculationState(a, l) }, { a, l -> hvac.unregisterHvacAutoRecirculationState(a, l) })
        listen<HvacAirCleanerStateListener>(Feature.AIR_CLEANER, { a, l -> hvac.registerHvacAirCleanerState(a, l) }, { a, l -> hvac.unregisterHvacAirCleanerState(a, l) })
        listen<HvacFanSpeedListener>(Feature.FAN_SPEED, { a, l -> hvac.registerHvacFanSpeed(a, l) }, { a, l -> hvac.unregisterHvacFanSpeed(a, l) })
        listen<HvacTemperatureListener>(Feature.TEMPERATURE, { a, l -> hvac.registerHvacTemperature(a, l) }, { a, l -> hvac.unregisterHvacTemperature(a, l) })
        listen<HvacTemperatureListener>(Feature.TEMPERATURE_CURRENT, { a, l -> hvac.registerHvacTemperatureCurrent(a, l) }, { a, l -> hvac.unregisterHvacTemperatureCurrent(a, l) })

        supportMap[Feature.DEFROSTER]?.takeIf { it.readable }?.windowArea?.let { area ->
            val l = proxyListener<HvacDefrosterStateListener>(Feature.DEFROSTER)
            if (safeCall(Feature.DEFROSTER) { hvac.registerHvacDefrosterState(area, l) }) {
                unregisterActions += { hvac.unregisterHvacDefrosterState(area, l) }
            }
        }
        GNCLog.i("[HvacManager] 리스너 ${unregisterActions.size}개 등록")
    }

    @SuppressLint("MissingPermission")
    fun stopListening() {
        unregisterActions.forEach { runCatching(it) }
        unregisterActions.clear()
        GNCLog.i("[HvacManager] 리스너 해제")
    }

    private inline fun <reified L : Any> listen(
        feature: Feature,
        crossinline register: (SeatArea, L) -> Unit,
        crossinline unregister: (SeatArea, L) -> Unit
    ) {
        val area = seatAreaFor(feature, write = false) ?: return
        val listener = proxyListener<L>(feature)
        if (safeCall(feature) { register(area, listener) }) {
            unregisterActions += { unregister(area, listener) }
        }
    }

    /**
     * SDK 리스너 인터페이스를 동적으로 구현.
     * 리스너마다 콜백 메서드 이름이 달라서(onXxxUpdated 등) 샘플 앱과 같은 Proxy 방식을 사용:
     * 이름에 "fail"이 들어간 메서드 → 에러, 나머지 → 첫 번째 인자를 새 값으로 처리.
     * 인터페이스를 Ctrl+클릭해서 메서드명을 확인한 뒤 `object : HvacPowerStateListener { ... }`로 바꿔도 됨.
     */
    private inline fun <reified L : Any> proxyListener(feature: Feature): L =
        Proxy.newProxyInstance(L::class.java.classLoader, arrayOf(L::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "GcHvacListener($feature)"
                else -> {
                    val value = args?.firstOrNull()
                    if (method.name.contains("fail", ignoreCase = true)) {
                        (value as? Exception)?.let { onError?.invoke(feature, it) }
                    } else {
                        onListenerValue(feature, value)
                    }
                    null
                }
            }
        } as L

    private fun onListenerValue(feature: Feature, value: Any?) {
        val cmd = lastCommands[feature]
        val isEcho = cmd != null &&
                System.currentTimeMillis() - cmd.atMs < COMMAND_ECHO_WINDOW_MS &&
                sameValue(cmd.value, value)
        val changed = currentValue(feature) != value
        update(feature, value)

        // 우리가 보낸 명령의 결과가 아닌데 값이 바뀜 → 사용자 수동 조작으로 판단
        // (실내 현재 온도는 센서값이라 제외)
        if (changed && !isEcho && feature != Feature.TEMPERATURE_CURRENT && cmd != null) {
            GNCLog.i("[HvacManager] 수동 조작 감지 — $feature: ${cmd.value} → $value")
            onManualOverride?.invoke(feature, value)
        }
    }

    private fun sameValue(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) kotlin.math.abs(a.toFloat() - b.toFloat()) < 0.01f else a == b

    // ────────────────────────────────────────────────────────────────
    // 공통
    // ────────────────────────────────────────────────────────────────

    private fun update(feature: Feature, value: Any?) {
        state = when (feature) {
            Feature.POWER -> state.copy(power = value as? Boolean)
            Feature.AUTO -> state.copy(auto = value as? Boolean)
            Feature.AC -> state.copy(ac = value as? Boolean)
            Feature.RECIRCULATION -> state.copy(recirculation = value as? Boolean)
            Feature.AUTO_RECIRCULATION -> state.copy(autoRecirculation = value as? Boolean)
            Feature.FAN_SPEED -> state.copy(fanSpeed = (value as? Number)?.toInt())
            Feature.FAN_DIRECTION -> state.copy(fanDirection = value as? FanDirectionType)
            Feature.TEMPERATURE -> state.copy(targetTempC = (value as? Number)?.toFloat())
            Feature.TEMPERATURE_CURRENT -> state.copy(cabinTempC = (value as? Number)?.toFloat())
            Feature.DEFROSTER -> state.copy(defroster = value as? Boolean)
            Feature.AIR_CLEANER -> state.copy(airCleaner = value as? Boolean)
        }
        onStateChanged?.invoke(state)
    }

    private fun currentValue(feature: Feature): Any? = when (feature) {
        Feature.POWER -> state.power
        Feature.AUTO -> state.auto
        Feature.AC -> state.ac
        Feature.RECIRCULATION -> state.recirculation
        Feature.AUTO_RECIRCULATION -> state.autoRecirculation
        Feature.FAN_SPEED -> state.fanSpeed
        Feature.FAN_DIRECTION -> state.fanDirection
        Feature.TEMPERATURE -> state.targetTempC
        Feature.TEMPERATURE_CURRENT -> state.cabinTempC
        Feature.DEFROSTER -> state.defroster
        Feature.AIR_CLEANER -> state.airCleaner
    }

    /** 해당 기능을 읽기/쓰기 할 수 있으면 그 SeatArea, 아니면 null (로그 남김) */
    private fun seatAreaFor(feature: Feature, write: Boolean): SeatArea? {
        val s = supportMap[feature]
        val ok = if (write) s?.writable == true else s?.readable == true
        if (!ok || s?.seatArea == null) {
            if (write) notSupported(feature)
            return null
        }
        return s.seatArea
    }

    private fun notSupported(feature: Feature): Boolean {
        GNCLog.d("[HvacManager] $feature 미지원 또는 쓰기 불가 — 건너뜀 (access=${supportMap[feature]?.accessType})")
        return false
    }

    private inline fun safeCall(feature: Feature?, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (e: SecurityException) {
        GNCLog.w("[HvacManager] ${feature ?: "HVAC"} 권한 없음 (매니페스트 CAR_CLIMATE / CONTROL_CAR_CLIMATE 확인): ${e.message}")
        onError?.invoke(feature, e)
        false
    } catch (e: Exception) {
        GNCLog.e("[HvacManager] ${feature ?: "HVAC"} 호출 오류: ${e.message}")
        onError?.invoke(feature, e)
        false
    }

    fun release() {
        stopListening()
        onCapabilitiesReady = null
        onStateChanged = null
        onManualOverride = null
        onError = null
        if (initialized) {
            vehicle.release()
            initialized = false
        }
        GNCLog.i("[HvacManager] 해제")
    }
}