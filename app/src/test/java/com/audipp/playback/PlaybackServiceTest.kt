package com.audipp.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.Process
import android.os.ParcelFileDescriptor
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.audipp.domain.model.*
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class PlaybackServiceTest {
    private lateinit var lifecycle: ServiceController<PlaybackService>
    private lateinit var service: PlaybackService
    private val controllers = mutableListOf<MediaController>()
    private val ownApp get() = controllerInfo(Process.myUid(), true)

    @Before
    fun createService() {
        lifecycle = Robolectric.buildService(PlaybackService::class.java).create()
        service = lifecycle.get()
    }

    @After
    fun destroyService() {
        controllers.forEach { it.release() }
        lifecycle.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun multipleControllersShareServicePlayerAndDisconnectDoesNotDestroySession() {
        val session = service.onGetSession(ownApp)!!
        val player = session.player
        val first = connect(session.token)
        assertTrue(first.isCommandAvailable(Player.COMMAND_STOP))
        assertFalse(first.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        first.release()
        val second = connect(session.token)
        assertSame(session, service.onGetSession(ownApp))
        assertSame(player, service.onGetSession(ownApp)!!.player)
        assertTrue(second.isConnected)
        assertEquals(PlaybackPhase.IDLE, PlaybackConnection.snapshot(second.sessionExtras)!!.phase)
    }

    @Test
    fun connectionPolicyAndEmptyBrowseDoNotExposeSources() {
        assertNull(service.onGetSession(controllerInfo(Process.myUid() + 1, false)))
        val session = service.onGetSession(ownApp)!!
        val callback = service.SessionCallback()
        val untrusted = callback.onConnect(session, controllerInfo(Process.myUid() + 1, false))
        assertFalse(untrusted.isAccepted)
        val trusted = controllerInfo(Process.myUid() + 1, true)
        val permissions = callback.onConnect(session, trusted)
        assertTrue(permissions.isAccepted)
        assertFalse(permissions.availableSessionCommands.contains(PlaybackConnection.playLocalCommand))
        assertFalse(permissions.availablePlayerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(permissions.availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE))
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, callback.onCustomCommand(session,
            trusted, PlaybackConnection.playLocalCommand, Bundle.EMPTY).get().resultCode)
        val root = callback.onGetLibraryRoot(session, ownApp, null).get().value!!
        assertNull(root.localConfiguration)
        assertTrue(root.mediaMetadata.isBrowsable!!)
        assertTrue(callback.onGetChildren(session, ownApp, root.mediaId, 0, 50, null)
            .get().value!!.isEmpty())
        assertEquals(SessionError.ERROR_BAD_VALUE,
            callback.onGetChildren(session, ownApp, "private", 0, 1, null).get().resultCode)
    }

    @Test
    fun mediaControllerReturnsCommonPermissionErrorAndReleasesSource() {
        registerProvider("denied", DeniedProvider())
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        val result = await(PlaybackConnection.playLocal(controller, QueueEntryId("entry"), variant()))
        assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        val error = PlaybackConnection.error(result)!!
        assertEquals(ErrorDomain.PERMISSION_DENIED, error.domain)
        assertFalse(ModelJson.encode(error).contains("private"))
        shadowOf(Looper.getMainLooper()).idle()
        val snapshot = PlaybackConnection.snapshot(controller.sessionExtras)!!
        assertEquals(PlaybackPhase.ERROR, snapshot.phase)
        assertEquals(ErrorDomain.PERMISSION_DENIED, snapshot.error!!.domain)
        assertNull(snapshot.effectiveSource)
        assertEquals(0, session.player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, session.player.playbackState)
    }

    @Test
    fun invalidCommandCannotPrepareArbitraryMedia() {
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        assertEquals(SessionError.ERROR_BAD_VALUE, await(controller.sendCustomCommand(
            PlaybackConnection.playLocalCommand, Bundle.EMPTY)).resultCode)
        for (args in listOf(
            Bundle().apply {
                putString(PlaybackConnection.ENTRY_ID, "x".repeat(257))
                putString(PlaybackConnection.VARIANT, ModelJson.encode(variant()))
            },
            Bundle().apply {
                putString(PlaybackConnection.ENTRY_ID, "entry")
                putString(PlaybackConnection.VARIANT, "x".repeat(16_385))
            },
        )) {
            val rejected = await(controller.sendCustomCommand(PlaybackConnection.playLocalCommand, args))
            assertEquals(SessionError.ERROR_BAD_VALUE, rejected.resultCode)
            assertEquals("INVALID_LOCAL_REQUEST", PlaybackConnection.error(rejected)!!.code)
        }
        val result = await(PlaybackConnection.playLocal(controller, QueueEntryId("x".repeat(256)),
            variant().copy(source = variant().source.copy(localDocumentUri = "https://private/song"))))
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, result.resultCode)
        assertEquals(ErrorDomain.FORMAT_UNSUPPORTED, PlaybackConnection.error(result)!!.domain)
        assertEquals(0, session.player.mediaItemCount)
    }

    @Test
    fun stopFromMediaControllerCancelsPendingProviderAndCannotStartLater() {
        val opened = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        registerProvider("slow", object : DeniedProvider() {
            override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?,
                signal: CancellationSignal?):
                AssetFileDescriptor? {
                signal!!.setOnCancelListener { cancelled.countDown() }
                opened.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS))
                signal.throwIfCanceled()
                return null
            }
        })
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        val slow = variant().copy(source = variant().source.copy(localDocumentUri = "content://slow/song"))
        val pending = PlaybackConnection.playLocal(controller, QueueEntryId("entry"), slow)
        val deadline = System.nanoTime() + 3_000_000_000L
        while (opened.count > 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(0L, opened.count)
        controller.stop()
        assertEquals(SessionResult.RESULT_INFO_SKIPPED, await(pending).resultCode)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0L, cancelled.count)
        assertEquals(PlaybackPhase.IDLE, PlaybackConnection.snapshot(controller.sessionExtras)!!.phase)
        assertEquals(0, session.player.mediaItemCount)
    }

    @Test
    fun corruptDocumentReportsFormatFailureAndClearsEngine() {
        val file = File(service.cacheDir, "corrupt-audio.bin").apply {
            writeBytes("deterministic unsupported audio fixture".toByteArray())
        }
        val opens = AtomicInteger()
        registerProvider("corrupt", object : DeniedProvider() {
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                opens.incrementAndGet()
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        })
        val session = service.onGetSession(ownApp)!!
        val controller = connect(session.token)
        val corrupt = variant().copy(source = variant().source.copy(localDocumentUri = "content://corrupt/song"))
        assertEquals(SessionResult.RESULT_SUCCESS,
            await(PlaybackConnection.playLocal(controller, QueueEntryId("entry"), corrupt)).resultCode)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (PlaybackConnection.snapshot(controller.sessionExtras)?.phase != PlaybackPhase.ERROR &&
            System.nanoTime() < deadline) {
            shadowOf(ReflectionHelpers.getField<ExoPlayer>(service, "player").playbackLooper)
                .idleFor(100, TimeUnit.MILLISECONDS)
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        val snapshot = PlaybackConnection.snapshot(controller.sessionExtras)!!
        assertEquals("snapshot=$snapshot; state=${session.player.playbackState}; opens=$opens",
            ErrorDomain.FORMAT_UNSUPPORTED, snapshot.error?.domain)
        assertEquals(0, session.player.mediaItemCount)
        assertEquals(Player.STATE_IDLE, session.player.playbackState)
        assertFalse(snapshot.toString().contains("content://"))
        file.delete()
    }

    @Test
    fun codecAndEngineErrorsAreSanitizedAndClassified() {
        val error = PlaybackException("content://private/song", IOException("secret"),
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED).toCommonError()
        assertEquals(ErrorDomain.FORMAT_UNSUPPORTED, error.domain)
        assertFalse(error.retryable)
        assertFalse(ModelJson.encode(error).contains("private"))
        assertEquals(ErrorDomain.PERMISSION_DENIED, PlaybackException("private", null,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION).toCommonError().domain)
        assertEquals(ErrorDomain.SOURCE_IO, PlaybackException("private", null,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND).toCommonError().domain)
        assertEquals(ErrorDomain.PERMISSION_DENIED, PlaybackException("private", SecurityException("private"),
            PlaybackException.ERROR_CODE_UNSPECIFIED).toCommonError().domain)
    }

    private fun connect(token: androidx.media3.session.SessionToken): MediaController =
        await(MediaController.Builder(service, token).buildAsync()).also { controllers += it }

    private fun registerProvider(authority: String, provider: ContentProvider) {
        provider.attachInfo(service, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!future.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Media3 response timed out", future.isDone)
        return future.get()
    }

    private fun variant() = TrackVariant(VariantId("variant"), TrackId("track"),
        SourceRef(SourceId("source"), SourceKind.LOCAL,
            localDocumentUri = "content://denied/private-song", availability = Availability.PLAYABLE))

    private fun controllerInfo(uid: Int, trusted: Boolean) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo("com.audipp", Process.myPid(),
            uid, 0, 0, trusted, Bundle.EMPTY, true)

    open class DeniedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/wav"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? =
            throw SecurityException("private URI must never leave provider")
    }
}
