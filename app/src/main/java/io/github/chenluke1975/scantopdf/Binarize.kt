package io.github.chenluke1975.scantopdf

import kotlin.math.max
import kotlin.math.min

/** Pixel filters on ARGB int arrays. No Android dependencies (unit-tested). */
object Binarize {
    private const val DARK = 60

    fun gray(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    /**
     * Adaptive (Bradley) threshold: a pixel becomes black if it is [percent]% darker than the
     * average of its neighbourhood. Handles uneven lighting and shadows on photographed paper.
     * Works in place; result is pure black / pure white.
     */
    fun adaptive(px: IntArray, w: Int, h: Int, percent: Int = 15) {
        require(px.size >= w * h)
        val iw = w + 1
        val integral = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var row = 0
            val base = y * w
            for (x in 0 until w) {
                row += gray(px[base + x])
                integral[(y + 1) * iw + x + 1] = integral[y * iw + x + 1] + row
            }
        }
        val half = max(8, max(w, h) / 16)
        val black = 0xFF000000.toInt()
        val white = -1
        for (y in 0 until h) {
            val y0 = max(0, y - half)
            val y1 = min(h - 1, y + half)
            for (x in 0 until w) {
                val x0 = max(0, x - half)
                val x1 = min(w - 1, x + half)
                val count = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * iw + x1 + 1] - integral[y0 * iw + x1 + 1] -
                    integral[(y1 + 1) * iw + x0] + integral[y0 * iw + x0]
                val i = y * w + x
                val g = gray(px[i])
                // Very dark pixels are always ink (keeps big bold areas solid instead of hollow).
                px[i] = if (g < DARK || g * count * 100 < sum.toLong() * (100 - percent)) black else white
            }
        }
    }
}
