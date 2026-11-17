/*
 * Kreslení stopy gesta převzaté z Blackline Keyboard (vychází z AOSP LatinIME a HeliBoard/LeanType).
 * Copyright (C) 2012–2013 The Android Open Source Project, upraveno
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package cz.pekey

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Stopa gesta jako v Blacklinu: u prstu široká a ke konci užší (zaoblené čáry);
 * po krátké prodlevě body postupně blednou a mizí.
 */
class GestureTrail(private val density: Float) {

    /** Šířka u prstu a na konci stopy. */
    var startWidth = 10f * density
    var endWidth = 2.5f * density

    /** Za jak dlouho začne bod blednout a jak dlouho bledne (ms). */
    var fadeoutDelay = 100
    var fadeoutDuration = 800
    private val linger get() = fadeoutDelay + fadeoutDuration

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    var color: Int = 0xFF2D7BFF.toInt()

    /** Body blíž než tohle se slučují (méně čar = plynulé kreslení). */
    private val minStep = 3f * density

    /**
     * Nakreslí stopu z bodů (časy v ms od začátku tahu). Vrací true, dokud je co kreslit.
     * Každý úsek je čára se zaoblenými konci; šířka a průhlednost podle stáří bodu.
     */
    fun draw(canvas: Canvas, xs: FloatArray, ys: FloatArray, ts: IntArray, n: Int, sinceBase: Int): Boolean {
        if (n < 2) return false
        var start = 0
        while (start < n && sinceBase - ts[start] >= linger) start++
        if (start >= n - 1) return false
        paint.color = color
        var px = xs[start]
        var py = ys[start]
        for (i in start + 1 until n) {
            val x = xs[i]
            val y = ys[i]
            if (i < n - 1 && Math.abs(x - px) + Math.abs(y - py) < minStep) continue
            val elapsed = sinceBase - ts[i]
            paint.strokeWidth = width(elapsed)
            paint.alpha = alpha(elapsed)
            canvas.drawLine(px, py, x, y, paint)
            px = x
            py = y
        }
        return true
    }

    private fun alpha(elapsed: Int): Int {
        val full = Color.alpha(color)
        if (elapsed < fadeoutDelay) return full
        val dec = full * (elapsed - fadeoutDelay) / fadeoutDuration
        return (full - dec).coerceIn(0, full)
    }

    private fun width(elapsed: Int): Float {
        val delta = startWidth - endWidth
        return (startWidth - delta * elapsed / linger).coerceAtLeast(endWidth)
    }
}
