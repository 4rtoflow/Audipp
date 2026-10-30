package com.audipp.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audipp.MainActivity
import com.audipp.R
import com.audipp.domain.model.ErrorDomain
import com.audipp.domain.model.ModelJson
import com.audipp.domain.model.PlaybackError
import com.audipp.domain.model.PlaybackPhase
import com.audipp.domain.model.QueueEntryId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.UserAction
import com.audipp.playback.source.AndroidDocumentAccess
import com.audipp.playback.source.SourceDescriptor
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var playback: LocalPlayback
    private var preparation: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Media3 decoder/provider exception logs can contain document URIs and causes.
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
        val selector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        player = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory {
                ContentDataSource(this)
            })
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        playback = LocalPlayback(object : LocalPlaybackEngine {
            override fun prepare(entryId: QueueEntryId, document: SourceDescriptor.LocalDocument) {
                player.setMediaItem(MediaItem.Builder().setMediaId(entryId.value).setUri(document.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(getString(R.string.app_name))
                        .setIsBrowsable(false).setIsPlayable(true).build()).build())
                player.prepare()
                player.play()
            }

            override fun reset() {
                player.stop()
                player.clearMediaItems()
            }
        }, AndroidDocumentAccess(contentResolver)::check)
        val sessionPlayer = object : ForwardingSimpleBasePlayer(player) {
            override fun getState(): State {
                val state = super.getState()
                val error = state.playerError ?: return state
                return state.buildUpon().setPlayerError(
                    PlaybackException("LOCAL_PLAYBACK_FAILURE", null, error.errorCode),
                ).build()
            }

            override fun handleStop(): ListenableFuture<*> {
                preparation?.cancel()
                playback.stop()
                return Futures.immediateVoidFuture()
            }

            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                if (!playWhenReady && playback.snapshot.value.phase == PlaybackPhase.RESOLVING) {
                    preparation?.cancel()
                    playback.stop()
                    return Futures.immediateVoidFuture()
                }
                return super.handleSetPlayWhenReady(playWhenReady)
            }
        }
        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, SessionCallback())
            .setSessionActivity(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = updateEngineSnapshot()

            override fun onPlayerError(error: PlaybackException) {
                preparation?.cancel()
                playback.fail(error.toCommonError())
            }
        })
        scope.launch {
            playback.snapshot.collect {
                mediaSession?.let { session ->
                    session.connectedControllers.filter { it.uid == Process.myUid() }.forEach {
                        session.setSessionExtras(it, snapshotExtras())
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (player.isPlaying) updateEngineSnapshot()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        if (controllerInfo.uid == Process.myUid() || controllerInfo.isTrusted) mediaSession else null

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        if (::playback.isInitialized) playback.stop()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun updateEngineSnapshot() {
        val phase = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
            Player.STATE_READY -> if (player.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED
            else -> PlaybackPhase.IDLE
        }
        playback.onEngineState(phase, player.currentPosition.takeUnless { it == C.TIME_UNSET },
            player.duration.takeUnless { it == C.TIME_UNSET }, player.isCurrentMediaItemSeekable)
    }

    private fun snapshotExtras() = Bundle().apply {
        putString(PlaybackConnection.SNAPSHOT,
            ModelJson.encode(playback.snapshot.value.withoutDocumentUri()))
    }

    internal inner class SessionCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo):
            MediaSession.ConnectionResult {
            val ownApp = controller.uid == Process.myUid()
            if (!ownApp && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val commands = SessionCommands.Builder()
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
            if (ownApp) commands.add(PlaybackConnection.playLocalCommand)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands.build())
                .setSessionExtras(if (ownApp) snapshotExtras() else Bundle.EMPTY)
                .setAvailablePlayerCommands(Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
                ).build()).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid() ||
                customCommand.customAction != PlaybackConnection.PLAY_LOCAL
            ) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            val request = try {
                val entryId = requireNotNull(args.getString(PlaybackConnection.ENTRY_ID))
                val document = requireNotNull(args.getString(PlaybackConnection.VARIANT))
                require(entryId.length <= 256 && document.length <= 16_384)
                QueueEntryId(entryId) to ModelJson.decode<TrackVariant>(document)
            } catch (_: IllegalArgumentException) {
                return Futures.immediateFuture(errorResult(playbackError(ErrorDomain.FORMAT_UNSUPPORTED,
                    "INVALID_LOCAL_REQUEST", false, UserAction.RESELECT_SOURCE), SessionError.ERROR_BAD_VALUE))
            }
            preparation?.cancel()
            val future = SettableFuture.create<SessionResult>()
            preparation = scope.launch {
                try {
                    val error = playback.start(request.first, request.second)
                    future.set(if (error == null) SessionResult(SessionResult.RESULT_SUCCESS) else errorResult(error))
                } catch (_: CancellationException) {
                    future.set(SessionResult(SessionError.INFO_CANCELLED))
                }
            }
            return future
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId(ROOT)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(getString(R.string.app_name))
                    .setIsBrowsable(true).setIsPlayable(false).build()).build(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?):
            ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(
                if (parentId == ROOT) LibraryResult.ofItemList(emptyList(), params)
                else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
            )
    }

    private companion object {
        const val ROOT = "audipp-root"
    }
}

private fun errorResult(error: PlaybackError,
    code: Int = when (error.domain) {
        ErrorDomain.PERMISSION_DENIED -> SessionError.ERROR_PERMISSION_DENIED
        ErrorDomain.FORMAT_UNSUPPORTED -> SessionError.ERROR_NOT_SUPPORTED
        ErrorDomain.SOURCE_IO -> SessionError.ERROR_IO
        else -> SessionError.ERROR_UNKNOWN
    }) = SessionResult(code, Bundle().apply {
    putString(PlaybackConnection.ERROR, ModelJson.encode(error))
})

internal fun PlaybackException.toCommonError(): PlaybackError {
    if (generateSequence<Throwable>(this) { it.cause }.take(8).any { it is SecurityException }) {
        return playbackError(ErrorDomain.PERMISSION_DENIED, "DOCUMENT_PERMISSION_DENIED", true,
            UserAction.REQUEST_PERMISSION)
    }
    return when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> playbackError(ErrorDomain.PERMISSION_DENIED,
            "DOCUMENT_PERMISSION_DENIED", true, UserAction.REQUEST_PERMISSION)
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> playbackError(ErrorDomain.FORMAT_UNSUPPORTED,
            "LOCAL_FORMAT_UNSUPPORTED", false, UserAction.RESELECT_SOURCE)
        in 2000..2999 -> playbackError(ErrorDomain.SOURCE_IO,
            "LOCAL_SOURCE_IO", true, UserAction.RESELECT_SOURCE)
        else -> playbackError(ErrorDomain.PLAYBACK_ENGINE, "LOCAL_ENGINE_FAILURE", true, UserAction.RETRY)
    }
}
