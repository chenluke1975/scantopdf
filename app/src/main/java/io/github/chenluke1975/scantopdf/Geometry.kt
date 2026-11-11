package io.github.chenluke1975.scantopdf

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point in normalized image coordinates (0..1, y pointing down). */
data class Pt(val x: Float, val y: Float)

/** Pure geometry helpers for the 4-corner crop. No Android dependencies (unit-tested). */
object Geometry {
    /** The whole image: top-left, top-right, bottom-right, bottom-left. */
    val FULL: List<Pt> = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

    fun isFull(q: List<Pt>, eps: Float = 0.002f): Boolean =
        q.size == 4 && q.zip(FULL).all { (a, b) -> abs(a.x - b.x) < eps && abs(a.y - b.y) < eps }

    fun clamp(p: Pt): Pt = Pt(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))

    /** Orders 4 points as top-left, top-right, bottom-right, bottom-left (clockwise on screen). */
    fun sortCorners(pts: List<Pt>): List<Pt> {
        require(pts.size == 4)
        val cx = pts.sumOf { it.x.toDouble() } / 4
        val cy = pts.sumOf { it.y.toDouble() } / 4
        val byAngle = pts.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = byAngle.indices.minBy { byAngle[it].x + byAngle[it].y }
        return List(4) { byAngle[(start + it) % 4] }
    }

    /** Polygon area (shoelace) in normalized units; 1.0 = whole image. */
    fun area(q: List<Pt>): Float {
        var s = 0f
        for (i in q.indices) {
            val a = q[i]
            val b = q[(i + 1) % q.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** True if the quad is convex and not tiny, i.e. safe to use for a perspective crop. */
    fun isUsable(q: List<Pt>, minArea: Float = 0.01f): Boolean {
        if (q.size != 4 || area(q) < minArea) return false
        var sign = 0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val c = q[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Maps a quad drawn on an image to the same quad after rotating the image 90° clockwise [turns] times. */
    fun rotateQuadCW(q: List<Pt>, turns: Int): List<Pt> {
        var r = q
        repeat(((turns % 4) + 4) % 4) { r = r.map { Pt(1f - it.y, it.x) } }
        return sortCorners(r)
    }

    /** Output pixel size of the flattened page for a quad on a [w]x[h] image. */
    fun outputSize(q: List<Pt>, w: Int, h: Int): Pair<Int, Int> {
        fun d(a: Pt, b: Pt) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val outW = max(d(q[0], q[1]), d(q[3], q[2]))
        val outH = max(d(q[0], q[3]), d(q[1], q[2]))
        return Pair(max(1, outW.roundToInt()), max(1, outH.roundToInt()))
    }

    /** Fits an [imgW]x[imgH] image inside a box with a margin, centered. Returns [left, top, width, height]. */
    fun fit(imgW: Float, imgH: Float, boxW: Float, boxH: Float, margin: Float = 0f): FloatArray {
        val aw = boxW - 2 * margin
        val ah = boxH - 2 * margin
        val s = min(aw / imgW, ah / imgH)
        val w = imgW * s
        val h = imgH * s
        return floatArrayOf((boxW - w) / 2f, (boxH - h) / 2f, w, h)
    }

    /** Scale factor so the long side is at most [maxSide] (never upscales). */
    fun downscale(w: Int, h: Int, maxSide: Int): Float {
        val long = max(w, h)
        return if (long <= maxSide) 1f else maxSide.toFloat() / long
    }

    /** Largest power-of-two sample size that keeps the long side >= [maxSide]. */
    fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }
}
