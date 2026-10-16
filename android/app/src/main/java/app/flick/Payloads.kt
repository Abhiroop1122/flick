package app.flick

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import app.flick.core.Payload
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream

/** A shared file, described without reading it. */
data class Shared(val uri: Uri, override val name: String, override val size: Long, override val mime: String) : Payload {
    @Transient var resolver: ContentResolver? = null
    @Volatile private var pre: ParcelFileDescriptor? = null

    override fun open(): InputStream {
        pre?.let { pre = null; return ParcelFileDescriptor.AutoCloseInputStream(it) }
        return resolver?.openInputStream(uri) ?: throw FileNotFoundException("Can't open $name")
    }

    /** Open now, while we still have permission; reading later works even after it's revoked. */
    fun preopen() {
        if (pre == null) pre = runCatching { resolver?.openFileDescriptor(uri, "r") }.getOrNull()
    }

    fun closeUnused() {
        runCatching { pre?.close() }
        pre = null
    }

    val isVisual get() = mime.startsWith("image/") || mime.startsWith("video/")
}

/** What arrived from the share sheet. */
data class Incoming(val items: List<Uri>, val text: String?)

object Payloads {
    fun fromIntent(intent: Intent): Incoming {
        val uris = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::addAll)
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(uris::add) }
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: intent.clipData?.takeIf { uris.isEmpty() && it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        return Incoming(uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }, text)
    }

    /** Reads name, size and type for each Uri. Runs on a background thread. */
    fun describe(cr: ContentResolver, uris: List<Uri>): List<Shared> = uris.mapIndexed { i, uri ->
        var name: String? = null
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        val mime = runCatching { cr.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()))
            ?: "application/octet-stream"
        var n = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file-${i + 1}"
        if (!n.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n = "$n.$it" }
        Shared(uri, n, if (size == 0L) -1L else size, mime).also { it.resolver = cr }
    }

    /** "3 photos", "a video", "2 items", "a link" */
    fun describeLabel(items: List<Shared>, text: String?): String {
        if (items.isEmpty()) return if (text != null && Regex("^https?://\\S+$").matches(text.trim())) "a link" else "text"
        val kinds = items.map {
            when {
                it.mime.startsWith("image/") -> "photo"
                it.mime.startsWith("video/") -> "video"
                it.mime.startsWith("audio/") -> "audio file"
                it.mime == "application/pdf" -> "PDF"
                else -> "file"
            }
        }.toSet()
        val n = items.size
        if (kinds.size == 1) {
            val k = kinds.first()
            return if (n == 1) (if (k[0] in "aeiou") "an " else "a ") + k else "$n ${k}s"
        }
        return "$n items"
    }

    fun thumbnail(cr: ContentResolver, item: Shared, px: Int = 256): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            cr.loadThumbnail(item.uri, Size(px, px), null)
        } else if (item.mime.startsWith("image/")) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (opts.outWidth / (sample * 2) >= px && opts.outHeight / (sample * 2) >= px) sample *= 2
            cr.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } else if (item.mime.startsWith("video/")) {
            MediaMetadataRetriever().run {
                try { setDataSource(appContext, item.uri); getFrameAtTime(0)?.let { scaleDown(it, px) } } finally { release() }
            }
        } else null
    }.getOrNull()

    /** Small JPEG for the accept popup on the computer. */
    fun thumbnailBase64(cr: ContentResolver, items: List<Shared>): String? {
        val first = items.firstOrNull { it.isVisual } ?: return null
        val bmp = thumbnail(cr, first, 200) ?: return null
        val square = centerSquare(bmp, 160)
        for (q in intArrayOf(78, 60, 45)) {
            val out = ByteArrayOutputStream()
            square.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() < 150_000) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        return null
    }

    private fun scaleDown(b: Bitmap, px: Int): Bitmap {
        val s = px.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun centerSquare(b: Bitmap, px: Int): Bitmap {
        val side = minOf(b.width, b.height)
        val sq = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side <= px) sq else Bitmap.createScaledBitmap(sq, px, px, true)
    }

    lateinit var appContext: android.content.Context
}
