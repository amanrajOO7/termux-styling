package com.termux.styling

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A row of rounded color swatches, used to show the palette of a color scheme before it is applied.
 */
class ColorBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var density = resources.displayMetrics.density
    private var colors: IntArray = IntArray(0)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.color_bar_description)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        density = context.resources.displayMetrics.density
    }

    /** Show these colors from left to right. */
    fun setColors(values: IntArray) {
        if (colors.contentEquals(values)) return
        colors = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = colors.size
        if (count == 0) return

        val gap = 3 * density
        val totalGaps = gap * (count - 1)
        if (width <= totalGaps) return

        val cellWidth = (width - totalGaps) / count
        if (cellWidth <= 0f) return

        val radius = 6 * density
        for (i in 0 until count) {
            val left = i * (cellWidth + gap)
            rect.set(left, 0f, left + cellWidth, height.toFloat())
            paint.color = colors[i]
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
