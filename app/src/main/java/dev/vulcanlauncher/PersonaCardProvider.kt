package dev.vulcanlauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Read-only, unexported provider. Android grants the chosen share target access to one random PNG URI. */
class PersonaCardProvider : ContentProvider() {
    companion object {
        internal fun save(context: Context, bitmap: Bitmap): Uri {
            val dir = File(context.cacheDir, "persona-cards").apply { mkdirs() }
            // Retain recent cards so a second share never invalidates a recipient's first attachment.
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86400000L }?.forEach { it.delete() }
            val file = File(dir, "${UUID.randomUUID()}.png")
            file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw java.io.IOException("PNG encoding failed") }
            return Uri.Builder().scheme("content").authority("${context.packageName}.persona-cards").appendPath("card").appendPath(file.name).build()
        }
    }
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        if (uri.authority != "${ctx.packageName}.persona-cards" || uri.pathSegments.size != 2 || uri.pathSegments[0] != "card" ||
            !Regex("[a-f0-9-]{36}\\.png").matches(uri.lastPathSegment.orEmpty())) throw FileNotFoundException()
        return File(File(ctx.cacheDir, "persona-cards"), uri.lastPathSegment!!).also { if (!it.isFile) throw FileNotFoundException() }
    }
    override fun getType(uri: Uri): String { file(uri); return "image/png" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) "Vulcan-Persona.png" else file.length() }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
