package io.github.chenluke1975.scantopdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

/** Copies images and PDF pages from other apps into the app's private pages folder. */
class Importer(private val ctx: Context) {

    class Result(val pages: List<Page>, val failed: List<String>, val lockedPdf: Boolean)

    fun importAll(uris: List<Uri>): Result {
        val pages = ArrayList<Page>()
        val failed = ArrayList<String>()
        var locked = false
        for (u in uris) {
            try {
                if (isPdf(u)) pages += importPdf(u) else pages += importImage(u)
            } catch (e: SecurityException) {
                if (isPdf(u)) locked = true
                failed += displayName(u)
            } catch (_: Throwable) {
                failed += displayName(u)
            }
        }
        return Result(pages, failed, locked)
    }

    /** Imports a photo taken by the camera app into a page; deletes the temporary file. */
    fun importCameraFile(f: File): Page? {
        if (!f.isFile || f.length() == 0L) return null
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            if (b.outWidth <= 0) return null
            val o = BitmapFactory.Options().apply { inSampleSize = Geometry.sampleSize(b.outWidth, b.outHeight, ImageOps.SOURCE_MAX) }
            var bmp = BitmapFactory.decodeFile(f.path, o) ?: return null
            bmp = ImageOps.scaleDown(bmp, ImageOps.SOURCE_MAX)
            bmp = ImageOps.applyExif(bmp, f.inputStream())
            savePage(bmp)
        } finally {
            f.delete()
        }
    }

    private fun isPdf(u: Uri): Boolean {
        val type = ctx.contentResolver.getType(u)
        if (type == "application/pdf") return true
        if (type != null && type.startsWith("image/")) return false
        return displayName(u).lowercase().endsWith(".pdf")
    }

    fun displayName(u: Uri): String {
        try {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) { }
        return u.lastPathSegment ?: u.toString()
    }

    private fun importImage(u: Uri): Page {
        val cr = ctx.contentResolver
        val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder applies EXIF orientation and understands HEIF/WebP.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, u)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = Geometry.downscale(info.size.width, info.size.height, ImageOps.SOURCE_MAX)
                if (s < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * s).roundToInt().coerceAtLeast(1),
                        (info.size.height * s).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, b) }
            require(b.outWidth > 0) { "not an image" }
            val o = BitmapFactory.Options().apply { inSampleSize = Geometry.sampleSize(b.outWidth, b.outHeight, ImageOps.SOURCE_MAX) }
            var x = cr.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) } ?: error("decode failed")
            x = ImageOps.scaleDown(x, ImageOps.SOURCE_MAX)
            ImageOps.applyExif(x, cr.openInputStream(u))
        }
        return savePage(bmp)
    }

    private fun importPdf(u: Uri): List<Page> {
        val out = ArrayList<Page>()
        val pfd = ctx.contentResolver.openFileDescriptor(u, "r") ?: error("cannot open")
        pfd.use {
            PdfRenderer(it).use { r ->
                for (i in 0 until minOf(r.pageCount, MAX_PDF_PAGES)) {
                    r.openPage(i).use { pg ->
                        val s = ImageOps.EXPORT_MAX.toFloat() / maxOf(pg.width, pg.height)
                        val w = (pg.width * s).roundToInt().coerceAtLeast(1)
                        val h = (pg.height * s).roundToInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out += savePage(bmp)
                    }
                }
            }
        }
        return out
    }

    private fun savePage(bmp: Bitmap): Page {
        val opaque = ImageOps.onWhite(bmp)
        bmp.recycle()
        val id = UUID.randomUUID().toString()
        val name = "$id.jpg"
        FileOutputStream(File(PageStore.pagesDir(ctx), name)).use {
            opaque.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        opaque.recycle()
        return Page(id, name)
    }

    companion object {
        const val MAX_PDF_PAGES = 300
    }
}
