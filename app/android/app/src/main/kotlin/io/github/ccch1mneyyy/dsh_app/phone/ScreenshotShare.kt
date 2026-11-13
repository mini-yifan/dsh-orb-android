package io.github.ccch1mneyyy.dsh_app.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.ccch1mneyyy.dsh_app.files.LegacyDownloads
import io.github.ccch1mneyyy.dsh_app.runtime.PhoneStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The two artefacts of one capture, before the clipboard write. */
data class ScreenshotStore(val path: String, val uri: Uri)

/**
 * One screenshot's user-visible artefacts: a copy in Downloads and the same image on the
 * clipboard. The caller has already applied the read policy to this exact capture before
 * [save] is reached; this only writes to the person's own storage and clipboard.
 */
object ScreenshotShare {
    /** Same folder FilesPlugin.saveToDownloads uses, so the guest's `/sdcard/Download/DSH-Go` sees it. */
    const val SUBDIR = "DSH-Go"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val KEEP_CACHE = 8

    /** `Screenshot 2026-11-13 at 14.05.32.jpg` — the desktop tool's filename shape. */
    fun name(now: Date = Date()): String =
        "Screenshot " + SimpleDateFormat("yyyy-MM-dd 'at' HH.mm.ss", Locale.getDefault()).format(now) + ".jpg"

    /** MediaStore/legacy Downloads + a private cache copy that becomes the clipboard URI. */
    fun save(context: Context, jpeg: ByteArray): ScreenshotStore {
        if (jpeg.isEmpty() || jpeg.size > MAX_BYTES) throw IllegalArgumentException("screenshot size")
        // A truncated or corrupt frame must not be saved or pasted as an empty image.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "screenshot bounds" }
        val stamp = name()
        val cacheName = stamp
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, stamp)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore refused the screenshot")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
                    ?: throw IllegalStateException("cannot write $uri")
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
            // MediaStore renames on collision ("Screenshot … (2).jpg"): report the name it kept.
            val shown = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: stamp
            "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/$shown"
        } else {
            if (!PhoneStorage.isGranted(context)) throw IllegalStateException("storage permission denied")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(downloads, SUBDIR).apply { check(isDirectory || mkdirs()) }
            "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR/${LegacyDownloads.copy(jpeg.inputStream(), dir, stamp).name}"
        }
        // dsh_file_paths.xml exposes cache-path "screenshots/", so this is a provider root.
        // Keep only the newest few: an old clipboard URI still resolves to a real file until
        // the person pastes it, but the cache must not grow without bound.
        val cache = File(context.cacheDir, "screenshots").apply { mkdirs() }
        val older = cache.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jpg") }
            .sortedByDescending { it.lastModified() }
        older.drop(KEEP_CACHE - 1).forEach { it.delete() }
        val file = File(cache, cacheName)
        file.outputStream().buffered().use { it.write(jpeg) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return ScreenshotStore(path, uri)
    }

    /** ClipboardService grants the URI's read permission to whoever pastes the clip. */
    fun clipboard(context: Context, uri: Uri): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "DSH screenshot", uri))
        true
    } catch (_: Exception) {
        false
    }
}
