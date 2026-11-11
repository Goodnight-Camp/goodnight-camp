package com.example.pleostest.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.pleostest.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 수면 화면의 은은한 모닥불 — 코드로 그려 에셋·라이선스가 없고, 밝기·색은 테마 토큰을 따른다.
 *
 * - 3겹 불꽃(바깥 주황 → 안쪽 크림색)이 겹친 사인파 노이즈로 일렁이고, 불씨가 위로 떠오른다.
 * - [glow]: 전체 밝기 상한 (밤 화면 눈부심 방지, 기본 0.55)
 * - [intensity]: 불 크기 0..1 — 기상 시퀀스가 시작되면 키워 "곧 깨운다"는 신호로 쓴다.
 * - 화면에 보일 때만 약 20fps로 돌고, 시스템 애니메이션이 꺼져 있으면 정지 화면만 그린다 (DESIGN.md reduced_motion).
 */
class CampfireView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var glow = 0.55f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var intensity = 0.6f
        set(value) { targetIntensity = value.coerceIn(0f, 1f) }

    private var targetIntensity = intensity
    private var shownIntensity = intensity

    private val warm = ContextCompat.getColor(context, R.color.regulation_orange)
    private val sun = ContextCompat.getColor(context, R.color.sunrise_sun)
    private val deep = 0xFFFF6A1E.toInt()
    private val core = 0xFFFFF1D6.toInt()
    private val log = 0xFF3A2A22.toInt()

    private val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = log }
    private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private data class Ember(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val size: Float)
    private val embers = ArrayList<Ember>()
    private val random = Random(7)

    private var running = false
    private var lastFrame = 0L
    private val startTime = SystemClock.uptimeMillis()

    private val reducedMotion: Boolean
        get() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimationDelayed(this, FRAME_MS)
        }
    }

    // ── 생명주기: 보일 때만 돈다 ──

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); updateRunning()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility); updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && isShown && windowVisibility == VISIBLE && !reducedMotion
        if (shouldRun && !running) {
            running = true
            lastFrame = SystemClock.uptimeMillis()
            post(frame)
        } else if (!shouldRun) {
            stop()
            invalidate()   // 정지 화면 한 장
        }
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
    }

    // ── 그리기 ──

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val now = SystemClock.uptimeMillis()
        val t = (now - startTime) / 1000f
        val dt = min((now - lastFrame) / 1000f, 0.1f)
        lastFrame = now

        // 크기 변화는 부드럽게
        shownIntensity += (targetIntensity - shownIntensity) * min(1f, dt * 1.5f)
        val k = 0.55f + 0.45f * shownIntensity
        val a = glow

        val cx = w / 2f
        val baseY = h * 0.84f
        val fireH = h * 0.62f * k
        val fireW = w * 0.30f * (0.8f + 0.2f * k)

        // 1) 바닥 빛 번짐
        val flicker = 0.85f + 0.15f * noise(t * 3.1f, 1.7f)
        glowPaint.shader = RadialGradient(cx, baseY, w * 0.48f * k,
            ColorUtils.setAlphaComponent(warm, (90 * a * flicker).toInt()),
            ColorUtils.setAlphaComponent(warm, 0), Shader.TileMode.CLAMP)
        canvas.drawOval(cx - w * 0.48f, baseY - h * 0.16f, cx + w * 0.48f, baseY + h * 0.16f, glowPaint)

        // 2) 장작 두 개 (엇갈림)
        logPaint.alpha = (255 * min(1f, a + 0.25f)).toInt()
        drawLog(canvas, cx, baseY + h * 0.03f, w * 0.62f, h * 0.075f, -12f)
        drawLog(canvas, cx, baseY + h * 0.03f, w * 0.62f, h * 0.075f, 12f)

        // 3) 불꽃 3겹 — 바깥(진한 주황) → 중간(주황) → 안쪽(크림)
        drawFlame(canvas, cx, baseY, fireW, fireH, t, 0f, deep, warm, 0.70f * a)
        drawFlame(canvas, cx, baseY, fireW * 0.72f, fireH * 0.80f, t, 1.3f, warm, sun, 0.85f * a)
        drawFlame(canvas, cx, baseY, fireW * 0.40f, fireH * 0.50f, t, 2.6f, sun, core, 0.95f * a)

        // 4) 불씨
        if (running) updateEmbers(dt, cx, baseY, fireW, fireH, k)
        for (e in embers) {
            emberPaint.color = ColorUtils.setAlphaComponent(sun, (230 * a * e.life).toInt())
            canvas.drawCircle(e.x, e.y, e.size * (0.6f + 0.4f * e.life), emberPaint)
        }
    }

    private fun drawLog(canvas: Canvas, cx: Float, cy: Float, len: Float, thick: Float, deg: Float) {
        canvas.save()
        canvas.rotate(deg, cx, cy)
        rect.set(cx - len / 2, cy - thick / 2, cx + len / 2, cy + thick / 2)
        canvas.drawRoundRect(rect, thick / 2, thick / 2, logPaint)
        canvas.restore()
    }

    /** 바닥이 둥글고 끝이 뾰족한 물방울 모양을 노이즈로 흔든다 */
    private fun drawFlame(
        canvas: Canvas, cx: Float, baseY: Float, fw: Float, fh: Float,
        t: Float, seed: Float, bottomColor: Int, topColor: Int, alpha: Float,
    ) {
        val sway = noise(t * 1.6f, seed) * fw * 0.35f            // 끝이 좌우로
        val stretch = 1f + 0.12f * noise(t * 2.7f, seed + 5f)     // 키가 들썩
        val tipX = cx + sway
        val tipY = baseY - fh * stretch
        val bulge = fw * (1f + 0.08f * noise(t * 3.3f, seed + 9f))
        val l1 = noise(t * 2.2f, seed + 2f) * fw * 0.25f
        val r1 = noise(t * 2.0f, seed + 3f) * fw * 0.25f

        path.reset()
        path.moveTo(cx - bulge * 0.55f, baseY)
        path.cubicTo(cx - bulge, baseY - fh * 0.35f, tipX - fw * 0.35f + l1, baseY - fh * 0.70f, tipX, tipY)
        path.cubicTo(tipX + fw * 0.35f + r1, baseY - fh * 0.70f, cx + bulge, baseY - fh * 0.35f, cx + bulge * 0.55f, baseY)
        path.quadTo(cx, baseY + fh * 0.06f, cx - bulge * 0.55f, baseY)
        path.close()

        val ia = (255 * alpha.coerceIn(0f, 1f)).toInt()
        flamePaint.shader = LinearGradient(0f, baseY, 0f, tipY,
            ColorUtils.setAlphaComponent(bottomColor, ia),
            ColorUtils.setAlphaComponent(topColor, (ia * 0.85f).toInt()), Shader.TileMode.CLAMP)
        canvas.drawPath(path, flamePaint)
    }

    private fun updateEmbers(dt: Float, cx: Float, baseY: Float, fw: Float, fh: Float, k: Float) {
        // 초당 약 3~6개 생성
        if (random.nextFloat() < dt * (3f + 3f * k) && embers.size < MAX_EMBERS) {
            embers += Ember(
                x = cx + (random.nextFloat() - 0.5f) * fw * 0.8f,
                y = baseY - fh * 0.4f,
                vx = (random.nextFloat() - 0.5f) * fw * 0.4f,
                vy = -(fh * (0.5f + random.nextFloat() * 0.6f)),
                life = 1f,
                size = max(1.5f, fw * (0.015f + random.nextFloat() * 0.02f)),
            )
        }
        val it = embers.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.x += e.vx * dt
            e.y += e.vy * dt
            e.vx += (random.nextFloat() - 0.5f) * fw * 0.6f * dt
            e.life -= dt * 0.7f
            if (e.life <= 0f || e.y < 0f) it.remove()
        }
    }

    /** 결이 다른 사인파 세 개의 합 — 반복이 눈에 띄지 않는 -1..1 노이즈 */
    private fun noise(x: Float, seed: Float): Float {
        val v = sin(x + seed) * 0.5f +
            sin(x * 1.73f + seed * 2.1f) * 0.3f +
            sin(x * 2.91f + seed * 0.7f + PI.toFloat() / 3) * 0.2f
        return v
    }

    companion object {
        private const val FRAME_MS = 50L      // 약 20fps — 밤새 켜져 있어도 가볍게
        private const val MAX_EMBERS = 14
    }
}
