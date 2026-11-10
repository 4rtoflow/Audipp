package com.audipp.integrations.network

import com.audipp.domain.model.SourceKind
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.TargetKind

class NetworkLibraryClient(
    private val adapter: NetworkLibraryAdapter,
    calls: NetworkCalls = NetworkCalls(),
    credentials: NetworkCredentials? = null,
    credential: NetworkCredential? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    init { require(adapter.endpoint.protocol in setOf(NetworkProtocol.SMB, NetworkProtocol.WEBDAV, NetworkProtocol.UPNP)) }
    private val access = NetworkAccess(adapter.endpoint, calls, credentials, credential)

    suspend fun listDirectory(request: DirectoryRequest): NetworkResult<List<NetworkDirectory>> = access.call { secret ->
        when (val result = adapter.listDirectory(request, secret)) {
            is NetworkResult.Failure -> result
            is NetworkResult.Success -> if (result.value.size <= request.limit && result.value.map { it.id }.distinct().size == result.value.size) {
                NetworkResult.Success(result.value.toList())
            } else NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE)
        }
    }

    suspend fun listMedia(request: DirectoryRequest): NetworkResult<List<NetworkMedia>> = access.call { secret ->
        when (val result = adapter.listMedia(request, secret)) {
            is NetworkResult.Failure -> result
            is NetworkResult.Success -> if (result.value.size <= request.limit &&
                result.value.map { it.variant.source.sourceId }.distinct().size == result.value.size &&
                result.value.map { it.variant.variantId }.distinct().size == result.value.size
            ) NetworkResult.Success(result.value.toList()) else NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE)
        }
    }

    suspend fun resolveStream(request: ResolutionRequest): NetworkResult<AdapterResolution.Available> {
        if (request.variant.source.kind != SourceKind.NETWORK || request.context.target.kind !in setOf(TargetKind.DEVICE, TargetKind.RENDERER) ||
            !validNetworkText(request.variant.source.sourceId.value) || !validNetworkText(request.variant.variantId.value) ||
            !validNetworkText(request.variant.trackId.value) || !validNetworkText(request.context.target.id)
        ) {
            return NetworkResult.Failure(NetworkFailure.INVALID_INPUT)
        }
        return access.call { secret ->
            when (val result = adapter.resolveStream(request, secret)) {
                is NetworkResult.Failure -> result
                is NetworkResult.Success -> {
                    val stream = result.value
                    val descriptor = stream.descriptor
                    val valid = when (request.context.target.kind) {
                        TargetKind.DEVICE -> descriptor is SourceDescriptor.DeviceResource && validNetworkText(descriptor.handle)
                        TargetKind.RENDERER -> descriptor is SourceDescriptor.RendererResource &&
                            validRendererResource(descriptor, request.context, nowEpochMs()) && !stream.capabilities.canAccessPcm
                        else -> false
                    }
                    if (stream.sourceId != request.variant.source.sourceId || stream.variantId != request.variant.variantId || !valid) {
                        NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE)
                    } else if (descriptor.expiresAtEpochMs?.let { it <= nowEpochMs() } == true) {
                        NetworkResult.Failure(NetworkFailure.EXPIRED)
                    } else NetworkResult.Success(stream)
                }
            }
        }
    }
}
