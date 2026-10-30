package com.audipp.playback

import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.audipp.domain.model.Availability
import com.audipp.playback.source.AndroidDocumentAccess
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35])
class AndroidDocumentAccessTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun successfulProbeClosesDescriptorAndRejectsNonContentBeforeProviderAccess() = runBlocking {
        val file = File(context.cacheDir, "access-fixture").apply { writeText("fixture") }
        var descriptor: ParcelFileDescriptor? = null
        register(object : PlaybackServiceTest.DeniedProvider() {
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptor = it }
        })
        val access = AndroidDocumentAccess(context.contentResolver)
        assertEquals(Availability.UNSUPPORTED, access.check("file:///private"))
        assertNull(descriptor)
        assertEquals(Availability.PLAYABLE, access.check("content://access/song"))
        assertNotNull(descriptor)
        assertFalse(descriptor!!.fileDescriptor.valid())
        file.delete()
        Unit
    }

    @Test
    fun cancelledProbeCancelsProviderAndCannotPublishLateSuccess() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val cancelled = CountDownLatch(1)
        register(object : PlaybackServiceTest.DeniedProvider() {
            override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?,
                signal: CancellationSignal?):
                AssetFileDescriptor? {
                signal!!.setOnCancelListener { cancelled.countDown() }
                opened.complete(Unit)
                check(cancelled.await(2, TimeUnit.SECONDS))
                signal.throwIfCanceled()
                return null
            }
        })
        val pending = async { AndroidDocumentAccess(context.contentResolver).check("content://access/song") }
        withTimeout(2_000) { opened.await() }
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(0L, cancelled.count)
    }

    private fun register(provider: PlaybackServiceTest.DeniedProvider) {
        provider.attachInfo(context, ProviderInfo().apply { authority = "access" })
        ShadowContentResolver.registerProviderInternal("access", provider)
    }
}
