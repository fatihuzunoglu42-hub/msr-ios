package com.msrcevirici.msr.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSignatureTest {

    @Test
    fun luminance_beyazVeSiyah() {
        val lum = FrameSignature.luminance(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        assertArrayEquals(intArrayOf(255, 0), lum)
    }

    @Test
    fun maskTiles_kutununKapladigiKarolariIsaretler() {
        // 100x100 kare, 4x4 ızgara → her karo 25x25.
        val mask = FrameSignature.maskTiles(listOf(Box(0, 0, 30, 20)), 100, 100, 4, 4)
        assertTrue(mask[0])
        assertTrue(mask[1]) // 30 piksel ikinci sütuna taşar
        assertFalse(mask[2])
        assertFalse(mask[4]) // ikinci satır
    }

    @Test
    fun maskTiles_ekranDisiKutuyuSinirlar() {
        val mask = FrameSignature.maskTiles(listOf(Box(-50, 90, 500, 300)), 100, 100, 4, 4)
        for (c in 0 until 4) assertTrue(mask[3 * 4 + c])
        assertFalse(mask[0])
    }

    @Test
    fun maskTiles_bosKutuyuAtlar() {
        val mask = FrameSignature.maskTiles(listOf(Box(10, 10, 10, 50)), 100, 100, 4, 4)
        assertFalse(mask.any { it })
    }

    @Test
    fun changedFraction_maskeliKaroyuSaymaz() {
        val prev = intArrayOf(0, 0, 0, 0)
        val cur = intArrayOf(200, 200, 0, 0)
        val mask = booleanArrayOf(true, false, false, false)
        assertEquals(1f / 3f, FrameSignature.changedFraction(prev, cur, mask), 1e-6f)
    }

    @Test
    fun changedFraction_esikAltiDegisimiYokSayar() {
        val prev = intArrayOf(100, 100)
        val cur = intArrayOf(105, 112)
        assertEquals(0f, FrameSignature.changedFraction(prev, cur, BooleanArray(2)), 0f)
    }

    @Test
    fun changedFraction_hepsiMaskeliyseSifir() {
        val mask = booleanArrayOf(true, true)
        assertEquals(0f, FrameSignature.changedFraction(intArrayOf(0, 0), intArrayOf(255, 255), mask), 0f)
    }
}
