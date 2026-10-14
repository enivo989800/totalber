package com.build.lotteryai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/** Lightweight native Canvas bar chart for descriptive historical frequencies. */
class FrequencyBarChartView(
    context: Context,
    private val chartTitle: String,
    labels: List<String>,
    values: List<Int>,
    barColor: Int
) : View(context) {
    private val chartLabels = labels.take(minOf(labels.size, values.size))
    private val chartValues = values.take(chartLabels.size).map { it.coerceAtLeast(0) }
    private val densityScale = resources.displayMetrics.density
    private fun dp(value: Float): Float = value * densityScale

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF163465.toInt()
        textSize = dp(13f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE5EDF8.toInt()
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor; style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF657EA5.toInt()
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF314565.toInt()
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }

    init {
        contentDescription = if (chartValues.isEmpty()) "$chartTitle：暂无数据" else
            "$chartTitle：" + chartLabels.zip(chartValues).joinToString("，") { "${it.first} ${it.second} 次" }
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = dp(166f).toInt()
        setMeasuredDimension(
            resolveSize(dp(320f).toInt(), widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(8f)
        val right = width.toFloat() - dp(8f)
        canvas.drawText(chartTitle, left, dp(16f), titlePaint)
        if (chartValues.isEmpty() || right <= left) {
            canvas.drawText("暂无可绘制数据", left, dp(46f), labelPaint.apply { textAlign = Paint.Align.LEFT })
            labelPaint.textAlign = Paint.Align.CENTER
            return
        }
        val top = dp(31f)
        val bottom = height.toFloat() - dp(24f)
        val plotHeight = max(dp(20f), bottom - top)
        val maxValue = max(1, chartValues.maxOrNull() ?: 1)
        for (line in 0..3) {
            val y = top + plotHeight * line / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val slot = (right - left) / chartValues.size.toFloat()
        val barWidth = minOf(dp(18f), slot * 0.62f)
        chartValues.forEachIndexed { index, value ->
            val centerX = left + slot * (index + 0.5f)
            val barHeight = plotHeight * value.toFloat() / maxValue.toFloat()
            val barTop = bottom - barHeight
            canvas.drawRoundRect(RectF(centerX - barWidth / 2f, barTop, centerX + barWidth / 2f, bottom), dp(3f), dp(3f), barPaint)
            val valueY = max(top + dp(10f), barTop - dp(3f))
            canvas.drawText(value.toString(), centerX, valueY, valuePaint)
            canvas.drawText(chartLabels[index], centerX, height.toFloat() - dp(6f), labelPaint)
        }
    }
}
