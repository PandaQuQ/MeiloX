package com.ljyh.mei.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.google.gson.Gson
import com.ljyh.mei.R
import com.ljyh.mei.constants.CookieKey
import com.ljyh.mei.constants.UserIdKey
import com.ljyh.mei.data.model.eapi.HomePageResourceShow
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block.DslData.BlockResource.Resource as HomeCardResource
import com.ljyh.mei.data.model.eapi.HomePageResourceShow.Data.Block.DslData.HomeCommon
import com.ljyh.mei.data.model.melox.AccountSong
import com.ljyh.mei.data.model.melox.CloudSong
import com.ljyh.mei.data.model.toMediaMetadata
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.data.network.Resource
import com.ljyh.mei.data.repository.HomeRepository
import com.ljyh.mei.data.repository.LibraryCollectionsRepository
import com.ljyh.mei.data.repository.MeloXRepository
import com.ljyh.mei.data.repository.PlayerRepository
import com.ljyh.mei.data.repository.PlaylistRepository
import com.ljyh.mei.di.repository.HistoryRepository
import com.ljyh.mei.di.repository.SongRepository
import com.ljyh.mei.ui.navigation.LibraryPage
import com.ljyh.mei.ui.screen.main.home.HomePlaylistBlockCodes
import com.ljyh.mei.ui.screen.main.home.HomeSongBlockCodes
import com.ljyh.mei.ui.screen.main.home.homeBlockData
import com.ljyh.mei.ui.screen.main.home.homeBlockTitle
import com.ljyh.mei.ui.screen.main.home.parseRecommendationPlaylistId
import com.ljyh.mei.utils.DateUtils
import com.ljyh.mei.utils.aaBrowseCoverUrl
import com.ljyh.mei.utils.aaNowPlayingCoverUrl
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Android Auto browse tree matching the phone bottom bar (designer checklist):
 * 首页 · 发现 · 媒体库 · 最近播放
 *
 * 媒体库: 歌单 / 专辑 / 歌曲 / 下载 / 云盘 as GROUP_TITLE under the tab (no intermediate
 * section level) so the tree stays ≤3 levels: tab → list → playable.
 * 发现: playlists/charts in GRID with category GROUP_TITLE (no category intermediate).
 *
 * Custom player buttons (like / repeat / shuffle) are intentionally out of scope (step 3).
 */
@UnstableApi
class AndroidAutoLibrary(
    private val context: Context,
    private val scope: CoroutineScope,
    private val historyRepository: HistoryRepository,
    private val libraryCollections: LibraryCollectionsRepository,
    private val playlistRepository: PlaylistRepository,
    private val meloXRepository: MeloXRepository,
    private val playerRepository: PlayerRepository,
    private val homeRepository: HomeRepository,
    private val songRepository: SongRepository,
) {
    private val gson = Gson()

    /**
     * Last [onGetChildren] pages keyed by parentId. Used to expand a tapped track into the
     * full parent queue without a second network round-trip when still warm.
     */
    private val childrenCache = ConcurrentHashMap<String, List<MediaItem>>()

    /** Last MediaLibrary browser package — used when granting artwork content:// read. */
    @Volatile
    private var lastBrowserPackage: String? = null

    fun onGetLibraryRoot(
        controller: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // Open to every controller — car hosts may use unexpected package/uid or legacy MediaBrowserCompat.
        lastBrowserPackage = controller.packageName
        return try {
            if (params?.isRecent == true) {
                // AA asks for the recent root on connect for resumption; never error here.
                val recent = browsableItem(
                    mediaId = HISTORY,
                    title = context.getString(R.string.app_tab_library_history),
                )
                recordDiag(controller, "onGetLibraryRoot", "isRecent", "success:HISTORY")
                Futures.immediateFuture(LibraryResult.ofItem(recent, params))
            } else {
                val extras = Bundle().apply {
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                    )
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                    )
                }
                val root = browsableItem(
                    mediaId = ROOT,
                    title = context.getString(R.string.app_name),
                    extras = extras,
                )
                recordDiag(controller, "onGetLibraryRoot", "root", "success:ROOT")
                Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "onGetLibraryRoot failed (isRecent=%s)", params?.isRecent)
            recordDiag(
                controller,
                "onGetLibraryRoot",
                if (params?.isRecent == true) "isRecent" else "root",
                "exception:${error.message}",
            )
            // Cold-start / process-isolation failures must not break AA connect.
            val fallbackId = if (params?.isRecent == true) HISTORY else ROOT
            val fallbackTitle = if (params?.isRecent == true) {
                context.getString(R.string.app_tab_library_history)
            } else {
                context.getString(R.string.app_name)
            }
            Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem(fallbackId, fallbackTitle), params),
            )
        }
    }

    fun onGetChildren(
        controller: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        lastBrowserPackage = controller.packageName
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            try {
                val items = loadChildren(parentId)
                val pageItems = paginate(items, page, pageSize)
                recordDiag(controller, "onGetChildren", parentId, "success:${pageItems.size}")
                future.set(LibraryResult.ofItemList(pageItems, params))
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "onGetChildren failed for %s", parentId)
                recordDiag(controller, "onGetChildren", parentId, "exception:${error.message}")
                // Prefer empty-but-successful over error so the car launcher keeps the app.
                future.set(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }
        return future
    }

    fun onGetItem(
        controller: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        lastBrowserPackage = controller.packageName
        val future = SettableFuture.create<LibraryResult<MediaItem>>()
        scope.launch {
            try {
                val item = resolveItem(mediaId)
                if (item != null) {
                    recordDiag(controller, "onGetItem", mediaId, "success")
                    future.set(LibraryResult.ofItem(item, /* params= */ null))
                } else {
                    recordDiag(controller, "onGetItem", mediaId, "error:BAD_VALUE")
                    future.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "onGetItem failed for %s", mediaId)
                recordDiag(controller, "onGetItem", mediaId, "exception:${error.message}")
                future.set(LibraryResult.ofError(SessionError.ERROR_UNKNOWN))
            }
        }
        return future
    }

    fun onSearch(
        controller: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        recordDiag(controller, "onSearch", query, "success:empty")
        return Futures.immediateFuture(LibraryResult.ofVoid(params))
    }

    fun onGetSearchResult(
        controller: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        recordDiag(controller, "onGetSearchResult", query, "success:empty")
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
    }

    /** Resolve browse / AA media items into playable [MediaItem]s for the existing playback path. */
    fun resolvePlayableItems(mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        val future = SettableFuture.create<List<MediaItem>>()
        scope.launch {
            try {
                future.set(mediaItems.map { item -> resolvePlayable(item) })
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "resolvePlayableItems failed")
                future.setException(error)
            }
        }
        return future
    }

    /**
     * Expand a browse tap into the full parent queue (playlist / album / daily / history / …),
     * starting at the tapped song. Playback [MediaItem.mediaId] stays the bare song id so
     * [MediaUriProvider] and the phone path keep working.
     */
    fun resolveSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            try {
                future.set(expandBrowseToQueue(mediaItems, startIndex, startPositionMs))
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "resolveSetMediaItems failed")
                future.setException(error)
            }
        }
        return future
    }

    private suspend fun expandBrowseToQueue(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition {
        // Multi-item requests (rare from AA) — just resolve each; do not invent a parent.
        if (mediaItems.size != 1) {
            val resolved = mediaItems.map { resolvePlayable(it) }
            val index = startIndex.coerceIn(0, (resolved.size - 1).coerceAtLeast(0))
            return MediaSession.MediaItemsWithStartPosition(resolved, index, startPositionMs)
        }
        val requested = mediaItems.single()
        val (parentId, songId) = parseTrackMediaId(requested.mediaId)
        val expanded = if (!parentId.isNullOrBlank()) {
            queueFromParent(parentId, songId, requested)
        } else {
            // Bare song id: try last browse pages (home song blocks, etc.).
            queueFromCachedParents(songId, requested)
        }
        if (expanded != null) {
            return MediaSession.MediaItemsWithStartPosition(
                expanded.first,
                expanded.second,
                /* startPositionMs= */ 0L,
            )
        }
        val single = resolvePlayable(requested)
        return MediaSession.MediaItemsWithStartPosition(listOf(single), 0, startPositionMs)
    }

    private suspend fun queueFromParent(
        parentId: String,
        songId: String,
        requested: MediaItem,
    ): Pair<List<MediaItem>, Int>? {
        val browseItems = loadChildrenCached(parentId)
        // Mixed tab parents (Home / Explore / Library) hold several song blocks — only queue
        // playable siblings that share the tapped item's CONTENT_STYLE_GROUP_TITLE.
        val sourceItems = if (parentId == HOME || parentId == EXPLORE || parentId == LIBRARY) {
            val match = browseItems.firstOrNull {
                it.mediaMetadata.isPlayable == true && songIdFromMediaId(it.mediaId) == songId
            } ?: return null
            val group = match.mediaMetadata.extras
                ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)
            browseItems.filter {
                it.mediaMetadata.isPlayable == true &&
                    (group == null || group == it.mediaMetadata.extras
                        ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
            }
        } else {
            browseItems
        }
        val playables = playableQueueItems(sourceItems, requested)
        if (playables.isEmpty()) return null
        val index = playables.indexOfFirst { it.mediaId == songId }.takeIf { it >= 0 }
            ?: playables.indexOfFirst {
                songIdFromMediaId(it.mediaId) == songId
            }.takeIf { it >= 0 }
            ?: 0
        return playables to index
    }

    private suspend fun queueFromCachedParents(
        songId: String,
        requested: MediaItem,
    ): Pair<List<MediaItem>, Int>? {
        // Prefer the parent whose cached children contain this song (most recent wins via scan).
        val entries = childrenCache.entries.toList().asReversed()
        for ((parentId, children) in entries) {
            if (parentId == ROOT || parentId == HOME || parentId == EXPLORE || parentId == LIBRARY) {
                // Mixed tabs: only queue playable siblings that share the same group title.
                val match = children.firstOrNull {
                    it.mediaMetadata.isPlayable == true && songIdFromMediaId(it.mediaId) == songId
                } ?: continue
                val group = match.mediaMetadata.extras
                    ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE)
                val siblings = children.filter {
                    it.mediaMetadata.isPlayable == true &&
                        (group == null || group == it.mediaMetadata.extras
                            ?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
                }
                val playables = playableQueueItems(siblings, requested)
                val index = playables.indexOfFirst { it.mediaId == songId }.takeIf { it >= 0 } ?: 0
                if (playables.isNotEmpty()) return playables to index
            } else {
                val has = children.any {
                    it.mediaMetadata.isPlayable == true && songIdFromMediaId(it.mediaId) == songId
                }
                if (has) {
                    return queueFromParent(parentId, songId, requested)
                }
            }
        }
        return null
    }

    private suspend fun playableQueueItems(
        browseItems: List<MediaItem>,
        requested: MediaItem,
    ): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        for (item in browseItems) {
            if (item.mediaMetadata.isPlayable != true) continue
            if (item.mediaId == LOGIN_ID || item.mediaId == EMPTY_ID || item.mediaId == ERROR_ID) continue
            out += resolvePlayable(item)
        }
        // If the tapped item somehow wasn't in the parent list, still include it.
        val reqSong = songIdFromMediaId(requested.mediaId)
        if (out.none { it.mediaId == reqSong }) {
            out.add(0, resolvePlayable(requested))
        }
        return out
    }

    private suspend fun loadChildrenCached(parentId: String): List<MediaItem> {
        childrenCache[parentId]?.let { return it }
        return loadChildren(parentId)
    }

    private suspend fun loadChildren(parentId: String): List<MediaItem> {
        val items = when {
            parentId == ROOT || parentId.isEmpty() -> rootTabs()
            parentId == HOME -> homeChildren()
            parentId == EXPLORE -> exploreChildren()
            parentId == LIBRARY -> libraryFlatChildren()
            parentId == HISTORY -> recentTracks(parentId = HISTORY)
            // Legacy section ids (pre-flatten) still resolve for any cached MediaIds.
            parentId == LIB_PLAYLISTS -> playlistChildren()
            parentId == LIB_ALBUMS -> albumChildren()
            parentId == LIB_SONGS -> likedTracks(parentId = LIB_SONGS)
            parentId == LIB_DOWNLOADS -> downloadTracks(parentId = LIB_DOWNLOADS)
            parentId == LIB_CLOUD -> cloudTracks(parentId = LIB_CLOUD)
            parentId == HOME_DAILY -> dailyRecommendTracks(parentId = HOME_DAILY)
            parentId.startsWith(EXPLORE_CAT_PREFIX) ->
                explorePlaylists(
                    Uri.decode(parentId.removePrefix(EXPLORE_CAT_PREFIX)),
                    groupTitle = Uri.decode(parentId.removePrefix(EXPLORE_CAT_PREFIX)),
                )
            parentId.startsWith(PLAYLIST_PREFIX) ->
                playlistTracks(parentId.removePrefix(PLAYLIST_PREFIX), parentId = parentId)
            parentId.startsWith(ALBUM_PREFIX) ->
                albumTracks(parentId.removePrefix(ALBUM_PREFIX), parentId = parentId)
            else -> emptyList()
        }
        if (parentId.isNotEmpty() && items.isNotEmpty()) {
            childrenCache[parentId] = items
        }
        return items
    }

    private suspend fun resolveItem(mediaId: String): MediaItem? {
        val (parentId, songId) = parseTrackMediaId(mediaId)
        if (!parentId.isNullOrBlank()) {
            loadChildrenCached(parentId).firstOrNull {
                songIdFromMediaId(it.mediaId) == songId || it.mediaId == mediaId
            }?.let { return it }
            return resolveTrackById(songId)
        }
        return when {
            mediaId == ROOT -> browsableItem(ROOT, context.getString(R.string.app_name))
            mediaId in setOf(HOME, EXPLORE, LIBRARY, HISTORY) ->
                rootTabs().firstOrNull { it.mediaId == mediaId }
            mediaId in setOf(LIB_PLAYLISTS, LIB_ALBUMS, LIB_SONGS, LIB_DOWNLOADS, LIB_CLOUD) ->
                libraryChildren().firstOrNull { it.mediaId == mediaId }
            mediaId == HOME_DAILY ->
                browsableItem(HOME_DAILY, DateUtils.getGreeting(), grid = false)
            mediaId.startsWith(EXPLORE_CAT_PREFIX) ||
                mediaId.startsWith(PLAYLIST_PREFIX) ||
                mediaId.startsWith(ALBUM_PREFIX) -> {
                val parent = when {
                    mediaId.startsWith(EXPLORE_CAT_PREFIX) -> EXPLORE
                    mediaId.startsWith(PLAYLIST_PREFIX) -> LIB_PLAYLISTS
                    else -> LIB_ALBUMS
                }
                loadChildren(parent).firstOrNull { it.mediaId == mediaId }
            }
            else -> resolveTrackById(mediaId)
        }
    }

    private suspend fun resolvePlayable(item: MediaItem): MediaItem {
        val songId = songIdFromMediaId(item.mediaId)
        val existing = item.localConfiguration?.tag
        if (existing is com.ljyh.mei.data.model.MediaMetadata) {
            return item.forPlayback(songId).withNowPlayingArtwork()
        }
        val title = item.mediaMetadata.title
        if (item.localConfiguration != null && !title.isNullOrBlank()) {
            return item.forPlayback(songId).withNowPlayingArtwork()
        }
        return resolveTrackById(songId) ?: item.forPlayback(songId)
    }

    /** Ensure ExoPlayer / MediaUriProvider see the bare NetEase song id as [MediaItem.mediaId]. */
    private fun MediaItem.forPlayback(songId: String = songIdFromMediaId(mediaId)): MediaItem {
        if (mediaId == songId && localConfiguration?.uri?.toString() == songId) return this
        val tag = localConfiguration?.tag
        val builder = buildUpon()
            .setMediaId(songId)
            .setUri(songId)
            .setCustomCacheKey(songId)
        if (tag != null) builder.setTag(tag)
        return builder.build()
    }

    private fun rootTabs(): List<MediaItem> = listOf(
        // BROWSABLE=GRID on these parents → playlist/album covers render as a grid in AA.
        tabItem(HOME, R.string.app_tab_home, R.drawable.ic_aa_tab_home, gridChildren = true),
        tabItem(EXPLORE, R.string.app_tab_explore, R.drawable.ic_aa_tab_explore, gridChildren = true),
        tabItem(LIBRARY, R.string.app_tab_library, R.drawable.ic_aa_tab_library, gridChildren = true),
        tabItem(HISTORY, R.string.app_tab_library_history, R.drawable.ic_aa_tab_history, gridChildren = false),
    )

    private fun libraryChildren(): List<MediaItem> = listOf(
        // Designer order: 歌单 / 专辑 / 歌曲 / 下载 / 云盘 (no podcasts)
        sectionItem(LIB_PLAYLISTS, LibraryPage.Playlists.titleRes, gridChildren = true),
        sectionItem(LIB_ALBUMS, LibraryPage.Albums.titleRes, gridChildren = true),
        sectionItem(LIB_SONGS, LibraryPage.Songs.titleRes, gridChildren = false),
        sectionItem(LIB_DOWNLOADS, LibraryPage.Downloads.titleRes, gridChildren = false),
        sectionItem(LIB_CLOUD, LibraryPage.Cloud.titleRes, gridChildren = false),
    )

    private suspend fun homeChildren(): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return when (val page = homeRepository.getHomePageResourceShow(refresh = false)) {
            is Resource.Success -> {
                val items = ArrayList<MediaItem>()
                for (block in page.data) {
                    items += homeBlockItems(block)
                }
                items.ifEmpty {
                    listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
                }
            }
            is Resource.Error -> listOf(
                messageItem(
                    ERROR_ID,
                    page.message.ifBlank { context.getString(R.string.android_auto_error) },
                ),
            )
            Resource.Loading -> listOf(
                messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)),
            )
        }
    }

    private fun homeBlockItems(block: Block): List<MediaItem> {
        val blockData = homeBlockData(block) ?: return emptyList()
        return when (block.positionCode) {
            "PAGE_RECOMMEND_DAILY_RECOMMEND" -> {
                val groupTitle = DateUtils.getGreeting()
                val resources = blockData.get("resources")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { element ->
                        runCatching {
                            gson.fromJson(element.asJsonObject, HomeCardResource::class.java)
                        }.getOrNull()
                    }
                    .orEmpty()
                resources.mapNotNull { resource ->
                    dailyResourceItem(resource, groupTitle)
                }
            }
            in HomePlaylistBlockCodes -> {
                val groupTitle = homeBlockTitle(blockData, block.positionCode)
                    ?: if (block.positionCode == "PAGE_RECOMMEND_RADAR") "雷达歌单" else null
                    ?: return emptyList()
                val resourceArray = if (block.positionCode == "PAGE_RECOMMEND_RADAR") {
                    blockData.get("resources") ?: blockData.get("blockResource")
                } else {
                    blockData.get("resources")
                }?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
                var resources = resourceArray.mapNotNull { element ->
                    runCatching {
                        gson.fromJson(element.asJsonObject, HomeCardResource::class.java)
                    }.getOrNull()
                }
                if (block.positionCode == "PAGE_RECOMMEND_MY_SHEET") {
                    resources = resources.dropLast(1)
                }
                resources.mapNotNull { resource ->
                    val playlistId = resource.resourceId.takeIf { it.isNotBlank() && it != "null" }
                        ?: return@mapNotNull null
                    browsableItem(
                        mediaId = PLAYLIST_PREFIX + playlistId,
                        title = resource.title.ifBlank { playlistId },
                        artworkUri = browseArtwork(resource.coverImg),
                        grid = true,
                        groupTitle = groupTitle,
                    )
                }
            }
            in HomeSongBlockCodes -> {
                val groupTitle = homeBlockTitle(blockData, block.positionCode) ?: return emptyList()
                val pages = blockData.get("content")
                    ?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return emptyList()
                val songs = pages.flatMap { page ->
                    page.takeIf { it.isJsonObject }?.asJsonObject
                        ?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { element ->
                            runCatching {
                                gson.fromJson(
                                    element.asJsonObject,
                                    HomeCommon.Content.Item.Item::class.java,
                                )
                            }.getOrNull()
                        }
                        .orEmpty()
                }
                songs.mapNotNull { song ->
                    val id = song.resourceId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    playableFromParts(
                        id = id,
                        title = song.title,
                        artists = song.artistName.splitArtistNames(),
                        coverUrl = song.coverUrl,
                        groupTitle = groupTitle,
                    )
                }
            }
            else -> emptyList()
        }
    }

    private fun dailyResourceItem(resource: HomeCardResource, groupTitle: String): MediaItem? {
        return when (resource.resourceType) {
            "dailySongs" -> browsableItem(
                mediaId = HOME_DAILY,
                title = resource.singleLineTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: resource.title.trim().ifBlank { DateUtils.getGreeting() },
                artworkUri = browseArtwork(resource.coverImg),
                grid = true,
                groupTitle = groupTitle,
            )
            "playList" -> {
                val id = resource.resourceId.takeIf { it.isNotBlank() } ?: return null
                browsableItem(
                    mediaId = PLAYLIST_PREFIX + id,
                    title = resource.singleLineTitle?.trim()?.takeIf { it.isNotEmpty() }
                        ?: resource.title.trim().ifBlank { id },
                    artworkUri = browseArtwork(resource.coverImg),
                    grid = true,
                    groupTitle = groupTitle,
                )
            }
            "nonstandard" -> {
                val playlistId = parseRecommendationPlaylistId(resource.action, resource.playBtnData)
                    ?: return null
                browsableItem(
                    mediaId = PLAYLIST_PREFIX + playlistId,
                    title = resource.singleLineTitle?.trim()?.takeIf { it.isNotEmpty() }
                        ?: resource.title.trim().ifBlank { playlistId.toString() },
                    artworkUri = browseArtwork(resource.coverImg),
                    grid = true,
                    groupTitle = groupTitle,
                )
            }
            else -> null // FM / podcast / star / similar* — not mapped in this PR
        }
    }

    private suspend fun dailyRecommendTracks(parentId: String = HOME_DAILY): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return when (val result = playlistRepository.getEveryDayRecommendSongs()) {
            is Resource.Success -> result.data.data.dailySongs
                .map { it.toMediaMetadata().toPlayableMediaItem(parentId = parentId) }
                .ifEmpty { listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty))) }
            is Resource.Error -> listOf(
                messageItem(
                    ERROR_ID,
                    result.message.ifBlank { context.getString(R.string.android_auto_error) },
                ),
            )
            Resource.Loading -> listOf(
                messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)),
            )
        }
    }

    /**
     * 发现 flattened to ≤3 levels: Explore → playlists (GRID + category group titles) → tracks.
     * No intermediate category nodes.
     */
    private suspend fun exploreChildren(): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        val items = coroutineScope {
            EXPLORE_FLAT_GROUPS.map { category ->
                async {
                    explorePlaylists(category, groupTitle = category, limit = 12)
                        .contentOnly()
                }
            }.awaitAll().flatten()
        }
        return items.ifEmpty {
            listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
        }
    }

    private suspend fun explorePlaylists(
        category: String,
        groupTitle: String? = null,
        limit: Int = 30,
    ): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        val normalized = if (category == "排行榜") "榜单" else category
        return when (val result = playlistRepository.getHighQualityPlaylist(normalized, limit = limit)) {
            is Resource.Success -> result.data.playlists.map { playlist ->
                browsableItem(
                    mediaId = PLAYLIST_PREFIX + playlist.id,
                    title = playlist.name,
                    subtitle = playlist.creator.nickname,
                    artworkUri = browseArtwork(playlist.coverImgUrl),
                    grid = true,
                    groupTitle = groupTitle,
                )
            }.ifEmpty {
                listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
            }
            is Resource.Error -> listOf(
                messageItem(
                    ERROR_ID,
                    result.message.ifBlank { context.getString(R.string.android_auto_error) },
                ),
            )
            Resource.Loading -> listOf(
                messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)),
            )
        }
    }

    /**
     * 媒体库 flattened: Library → playlists/albums/songs (with 歌单/专辑/… group titles) → tracks.
     * Keeps designer section names as GROUP_TITLE rather than an extra browsable level.
     */
    private suspend fun libraryFlatChildren(): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val playlistsTitle = context.getString(LibraryPage.Playlists.titleRes)
        val albumsTitle = context.getString(LibraryPage.Albums.titleRes)
        val songsTitle = context.getString(LibraryPage.Songs.titleRes)
        val downloadsTitle = context.getString(LibraryPage.Downloads.titleRes)
        val cloudTitle = context.getString(LibraryPage.Cloud.titleRes)

        if (isLoggedIn()) {
            out += playlistChildren().contentOnly().map { it.withGroupTitle(playlistsTitle) }
            out += albumChildren().contentOnly().map { it.withGroupTitle(albumsTitle) }
            out += likedTracks().contentOnly().map { it.withGroupTitle(songsTitle) }
            out += cloudTracks().contentOnly().map { it.withGroupTitle(cloudTitle) }
        }
        out += downloadTracks().contentOnly().map { it.withGroupTitle(downloadsTitle) }

        if (out.isNotEmpty()) return out
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
    }

    private fun List<MediaItem>.contentOnly(): List<MediaItem> =
        filterNot { it.mediaId == LOGIN_ID || it.mediaId == EMPTY_ID || it.mediaId == ERROR_ID }

    private fun MediaItem.withGroupTitle(groupTitle: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle()).apply {
            putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
        }
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    private suspend fun recentTracks(parentId: String = HISTORY): List<MediaItem> {
        val local = historyRepository.getRecentSongs(limit = 50).map { history ->
            history.song.toMediaMetadata().toPlayableMediaItem(parentId = parentId)
        }
        if (local.isNotEmpty()) return local
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return runCatching {
            meloXRepository.recentSongs(limit = 50).map { it.toPlayableMediaItem(parentId = parentId) }
        }.getOrElse {
            Timber.tag(TAG).w(it, "remote recent songs failed")
            listOf(messageItem(ERROR_ID, context.getString(R.string.android_auto_error)))
        }.ifEmpty {
            listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
        }
    }

    private suspend fun playlistChildren(): List<MediaItem> {
        val account = currentAccount() ?: return listOf(loginRequiredItem())
        return runCatching {
            libraryCollections.allPlaylists(account)
                .filterNot { it.specialType == 5 && it.creator.userId.toString() == account.userId }
                .map { playlist ->
                    val owned = playlist.userId.toString() == account.userId ||
                        playlist.creator.userId.toString() == account.userId
                    browsableItem(
                        mediaId = PLAYLIST_PREFIX + playlist.id,
                        title = playlist.name,
                        subtitle = if (owned) {
                            context.getString(R.string.android_auto_track_count, playlist.trackCount)
                        } else {
                            playlist.creator.nickname
                        },
                        artworkUri = browseArtwork(playlist.coverImgUrl),
                        grid = true,
                    )
                }
        }.getOrElse {
            Timber.tag(TAG).w(it, "playlists failed")
            listOf(messageItem(ERROR_ID, context.getString(R.string.android_auto_error)))
        }.ifEmpty {
            listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
        }
    }

    private suspend fun albumChildren(): List<MediaItem> {
        val account = currentAccount() ?: return listOf(loginRequiredItem())
        return runCatching {
            val collected = ArrayList<MediaItem>()
            var offset = 0
            do {
                val page = libraryCollections.albums(account, offset)
                collected += page.items.map { album ->
                    browsableItem(
                        mediaId = ALBUM_PREFIX + album.id,
                        title = album.title,
                        subtitle = album.artist.joinToString(" / ") { it.name },
                        artworkUri = browseArtwork(album.cover),
                        grid = true,
                    )
                }
                if (!page.hasMore || page.items.isEmpty()) break
                offset = page.nextOffset
            } while (collected.size < 200)
            collected
        }.getOrElse {
            Timber.tag(TAG).w(it, "albums failed")
            listOf(messageItem(ERROR_ID, context.getString(R.string.android_auto_error)))
        }.ifEmpty {
            listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
        }
    }

    private suspend fun likedTracks(parentId: String = LIB_SONGS): List<MediaItem> {
        val account = currentAccount() ?: return listOf(loginRequiredItem())
        return runCatching {
            val likedId = libraryCollections.allPlaylists(account)
                .firstOrNull { it.specialType == 5 && it.creator.userId.toString() == account.userId }
                ?.id
                ?: return listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
            // Prefer playlist parent so expand reloads the liked playlist in order.
            playlistTracks(likedId.toString(), parentId = PLAYLIST_PREFIX + likedId)
                .also { childrenCache[parentId] = it }
        }.getOrElse {
            Timber.tag(TAG).w(it, "liked tracks failed")
            listOf(messageItem(ERROR_ID, context.getString(R.string.android_auto_error)))
        }
    }

    private suspend fun downloadTracks(parentId: String = LIB_DOWNLOADS): List<MediaItem> {
        val songs = songRepository.getSongsBySource(SourceType.DOWNLOAD).first()
        return songs.map { it.toMediaMetadata().toPlayableMediaItem(parentId = parentId) }
            .ifEmpty { listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty))) }
    }

    private suspend fun cloudTracks(parentId: String = LIB_CLOUD): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return runCatching {
            val collected = ArrayList<MediaItem>()
            var offset = 0
            do {
                val page = meloXRepository.cloudSongs(offset = offset)
                collected += page.songs.map { it.toPlayableMediaItem(parentId = parentId) }
                if (!page.hasMore || page.songs.isEmpty()) break
                offset = page.nextOffset
            } while (collected.size < 400)
            collected
        }.getOrElse {
            Timber.tag(TAG).w(it, "cloud songs failed")
            listOf(messageItem(ERROR_ID, context.getString(R.string.android_auto_error)))
        }.ifEmpty {
            listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
        }
    }

    private suspend fun playlistTracks(
        playlistId: String,
        parentId: String = PLAYLIST_PREFIX + playlistId,
    ): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return when (val detail = playlistRepository.getPlaylistDetail(playlistId)) {
            is Resource.Success -> {
                // Same complete-track fetch the phone playlist screen uses — no per-song URL prefetch.
                val tracks = runCatching {
                    playlistRepository.getCompletePlaylistTracks(detail.data)
                }.getOrElse {
                    detail.data.playlist.tracks.map { it.toMediaMetadata() }
                }
                tracks.map { it.toPlayableMediaItem(parentId = parentId) }
                    .ifEmpty {
                        listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
                    }
            }
            is Resource.Error -> listOf(
                messageItem(
                    ERROR_ID,
                    detail.message.ifBlank { context.getString(R.string.android_auto_error) },
                ),
            )
            Resource.Loading -> listOf(
                messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)),
            )
        }
    }

    private suspend fun albumTracks(
        albumId: String,
        parentId: String = ALBUM_PREFIX + albumId,
    ): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return when (val detail = playlistRepository.getAlbumDetail(albumId)) {
            is Resource.Success -> detail.data.songs.map {
                it.toMediaMetadata().toPlayableMediaItem(parentId = parentId)
            }
                .ifEmpty {
                    listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
                }
            is Resource.Error -> listOf(
                messageItem(
                    ERROR_ID,
                    detail.message.ifBlank { context.getString(R.string.android_auto_error) },
                ),
            )
            Resource.Loading -> listOf(
                messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)),
            )
        }
    }

    private suspend fun resolveTrackById(mediaId: String): MediaItem? {
        val bareId = songIdFromMediaId(mediaId)
        val songId = bareId.toLongOrNull() ?: return null
        historyRepository.getHistory()
            .firstOrNull { it.song.id == bareId || it.song.id == songId.toString() }
            ?.let { return it.song.toMediaMetadata().toPlayableMediaItem().forPlayback(bareId) }
        return when (val detail = playerRepository.getSongDetail(bareId)) {
            is Resource.Success -> detail.data.songs.firstOrNull()
                ?.toMediaMetadata()
                ?.toPlayableMediaItem()
                ?.forPlayback(bareId)
                ?.withNowPlayingArtwork()
            else -> null
        }
    }

    private suspend fun currentAccount() = libraryCollections.accounts.first()

    private fun isLoggedIn(): Boolean {
        val prefs = context.dataStore
        val userId = prefs[UserIdKey].orEmpty()
        val cookie = prefs[CookieKey].orEmpty()
        return userId.toLongOrNull()?.let { it > 0 } == true && cookie.isNotBlank()
    }

    private fun loginRequiredItem(): MediaItem =
        messageItem(LOGIN_ID, context.getString(R.string.android_auto_login_required))

    private fun tabItem(
        id: String,
        @StringRes titleRes: Int,
        @DrawableRes iconRes: Int,
        gridChildren: Boolean = false,
    ): MediaItem =
        browsableItem(
            mediaId = id,
            title = context.getString(titleRes),
            artworkUri = resourceUri(iconRes),
            // grid=true → EXTRAS_KEY_CONTENT_STYLE_BROWSABLE=GRID for this parent's children.
            grid = gridChildren,
        )

    private fun sectionItem(
        id: String,
        @StringRes titleRes: Int,
        gridChildren: Boolean,
    ): MediaItem = browsableItem(
        mediaId = id,
        title = context.getString(titleRes),
        grid = gridChildren,
    )

    private fun browsableItem(
        mediaId: String,
        title: String,
        subtitle: String? = null,
        artworkUri: Uri? = null,
        grid: Boolean = false,
        groupTitle: String? = null,
        extras: Bundle = Bundle(),
    ): MediaItem {
        val styleExtras = Bundle(extras).apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
                else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
            if (!groupTitle.isNullOrBlank()) {
                putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
            }
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(artworkUri)
                    .setExtras(styleExtras)
                    .build(),
            )
            .build()
    }

    private fun messageItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    private fun playableFromParts(
        id: String,
        title: String,
        artists: List<String>,
        coverUrl: String?,
        durationMs: Long = 0L,
        albumTitle: String = "",
        albumId: Long = 0L,
        artistIds: List<Long> = emptyList(),
        groupTitle: String? = null,
        parentId: String? = HOME,
    ): MediaItem {
        val metadata = com.ljyh.mei.data.model.MediaMetadata(
            id = id.toLongOrNull() ?: id.hashCode().toLong(),
            title = title,
            coverUrl = coverUrl.orEmpty(),
            artists = artists.mapIndexed { index, name ->
                com.ljyh.mei.data.model.MediaMetadata.Artist(
                    id = artistIds.getOrNull(index) ?: name.hashCode().toLong().let { if (it < 0) -it else it },
                    name = name,
                )
            },
            duration = durationMs,
            album = com.ljyh.mei.data.model.MediaMetadata.Album(id = albumId, title = albumTitle),
        )
        return metadata.toPlayableMediaItem(groupTitle = groupTitle, parentId = parentId)
    }

    private fun com.ljyh.mei.data.model.MediaMetadata.toPlayableMediaItem(
        groupTitle: String? = null,
        parentId: String? = null,
    ): MediaItem {
        val art = browseArtwork(coverUrl) ?: resourceUri(R.drawable.ic_aa_artwork_placeholder)
        val extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
            if (!groupTitle.isNullOrBlank()) {
                putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
            }
        }
        val artistLine = artists.joinToString(" / ") { it.name }
        val songId = id.toString()
        // Browse mediaId encodes parent so onSetMediaItems can rebuild the full queue.
        // Uri / cache key stay the bare song id for MediaUriProvider if AA plays without expand.
        val browseId = if (!parentId.isNullOrBlank()) encodeTrackMediaId(parentId, songId) else songId
        return MediaItem.Builder()
            .setMediaId(browseId)
            .setUri(songId)
            .setCustomCacheKey(songId)
            .setTag(this)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(artistLine)
                    .setArtist(artistLine)
                    // Spec: title = song name only, subtitle = artists; no album/duration/VIP in the row.
                    // Album/duration still set for MediaSession now-playing metadata.
                    .setAlbumTitle(album.title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(art)
                    .setExtras(extras)
                    .apply { if (duration > 0) setDurationMs(duration) }
                    .build(),
            )
            .build()
    }

    private fun AccountSong.toPlayableMediaItem(parentId: String? = null): MediaItem {
        val metadata = com.ljyh.mei.data.model.MediaMetadata(
            id = id,
            title = name,
            coverUrl = coverUrl.orEmpty(),
            artists = artists.mapIndexed { index, artistName ->
                com.ljyh.mei.data.model.MediaMetadata.Artist(
                    id = artistIds.getOrNull(index) ?: 0L,
                    name = artistName,
                )
            },
            duration = durationMs,
            album = com.ljyh.mei.data.model.MediaMetadata.Album(id = albumId, title = album),
        )
        return metadata.toPlayableMediaItem(parentId = parentId)
    }

    private fun CloudSong.toPlayableMediaItem(parentId: String? = null): MediaItem {
        val metadata = com.ljyh.mei.data.model.MediaMetadata(
            id = id,
            title = name,
            coverUrl = coverUrl.orEmpty(),
            artists = artist.splitArtistNames().map { name ->
                com.ljyh.mei.data.model.MediaMetadata.Artist(id = 0L, name = name)
            },
            duration = durationMs,
            album = com.ljyh.mei.data.model.MediaMetadata.Album(id = 0L, title = album),
        )
        return metadata.toPlayableMediaItem(parentId = parentId)
    }

    private fun browseArtwork(url: String?): Uri? {
        val trimmed = url?.takeIf { it.isNotBlank() } ?: return null
        if (trimmed.startsWith("/")) return trimmed.toUri()
        val sized = trimmed.aaBrowseCoverUrl()
        val uri = ArtworkContentProvider.browseUri(context, sized)
        ArtworkContentProvider.grantReadForCar(context, uri, lastBrowserPackage)
        return uri
    }

    private fun MediaItem.withNowPlayingArtwork(): MediaItem {
        val meta = localConfiguration?.tag as? com.ljyh.mei.data.model.MediaMetadata ?: return this
        val cover = meta.coverUrl.takeIf { it.isNotBlank() && !it.startsWith("/") } ?: return this
        val uri = ArtworkContentProvider.nowPlayingUri(context, cover.aaNowPlayingCoverUrl())
        ArtworkContentProvider.grantReadForCar(context, uri, lastBrowserPackage)
        return buildUpon()
            .setMediaMetadata(
                mediaMetadata.buildUpon()
                    .setArtworkUri(uri)
                    .build(),
            )
            .build()
    }

    private fun resourceUri(@DrawableRes resId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resId")

    private fun paginate(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        if (pageSize <= 0) return ImmutableList.copyOf(items)
        val from = (page.coerceAtLeast(0) * pageSize).coerceAtMost(items.size)
        val to = (from + pageSize).coerceAtMost(items.size)
        return ImmutableList.copyOf(items.subList(from, to))
    }

    companion object {
        private const val TAG = "AndroidAutoLibrary"
        /** Encodes parent container into a playable child's mediaId: `aa_playlist:1/track:2`. */
        const val TRACK_SEP = "/track:"

        fun encodeTrackMediaId(parentId: String, songId: String): String = "$parentId$TRACK_SEP$songId"

        fun parseTrackMediaId(mediaId: String): Pair<String?, String> {
            val idx = mediaId.indexOf(TRACK_SEP)
            if (idx <= 0) return null to mediaId
            val parent = mediaId.substring(0, idx)
            val song = mediaId.substring(idx + TRACK_SEP.length)
            if (parent.isBlank() || song.isBlank()) return null to mediaId
            return parent to song
        }

        fun songIdFromMediaId(mediaId: String): String = parseTrackMediaId(mediaId).second

        const val ROOT = "aa_root"
        const val HOME = "aa_home"
        const val EXPLORE = "aa_explore"
        const val LIBRARY = "aa_library"
        const val HISTORY = "aa_history"
        const val LIB_PLAYLISTS = "aa_lib_playlists"
        const val LIB_ALBUMS = "aa_lib_albums"
        const val LIB_SONGS = "aa_lib_songs"
        const val LIB_DOWNLOADS = "aa_lib_downloads"
        const val LIB_CLOUD = "aa_lib_cloud"
        const val HOME_DAILY = "aa_home_daily"
        const val EXPLORE_CAT_PREFIX = "aa_explore_cat:"
        const val PLAYLIST_PREFIX = "aa_playlist:"
        const val ALBUM_PREFIX = "aa_album:"
        private const val LOGIN_ID = "aa_login_required"
        private const val EMPTY_ID = "aa_empty"
        private const val ERROR_ID = "aa_error"

        /** Curated 发现 groups shown directly under the Explore tab (GRID + group titles). */
        private val EXPLORE_FLAT_GROUPS =
            listOf(
                "全部", "华语", "欧美", "日语", "韩语",
                "流行", "摇滚", "电子", "说唱", "轻音乐",
                "ACG", "古风", "影视原声", "榜单",
            )

        /**
         * Packages allowed for *sensitive* custom commands (notification left/right etc.).
         * Browse / library / play-from-browse is open to every controller — car OEM hosts
         * often use a different package/uid (or legacy MediaBrowserCompat) than Gearhead.
         */
        val ALLOWED_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.gms",
            "com.android.systemui",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",
        )

        /** Allow-list for sensitive custom commands only — not for browse. */
        fun allowsSensitiveCommands(controller: MediaSession.ControllerInfo, appPackage: String): Boolean {
            if (controller.isTrusted || controller.packageName == appPackage) return true
            return controller.packageName in ALLOWED_PACKAGES
        }

        @Deprecated("Browse is open; use allowsSensitiveCommands for custom cmds", ReplaceWith("allowsSensitiveCommands(controller, appPackage)"))
        fun allowsBrowse(controller: MediaSession.ControllerInfo, appPackage: String): Boolean =
            allowsSensitiveCommands(controller, appPackage)
    }

    private fun recordDiag(
        controller: MediaSession.ControllerInfo,
        method: String,
        parentId: String?,
        result: String,
    ) {
        AaBrowseDiagnostics.record(
            packageName = controller.packageName,
            uid = controller.uid,
            isTrusted = controller.isTrusted,
            controllerVersion = controller.controllerVersion,
            method = method,
            parentId = parentId,
            result = result,
        )
    }
}

private fun String.splitArtistNames(): List<String> =
    split('/', '、', ',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf(this.trim()).filter { it.isNotEmpty() } }
