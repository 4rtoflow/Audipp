package com.audipp.integrations.network

import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.TrackVariant
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.SourceDescriptor
import java.net.URI
import java.net.URISyntaxException

enum class NetworkProtocol { SMB, WEBDAV, MPD, UPNP, OPENHOME }

class NetworkEndpoint(val protocol: NetworkProtocol, val uri: String) {
    init {
        require(uri.length in 1..2048 && !uri.any { it.isISOControl() })
        require(uri.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == uri)
        val parsed = try { URI(uri) } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Invalid network endpoint")
        }
        val schemes = when (protocol) {
            NetworkProtocol.SMB -> setOf("smb")
            NetworkProtocol.MPD -> setOf("mpd")
            else -> setOf("http", "https")
        }
        require(parsed.scheme?.lowercase() in schemes && !parsed.host.isNullOrBlank())
        require(parsed.port == -1 || parsed.port in 1..65535)
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null)
        require(!parsed.path.orEmpty().any { it.isISOControl() })
    }

    override fun toString() = "NetworkEndpoint(redacted)"
}

enum class NetworkFailure {
    OFFLINE, AUTH_REQUIRED, PERMISSION_REQUIRED, TIMEOUT, IO, UNSUPPORTED,
    INVALID_INPUT, INVALID_RESPONSE, STALE_RESPONSE, EXPIRED,
}

sealed interface NetworkResult<out T> {
    class Success<T>(val value: T) : NetworkResult<T> {
        override fun toString() = "NetworkResult.Success(redacted)"
    }
    data class Failure(val reason: NetworkFailure) : NetworkResult<Nothing>
}

class NetworkDirectory(val id: String, val name: String) {
    init { requireNetworkText(id); requireNetworkText(name) }
    override fun toString() = "NetworkDirectory(redacted)"
}

class NetworkMedia(val variant: TrackVariant) {
    init {
        require(variant.source.kind == SourceKind.NETWORK)
        requireNetworkText(variant.source.sourceId.value)
        requireNetworkText(variant.variantId.value)
        requireNetworkText(variant.trackId.value)
    }
    override fun toString() = "NetworkMedia(redacted)"
}

data class DirectoryRequest(val directoryId: String, val offset: Int = 0, val limit: Int = 100) {
    init { requireNetworkText(directoryId); require(offset >= 0 && limit in 1..1000) }
    override fun toString() = "DirectoryRequest(redacted)"
}

interface NetworkLibraryAdapter {
    val endpoint: NetworkEndpoint
    suspend fun listDirectory(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkDirectory>>
    suspend fun listMedia(request: DirectoryRequest, credential: ByteArray?): NetworkResult<List<NetworkMedia>>
    suspend fun resolveStream(request: ResolutionRequest, credential: ByteArray?): NetworkResult<AdapterResolution.Available>
}

class RendererDevice(val id: String, val endpoint: NetworkEndpoint, val expiresAtEpochMs: Long) {
    init {
        requireNetworkText(id)
        require(endpoint.protocol in setOf(NetworkProtocol.MPD, NetworkProtocol.UPNP, NetworkProtocol.OPENHOME))
        require(expiresAtEpochMs >= 0)
    }
    override fun toString() = "RendererDevice(redacted)"
}

enum class RendererAction { PLAY, PAUSE, STOP, SEEK, SET_VOLUME, SET_RESOURCE }

class RendererCapabilities(
    actions: Set<RendererAction> = emptySet(),
    val canObserveState: Boolean = false,
    val independentQueue: Boolean = false,
) {
    val actions = actions.toSet()
}

sealed class RendererCommand(val action: RendererAction) {
    data object Play : RendererCommand(RendererAction.PLAY)
    data object Pause : RendererCommand(RendererAction.PAUSE)
    data object Stop : RendererCommand(RendererAction.STOP)
    class Seek(val positionMs: Long) : RendererCommand(RendererAction.SEEK) {
        init { require(positionMs >= 0) }
    }
    class SetVolume(val remoteVolume: Double) : RendererCommand(RendererAction.SET_VOLUME) {
        init { require(remoteVolume.isFinite() && remoteVolume in 0.0..1.0) }
    }
    class SetResource(val resource: SourceDescriptor.RendererResource) : RendererCommand(RendererAction.SET_RESOURCE)
    override fun toString() = "RendererCommand($action)"
}

enum class CommandReceipt { ACKNOWLEDGED, UNCONFIRMED }
enum class RendererPhase { UNKNOWN, STOPPED, PLAYING, PAUSED }
enum class RendererConnection { DISCONNECTED, CONNECTED, OFFLINE, AUTH_REQUIRED }

class RendererObservation(
    val rendererId: String,
    val sequence: Long,
    val phase: RendererPhase,
    val positionMs: Long? = null,
    val currentSourceId: SourceId? = null,
    val queueVersion: String? = null,
) {
    init {
        requireNetworkText(rendererId)
        require(sequence >= 0 && (positionMs == null || positionMs >= 0))
        currentSourceId?.let { requireNetworkText(it.value) }
        queueVersion?.let { requireNetworkText(it) }
    }
    override fun toString() = "RendererObservation(redacted)"
}

data class RendererSnapshot(
    val context: ResolutionContext,
    val revision: Long = 0,
    val connection: RendererConnection = RendererConnection.DISCONNECTED,
    val capabilities: RendererCapabilities? = null,
    val observation: RendererObservation? = null,
    val observedAtEpochMs: Long? = null,
    val commandReceipt: CommandReceipt? = null,
    val requiresQueueResync: Boolean = false,
    val failure: NetworkFailure? = null,
) {
    override fun toString() = "RendererSnapshot(redacted)"
}

interface RendererAdapter {
    suspend fun discover(limit: Int): NetworkResult<List<RendererDevice>>
    suspend fun connect(device: RendererDevice, credential: ByteArray?): NetworkResult<String>
    suspend fun getCapabilities(device: RendererDevice, credential: ByteArray?): NetworkResult<RendererCapabilities>
    suspend fun sendCommand(device: RendererDevice, command: RendererCommand, credential: ByteArray?): NetworkResult<CommandReceipt>
    suspend fun observeState(device: RendererDevice, credential: ByteArray?): NetworkResult<RendererObservation>
    suspend fun disconnect(device: RendererDevice): NetworkResult<Unit>
}

internal fun requireNetworkText(value: String) {
    require(validNetworkText(value))
}

internal fun validNetworkText(value: String): Boolean = value.isNotBlank() && value.length <= 1024 &&
    !value.any { it.isISOControl() } && value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value

internal fun validRendererResource(resource: SourceDescriptor.RendererResource, context: ResolutionContext, now: Long): Boolean =
    resource.targetId == context.target.id && resource.routeRevision == context.routeRevision &&
        resource.reachabilityConfirmed && (resource.expiresAtEpochMs?.let { now < it } != false) &&
        resource.uri.length in 1..8192 && !resource.uri.any { it.isISOControl() } &&
        resource.uri.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == resource.uri &&
        runCatching {
            val uri = URI(resource.uri)
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                (uri.port == -1 || uri.port in 1..65535) &&
                uri.rawUserInfo == null && uri.rawFragment == null &&
                !uri.path.orEmpty().any { it.isISOControl() } && !uri.query.orEmpty().any { it.isISOControl() }
        }.getOrDefault(false)
