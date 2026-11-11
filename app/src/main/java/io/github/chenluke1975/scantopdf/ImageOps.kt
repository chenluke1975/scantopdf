package io.github.chenluke1975.scantopdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

/** Bitmap operations: decode, rotate, perspective-crop, filter. */
object ImageOps {
    /** Long side of the stored source images. */
    const val SOURCE_MAX = 2400
    /** Long side of each page in the exported PDF (~240 dpi on A4). */
    const val EXPORT_MAX = 2000
    const val EXPORT_QUALITY = 80

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun decodeFile(file: File, maxSide: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return null
        val o = BitmapFactory.Options().apply {
            inSampleSize = Geometry.sampleSize(b.outWidth, b.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, o) ?: return null
        return scaleDown(bmp, maxSide)
    }

    fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = Geometry.downscale(bmp.width, bmp.height, maxSide)
        if (s >= 1f) return bmp
        val out = Bitmap.createScaledBitmap(
            bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true
        )
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Applies an EXIF orientation tag (1..8) read from [exifStream]. */
    fun applyExif(bmp: Bitmap, exifStream: InputStream?): Bitmap {
        val orientation = try {
            exifStream?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (_: Exception) { 1 }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Flattens transparency onto white (JPEG has no alpha) and returns an opaque copy. */
    fun onWhite(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, filterPaint)
        return out
    }

    fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bmp
        val m = Matrix().apply { setRotate(d.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** Perspective-corrects the [quad] area of [bmp] into an upright rectangle. */
    fun warp(bmp: Bitmap, quad: List<Pt>, maxSide: Int): Bitmap {
        if (Geometry.isFull(quad) || !Geometry.isUsable(quad)) return bmp
        val (w0, h0) = Geometry.outputSize(quad, bmp.width, bmp.height)
        val s = Geometry.downscale(w0, h0, maxSide)
        val w = (w0 * s).roundToInt().coerceAtLeast(1)
        val h = (h0 * s).roundToInt().coerceAtLeast(1)
        val src = FloatArray(8)
        quad.forEachIndexed { i, p -> src[i * 2] = p.x * bmp.width; src[i * 2 + 1] = p.y * bmp.height }
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return bmp
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(bmp, m, filterPaint)
        return out
    }

    fun applyFilter(bmp: Bitmap, filter: Filter): Bitmap = when (filter) {
        Filter.ORIGINAL -> bmp
        Filter.GRAY -> {
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.FILTER_BITMAP_FLAG)
            // Grayscale with a gentle contrast boost so paper looks whiter.
            val cm = ColorMatrix().apply { setSaturation(0f) }
            val k = 1.2f
            val t = -0.1f * 255f
            cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
            p.colorFilter = ColorMatrixColorFilter(cm)
            Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, p) }
            out
        }
        Filter.BW -> {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            Binarize.adaptive(px, w, h)
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }

    /** Full pipeline for a page: decode -> rotate -> crop -> filter. */
    fun render(file: File, page: Page, maxSide: Int): Bitmap? {
        // Decode a bit larger than needed so the crop keeps detail.
        val src = decodeFile(file, maxSide) ?: return null
        val rotated = rotate(src, page.rotation)
        if (rotated !== src) src.recycle()
        val cropped = warp(rotated, page.quad, maxSide)
        if (cropped !== rotated) rotated.recycle()
        val filtered = applyFilter(cropped, page.filter)
        if (filtered !== cropped) cropped.recycle()
        return filtered
    }
}
