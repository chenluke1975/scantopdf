package io.github.chenluke1975.scantopdf

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import java.util.concurrent.Executors

/** Per-page editor: 4-corner crop, rotate, and filter. */
class EditActivity : Activity() {
    private lateinit var page: Page
    private lateinit var crop: CropView
    private lateinit var progress: ProgressBar
    private lateinit var filterButtons: Map<Filter, Button>

    private var base: Bitmap? = null
    private var rotation = 0
    private var filter = Filter.ORIGINAL
    private var generation = 0

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PageStore.load(this)
        val p = PageStore.find(intent.getStringExtra(EXTRA_ID))
        if (p == null) { finish(); return }
        page = p
        setContentView(R.layout.activity_edit)
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        crop = findViewById(R.id.crop)
        progress = findViewById(R.id.progress)

        rotation = savedInstanceState?.getInt("rotation") ?: page.rotation
        filter = savedInstanceState?.getString("filter")?.let { runCatching { Filter.valueOf(it) }.getOrNull() } ?: page.filter
        crop.quad = savedInstanceState?.getFloatArray("quad")?.let { a -> List(4) { Pt(a[it * 2], a[it * 2 + 1]) } } ?: page.quad

        filterButtons = mapOf(
            Filter.ORIGINAL to findViewById(R.id.filterOriginal),
            Filter.GRAY to findViewById(R.id.filterGray),
            Filter.BW to findViewById(R.id.filterBw),
        )
        filterButtons.forEach { (f, b) -> b.setOnClickListener { filter = f; refresh() } }
        findViewById<Button>(R.id.rotate).setOnClickListener {
            rotation = (rotation + 90) % 360
            crop.quad = Geometry.rotateQuadCW(crop.quad, 1)
            refresh()
        }
        findViewById<Button>(R.id.resetCrop).setOnClickListener { crop.quad = Geometry.FULL }
        findViewById<Button>(R.id.cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.done).setOnClickListener { save() }

        progress.visibility = View.VISIBLE
        val file = PageStore.fileOf(this, page)
        worker.execute {
            val b = ImageOps.decodeFile(file, PREVIEW_MAX)
            main.post {
                if (isDestroyed) return@post
                if (b == null) { finish(); return@post }
                base = b
                refresh()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("rotation", rotation)
        outState.putString("filter", filter.name)
        val a = FloatArray(8)
        crop.quad.forEachIndexed { i, p -> a[i * 2] = p.x; a[i * 2 + 1] = p.y }
        outState.putFloatArray("quad", a)
    }

    private fun refresh() {
        filterButtons.forEach { (f, b) ->
            val sel = f == filter
            b.isSelected = sel
            b.setBackgroundResource(if (sel) R.drawable.bg_primary else R.drawable.bg_secondary)
            b.setTextColor(getColor(if (sel) R.color.on_primary else R.color.ink))
        }
        val src = base ?: return
        val gen = ++generation
        val rot = rotation
        val f = filter
        progress.visibility = View.VISIBLE
        worker.execute {
            val r = ImageOps.rotate(src, rot)
            val out = ImageOps.applyFilter(r, f)
            if (r !== src && out !== r) r.recycle()
            main.post {
                if (isDestroyed || gen != generation) return@post
                crop.bitmap = out
                progress.visibility = View.GONE
            }
        }
    }

    private fun save() {
        var q = Geometry.sortCorners(crop.quad)
        if (!Geometry.isUsable(q)) {
            Toast.makeText(this, R.string.error_crop, Toast.LENGTH_LONG).show()
            q = Geometry.FULL
        }
        page.rotation = rotation
        page.quad = q
        page.filter = filter
        PageStore.save(this)
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    companion object {
        const val EXTRA_ID = "page_id"
        private const val PREVIEW_MAX = 1600
    }
}
