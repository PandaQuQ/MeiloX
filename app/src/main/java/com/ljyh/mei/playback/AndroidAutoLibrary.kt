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

    private suspend fun loadChildren(parentId: String): List<MediaItem> = when {
        parentId == ROOT || parentId.isEmpty() -> rootTabs()
        parentId == HOME -> homeChildren()
        parentId == EXPLORE -> exploreChildren()
        parentId == LIBRARY -> libraryFlatChildren()
        parentId == HISTORY -> recentTracks()
        // Legacy section ids (pre-flatten) still resolve for any cached MediaIds.
        parentId == LIB_PLAYLISTS -> playlistChildren()
        parentId == LIB_ALBUMS -> albumChildren()
        parentId == LIB_SONGS -> likedTracks()
        parentId == LIB_DOWNLOADS -> downloadTracks()
        parentId == LIB_CLOUD -> cloudTracks()
        parentId == HOME_DAILY -> dailyRecommendTracks()
        parentId.startsWith(EXPLORE_CAT_PREFIX) ->
            explorePlaylists(
                Uri.decode(parentId.removePrefix(EXPLORE_CAT_PREFIX)),
                groupTitle = Uri.decode(parentId.removePrefix(EXPLORE_CAT_PREFIX)),
            )
        parentId.startsWith(PLAYLIST_PREFIX) -> playlistTracks(parentId.removePrefix(PLAYLIST_PREFIX))
        parentId.startsWith(ALBUM_PREFIX) -> albumTracks(parentId.removePrefix(ALBUM_PREFIX))
        else -> emptyList()
    }

    private suspend fun resolveItem(mediaId: String): MediaItem? = when {
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
            // Best-effort: rebuild parent listing and pick matching child.
            val parent = when {
                mediaId.startsWith(EXPLORE_CAT_PREFIX) -> EXPLORE
                mediaId.startsWith(PLAYLIST_PREFIX) -> LIB_PLAYLISTS
                else -> LIB_ALBUMS
            }
            loadChildren(parent).firstOrNull { it.mediaId == mediaId }
        }
        else -> resolveTrackById(mediaId)
    }

    private suspend fun resolvePlayable(item: MediaItem): MediaItem {
        val existing = item.localConfiguration?.tag
        if (existing is com.ljyh.mei.data.model.MediaMetadata) {
            return item.withNowPlayingArtwork()
        }
        val title = item.mediaMetadata.title
        if (item.localConfiguration != null && !title.isNullOrBlank()) {
            return item.withNowPlayingArtwork()
        }
        return resolveTrackById(item.mediaId) ?: item
    }

    private fun rootTabs(): List<MediaItem> = listOf(
        tabItem(HOME, R.string.app_tab_home, R.drawable.ic_aa_tab_home),
        tabItem(EXPLORE, R.string.app_tab_explore, R.drawable.ic_aa_tab_explore),
        tabItem(LIBRARY, R.string.app_tab_library, R.drawable.ic_aa_tab_library),
        tabItem(HISTORY, R.string.app_tab_library_history, R.drawable.ic_aa_tab_history),
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

    private suspend fun dailyRecommendTracks(): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return when (val result = playlistRepository.getEveryDayRecommendSongs()) {
            is Resource.Success -> result.data.data.dailySongs
                .map { it.toMediaMetadata().toPlayableMediaItem() }
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

    private suspend fun recentTracks(): List<MediaItem> {
        val local = historyRepository.getRecentSongs(limit = 50).map { history ->
            history.song.toMediaMetadata().toPlayableMediaItem()
        }
        if (local.isNotEmpty()) return local
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return runCatching {
            meloXRepository.recentSongs(limit = 50).map { it.toPlayableMediaItem() }
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
                    browsableItem(
                        mediaId = PLAYLIST_PREFIX + playlist.id,
                        title = playlist.name,
                        subtitle = playlist.creator.nickname,
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

    private suspend fun likedTracks(): List<MediaItem> {
        val account = currentAccount() ?: return listOf(loginRequiredItem())
        return runCatching {
            val likedId = libraryCollections.allPlaylists(account)
                .firstOrNull { it.specialType == 5 && it.creator.userId.toString() == account.userId }
                ?.id
                ?: return listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty)))
            playlistTracks(likedId.toString())
        }.getOrElse {
            Timber.tag(TAG).w(it, "liked tracks failed")
            listOf(messageItem(ERROR_ID, context.getString(R.string.android_auto_error)))
        }
    }

    private suspend fun downloadTracks(): List<MediaItem> {
        val songs = songRepository.getSongsBySource(SourceType.DOWNLOAD).first()
        return songs.map { it.toMediaMetadata().toPlayableMediaItem() }
            .ifEmpty { listOf(messageItem(EMPTY_ID, context.getString(R.string.android_auto_empty))) }
    }

    private suspend fun cloudTracks(): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return runCatching {
            val collected = ArrayList<MediaItem>()
            var offset = 0
            do {
                val page = meloXRepository.cloudSongs(offset = offset)
                collected += page.songs.map { it.toPlayableMediaItem() }
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

    private suspend fun playlistTracks(playlistId: String): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return when (val detail = playlistRepository.getPlaylistDetail(playlistId)) {
            is Resource.Success -> {
                val tracks = runCatching {
                    playlistRepository.getCompletePlaylistTracks(detail.data)
                }.getOrElse {
                    detail.data.playlist.tracks.map { it.toMediaMetadata() }
                }
                tracks.map { it.toPlayableMediaItem() }
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

    private suspend fun albumTracks(albumId: String): List<MediaItem> {
        if (!isLoggedIn()) return listOf(loginRequiredItem())
        return when (val detail = playlistRepository.getAlbumDetail(albumId)) {
            is Resource.Success -> detail.data.songs.map { it.toMediaMetadata().toPlayableMediaItem() }
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
        val songId = mediaId.toLongOrNull() ?: return null
        historyRepository.getHistory()
            .firstOrNull { it.song.id == mediaId || it.song.id == songId.toString() }
            ?.let { return it.song.toMediaMetadata().toPlayableMediaItem() }
        return when (val detail = playerRepository.getSongDetail(mediaId)) {
            is Resource.Success -> detail.data.songs.firstOrNull()
                ?.toMediaMetadata()
                ?.toPlayableMediaItem()
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

    private fun tabItem(id: String, @StringRes titleRes: Int, @DrawableRes iconRes: Int): MediaItem =
        browsableItem(
            mediaId = id,
            title = context.getString(titleRes),
            artworkUri = resourceUri(iconRes),
            grid = false,
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
        return metadata.toPlayableMediaItem(groupTitle)
    }

    private fun com.ljyh.mei.data.model.MediaMetadata.toPlayableMediaItem(
        groupTitle: String? = null,
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
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(id.toString())
            .setCustomCacheKey(id.toString())
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

    private fun AccountSong.toPlayableMediaItem(): MediaItem {
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
        return metadata.toPlayableMediaItem()
    }

    private fun CloudSong.toPlayableMediaItem(): MediaItem {
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
        return metadata.toPlayableMediaItem()
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

        /**
         * Controllers that represent Android Auto / car hosts (not SystemUI/GMS).
         * Used to gate car now-playing lyric overlay without affecting the phone notification.
         */
        val ANDROID_AUTO_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.carassistant",
        )

        fun isAndroidAutoController(controller: MediaSession.ControllerInfo): Boolean {
            val pkg = controller.packageName
            if (pkg in ANDROID_AUTO_PACKAGES) return true
            // OEM / DHU variants often keep "gearhead" or "projection" in the package name.
            return pkg.contains("gearhead", ignoreCase = true) ||
                pkg.contains("projection.gearhead", ignoreCase = true)
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
