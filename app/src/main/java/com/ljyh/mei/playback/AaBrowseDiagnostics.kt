package com.ljyh.mei.playback

/**
 * Ring-buffer diagnostics for Android Auto browse/connect.
 * Main ships a no-op recorder; debug source set installs a persisted sink.
 * Release builds stay unaffected beyond these cheap no-op calls.
 */
object AaBrowseDiagnostics {
    data class Entry(
        val timestampMs: Long,
        val packageName: String,
        val uid: Int,
        val isTrusted: Boolean,
        val controllerVersion: Int,
        val isLegacy: Boolean,
        val method: String,
        val parentId: String?,
        val result: String,
    )

    interface Recorder {
        fun record(entry: Entry)
        fun snapshot(): List<Entry>
        fun clear()
    }

    object NoOpRecorder : Recorder {
        override fun record(entry: Entry) = Unit
        override fun snapshot(): List<Entry> = emptyList()
        override fun clear() = Unit
    }

    @Volatile
    var recorder: Recorder = NoOpRecorder

    const val CAPACITY = 20

    fun record(
        packageName: String,
        uid: Int,
        isTrusted: Boolean,
        controllerVersion: Int,
        method: String,
        parentId: String? = null,
        result: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        recorder.record(
            Entry(
                timestampMs = timestampMs,
                packageName = packageName,
                uid = uid,
                isTrusted = isTrusted,
                controllerVersion = controllerVersion,
                isLegacy = controllerVersion ==
                    androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
                method = method,
                parentId = parentId,
                result = result,
            ),
        )
    }
}
