package com.audipp.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.audipp.domain.model.ModelJson
import com.audipp.domain.model.PlaybackError
import com.audipp.domain.model.PlaybackSnapshot
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TrackVariant
import com.google.common.util.concurrent.ListenableFuture

/** Connect on the main looper; release the future with MediaController.releaseFuture on UI teardown. */
@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackConnection {
    internal const val PLAY_LOCAL = "com.audipp.playback.PLAY_LOCAL"
    internal const val VARIANT = "variant"
    internal const val ENTRY_ID = "entryId"
    internal const val SNAPSHOT = "playbackSnapshot"
    internal const val ERROR = "playbackError"
    internal val playLocalCommand = SessionCommand(PLAY_LOCAL, Bundle.EMPTY)

    fun connect(context: Context, listener: MediaController.Listener): ListenableFuture<MediaController> =
        MediaController.Builder(context.applicationContext,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(listener).buildAsync()

    /** Success acknowledges preparation only; actual engine state/errors arrive in session extras. */
    fun playLocal(controller: MediaController, entryId: QueueEntryId,
        variant: TrackVariant): ListenableFuture<SessionResult> = controller.sendCustomCommand(
        playLocalCommand, Bundle().apply {
            putString(ENTRY_ID, entryId.value)
            putString(VARIANT, ModelJson.encode(variant))
        },
    )

    fun snapshot(extras: Bundle): PlaybackSnapshot? = extras.getString(SNAPSHOT)?.let {
        ModelJson.decode<PlaybackSnapshot>(it)
    }

    fun error(result: SessionResult): PlaybackError? = result.extras.getString(ERROR)?.let {
        ModelJson.decode<PlaybackError>(it)
    }
}
