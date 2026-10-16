package app.flick

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Saves files that arrive from a computer where people expect them:
 * photos in Pictures/Flick and videos in Movies/Flick (so they show up in the
 * gallery), everything else in Download/Flick.
 */
class Saver(private val ctx: Context) {
    class Target internal constructor(val uri: Uri?, val file: File?, val out: OutputStream, val name: String, val mime: String)
    data class Saved(val uri: Uri?, val name: String, val mime: String)

    private val cr = ctx.contentResolver

    fun create(name: String, mime: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_DOWNLOADS}/Flick"
            val choices = when {
                mime.startsWith("image/") -> listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_PICTURES}/Flick", downloads)
                mime.startsWith("video/") -> listOf(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MOVIES}/Flick", downloads)
                else -> listOf(downloads)
            }
            for ((collection, dir) in choices) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                // A collection may refuse an unusual type (e.g. a Photoshop file as an image): try the next one.
                val uri = runCatching { cr.insert(collection, values) }.getOrNull() ?: continue
                val out = runCatching { cr.openOutputStream(uri) }.getOrNull()
                if (out == null) {
                    cr.delete(uri, null, null)
                    continue
                }
                return Target(uri, null, out, name, mime)
            }
            throw IOException("Couldn't save $name")
        }
        // Android 8–9: public Downloads needs the storage permission; otherwise the app's own folder.
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val base = if (granted) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        else ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        val dir = File(base, "Flick").apply { mkdirs() }
        val f = uniqueFile(dir, name)
        return Target(null, f, FileOutputStream(f), name, mime)
    }

    fun finish(t: Target): Saved {
        if (t.uri != null) {
            cr.update(t.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return Saved(t.uri, t.name, t.mime)
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(t.file!!.path), arrayOf(t.mime), null)
        return Saved(null, t.file.name, t.mime)
    }

    fun abort(t: Target) {
        runCatching { t.out.close() }
        if (t.uri != null) runCatching { cr.delete(t.uri, null, null) }
        t.file?.delete()
    }

    private fun uniqueFile(dir: File, name: String): File {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        var f = File(dir, name)
        var i = 1
        while (f.exists()) f = File(dir, "$base (${i++})$ext")
        return f
    }
}
