package com.msrcevirici.msr.pipeline

/**
 * Piksel koordinatlarında dikdörtgen. android.graphics.Rect yerine kullanılır,
 * böylece pipeline mantığı Android'siz JVM testlerinde çalışır.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersects(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun scaled(factor: Float): Box = Box(
        (left * factor).toInt(),
        (top * factor).toInt(),
        (right * factor).toInt(),
        (bottom * factor).toInt(),
    )
}

/** OCR'ın bulduğu, henüz çevrilmemiş metin bloğu (ekran koordinatları). */
data class Detection(val box: Box, val text: String)

/** Overlay'de çizilen çeviri kutusu (ekran koordinatları). */
data class OverlayItem(val box: Box, val source: String, val text: String)
