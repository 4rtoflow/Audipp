package com.audipp.integrations.network

import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.TargetKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RendererDiscovery(private val adapter: RendererAdapter, private val calls: NetworkCalls = NetworkCalls()) {
    suspend fun discover(limit: Int = 100): NetworkResult<List<RendererDevice>> {
        require(limit in 1..1000)
        return calls.run {
            when (val result = adapter.discover(limit)) {
                is NetworkResult.Failure -> result
                is NetworkResult.Success -> if (result.value.size <= limit && result.value.map { it.id }.distinct().size == result.value.size) {
                    NetworkResult.Success(result.value.toList())
                } else NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE)
            }
        }
    }
}

class NetworkRendererClient(
    private val adapter: RendererAdapter,
    private val device: RendererDevice,
    private val context: ResolutionContext,
    private val currentContext: () -> ResolutionContext,
    private val calls: NetworkCalls = NetworkCalls(),
    credentials: NetworkCredentials? = null,
    credential: NetworkCredential? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    init { require(context.target.kind == TargetKind.RENDERER && context.target.id == device.id) }
    private val access = NetworkAccess(device.endpoint, calls, credentials, credential)
    private val lock = Any()
    private var generation = 0L
    private val mutableState = MutableStateFlow(RendererSnapshot(context))
    val state: StateFlow<RendererSnapshot> = mutableState.asStateFlow()

    suspend fun connect(): NetworkResult<Unit> {
        val token = synchronized(lock) {
            generation++
            publish(RendererSnapshot(context))
            generation
        }
        val result = access.call(timeoutMs = calls.policy.connectTimeoutMs, rejection = { rejection(token) }) {
            when (val response = adapter.connect(device, it)) {
                is NetworkResult.Failure -> response
                is NetworkResult.Success -> if (response.value == device.id) NetworkResult.Success(Unit)
                    else NetworkResult.Failure(NetworkFailure.INVALID_RESPONSE)
            }
        }
        return commit(token, result, requireConnected = false) { copy(connection = RendererConnection.CONNECTED, failure = null) }
    }

    suspend fun getCapabilities(): NetworkResult<RendererCapabilities> {
        val token = synchronized(lock) { generation }
        val result = access.call(rejection = { connectedRejection(token) }) { adapter.getCapabilities(device, it) }
        return commit(token, result) { capabilities -> copy(capabilities = capabilities, failure = null) }
    }

    suspend fun sendCommand(command: RendererCommand): NetworkResult<CommandReceipt> {
        val token = synchronized(lock) { generation }
        val result = access.call(retrySafe = false, rejection = {
            connectedRejection(token) ?: when {
                command.action !in state.value.capabilities?.actions.orEmpty() -> NetworkFailure.UNSUPPORTED
                command is RendererCommand.SetResource && !validRendererResource(command.resource, context, nowEpochMs()) -> NetworkFailure.INVALID_INPUT
                else -> null
            }
        }) { adapter.sendCommand(device, command, it) }
        return commit(token, result, command = true) { receipt -> copy(commandReceipt = receipt, failure = null) }
    }

    suspend fun observeState(): NetworkResult<RendererObservation> {
        val token = synchronized(lock) { generation }
        val result = access.call(rejection = {
            connectedRejection(token) ?: if (state.value.capabilities?.canObserveState == true) null else NetworkFailure.UNSUPPORTED
        }) { adapter.observeState(device, it) }
        return synchronized(lock) {
            val checked = if (result is NetworkResult.Success &&
                (result.value.rendererId != device.id || state.value.observation?.let { result.value.sequence <= it.sequence } == true)
            ) NetworkResult.Failure(NetworkFailure.STALE_RESPONSE) else result
            commit(token, checked) { observation ->
                copy(observation = observation, observedAtEpochMs = nowEpochMs(), failure = null,
                    requiresQueueResync = requiresQueueResync || (this.observation?.queueVersion?.let { it != observation.queueVersion } == true))
            }
        }
    }

    suspend fun disconnect(): NetworkResult<Unit> {
        synchronized(lock) {
            generation++
            publish(RendererSnapshot(context))
        }
        // Cleanup still addresses the old, fixed device after the user has selected another output.
        return calls.run(retrySafe = false, timeoutMs = calls.policy.connectTimeoutMs) { adapter.disconnect(device) }
    }

    private fun rejection(token: Long): NetworkFailure? = synchronized(lock) {
        when {
            token != generation || currentContext() != context -> NetworkFailure.STALE_RESPONSE
            nowEpochMs() >= device.expiresAtEpochMs -> NetworkFailure.EXPIRED
            else -> null
        }
    }

    private fun connectedRejection(token: Long): NetworkFailure? = rejection(token) ?: when (state.value.connection) {
        RendererConnection.CONNECTED -> null
        RendererConnection.AUTH_REQUIRED -> NetworkFailure.AUTH_REQUIRED
        else -> NetworkFailure.OFFLINE
    }

    private fun <T> commit(
        token: Long, result: NetworkResult<T>, command: Boolean = false, requireConnected: Boolean = true,
        success: RendererSnapshot.(T) -> RendererSnapshot,
    ): NetworkResult<T> = synchronized(lock) {
        val rejection = if (requireConnected) connectedRejection(token) else rejection(token)
        val checked = rejection?.let { NetworkResult.Failure(it) } ?: result
        if (token != generation) return@synchronized checked
        when (checked) {
            is NetworkResult.Success -> publish(state.value.success(checked.value))
            is NetworkResult.Failure -> {
                val previous = state.value
                publish(previous.copy(
                    connection = when (checked.reason) {
                        NetworkFailure.OFFLINE -> RendererConnection.OFFLINE
                        NetworkFailure.AUTH_REQUIRED -> RendererConnection.AUTH_REQUIRED
                        else -> previous.connection
                    },
                    observation = if (checked.reason == NetworkFailure.STALE_RESPONSE && rejection == null) previous.observation else null,
                    observedAtEpochMs = if (checked.reason == NetworkFailure.STALE_RESPONSE && rejection == null) previous.observedAtEpochMs else null,
                    capabilities = if (checked.reason in setOf(NetworkFailure.OFFLINE, NetworkFailure.AUTH_REQUIRED, NetworkFailure.EXPIRED) || rejection != null) null else previous.capabilities,
                    commandReceipt = if (command) CommandReceipt.UNCONFIRMED else previous.commandReceipt,
                    failure = checked.reason,
                ))
            }
        }
        checked
    }

    private fun publish(snapshot: RendererSnapshot) {
        mutableState.value = snapshot.copy(revision = state.value.revision + 1)
    }
}
