package com.msrcevirici.msr.pipeline

import kotlin.math.abs

/**
 * Kareyi cols x rows ızgaralık parlaklık değerlerine indirger ve iki kare arasındaki
 * değişimi ölçer. Kendi kutularımızın kapladığı karolar maskelenir; aksi halde
 * overlay'in kendisi değişim sayılır ve sonsuz OCR döngüsü oluşur.
 */
object FrameSignature {

    /** ARGB pikselleri 0..255 parlaklığa çevirir. */
    fun luminance(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000
    }

    /** Verilen kutularla kesişen karolar için true. */
    fun maskTiles(boxes: List<Box>, frameW: Int, frameH: Int, cols: Int, rows: Int): BooleanArray {
        val mask = BooleanArray(cols * rows)
        if (frameW <= 0 || frameH <= 0) return mask
        for (b in boxes) {
            if (b.isEmpty) continue
            val c0 = (b.left.coerceAtLeast(0) * cols / frameW).coerceIn(0, cols - 1)
            val c1 = ((b.right - 1).coerceAtLeast(0) * cols / frameW).coerceIn(0, cols - 1)
            val r0 = (b.top.coerceAtLeast(0) * rows / frameH).coerceIn(0, rows - 1)
            val r1 = ((b.bottom - 1).coerceAtLeast(0) * rows / frameH).coerceIn(0, rows - 1)
            for (r in r0..r1) for (c in c0..c1) mask[r * cols + c] = true
        }
        return mask
    }

    /** Maskelenmemiş karolardan parlaklığı [threshold]'dan fazla değişenlerin oranı (0..1). */
    fun changedFraction(prev: IntArray, cur: IntArray, mask: BooleanArray, threshold: Int = 12): Float {
        require(prev.size == cur.size && cur.size == mask.size) { "Boyutlar eşleşmiyor" }
        var considered = 0
        var changed = 0
        for (i in cur.indices) {
            if (mask[i]) continue
            considered++
            if (abs(cur[i] - prev[i]) > threshold) changed++
        }
        return if (considered == 0) 0f else changed.toFloat() / considered
    }
}
