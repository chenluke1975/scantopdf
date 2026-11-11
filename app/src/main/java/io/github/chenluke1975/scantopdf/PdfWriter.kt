package io.github.chenluke1975.scantopdf

import java.io.OutputStream

/**
 * Minimal PDF 1.4 writer that embeds JPEG data directly (DCTDecode), one image per page.
 * Keeps files small: android.graphics.pdf.PdfDocument re-encodes bitmaps losslessly and
 * produces PDFs several times larger. No Android dependencies (unit-tested).
 */
class PdfWriter(out: OutputStream) {
    private val out = CountingStream(out)
    private val offsets = ArrayList<Long>() // index = object number - 1
    private val pageObjs = ArrayList<Int>()
    private var finished = false

    init {
        write("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")
        offsets.add(-1) // 1: catalog (written at the end)
        offsets.add(-1) // 2: page tree (written at the end)
    }

    /**
     * Adds a page of [pageW]x[pageH] points showing [jpeg] fitted inside [margin].
     * A4 portrait is 595.28 x 841.89 points.
     */
    fun addJpegPage(jpeg: ByteArray, pageW: Float, pageH: Float, margin: Float = 0f) {
        check(!finished)
        val info = JpegInfo.parse(jpeg) ?: throw IllegalArgumentException("Not a JPEG")
        val r = Geometry.fit(info.width.toFloat(), info.height.toFloat(), pageW, pageH, margin)
        val imgNum = offsets.size + 1
        val colorSpace = when (info.components) {
            1 -> "/DeviceGray"
            4 -> "/DeviceCMYK"
            else -> "/DeviceRGB"
        }
        beginObj(imgNum)
        write(
            "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
        )
        out.write(jpeg)
        write("\nendstream\nendobj\n")

        val content = "q ${n(r[2])} 0 0 ${n(r[3])} ${n(r[0])} ${n(pageH - r[1] - r[3])} cm /Im0 Do Q\n"
        val contentBytes = content.toByteArray(Charsets.ISO_8859_1)
        val contentNum = offsets.size + 1
        beginObj(contentNum)
        write("<< /Length ${contentBytes.size} >>\nstream\n")
        out.write(contentBytes)
        write("\nendstream\nendobj\n")

        val pageNum = offsets.size + 1
        beginObj(pageNum)
        write(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pageW)} ${n(pageH)}] " +
                "/Resources << /XObject << /Im0 $imgNum 0 R >> /ProcSet [/PDF /ImageC /ImageB] >> " +
                "/Contents $contentNum 0 R >>\nendobj\n"
        )
        pageObjs.add(pageNum)
    }

    val pageCount: Int get() = pageObjs.size

    fun finish() {
        check(!finished)
        finished = true
        offsets[1] = out.count
        write("2 0 obj\n<< /Type /Pages /Kids [${pageObjs.joinToString(" ") { "$it 0 R" }}] /Count ${pageObjs.size} >>\nendobj\n")
        offsets[0] = out.count
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val infoNum = offsets.size + 1
        beginObj(infoNum)
        write("<< /Producer (Scan to PDF) >>\nendobj\n")
        val xref = out.count
        val sb = StringBuilder()
        sb.append("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info $infoNum 0 R >>\nstartxref\n$xref\n%%EOF\n")
        write(sb.toString())
        out.flush()
    }

    private fun beginObj(num: Int) {
        check(num == offsets.size + 1)
        offsets.add(out.count)
        write("$num 0 obj\n")
    }

    private fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

    private fun n(f: Float): String = String.format(java.util.Locale.ROOT, "%.2f", f)

    private class CountingStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { inner.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); count += len }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    companion object {
        const val A4_W = 595.28f
        const val A4_H = 841.89f
    }
}

data class JpegInfo(val width: Int, val height: Int, val components: Int) {
    companion object {
        /** Reads the frame header (SOFn) of a JPEG; null if the data is not a valid JPEG. */
        fun parse(d: ByteArray): JpegInfo? {
            if (d.size < 4 || (d[0].toInt() and 0xFF) != 0xFF || (d[1].toInt() and 0xFF) != 0xD8) return null
            var i = 2
            while (i + 3 < d.size) {
                if ((d[i].toInt() and 0xFF) != 0xFF) { i++; continue }
                val marker = d[i + 1].toInt() and 0xFF
                if (marker == 0xFF) { i++; continue }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                if (marker == 0xD9 || marker == 0xDA) return null
                val len = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) {
                    if (i + 9 >= d.size) return null
                    val h = ((d[i + 5].toInt() and 0xFF) shl 8) or (d[i + 6].toInt() and 0xFF)
                    val w = ((d[i + 7].toInt() and 0xFF) shl 8) or (d[i + 8].toInt() and 0xFF)
                    val c = d[i + 9].toInt() and 0xFF
                    return if (w > 0 && h > 0) JpegInfo(w, h, c) else null
                }
                i += 2 + len
            }
            return null
        }
    }
}
