package com.example.pleostest.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.pleostest.global.GlobalFunction.BatteryBudgetResult

class BatteryBudgetChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var result: BatteryBudgetResult? = null
    private val path = Path()

    // 예상 잔량 — #5A46FA
    private val forecastFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 90, 70, 250)
        style = Paint.Style.FILL
    }
    private val forecastLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 70, 250)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 70, 250)
        style = Paint.Style.FILL
    }
    private val pctLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 70, 250)
        typeface = Typeface.DEFAULT_BOLD
    }

    // 집에 갈 몫 — #FE3D16
    private val reserveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 254, 61, 22)
        style = Paint.Style.FILL
    }
    private val reserveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(254, 61, 22)
        style = Paint.Style.STROKE
    }
    private val reserveLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(254, 61, 22)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.RIGHT
    }
    private val reserveBandTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(254, 61, 22)
    }

    // 시간 레이블 — text_tertiary rgba(19,20,23,0.64)
    private val timeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(163, 19, 20, 23)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val d = resources.displayMetrics.density
        @Suppress("DEPRECATION")
        val s = resources.displayMetrics.scaledDensity
        forecastLinePaint.strokeWidth = 3f * d
        reserveLinePaint.strokeWidth = 2f * d
        val pctSp = 24f * s
        val labelSp = 20f * s
        pctLabelPaint.textSize = pctSp
        reserveLabelPaint.textSize = pctSp
        reserveBandTextPaint.textSize = labelSp
        timeLabelPaint.textSize = labelSp
    }

    fun setData(r: BatteryBudgetResult) {
        result = r
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = result ?: return
        val d = resources.displayMetrics.density
        @Suppress("DEPRECATION")
        val s = resources.displayMetrics.scaledDensity

        val cLeft = 16f * d
        val cRight = width.toFloat() - 16f * d
        val cTop = 40f * d
        val cBottom = height.toFloat() - 50f * d
        val cH = cBottom - cTop

        // 동적 Y 범위 — 관련 구간만 확대
        val yMin = (r.reservePct - 10).coerceAtLeast(0)
        val yMax = (r.currentPct + 5).coerceAtMost(100)
        val yRange = (yMax - yMin).coerceAtLeast(1)

        fun pctY(pct: Int) = cBottom - ((pct - yMin) / yRange.toFloat()) * cH

        val startY = pctY(r.currentPct)
        val endY   = pctY(r.morningPct)
        val reserveY = pctY(r.reservePct)

        // 1. reserve 영역 채우기 (차트 바닥 ~ reserve선)
        canvas.drawRect(cLeft, reserveY, cRight, cBottom, reserveFillPaint)

        // 2. 예상 잔량 영역 채우기 (reserve선 ~ 배터리 라인)
        val fillEndY = endY.coerceAtMost(reserveY)
        path.reset()
        path.moveTo(cLeft, startY)
        path.lineTo(cRight, fillEndY)
        path.lineTo(cRight, reserveY)
        path.lineTo(cLeft, reserveY)
        path.close()
        canvas.drawPath(path, forecastFillPaint)

        // 3. reserve 선
        canvas.drawLine(cLeft, reserveY, cRight, reserveY, reserveLinePaint)

        // 4. reserve 밴드 텍스트
        val marginPct = r.reservePct - r.neededForHomePct
        val bandText = "집까지 ${r.neededForHomePct}% + 여유 ${marginPct}%  ·  이 아래는 쓰지 않아요"
        canvas.drawText(bandText, cLeft + 10f * d, reserveY + 18f * s, reserveBandTextPaint)

        // 5. 배터리 라인
        canvas.drawLine(cLeft, startY, cRight, endY, forecastLinePaint)

        // 6. 시작·끝 도트
        val dotR = 7f * d
        canvas.drawCircle(cLeft, startY, dotR, dotPaint)
        canvas.drawCircle(cRight, endY, dotR, dotPaint)

        // 7. % 레이블 (도트 위)
        val halfText = pctLabelPaint.textSize * 0.35f
        pctLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${r.currentPct}%", cLeft, startY - 14f * d, pctLabelPaint)
        pctLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("${r.morningPct}%", cRight, endY - 14f * d, pctLabelPaint)

        // 8. reserve % 레이블 (reserve선 왼쪽, 도트와 겹치지 않는 경우만)
        if (Math.abs(reserveY - endY) > 30f * d && Math.abs(reserveY - startY) > 30f * d) {
            canvas.drawText("${r.reservePct}%", cLeft + 60f * d, reserveY - 8f * d, reserveLabelPaint)
        }

        // 9. 시간 레이블 (시작 + 중간 2개 + 끝)
        val timeY = cBottom + 30f * d
        val duration = if (r.wakeTimeMin > r.startTimeMin)
            r.wakeTimeMin - r.startTimeMin
        else
            24 * 60 - r.startTimeMin + r.wakeTimeMin

        val span = cRight - cLeft
        timeLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(minToHhmm(r.startTimeMin), cLeft, timeY, timeLabelPaint)
        timeLabelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(minToHhmm((r.startTimeMin + duration / 3) % (24 * 60)), cLeft + span / 3f, timeY, timeLabelPaint)
        canvas.drawText(minToHhmm((r.startTimeMin + duration * 2 / 3) % (24 * 60)), cLeft + span * 2f / 3f, timeY, timeLabelPaint)
        timeLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(minToHhmm(r.wakeTimeMin), cRight, timeY, timeLabelPaint)
    }

    private fun minToHhmm(min: Int): String {
        val h = (min / 60) % 24
        val m = min % 60
        return String.format("%02d:%02d", h, m)
    }
}
