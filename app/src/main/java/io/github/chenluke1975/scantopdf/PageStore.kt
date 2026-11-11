package io.github.chenluke1975.scantopdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Filter { ORIGINAL, GRAY, BW }

/**
 * One page of the document. [file] is a normalized JPEG (EXIF orientation applied) in the
 * app's private pages folder. [quad] is in normalized coordinates of the image AFTER [rotation].
 */
data class Page(
    val id: String,
    val file: String,
    var rotation: Int = 0,
    var quad: List<Pt> = Geometry.FULL,
    var filter: Filter = Filter.ORIGINAL,
) {
    /** Changes whenever anything that affects the rendered output changes. */
    val renderKey: String
        get() = "$id|$rotation|${quad.joinToString(";") { "%.4f,%.4f".format(java.util.Locale.ROOT, it.x, it.y) }}|$filter"
}

/** The current document, kept on disk so nothing is lost if Android closes the app. */
object PageStore {
    val pages = ArrayList<Page>()
    private var loaded = false

    fun pagesDir(ctx: Context): File = File(ctx.filesDir, "pages").apply { mkdirs() }
    fun fileOf(ctx: Context, p: Page): File = File(pagesDir(ctx), p.file)
    fun find(id: String?): Page? = pages.firstOrNull { it.id == id }

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        pages.clear()
        val f = File(ctx.filesDir, "session.json")
        if (!f.isFile) return
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val q = o.optJSONArray("quad")
                val quad = if (q != null && q.length() == 8) {
                    List(4) { Pt(q.getDouble(it * 2).toFloat(), q.getDouble(it * 2 + 1).toFloat()) }
                } else Geometry.FULL
                val p = Page(
                    id = o.getString("id"),
                    file = o.getString("file"),
                    rotation = o.optInt("rotation", 0),
                    quad = quad,
                    filter = runCatching { Filter.valueOf(o.optString("filter")) }.getOrDefault(Filter.ORIGINAL),
                )
                if (fileOf(ctx, p).isFile) pages.add(p)
            }
        } catch (_: Exception) {
            // Corrupt session: start empty.
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        val arr = JSONArray()
        for (p in pages) {
            val q = JSONArray()
            p.quad.forEach { q.put(it.x.toDouble()); q.put(it.y.toDouble()) }
            arr.put(
                JSONObject().put("id", p.id).put("file", p.file).put("rotation", p.rotation)
                    .put("quad", q).put("filter", p.filter.name)
            )
        }
        val f = File(ctx.filesDir, "session.json")
        val tmp = File(ctx.filesDir, "session.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }

    fun remove(ctx: Context, p: Page) {
        pages.remove(p)
        fileOf(ctx, p).delete()
        save(ctx)
    }

    fun clear(ctx: Context) {
        pages.clear()
        pagesDir(ctx).listFiles()?.forEach { it.delete() }
        save(ctx)
    }
}
