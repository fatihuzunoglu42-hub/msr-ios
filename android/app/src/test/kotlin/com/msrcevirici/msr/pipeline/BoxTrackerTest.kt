package com.msrcevirici.msr.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxTrackerTest {

    @Test
    fun newDetections_doluAlanlaCakisaniAtar() {
        val occupied = listOf(Box(0, 0, 100, 50))
        val detected = listOf(
            Detection(Box(10, 10, 90, 40), "Kendi çevirimiz"),
            Detection(Box(0, 60, 100, 90), "Place order"),
        )
        val result = BoxTracker.newDetections(occupied, detected, minHeight = 5)
        assertEquals(listOf("Place order"), result.map { it.text })
    }

    @Test
    fun newDetections_kucukVeHarfsizMetniAtar() {
        val detected = listOf(
            Detection(Box(0, 0, 50, 3), "tiny"),
            Detection(Box(0, 10, 50, 30), "$12.99"),
            Detection(Box(0, 40, 50, 60), "Total"),
        )
        val result = BoxTracker.newDetections(emptyList(), detected, minHeight = 5)
        assertEquals(listOf("Total"), result.map { it.text })
    }

    @Test
    fun isTranslatable_turkceHarfleriTanir() {
        assertTrue(BoxTracker.isTranslatable("ğ"))
        assertFalse(BoxTracker.isTranslatable("12:30 — 4.5"))
    }

    @Test
    fun box_kesisimKenardaDegil() {
        assertFalse(Box(0, 0, 10, 10).intersects(Box(10, 0, 20, 10)))
        assertTrue(Box(0, 0, 10, 10).intersects(Box(9, 9, 20, 20)))
    }

    @Test
    fun translationCache_enEskiyiCikarir() {
        val cache = TranslationCache(maxSize = 2)
        cache["a"] = "A"
        cache["b"] = "B"
        cache["a"] // a son kullanılan olur
        cache["c"] = "C"
        assertNull(cache["b"])
        assertEquals("A", cache["a"])
        assertEquals(2, cache.size)
    }
}
