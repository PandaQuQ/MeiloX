package com.ljyh.mei.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves car-friendly cover art as content:// URIs.
 *
 * Coil downloads + caches the remote image; we write a JPEG into the app cache
 * and hand the host a read-only [ParcelFileDescriptor]. Paths:
 *   content://${applicationId}.aa.artwork/browse/<url>
 *   content://${applicationId}.aa.artwork/nowplaying/<url>
 */
class ArtworkContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        if (mode != "r") return null
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val kind = segments[0]
        val remoteUrl = Uri.decode(segments[1])
        if (remoteUrl.isBlank()) return null
        return try {
            val file = cachedFile(context, kind, remoteUrl)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "openFile failed for %s", uri)
            null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "ArtworkContentProvider"
        const val AUTHORITY_SUFFIX = ".aa.artwork"
        private const val PATH_BROWSE = "browse"
        private const val PATH_NOW_PLAYING = "nowplaying"
        private val locks = ConcurrentHashMap<String, Any>()

        fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

        fun browseUri(context: Context, httpsUrl: String): Uri =
            buildUri(context, PATH_BROWSE, httpsUrl)

        fun nowPlayingUri(context: Context, httpsUrl: String): Uri =
            buildUri(context, PATH_NOW_PLAYING, httpsUrl)

        private fun buildUri(context: Context, path: String, url: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(path)
                .appendPath(Uri.encode(url))
                .build()

        private fun cachedFile(context: Context, kind: String, url: String): File {
            val dir = File(context.cacheDir, "aa_artwork").also { it.mkdirs() }
            val key = Integer.toHexString((kind + '\u0000' + url).hashCode())
            val out = File(dir, "$key.jpg")
            if (out.isFile && out.length() > 0L) return out
            val lock = locks.getOrPut(key) { Any() }
            synchronized(lock) {
                if (out.isFile && out.length() > 0L) return out
                val loader = SingletonImageLoader.get(context)
                val size = if (kind == PATH_NOW_PLAYING) 500 else 300
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(size)
                    .allowHardware(false)
                    .build()
                val image = runBlocking {
                    loader.execute(request).image
                } ?: error("Coil returned no image for $url")
                val bitmap = image.toBitmap()
                val tmp = File(dir, "$key.tmp")
                FileOutputStream(tmp).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                }
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                return out
            }
        }
    }
}
