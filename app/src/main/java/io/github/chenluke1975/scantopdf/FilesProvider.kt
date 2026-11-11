package io.github.chenluke1975.scantopdf

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Tiny replacement for androidx FileProvider: lends the camera app a file to write the photo
 * into, and lends other apps the exported PDF to share. Only two cache folders are reachable.
 */
class FilesProvider : ContentProvider() {
    override fun onCreate() = true

    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val segs = uri.pathSegments
        if (segs.size != 2 || segs[0] !in ROOTS) throw FileNotFoundException(uri.toString())
        val root = rootDir(ctx, segs[0]).canonicalFile
        val f = File(root, segs[1]).canonicalFile
        if (f.parentFile != root) throw FileNotFoundException(uri.toString())
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.parseMode(mode))

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val names = ArrayList<String>()
        val values = ArrayList<Any?>()
        for (c in cols) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> { names += c; values += f.name }
                OpenableColumns.SIZE -> { names += c; values += f.length() }
            }
        }
        return MatrixCursor(names.toTypedArray(), 1).apply { addRow(values.toTypedArray()) }
    }

    override fun getType(uri: Uri): String = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val CAMERA = "camera"
        const val SHARE = "share"
        private val ROOTS = setOf(CAMERA, SHARE)

        fun rootDir(ctx: Context, root: String) = File(ctx.cacheDir, root).apply { mkdirs() }

        fun uriFor(ctx: Context, root: String, f: File): Uri =
            Uri.Builder().scheme("content").authority("${ctx.packageName}.files")
                .appendPath(root).appendPath(f.name).build()
    }
}
