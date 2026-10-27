package com.msrcevirici.msr.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.msrcevirici.msr.pipeline.Box
import com.msrcevirici.msr.pipeline.OverlayItem

/**
 * Çeviri kutularını ve ölçüm göstergesini (HUD) çizer. Kutular ekran koordinatlarında
 * gelir; pencere ekranın (0,0) noktasında başlamıyorsa aradaki fark burada düzeltilir.
 */
class OverlayView(context: Context, private val hudBox: Box) : View(context) {

    private val density = resources.displayMetrics.density
    private val minTextPx = 9f * resources.displayMetrics.scaledDensity
    private val maxTextPx = 18f * resources.displayMetrics.scaledDensity
    private val padding = 2f * density
    private val corner = 4f * density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 28) }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 90, 60) }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hudTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private val location = IntArray(2)
    private val rect = RectF()
    private var items: List<OverlayItem> = emptyList()
    private var hud: String = ""

    fun update(items: List<OverlayItem>, hud: String) {
        this.items = items
        this.hud = hud
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(location)
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        for (item in items) drawItem(canvas, item)
        drawHud(canvas)
        canvas.restore()
    }

    private fun drawItem(canvas: Canvas, item: OverlayItem) {
        val b = item.box
        val innerW = (b.width - 2 * padding).toInt().coerceAtLeast(1)
        val innerH = b.height - 2 * padding
        // Satır yüksekliği kadar başla, sığana kadar küçült.
        var size = (b.height * 0.75f).coerceIn(minTextPx, maxTextPx)
        var layout = buildLayout(item.text, innerW, size)
        while (layout.height > innerH && size > minTextPx) {
            size = (size * 0.9f).coerceAtLeast(minTextPx)
            layout = buildLayout(item.text, innerW, size)
        }
        // Yine sığmadıysa kutuyu aşağı doğru büyüt; metni kesmektense taşırmak daha okunur.
        val h = maxOf(b.height.toFloat(), layout.height + 2 * padding)
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + h)
        canvas.drawRoundRect(rect, corner, corner, boxPaint)
        canvas.save()
        canvas.translate(b.left + padding, b.top + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, width: Int, size: Float): StaticLayout {
        textPaint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun drawHud(canvas: Canvas) {
        if (hud.isEmpty()) return
        rect.set(hudBox.left.toFloat(), hudBox.top.toFloat(), hudBox.right.toFloat(), hudBox.bottom.toFloat())
        canvas.drawRoundRect(rect, corner, corner, hudPaint)
        val y = rect.centerY() - (hudTextPaint.descent() + hudTextPaint.ascent()) / 2
        canvas.drawText(hud, rect.left + 3 * padding, y, hudTextPaint)
    }
}
