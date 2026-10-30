package com.audipp.playback.source

import com.audipp.integrations.network.NetworkFailure
import com.audipp.integrations.network.NetworkLibraryClient
import com.audipp.integrations.network.NetworkResult

class NetworkLibrarySourceAdapter(private val library: NetworkLibraryClient) : SourceAdapter {
    override suspend fun resolve(request: ResolutionRequest): AdapterResolution = when (val result = library.resolveStream(request)) {
        is NetworkResult.Success -> result.value
        is NetworkResult.Failure -> AdapterResolution.Unavailable(when (result.reason) {
            NetworkFailure.AUTH_REQUIRED -> ResolutionFailure.AUTH_REQUIRED
            NetworkFailure.PERMISSION_REQUIRED -> ResolutionFailure.PERMISSION_REQUIRED
            NetworkFailure.TIMEOUT -> ResolutionFailure.TIMEOUT
            NetworkFailure.OFFLINE -> ResolutionFailure.SOURCE_IO
            NetworkFailure.UNSUPPORTED -> ResolutionFailure.PLAYBACK_UNSUPPORTED
            NetworkFailure.EXPIRED -> ResolutionFailure.EXPIRED
            NetworkFailure.INVALID_INPUT, NetworkFailure.INVALID_RESPONSE -> ResolutionFailure.INVALID_DESCRIPTOR
            NetworkFailure.STALE_RESPONSE -> ResolutionFailure.STALE_TRANSITION
            NetworkFailure.IO -> ResolutionFailure.SOURCE_IO
        })
    }
}
