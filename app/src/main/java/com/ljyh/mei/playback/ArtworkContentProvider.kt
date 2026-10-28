package com.ljyh.mei.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.ljyh.mei.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves car-friendly cover art as content:// URIs.
 *
 * Coil downloads + caches the remote image; we write a JPEG into the app cache
 * and hand the host a read-only [ParcelFileDescriptor]. Paths:
 *   content://${applicationId}.aa.artwork/browse/<url>
 *   content://${applicationId}.aa.artwork/nowplaying/<url>
 *
 * [openFile] must not hang the binder thread on a cold cache miss — misses are
 * capped with [OPEN_MISS_TIMEOUT_MS]. [prefetchBrowseFromItems] warms the disk
 * cache from [onGetChildren] so the car host usually hits a file.
 */
class ArtworkContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: throw FileNotFoundException("no context")
        if (mode != "r") throw FileNotFoundException("mode $mode not supported")
        val segments = uri.pathSegments
        if (segments.size < 2) throw FileNotFoundException("bad artwork uri")
        val kind = segments[0]
        val remoteUrl = Uri.decode(segments[1])
        if (remoteUrl.isBlank()) throw FileNotFoundException("empty artwork url")
        return try {
            val file = cachedFile(context, kind, remoteUrl, timeoutMs = OPEN_MISS_TIMEOUT_MS)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (error: FileNotFoundException) {
            throw error
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "openFile failed for %s", uri)
            throw FileNotFoundException("artwork unavailable: ${error.message}").apply { initCause(error) }
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
        /** Android Auto / Projection host — must be granted read on non-exported artwork URIs. */
        const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
        private const val PATH_BROWSE = "browse"
        private const val PATH_NOW_PLAYING = "nowplaying"
        /** Binder-thread miss budget; AA falls back to placeholder on [FileNotFoundException]. */
        private const val OPEN_MISS_TIMEOUT_MS = 2_500L
        private val locks = ConcurrentHashMap<String, Any>()
        private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val prefetchSemaphore = Semaphore(permits = 4)

        fun authority(context: Context): String = "${BuildConfig.APPLICATION_ID}$AUTHORITY_SUFFIX"

        fun browseUri(context: Context, httpsUrl: String): Uri =
            buildUri(context, PATH_BROWSE, httpsUrl)

        fun nowPlayingUri(context: Context, httpsUrl: String): Uri =
            buildUri(context, PATH_NOW_PLAYING, httpsUrl)

        /**
         * Grant read access so Android Auto (separate process) can open our non-exported
         * content:// artwork. Always grants [GEARHEAD_PACKAGE]; also [controllerPackage] when set.
         * Provider stays exported=false with grantUriPermissions=true.
         */
        fun grantReadForCar(context: Context, uri: Uri, controllerPackage: String? = null) {
            if (uri.scheme != "content") return
            val targets = linkedSetOf(GEARHEAD_PACKAGE)
            if (!controllerPackage.isNullOrBlank()) {
                targets += controllerPackage
            }
            for (pkg in targets) {
                try {
                    context.grantUriPermission(
                        pkg,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (error: SecurityException) {
                    Timber.tag(TAG).w(error, "grantUriPermission failed for %s → %s", pkg, uri)
                }
            }
        }

        /**
         * Fire-and-forget: warm browse JPEG disk cache for artwork on the given page.
         * Bounded to 4 concurrent Coil fetches on [Dispatchers.IO]. Does not block the caller.
         */
        fun prefetchBrowseFromItems(context: Context, items: List<MediaItem>) {
            if (items.isEmpty()) return
            val auth = authority(context)
            val urls = items.mapNotNull { item ->
                val uri = item.mediaMetadata.artworkUri ?: return@mapNotNull null
                if (uri.scheme != "content" || uri.authority != auth) return@mapNotNull null
                val segs = uri.pathSegments
                if (segs.size < 2 || segs[0] != PATH_BROWSE) return@mapNotNull null
                Uri.decode(segs[1]).takeIf { it.isNotBlank() }
            }.distinct()
            if (urls.isEmpty()) return
            val appContext = context.applicationContext
            prefetchScope.launch {
                urls.map { url ->
                    async {
                        prefetchSemaphore.withPermit {
                            runCatching {
                                cachedFile(appContext, PATH_BROWSE, url, timeoutMs = 30_000L)
                            }.onFailure { err ->
                                Timber.tag(TAG).d(err, "prefetch miss for %s", url.take(80))
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        private fun buildUri(context: Context, path: String, url: String): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(path)
                .appendPath(Uri.encode(url))
                .build()

        private fun cachedFile(
            context: Context,
            kind: String,
            url: String,
            timeoutMs: Long,
        ): File {
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
                val image = try {
                    runBlocking {
                        withTimeout(timeoutMs) {
                            loader.execute(request).image
                        }
                    }
                } catch (error: Exception) {
                    throw FileNotFoundException(
                        "artwork load timed out or failed (${timeoutMs}ms): ${error.message}",
                    ).apply { initCause(error) }
                } ?: throw FileNotFoundException("Coil returned no image for $url")
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
