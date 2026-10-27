package com.msrcevirici.msr.pipeline

/**
 * Hangi OCR sonuçlarının yeni kutu olacağına karar verir.
 *
 * Kendi kutularımızın altındaki metni artık göremiyoruz (FLAG_SECURE ile siyah,
 * aksi halde kendi Türkçe çevirimiz). Bu yüzden mevcut kutularla veya maskelenen
 * alanlarla çakışan algılamalar atılır; mevcut kutular büyük bir ekran değişimine
 * (kaydırma, sayfa geçişi) kadar korunur.
 */
object BoxTracker {

    fun newDetections(occupied: List<Box>, detected: List<Detection>, minHeight: Int): List<Detection> =
        detected.filter { d ->
            !d.box.isEmpty &&
                d.box.height >= minHeight &&
                isTranslatable(d.text) &&
                occupied.none { it.intersects(d.box) }
        }

    /** Fiyat, saat gibi harf içermeyen metinler çevrilmez. */
    fun isTranslatable(text: String): Boolean = text.any { it.isLetter() }
}
