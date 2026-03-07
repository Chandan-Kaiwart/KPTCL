package com.apc.kptcl.home.users.dashboard

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Simple custom PieChartView to show Full / Partial / Missing day distribution.
 * No external library needed.
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var fullCount = 0
    private var partialCount = 0
    private var missingCount = 0

    private val paintFull = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")   // Green
        style = Paint.Style.FILL
    }
    private val paintPartial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107")   // Yellow
        style = Paint.Style.FILL
    }
    private val paintMissing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F44336")   // Red
        style = Paint.Style.FILL
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
        isFakeBoldText = true
    }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val oval = RectF()

    fun setData(fullCount: Int, partialCount: Int, missingCount: Int) {
        this.fullCount = fullCount
        this.partialCount = partialCount
        this.missingCount = missingCount
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val total = fullCount + partialCount + missingCount
        if (total == 0) {
            // Draw empty circle
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) - 8f
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            paintStroke.color = Color.parseColor("#E0E0E0")
            canvas.drawOval(oval, paintStroke)
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - 8f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val fullAngle = (fullCount.toFloat() / total) * 360f
        val partialAngle = (partialCount.toFloat() / total) * 360f
        val missingAngle = 360f - fullAngle - partialAngle

        var startAngle = -90f

        // Draw Full slice
        if (fullAngle > 0) {
            canvas.drawArc(oval, startAngle, fullAngle, true, paintFull)
            drawSliceLabel(canvas, cx, cy, radius, startAngle, fullAngle, "$fullCount\nFull")
            startAngle += fullAngle
        }

        // Draw Partial slice
        if (partialAngle > 0) {
            canvas.drawArc(oval, startAngle, partialAngle, true, paintPartial)
            drawSliceLabel(canvas, cx, cy, radius, startAngle, partialAngle, "$partialCount\nPartial")
            startAngle += partialAngle
        }

        // Draw Missing slice
        if (missingAngle > 0) {
            canvas.drawArc(oval, startAngle, missingAngle, true, paintMissing)
            drawSliceLabel(canvas, cx, cy, radius, startAngle, missingAngle, "$missingCount\nMissing")
        }

        // White border on top
        canvas.drawArc(oval, 0f, 360f, false, paintStroke.also {
            it.color = Color.WHITE
            it.strokeWidth = 2f
        })
    }

    private fun drawSliceLabel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        startAngle: Float,
        sweepAngle: Float,
        label: String
    ) {
        if (sweepAngle < 20f) return   // Too small to label

        val midAngle = Math.toRadians((startAngle + sweepAngle / 2).toDouble())
        val labelRadius = radius * 0.65f
        val lx = (cx + labelRadius * Math.cos(midAngle)).toFloat()
        val ly = (cy + labelRadius * Math.sin(midAngle)).toFloat()

        val lines = label.split("\n")
        val lineHeight = paintText.textSize + 4f
        val startY = ly - (lines.size - 1) * lineHeight / 2

        lines.forEachIndexed { i, line ->
            canvas.drawText(line, lx, startY + i * lineHeight, paintText)
        }
    }
}
