package io.github.chenluke1975.scantopdf

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Renders every page and writes them into one PDF (A4, page orientation follows the image). */
object PdfExporter {
    private const val MARGIN = 14f // points (~5 mm)

    fun export(ctx: Context, pages: List<Page>, out: OutputStream, progress: (Int) -> Unit = {}) {
        val w = PdfWriter(out)
        pages.forEachIndexed { i, p ->
            progress(i + 1)
            val bmp = ImageOps.render(PageStore.fileOf(ctx, p), p, ImageOps.EXPORT_MAX) ?: return@forEachIndexed
            val jpeg = ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, ImageOps.EXPORT_QUALITY, bos)
                bos.toByteArray()
            }
            val landscape = bmp.width > bmp.height
            bmp.recycle()
            if (landscape) w.addJpegPage(jpeg, PdfWriter.A4_H, PdfWriter.A4_W, MARGIN)
            else w.addJpegPage(jpeg, PdfWriter.A4_W, PdfWriter.A4_H, MARGIN)
        }
        w.finish()
    }
}
