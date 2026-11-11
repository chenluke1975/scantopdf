package io.github.chenluke1975.scantopdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class LogicTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test fun sortCornersOrdersClockwiseFromTopLeft() {
        val shuffled = listOf(Pt(0.9f, 0.95f), Pt(0.1f, 0.1f), Pt(0.05f, 0.9f), Pt(0.8f, 0.05f))
        assertEquals(listOf(Pt(0.1f, 0.1f), Pt(0.8f, 0.05f), Pt(0.9f, 0.95f), Pt(0.05f, 0.9f)), Geometry.sortCorners(shuffled))
    }

    @Test fun rotatingFullQuadKeepsFull() {
        for (t in 0..4) assertTrue(Geometry.isFull(Geometry.rotateQuadCW(Geometry.FULL, t)))
    }

    @Test fun rotateQuadMovesPointsClockwise() {
        val q = listOf(Pt(0.1f, 0.2f), Pt(0.5f, 0.2f), Pt(0.5f, 0.4f), Pt(0.1f, 0.4f))
        val r = Geometry.rotateQuadCW(q, 1)
        // Top-left of the region in the rotated image comes from the old bottom-left (0.1,0.4).
        assertEquals(0.6f, r[0].x, 1e-5f); assertEquals(0.1f, r[0].y, 1e-5f)
        assertEquals(0.8f, r[2].x, 1e-5f); assertEquals(0.5f, r[2].y, 1e-5f)
        // Four turns = identity.
        val back = Geometry.rotateQuadCW(q, 4)
        q.zip(back).forEach { (a, b) -> assertEquals(a.x, b.x, 1e-5f); assertEquals(a.y, b.y, 1e-5f) }
    }

    @Test fun usableRejectsTinyAndTwisted() {
        assertTrue(Geometry.isUsable(Geometry.FULL))
        assertFalse(Geometry.isUsable(listOf(Pt(0f, 0f), Pt(0.05f, 0f), Pt(0.05f, 0.05f), Pt(0f, 0.05f))))
        assertFalse(Geometry.isUsable(listOf(Pt(0f, 0f), Pt(1f, 1f), Pt(1f, 0f), Pt(0f, 1f)))) // bow-tie
    }

    @Test fun outputSizeUsesLongestEdges() {
        val q = listOf(Pt(0f, 0f), Pt(0.5f, 0f), Pt(0.5f, 1f), Pt(0f, 0.5f))
        assertEquals(Pair(707, 1000), Geometry.outputSize(q, 1000, 1000))
    }

    @Test fun fitCentersInsideMargin() {
        val r = Geometry.fit(200f, 100f, 600f, 800f, 10f)
        assertEquals(580f, r[2], 1e-3f); assertEquals(290f, r[3], 1e-3f)
        assertEquals(10f, r[0], 1e-3f); assertEquals(255f, r[1], 1e-3f)
    }

    @Test fun sampleSizeAndDownscale() {
        assertEquals(1, Geometry.sampleSize(2000, 1000, 2400))
        assertEquals(2, Geometry.sampleSize(4000, 3000, 2000))
        assertEquals(4, Geometry.sampleSize(9000, 6000, 2000))
        assertEquals(1f, Geometry.downscale(100, 50, 2000), 0f)
        assertEquals(0.5f, Geometry.downscale(4000, 100, 2000), 1e-6f)
    }

    @Test fun binarizeKeepsTextAndWhitensUnevenPaper() {
        val w = 200; val h = 100
        val px = IntArray(w * h) { i ->
            val x = i % w
            val bg = 255 - x / 2 // paper gets darker to the right (shadow)
            val y = i / w
            // a thin grey stroke (like printed text) and a big solid dark block
            val v = if (x in 98..102 && y in 40..60) 110 else if (x in 20..60 && y in 20..80) 20 else bg
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Binarize.adaptive(px, w, h)
        assertEquals(0xFF000000.toInt(), px[50 * w + 100])  // text stroke
        assertEquals(0xFF000000.toInt(), px[50 * w + 40])   // middle of solid block
        assertEquals(-1, px[10 * w + 190])                  // shadowed paper -> white
        assertEquals(-1, px[10 * w + 80])
        assertTrue(px.all { it == -1 || it == 0xFF000000.toInt() })
    }

    @Test fun jpegInfoParses() {
        assertEquals(JpegInfo(300, 200, 3), JpegInfo.parse(res("sample.jpg")))
        assertEquals(JpegInfo(50, 80, 1), JpegInfo.parse(res("gray.jpg")))
        assertNull(JpegInfo.parse(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun pdfWriterProducesValidStructure() {
        val bos = ByteArrayOutputStream()
        val w = PdfWriter(bos)
        w.addJpegPage(res("sample.jpg"), PdfWriter.A4_H, PdfWriter.A4_W, 14f)
        w.addJpegPage(res("gray.jpg"), PdfWriter.A4_W, PdfWriter.A4_H, 14f)
        w.finish()
        val bytes = bos.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/ColorSpace /DeviceGray"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        // Every xref offset must point at "N 0 obj".
        val start = text.substringAfterLast("startxref\n").trim().removeSuffix("%%EOF").trim().toInt()
        val lines = text.substring(start).lines()
        val count = lines[1].split(" ")[1].toInt()
        for (n in 1 until count) {
            val off = lines[2 + n].substring(0, 10).toInt()
            assertTrue("obj $n", text.startsWith("$n 0 obj", off))
        }
        assertNotNull(System.getProperty("java.io.tmpdir"))
        File("build/test-sample.pdf").writeBytes(bytes)
    }
}
