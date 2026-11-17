/*
 * Kreslení stopy gesta převzaté z Blackline Keyboard (vychází z AOSP LatinIME a HeliBoard/LeanType).
 * Copyright (C) 2012–2013 The Android Open Source Project, upraveno
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package cz.pekey

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Stopa gesta jako v Blacklinu: plné zaoblené úseky, u prstu široké a ke konci užší;
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

    private val roundedLine = RoundedLine()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var color: Int = 0xFF2D7BFF.toInt()

    /**
     * Nakreslí stopu z bodů (časy v ms od [base]). Vrací true, dokud je co kreslit.
     */
    fun draw(canvas: Canvas, xs: FloatArray, ys: FloatArray, ts: IntArray, n: Int, sinceBase: Int): Boolean {
        if (n < 2) return false
        var start = 0
        while (start < n && sinceBase - ts[start] >= linger) start++
        if (start >= n - 1) return false
        paint.color = color
        var p1x = xs[start]
        var p1y = ys[start]
        var r1 = width(sinceBase - ts[start]) / 2f
        for (i in start + 1 until n) {
            val elapsed = sinceBase - ts[i]
            val p2x = xs[i]
            val p2y = ys[i]
            val r2 = width(elapsed) / 2f
            val path = roundedLine.makePath(p1x, p1y, r1, p2x, p2y, r2)
            if (!path.isEmpty) {
                paint.alpha = alpha(elapsed)
                canvas.drawPath(path, paint)
            }
            p1x = p2x
            p1y = p2y
            r1 = r2
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

    /** Úsek mezi dvěma kruhy různých poloměrů spojený tečnami (z AOSP). */
    private class RoundedLine {
        private val arc1 = RectF()
        private val arc2 = RectF()
        private val path = Path()

        fun makePath(p1x: Float, p1y: Float, r1: Float, p2x: Float, p2y: Float, r2: Float): Path {
            path.rewind()
            val dx = (p2x - p1x).toDouble()
            val dy = (p2y - p1y).toDouble()
            val l = hypot(dx, dy)
            if (l == 0.0) return path
            val dr = (r2 - r1).toDouble()
            if (kotlin.math.abs(dr) >= l) return path
            val a = atan2(dy, dx)
            val ar = asin(dr / l)
            val aa = a - (RIGHT_ANGLE + ar)
            val ab = a + (RIGHT_ANGLE + ar)
            val cosa = cos(aa).toFloat()
            val sina = sin(aa).toFloat()
            val cosb = cos(ab).toFloat()
            val sinb = sin(ab).toFloat()
            val p1ax = p1x + r1 * cosa
            val p1ay = p1y + r1 * sina
            val p1bx = p1x + r1 * cosb
            val p1by = p1y + r1 * sinb
            val p2ax = p2x + r2 * cosa
            val p2ay = p2y + r2 * sina
            val p2bx = p2x + r2 * cosb
            val p2by = p2y + r2 * sinb
            val angle = (aa * RADIAN_TO_DEGREE).toFloat()
            val ar2 = (ar * 2.0 * RADIAN_TO_DEGREE).toFloat()
            arc1.set(p1x - r1, p1y - r1, p1x + r1, p1y + r1)
            arc2.set(p2x - r2, p2y - r2, p2x + r2, p2y + r2)
            path.moveTo(p1x, p1y)
            path.arcTo(arc1, angle, -180f + ar2)
            path.moveTo(p2x, p2y)
            path.arcTo(arc2, angle, 180f + ar2)
            path.moveTo(p1ax, p1ay)
            path.lineTo(p1x, p1y)
            path.lineTo(p1bx, p1by)
            path.lineTo(p2bx, p2by)
            path.lineTo(p2x, p2y)
            path.lineTo(p2ax, p2ay)
            path.close()
            return path
        }

        companion object {
            private const val RIGHT_ANGLE = PI / 2.0
            private const val RADIAN_TO_DEGREE = 180.0 / PI
        }
    }
}
