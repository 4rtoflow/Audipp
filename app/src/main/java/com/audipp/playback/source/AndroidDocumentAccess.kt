package com.audipp.playback.source

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import com.audipp.domain.model.Availability
import java.io.FileNotFoundException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class AndroidDocumentAccess(private val resolver: ContentResolver) {
    suspend fun check(value: String): Availability = withContext(Dispatchers.IO) {
        if (!isContentUri(value)) return@withContext Availability.UNSUPPORTED
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val result = resolver.openAssetFileDescriptor(Uri.parse(value), "r", signal)?.use {
                    Availability.PLAYABLE
                } ?: Availability.MISSING
                continuation.resume(result)
            } catch (_: FileNotFoundException) {
                continuation.resume(Availability.MISSING)
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }
}
