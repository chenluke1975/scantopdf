package io.github.chenluke1975.scantopdf

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var countText: TextView
    private lateinit var overlay: View
    private lateinit var overlayText: TextView
    private lateinit var adapter: PageAdapter

    private val worker = Executors.newSingleThreadExecutor()
    private val thumbs = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var busy = false
    private var pendingCamera: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pendingCamera = savedInstanceState?.getString("camera")
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        list = findViewById(R.id.list)
        countText = findViewById(R.id.count)
        overlay = findViewById(R.id.overlay)
        overlayText = findViewById(R.id.overlayText)
        list.emptyView = findViewById(R.id.empty)
        adapter = PageAdapter()
        list.adapter = adapter

        findViewById<Button>(R.id.btnCamera).setOnClickListener { if (!busy) takePhoto() }
        findViewById<Button>(R.id.btnGallery).setOnClickListener { if (!busy) pickImages() }
        findViewById<Button>(R.id.btnFiles).setOnClickListener { if (!busy) pickFiles() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { if (!busy) startSave() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { if (!busy) share() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { if (!busy) confirmClear() }

        setBusy(getString(R.string.working_import))
        worker.execute {
            PageStore.load(this)
            main.post {
                setBusy(null)
                refresh()
                if (savedInstanceState == null) handleIncoming(intent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("camera", pendingCamera)
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbs.shutdownNow()
        worker.shutdown()
    }

    // ---------- adding pages ----------

    private fun takePhoto() {
        val dir = FilesProvider.rootDir(this, FilesProvider.CAMERA)
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FilesProvider.uriFor(this, FilesProvider.CAMERA, f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        try {
            pendingCamera = f.path
            startActivityForResult(i, REQ_CAMERA)
        } catch (_: ActivityNotFoundException) {
            pendingCamera = null
            toast(R.string.error_no_camera)
        }
    }

    private fun pickImages() {
        val i = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES)
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_IMAGES)
        } catch (_: ActivityNotFoundException) {
            pickFiles()
        }
    }

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/*"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, REQ_FILES)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.error_no_picker)
        }
    }

    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> uris += streamListExtra(intent)
            Intent.ACTION_VIEW -> intent.data?.let { uris += it }
        }
        if (uris.isEmpty()) return
        intent.action = null // don't import again on rotation
        importUris(uris, openEditor = false)
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamListExtra(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    @Deprecated("Platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_CAMERA -> {
                val path = pendingCamera ?: return
                pendingCamera = null
                val f = File(path)
                if (resultCode != RESULT_OK) { f.delete(); return }
                setBusy(getString(R.string.working_import))
                worker.execute {
                    PageStore.load(this)
                    val page = try { Importer(this).importCameraFile(f) } catch (_: Throwable) { null }
                    main.post {
                        setBusy(null)
                        if (page == null) { toast(R.string.error_camera); return@post }
                        PageStore.pages += page
                        PageStore.save(this)
                        refresh()
                        openEditor(page)
                    }
                }
            }
            REQ_IMAGES, REQ_FILES -> {
                if (resultCode != RESULT_OK || data == null) return
                val uris = ArrayList<Uri>()
                val clip = data.clipData
                if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris += it }
                else data.data?.let { uris += it }
                if (uris.isNotEmpty()) importUris(uris, openEditor = false)
            }
            REQ_SAVE -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) writePdfTo(uri)
            }
        }
    }

    private fun importUris(uris: List<Uri>, openEditor: Boolean) {
        setBusy(getString(R.string.working_import))
        worker.execute {
            PageStore.load(this)
            val r = Importer(this).importAll(uris)
            main.post {
                setBusy(null)
                PageStore.pages += r.pages
                PageStore.save(this)
                refresh()
                if (r.pages.isNotEmpty()) {
                    Toast.makeText(this, resources.getQuantityString(R.plurals.added_pages, r.pages.size, r.pages.size), Toast.LENGTH_SHORT).show()
                    list.smoothScrollToPosition(PageStore.pages.size - 1)
                }
                if (r.lockedPdf) toast(R.string.error_pdf_locked)
                else if (r.failed.isNotEmpty()) {
                    Toast.makeText(this, getString(R.string.error_import, r.failed.joinToString("、")), Toast.LENGTH_LONG).show()
                }
                if (openEditor && r.pages.size == 1) openEditor(r.pages[0])
            }
        }
    }

    private fun openEditor(p: Page) {
        startActivity(Intent(this, EditActivity::class.java).putExtra(EditActivity.EXTRA_ID, p.id))
    }

    // ---------- exporting ----------

    private fun fileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
        return "${getString(R.string.file_prefix)}_$ts.pdf"
    }

    private fun startSave() {
        if (PageStore.pages.isEmpty()) { toast(R.string.error_no_pages); return }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, fileName())
        try {
            startActivityForResult(i, REQ_SAVE)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.error_no_picker)
        }
    }

    private fun writePdfTo(uri: Uri) {
        val pages = PageStore.pages.map { it.copy() }
        setBusy(getString(R.string.working_pdf))
        worker.execute {
            val ok = try {
                val os = try { contentResolver.openOutputStream(uri, "wt") } catch (_: Exception) { null }
                    ?: contentResolver.openOutputStream(uri, "w")
                os!!.use { PdfExporter.export(this, pages, it) { n -> progress(n, pages.size) } }
                true
            } catch (_: Throwable) { false }
            main.post {
                setBusy(null)
                if (ok) showSaved() else toast(R.string.error_save)
            }
        }
    }

    private fun share() {
        if (PageStore.pages.isEmpty()) { toast(R.string.error_no_pages); return }
        val pages = PageStore.pages.map { it.copy() }
        setBusy(getString(R.string.working_pdf))
        worker.execute {
            val dir = FilesProvider.rootDir(this, FilesProvider.SHARE)
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, fileName())
            val ok = try {
                f.outputStream().use { PdfExporter.export(this, pages, it) { n -> progress(n, pages.size) } }
                true
            } catch (_: Throwable) { false }
            main.post {
                setBusy(null)
                if (!ok) { toast(R.string.error_save); return@post }
                val uri = FilesProvider.uriFor(this, FilesProvider.SHARE, f)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, f.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(f.name, uri)
                try {
                    startActivity(Intent.createChooser(send, getString(R.string.share_title)))
                } catch (_: ActivityNotFoundException) {
                    toast(R.string.error_save)
                }
            }
        }
    }

    private fun progress(n: Int, total: Int) {
        main.post { if (busy) overlayText.text = getString(R.string.working_pdf_n, n, total) }
    }

    private fun showSaved() {
        AlertDialog.Builder(this)
            .setMessage(R.string.saved_ok)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------- page list ----------

    private fun confirmClear() {
        if (PageStore.pages.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_clear)
            .setPositiveButton(R.string.yes_delete) { _, _ ->
                PageStore.clear(this)
                cache.evictAll()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(p: Page) {
        val n = PageStore.pages.indexOf(p) + 1
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete, n))
            .setPositiveButton(R.string.yes_delete) { _, _ ->
                PageStore.remove(this, p)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun move(p: Page, delta: Int) {
        val i = PageStore.pages.indexOf(p)
        val j = i + delta
        if (i < 0 || j < 0 || j >= PageStore.pages.size) return
        PageStore.pages[i] = PageStore.pages[j]
        PageStore.pages[j] = p
        PageStore.save(this)
        refresh()
    }

    private fun rotate(p: Page) {
        p.rotation = (p.rotation + 90) % 360
        p.quad = Geometry.rotateQuadCW(p.quad, 1)
        PageStore.save(this)
        refresh()
    }

    private fun refresh() {
        val n = PageStore.pages.size
        countText.text = resources.getQuantityString(R.plurals.page_count, n, n)
        adapter.notifyDataSetChanged()
    }

    private fun setBusy(message: String?) {
        busy = message != null
        overlay.visibility = if (busy) View.VISIBLE else View.GONE
        if (message != null) overlayText.text = message
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private inner class PageAdapter : BaseAdapter() {
        override fun getCount() = PageStore.pages.size
        override fun getItem(position: Int) = PageStore.pages[position]
        override fun getItemId(position: Int) = PageStore.pages[position].id.hashCode().toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
            val p = getItem(position)
            v.findViewById<TextView>(R.id.pageLabel).text = getString(R.string.page_label, position + 1)
            val img = v.findViewById<ImageView>(R.id.thumb)
            val key = p.renderKey
            img.tag = key
            val cached = cache.get(key)
            if (cached != null) img.setImageBitmap(cached) else {
                img.setImageDrawable(null)
                val file = PageStore.fileOf(this@MainActivity, p)
                val snapshot = p.copy()
                thumbs.execute {
                    val b = try { ImageOps.render(file, snapshot, THUMB_MAX) } catch (_: Throwable) { null }
                    if (b != null) main.post {
                        cache.put(key, b)
                        if (img.tag == key) img.setImageBitmap(b)
                    }
                }
            }
            img.contentDescription = getString(R.string.page_label, position + 1)
            img.setOnClickListener { openEditor(p) }
            v.findViewById<Button>(R.id.edit).setOnClickListener { openEditor(p) }
            v.findViewById<Button>(R.id.rotate).setOnClickListener { rotate(p) }
            v.findViewById<Button>(R.id.up).apply {
                isEnabled = position > 0
                alpha = if (isEnabled) 1f else 0.35f
                setOnClickListener { move(p, -1) }
            }
            v.findViewById<Button>(R.id.down).apply {
                isEnabled = position < PageStore.pages.size - 1
                alpha = if (isEnabled) 1f else 0.35f
                setOnClickListener { move(p, 1) }
            }
            v.findViewById<Button>(R.id.delete).setOnClickListener { confirmDelete(p) }
            return v
        }
    }

    companion object {
        private const val REQ_CAMERA = 1
        private const val REQ_IMAGES = 2
        private const val REQ_FILES = 3
        private const val REQ_SAVE = 4
        private const val THUMB_MAX = 480
    }
}
