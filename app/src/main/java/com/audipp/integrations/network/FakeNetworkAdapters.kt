package com.audipp.integrations.network

import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.VariantId
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.TargetKind
import kotlinx.coroutines.delay

class FakeNetworkStream(
    val variant: TrackVariant,
    val deviceHandle: String,
    val deviceCapabilities: PlaybackCapabilities,
    rendererResources: Map<String, SourceDescriptor.RendererResource> = emptyMap(),
) {
    val rendererResources = rendererResources.toMap()
}

class FakeNetworkLibraryAdapter(
    override val endpoint: NetworkEndpoint,
    directories: Map<String, List<NetworkDirectory>> = emptyMap(),
    media: Map<String, List<NetworkMedia>> = emptyMap(),
    streams: Map<VariantId, FakeNetworkStream> = emptyMap(),
) : NetworkLibraryAdapter {
    private val directories = directories.mapValues { it.value.toList() }
    private val media = media.mapValues { it.value.toList() }
    private val streams = streams.toMap()
    @Volatile var failure: NetworkFailure? = null
    @Volatile var latencyMs: Long = 0
        set(value) { require(value >= 0); field = value }

    override suspend fun listDirectory(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkDirectory>> = respond {
        NetworkResult.Success(directories[request.directoryId].orEmpty().drop(request.offset).take(request.limit))
    }

    override suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>> = respond {
        NetworkResult.Success(media[request.directoryId].orEmpty().drop(request.offset).take(request.limit))
    }

    override suspend fun resolveStream(request: ResolutionRequest, credential: ByteArray?): NetworkResult<AdapterResolution.Available> = respond {
        val stream = streams[request.variant.variantId]
        if (stream == null || stream.variant.source != request.variant.source) {
            NetworkResult.Failure(NetworkFailure.INVALID_INPUT)
        } else when (request.context.target.kind) {
            TargetKind.DEVICE -> NetworkResult.Success(AdapterResolution.Available(stream.variant.variantId,
                stream.variant.source.sourceId, SourceDescriptor.DeviceResource(stream.deviceHandle), stream.deviceCapabilities))
            TargetKind.RENDERER -> stream.rendererResources[request.context.target.id]?.let { resource ->
                NetworkResult.Success(AdapterResolution.Available(stream.variant.variantId, stream.variant.source.sourceId,
                    resource, PlaybackCapabilities(canPlay = true)))
            } ?: NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
            else -> NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
        }
    }

    private suspend fun <T> respond(value: () -> NetworkResult<T>): NetworkResult<T> {
        delay(latencyMs)
        return failure?.let { NetworkResult.Failure(it) } ?: value()
    }
}

class FakeRendererAdapter(
    devices: List<RendererDevice>,
    capabilities: Map<String, RendererCapabilities>,
) : RendererAdapter {
    private val devices = devices.toList()
    private val capabilities = capabilities.toMap()
    private val lock = Any()
    private val connected = mutableSetOf<String>()
    private val observations = mutableMapOf<String, RendererObservation>()
    @Volatile var failure: NetworkFailure? = null
    @Volatile var commandReceipt = CommandReceipt.ACKNOWLEDGED
    @Volatile var latencyMs: Long = 0
        set(value) { require(value >= 0); field = value }

    fun report(observation: RendererObservation) = synchronized(lock) {
        require(devices.any { it.id == observation.rendererId })
        observations[observation.rendererId] = observation
    }

    override suspend fun discover(limit: Int): NetworkResult<List<RendererDevice>> = respond {
        NetworkResult.Success(devices.take(limit))
    }

    override suspend fun connect(device: RendererDevice, credential: ByteArray?): NetworkResult<String> = respond {
        if (devices.none { it.id == device.id && it.endpoint.protocol == device.endpoint.protocol && it.endpoint.uri == device.endpoint.uri }) {
            NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE)
        } else {
            synchronized(lock) { connected.add(device.id) }
            NetworkResult.Success(device.id)
        }
    }

    override suspend fun getCapabilities(device: RendererDevice, credential: ByteArray?): NetworkResult<RendererCapabilities> = connected(device) {
        capabilities[device.id]?.let { NetworkResult.Success(it) } ?: NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
    }

    override suspend fun sendCommand(device: RendererDevice, command: RendererCommand, credential: ByteArray?): NetworkResult<CommandReceipt> = connected(device) {
        if (command.action in capabilities[device.id]?.actions.orEmpty()) NetworkResult.Success(commandReceipt)
            else NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
    }

    override suspend fun observeState(device: RendererDevice, credential: ByteArray?): NetworkResult<RendererObservation> = connected(device) {
        observations[device.id]?.let { NetworkResult.Success(it) } ?: NetworkResult.Failure(NetworkFailure.UNSUPPORTED)
    }

    override suspend fun disconnect(device: RendererDevice): NetworkResult<Unit> = respond {
        synchronized(lock) { connected.remove(device.id) }
        NetworkResult.Success(Unit)
    }

    private suspend fun <T> connected(device: RendererDevice, value: () -> NetworkResult<T>): NetworkResult<T> = respond {
        synchronized(lock) {
            if (device.id in connected) value() else NetworkResult.Failure(NetworkFailure.OFFLINE)
        }
    }

    private suspend fun <T> respond(value: () -> NetworkResult<T>): NetworkResult<T> {
        delay(latencyMs)
        return failure?.let { NetworkResult.Failure(it) } ?: value()
    }
}
