package com.example.pleostest.sdk

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.example.pleostest.util.GNCLog

/** 화면에 보여줄 시퀀스 진행 상황 (어느 스레드에서든 읽기 가능한 불변 스냅샷) */
data class WakeSequenceStatus(
    val active: Boolean = false,
    val wakeAtMs: Long = 0L,
    val eco: Boolean = false,
    /** 시퀀스 계획에 쓴 바깥 온도 (음성 안내용) */
    val outsideC: Float? = null,
    val items: List<Item> = emptyList(),
) {
    enum class State { PENDING, RUNNING, DONE, UNSUPPORTED, DENIED, FAILED }
    data class Item(val action: WakeAction, val atSec: Int, val state: State, val note: String? = null)
}

/**
 * 기상 시퀀스 실행기 — [WakeSequence.plan]의 시간표를 1초 간격으로 확인해 차에 명령을 내린다.
 *
 * - 시계: 평소엔 실제 시각. 시연용 [arm]의 speed > 1이면 시퀀스 시간이 그 배속으로 흐른다 (15배 → 15분이 1분).
 * - 늦게 시작해도(예: 기상 10분 전에 잠듦) 이미 지난 단계는 첫 틱에 바로 실행한다.
 * - [snooze]: 조명만 다시 은은하게 → 5분 뒤 다시 깨움.
 * - [finish]: 일어났을 때 — 성에 제거·창문 정리, 조명·송풍을 잠들기 전 상태로 되돌림.
 */
class WakeSequenceRunner(
    private val control: CarControl,
    private val listener: Listener,
) {
    interface Listener {
        /** 정각 — 알림·음성·기상 화면 (러너 스레드에서 호출) */
        fun onAlarm()
        fun onStatusChanged(status: WakeSequenceStatus)
    }

    private val thread = HandlerThread("wake-sequence").apply { start() }
    private val handler = Handler(thread.looper)

    // ── 이하 상태는 모두 handler 스레드에서만 접근 ──
    private var active = false
    private var wakeAtMs = 0L
    private var conditions: WakeConditions? = null
    private var steps: List<WakeStep> = emptyList()
    private val started = mutableSetOf<WakeStep>()
    /** 이번 밤에 한 번이라도 실행한 동작 — 스누즈로 시간표가 바뀌어도 유지 (기상 후 정리 기준) */
    private val ranTonight = mutableSetOf<WakeAction>()
    private var items = mutableMapOf<WakeAction, WakeSequenceStatus.Item>()
    private var caps: CarControl.Caps? = null
    private var snap: CarControl.Snapshot? = null
    private var preparing = false

    // 가상 시계 (시연 배속)
    private var speed = 1.0
    private var anchorReal = 0L      // elapsedRealtime
    private var anchorWall = 0L      // 그 순간의 currentTimeMillis

    // 조명 램프
    private var rampLevel: Int? = null
    private var rampInFlight = false
    private var rampStep: WakeStep? = null

    @Volatile var status = WakeSequenceStatus()
        private set

    /** 시퀀스 시간 기준 현재 시각 (ms) */
    fun now(): Long = if (anchorReal == 0L) System.currentTimeMillis()
        else anchorWall + ((SystemClock.elapsedRealtime() - anchorReal) * speed).toLong()

    fun isActive() = active
    fun speed() = speed

    /** [wakeAt] 기상 시퀀스 시작. 이미 같은 기상 시각으로 진행 중이면 무시 */
    fun arm(wakeAt: Long, cond: WakeConditions, speed: Double = 1.0) = handler.post {
        if (active && wakeAt == wakeAtMs) return@post
        if (active) GNCLog.i("[WakeSequence] 기상 시각 변경 → 다시 시작")
        this.speed = speed.coerceAtLeast(1.0)
        anchorReal = SystemClock.elapsedRealtime()
        anchorWall = System.currentTimeMillis()
        active = true
        wakeAtMs = wakeAt
        conditions = cond
        steps = WakeSequence.plan(cond)
        started.clear()
        ranTonight.clear()
        items = steps.associate { it.action to WakeSequenceStatus.Item(it.action, it.atSec, WakeSequenceStatus.State.PENDING) }
            .toMutableMap()
        rampLevel = null
        rampStep = null
        GNCLog.i("[WakeSequence] 시작 — 기상까지 ${(wakeAt - now()) / 1000}초, 배속 ${this.speed}, 바깥 ${cond.outsideC}°C, 여유 배터리 ${cond.batterySparePct}%, 절약 ${WakeSequence.isEco(cond)}")
        GNCLog.i("[WakeSequence] 시간표 — " + steps.joinToString { "${it.action}@${it.atSec}s" })
        publish()
        prepare()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    /** 5분 더 자기: 조명 다시 은은하게 → [minutes]분 뒤 다시 깨움 */
    fun snooze(minutes: Int = 5) = handler.post {
        if (!active) return@post
        val cond = (conditions ?: return@post).copy(snooze = true)
        wakeAtMs = now() + minutes * 60_000L
        steps = WakeSequence.plan(cond)
        started.clear()
        steps.forEach { items[it.action] = WakeSequenceStatus.Item(it.action, it.atSec, WakeSequenceStatus.State.PENDING) }
        rampLevel = null
        rampStep = null
        GNCLog.i("[WakeSequence] 스누즈 ${minutes}분 — 조명 다시 은은하게")
        publish()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    /** 실제 기상 시각(ms, 실제 시계)으로 환산 — 시연 배속일 때 스누즈 화면 표시용 */
    fun realTimeOf(sequenceMs: Long): Long =
        System.currentTimeMillis() + ((sequenceMs - now()) / speed).toLong()

    fun wakeAtMs() = wakeAtMs

    /** 일어났음 — 남은 정리 후 원래 상태로 */
    fun finish() = handler.post { wrapUp("기상 완료") }

    /** 캠핑 종료 등으로 중단 — 이미 바꾼 것만 되돌림 */
    fun cancel() = handler.post { if (active) wrapUp("중단") }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    // ────────────────────────────────────────────────────────────────

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!active) return
            tick()
            if (active) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun prepare() {
        if (caps != null && snap != null || preparing) return
        preparing = true
        control.discover { c ->
            handler.post {
                caps = c
                GNCLog.i("[WakeSequence] 차량 지원 기능 — ${c.summary()}")
                control.snapshot(c) { s ->
                    handler.post {
                        snap = s
                        preparing = false
                        GNCLog.i("[WakeSequence] 원래 상태 저장 — 조명 ${s.ambientBrightness.values} 색 ${s.ambientColor.values.map { Integer.toHexString(it) }} 송풍 ${s.fanSpeed.values} 내기 ${s.recirc.values} 창문 ${s.windowPosition.values}")
                        tick()
                    }
                }
            }
        }
    }

    private fun tick() {
        val c = caps ?: return            // capability 조회 전
        if (snap == null) return
        val sec = (now() - wakeAtMs) / 1000.0
        for (step in steps) {
            if (step in started || step.atSec > sec) continue
            started += step
            ranTonight += step.action
            run(step, c)
        }
        rampStep?.let { updateRamp(c, sec) }
    }

    private fun run(step: WakeStep, c: CarControl.Caps) {
        val cond = conditions ?: return
        val s = snap
        setState(step.action, WakeSequenceStatus.State.RUNNING)
        GNCLog.i("[WakeSequence] ▶ ${step.action} (기상 ${-step.atSec}초 전)")
        val done: (CarOutcome) -> Unit = { o -> handler.post { onOutcome(step.action, o) } }
        when (step.action) {
            WakeAction.DEFROST_ON -> control.defrostOn(c, cond.targetTempC, done)
            WakeAction.MIRROR_HEAT_ON -> control.mirrorHeat(c, true, done)
            WakeAction.STEERING_HEAT_ON -> control.steeringHeat(c, done)
            WakeAction.SEAT_HEAT_ON -> control.seatComfort(c, ventilate = false, onDone = done)
            WakeAction.SEAT_VENT_ON -> control.seatComfort(c, ventilate = true, onDone = done)
            WakeAction.LIGHT_RAMP -> { rampStep = step; rampLevel = null; updateRamp(c, (now() - wakeAtMs) / 1000.0) }
            WakeAction.FRESH_AIR -> control.freshAir(c, s, done)
            WakeAction.WINDOW_CRACK -> control.frontWindows(c, open = true, snap = s, onDone = done)
            WakeAction.WINDOW_CLOSE -> control.frontWindows(c, open = false, snap = s, onDone = done)
            WakeAction.DEFROST_OFF -> control.defrostOff(c, s, done)
            WakeAction.MIRROR_UNFOLD -> control.unfoldMirrors(done)
            WakeAction.ALARM -> {
                listener.onAlarm()
                onOutcome(WakeAction.ALARM, CarOutcome.Done)
            }
        }
    }

    private fun updateRamp(c: CarControl.Caps, sec: Double) {
        if (rampInFlight) return
        val bounds = c.ambientBrightness.values.firstOrNull()
        if (bounds == null && c.ambientColorAreas.isEmpty()) {
            rampStep = null
            onOutcome(WakeAction.LIGHT_RAMP, CarOutcome.Unsupported)
            return
        }
        val progress = WakeSequence.lightProgress(-sec) ?: return
        val level = bounds?.let { WakeSequence.lightLevel(progress, it.minValue, it.maxValue) }
        val first = rampLevel == null
        if (!first && level == rampLevel) {
            if (progress >= 1.0) rampStep = null   // 최대 밝기 도달 → 램프 끝
            return
        }
        rampLevel = level ?: -1
        rampInFlight = true
        val color = WakeSequence.sunriseColor(progress)
        GNCLog.d("[WakeSequence] 조명 ${level ?: "-"} (${"%.0f".format(progress * 100)}%) 색 #${Integer.toHexString(color)}")
        control.ambient(c, level, color, turnOn = first) { o ->
            handler.post {
                rampInFlight = false
                if (first || o !is CarOutcome.Done) onOutcome(WakeAction.LIGHT_RAMP, o)
                if (o !is CarOutcome.Done) rampStep = null   // 실패하면 반복 시도하지 않음
            }
        }
    }

    private fun onOutcome(action: WakeAction, o: CarOutcome) {
        val (state, note) = when (o) {
            CarOutcome.Done -> WakeSequenceStatus.State.DONE to null
            CarOutcome.Unsupported -> WakeSequenceStatus.State.UNSUPPORTED to null
            is CarOutcome.Denied -> WakeSequenceStatus.State.DENIED to o.reason
            is CarOutcome.Failed -> WakeSequenceStatus.State.FAILED to o.reason
        }
        GNCLog.i("[WakeSequence] ■ $action → $o")
        setState(action, state, note)
    }

    private fun setState(action: WakeAction, state: WakeSequenceStatus.State, note: String? = null) {
        val prev = items[action] ?: return
        items[action] = prev.copy(state = state, note = note)
        publish()
    }

    private fun publish() {
        val order = WakeAction.values()
        status = WakeSequenceStatus(
            active = active,
            wakeAtMs = wakeAtMs,
            eco = conditions?.let { WakeSequence.isEco(it) } ?: false,
            outsideC = conditions?.outsideC,
            items = items.values.sortedWith(compareBy({ it.atSec }, { order.indexOf(it.action) })),
        )
        listener.onStatusChanged(status)
    }

    private fun wrapUp(reason: String) {
        val c = caps
        val s = snap
        handler.removeCallbacks(tickRunnable)
        rampStep = null
        if (c != null && s != null) {
            val ran = ranTonight.toSet()
            val log: (String) -> (CarOutcome) -> Unit = { what -> { o -> GNCLog.i("[WakeSequence] 정리 · $what → $o") } }
            if (WakeAction.DEFROST_ON in ran && WakeAction.DEFROST_OFF !in ran) control.defrostOff(c, s, log("성에 제거 끄기"))
            if (WakeAction.WINDOW_CRACK in ran && WakeAction.WINDOW_CLOSE !in ran) control.frontWindows(c, false, s, log("창문 닫기"))
            if (WakeAction.MIRROR_HEAT_ON in ran) control.mirrorHeat(c, false, log("미러 열선 끄기"))
            if (WakeAction.FRESH_AIR in ran) control.restoreAir(c, s, log("내외기·송풍 복원"))
            if (WakeAction.LIGHT_RAMP in ran) control.restoreAmbient(s, log("조명 복원"))
        }
        active = false
        snap = null          // 다음 밤엔 그때 상태를 다시 저장
        anchorReal = 0L
        speed = 1.0
        GNCLog.i("[WakeSequence] 종료 ($reason)")
        publish()
    }

    companion object {
        const val TICK_MS = 1000L
    }
}
