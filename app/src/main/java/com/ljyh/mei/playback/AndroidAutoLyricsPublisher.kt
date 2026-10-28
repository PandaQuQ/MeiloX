package com.ljyh.mei.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.ljyh.mei.constants.AndroidAutoLyricsEnabledKey
import com.ljyh.mei.constants.LyricTranslationEnabledKey
import com.ljyh.mei.data.model.metadata
import com.ljyh.mei.ui.model.LyricSource
import com.ljyh.mei.utils.dataStore
import com.ljyh.mei.utils.lyric.LyricManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * While an Android Auto (gearhead etc.) controller is connected and the user setting is on,
 * overlays the current lyric line onto session now-playing metadata for the car screen:
 * title = lyric line, artist/subtitle = "歌名 · 歌手".
 *
 * Media3 does not expose per-controller metadata, so this is session-wide only while a car
 * controller is connected; phone notification/lock screen stay normal when the car disconnects.
 * Browse lists are untouched (player queue MediaItems keep their original metadata).
 */
internal class AndroidAutoLyricsPublisher(
    private val context: Context,
    private val player: Player,
    private val externalPlayer: ExternalMediaPlayer,
    private val lyricManager: LyricManager,
    private val scope: CoroutineScope,
) : Player.Listener {
    private val carControllerKeys = linkedSetOf<String>()
    private var settingEnabled = true
    private var displayTranslation = true
    private var syncJob: Job? = null
    private var lastPublishedLine: String? = null
    private var lastPublishedMediaId: String? = null

    init {
        player.addListener(this)
        scope.launch {
            context.dataStore.data.map {
                (it[AndroidAutoLyricsEnabledKey] ?: true) to (it[LyricTranslationEnabledKey] ?: true)
            }.distinctUntilChanged().collect { (enabled, translation) ->
                settingEnabled = enabled
                displayTranslation = translation
                lastPublishedLine = null
                syncOverlay()
            }
        }
        scope.launch {
            lyricManager.lyricData.collect {
                lastPublishedLine = null
                syncOverlay()
            }
        }
    }

    fun onControllerConnected(controller: MediaSession.ControllerInfo) {
        if (!AndroidAutoLibrary.isAndroidAutoController(controller)) return
        val key = controllerKey(controller)
        if (!carControllerKeys.add(key)) return
        AaBrowseDiagnostics.record(
            packageName = controller.packageName,
            uid = controller.uid,
            isTrusted = controller.isTrusted,
            controllerVersion = controller.controllerVersion,
            method = "aaLyrics.carConnected",
            parentId = null,
            result = "cars=${carControllerKeys.size}",
        )
        lastPublishedLine = null
        syncOverlay()
    }

    fun onControllerDisconnected(controller: MediaSession.ControllerInfo) {
        if (!AndroidAutoLibrary.isAndroidAutoController(controller)) return
        val key = controllerKey(controller)
        if (!carControllerKeys.remove(key)) return
        AaBrowseDiagnostics.record(
            packageName = controller.packageName,
            uid = controller.uid,
            isTrusted = controller.isTrusted,
            controllerVersion = controller.controllerVersion,
            method = "aaLyrics.carDisconnected",
            parentId = null,
            result = "cars=${carControllerKeys.size}",
        )
        lastPublishedLine = null
        syncOverlay()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (!shouldOverlay()) return
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                lastPublishedLine = null
                lastPublishedMediaId = null
            }
            syncOverlay()
        }
    }

    private fun shouldOverlay(): Boolean = settingEnabled && carControllerKeys.isNotEmpty()

    private fun syncOverlay() {
        syncJob?.cancel()
        syncJob = null
        if (!shouldOverlay()) {
            clearOverlay()
            return
        }
        val mediaItem = player.currentMediaItem ?: run {
            clearOverlay()
            return
        }
        val meta = mediaItem.metadata
        val songId = meta?.id?.toString() ?: mediaItem.mediaId
        val data = lyricManager.lyricData.value.takeIf { lyricManager.songId == songId }
        if (meta == null || data == null || data.isPureMusic ||
            data.source == LyricSource.Empty || data.source == LyricSource.Loading
        ) {
            clearOverlay()
            return
        }
        val song = meta.toSystemLyricSong(data)
        val lines = song.lyrics.orEmpty()
        if (lines.isEmpty()) {
            clearOverlay()
            return
        }
        val songTitle = meta.title
        val artist = meta.artists.joinToString(" / ") { it.name }
        syncJob = scope.launch {
            while (isActive && shouldOverlay() && player.currentMediaItem?.mediaId == mediaItem.mediaId) {
                val position = player.currentPosition.coerceAtLeast(0L)
                val line = lines.lastOrNull { position >= it.begin }
                val text = line?.let { currentLineText(it) }?.takeIf { it.isNotBlank() }
                publishLine(mediaItem.mediaId, text, songTitle, artist)
                if (!player.isPlaying) break
                val nextBoundary = lines.firstOrNull { it.begin > position }?.begin ?: break
                val waitMs = ((nextBoundary - position) / player.playbackParameters.speed.toDouble()).toLong()
                delay(waitMs.coerceAtLeast(20L))
            }
        }
    }

    private fun currentLineText(line: io.github.proify.lyricon.lyric.model.RichLyricLine): String? {
        if (displayTranslation) {
            line.translation?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return line.text?.takeIf { it.isNotBlank() }
    }

    private fun publishLine(mediaId: String, lyric: String?, songTitle: String, artist: String) {
        if (lyric == null) {
            clearOverlay()
            return
        }
        if (mediaId == lastPublishedMediaId && lyric == lastPublishedLine) return
        val subtitle = listOf(songTitle, artist).filter { it.isNotBlank() }.joinToString(" · ")
        externalPlayer.setCarNowPlayingOverlay(mediaId, lyric, subtitle)
        lastPublishedMediaId = mediaId
        lastPublishedLine = lyric
        AaBrowseDiagnostics.record(
            packageName = "aa-lyrics",
            uid = -1,
            isTrusted = true,
            controllerVersion = 0,
            method = "aaLyrics.line",
            parentId = mediaId,
            result = lyric.take(80),
        )
    }

    private fun clearOverlay() {
        if (lastPublishedLine != null || lastPublishedMediaId != null) {
            AaBrowseDiagnostics.record(
                packageName = "aa-lyrics",
                uid = -1,
                isTrusted = true,
                controllerVersion = 0,
                method = "aaLyrics.clear",
                parentId = lastPublishedMediaId,
                result = "restored",
            )
        }
        lastPublishedLine = null
        lastPublishedMediaId = null
        externalPlayer.setCarNowPlayingOverlay(null, null, null)
    }

    fun release() {
        player.removeListener(this)
        syncJob?.cancel()
        syncJob = null
        carControllerKeys.clear()
        clearOverlay()
    }

    private fun controllerKey(controller: MediaSession.ControllerInfo): String =
        "${controller.packageName}:${controller.uid}"
}
