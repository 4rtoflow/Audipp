package com.audipp.integrations.streaming

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.audipp.data.preferences.ProviderPolicyFailure
import com.audipp.data.preferences.ProviderPolicyStorageException
import com.audipp.data.preferences.ProviderPolicyStore
import com.audipp.domain.model.Availability
import com.audipp.domain.model.ExecutionMode
import com.audipp.domain.model.ModelJson
import com.audipp.domain.model.PcmOperation
import com.audipp.domain.model.PermissionState
import com.audipp.domain.model.PlanRejection
import com.audipp.domain.model.PlaybackCapabilities
import com.audipp.domain.model.ProviderCapabilities
import com.audipp.domain.model.ProviderPermissionEvidence
import com.audipp.domain.model.ProviderPolicy
import com.audipp.domain.model.ProviderPolicySnapshot
import com.audipp.domain.model.ProviderScope
import com.audipp.domain.model.ProviderUse
import com.audipp.domain.model.SourceId
import com.audipp.domain.model.SourceKind
import com.audipp.domain.model.SourceRef
import com.audipp.domain.model.TrackId
import com.audipp.domain.model.TrackVariant
import com.audipp.domain.model.TransitionId
import com.audipp.domain.model.UsePermission
import com.audipp.domain.model.VariantId
import com.audipp.playback.source.AdapterKey
import com.audipp.playback.source.AdapterResolution
import com.audipp.playback.source.PlaybackTarget
import com.audipp.playback.source.ResolutionContext
import com.audipp.playback.source.ResolutionFailure
import com.audipp.playback.source.ResolutionRequest
import com.audipp.playback.source.ResolutionUse
import com.audipp.playback.source.SourceAdapter
import com.audipp.playback.source.SourceDescriptor
import com.audipp.playback.source.SourceResolution
import com.audipp.playback.source.SourceResolver
import com.audipp.playback.source.TargetKind
import com.audipp.playback.source.pcmRejectionFor
import com.audipp.playback.source.playbackPermissionChecker
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderAccessGateTest {
    @get:Rule val temporary = TemporaryFolder()

    private val scope = ProviderScope("fixture", "account-a", "com.audipp", "JP", "contract-a")
    private val evidence = ProviderPermissionEvidence("private-evidence-id", "private-conditions-id", 10, 200)
    private val allowed = UsePermission(PermissionState.ALLOWED, evidence)
    private val capabilities = ProviderCapabilities(ProviderUse.entries.toSet())
    private var now = 100L

    @Test(timeout = 10000)
    fun playbackOnlyProviderCannotFeedAnyOtherPurpose() = withStore { store ->
        store.setPermission(scope, ProviderUse.DIRECT_PLAYBACK, allowed)
        val gate = gate(store)
        for (use in ProviderUse.entries) {
            var calls = 0
            val result = gate.execute<Any?>(scope, use, capabilities) {
                calls++
                ProviderCallResult.Value("private-provider-payload")
            }
            if (use == ProviderUse.DIRECT_PLAYBACK) {
                assertEquals("private-provider-payload", (result as ProviderAccessResult.Success).value)
                assertEquals(1, calls)
            } else {
                assertBlocked(ProviderAccessFailure.UNREVIEWED, result)
                assertEquals(0, calls)
            }
        }
    }

    @Test(timeout = 10000)
    fun everyUseHasIndependentAllowedDeniedAndUnreviewedStorage() = runBlocking {
        val file = File(temporary.root, "policy.preferences_pb")
        withStore(file) { store ->
            for ((index, use) in ProviderUse.entries.withIndex()) {
                val state = PermissionState.entries[index % PermissionState.entries.size]
                store.setPermission(scope, use, UsePermission(state, evidence))
            }
        }
        withStore(file) { store ->
            val snapshot = store.data.first()
            assertEquals(ProviderUse.entries.size.toLong(), snapshot.revision)
            for ((index, use) in ProviderUse.entries.withIndex()) {
                assertEquals(UsePermission(PermissionState.entries[index % PermissionState.entries.size], evidence),
                    snapshot.permission(scope, use))
            }
            assertEquals(evidence, snapshot.permission(scope, ProviderUse.SEARCH).evidence)
        }
    }

    @Test(timeout = 10000)
    fun accountAppRegionContractAndProviderCannotReuseAnotherScopeGrant() = withStore { store ->
        store.setPermission(scope, ProviderUse.SEARCH, allowed)
        for (other in listOf(scope.copy(providerKey = "other"), scope.copy(accountId = "account-b"),
            scope.copy(appId = "other.app"), scope.copy(region = "US"), scope.copy(contractId = "contract-b"))) {
            assertBlocked(ProviderAccessFailure.UNREVIEWED, gate(store).execute<Any?>(other, ProviderUse.SEARCH, capabilities) {
                error("must not search")
            })
        }
    }

    @Test(timeout = 10000)
    fun explicitDenialAndUnreviewedNeverInvokeTheOperation() = withStore { store ->
        for (state in listOf(PermissionState.DENIED, PermissionState.UNREVIEWED)) {
            store.setPermission(scope, ProviderUse.SEARCH, UsePermission(state))
            assertBlocked(if (state == PermissionState.DENIED) ProviderAccessFailure.DENIED else ProviderAccessFailure.UNREVIEWED,
                gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) { error("must not search") })
        }
    }

    @Test(timeout = 10000)
    fun reviewAndExpiryBoundariesAreCheckedOnEveryInvocation() = withStore { store ->
        store.setPermission(scope, ProviderUse.SEARCH, allowed)
        for (instant in listOf(9L, 10L, 199L, 200L, 201L)) {
            now = instant
            var called = false
            val result = gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                called = true
                ProviderCallResult.Value(Unit)
            }
            assertEquals(instant in 10L..199L, called)
            if (!called) assertBlocked(if (instant < 10) ProviderAccessFailure.UNREVIEWED else ProviderAccessFailure.EXPIRED, result)
        }
    }

    @Test(timeout = 10000)
    fun technicalCapabilityDoesNotGrantPermissionAndLicenseDoesNotGrantCapability() = withStore { store ->
        store.setPermission(scope, ProviderUse.SEARCH, allowed)
        assertBlocked(ProviderAccessFailure.UNSUPPORTED,
            gate(store).execute<Any?>(scope, ProviderUse.SEARCH, ProviderCapabilities()) { error("unsupported") })
        assertBlocked(ProviderAccessFailure.UNREVIEWED,
            gate(store).execute<Any?>(scope, ProviderUse.PCM_ANALYSIS, capabilities) { error("unlicensed") })
    }

    @Test(timeout = 10000)
    fun forbiddenIsPersistedAndAuthenticationRefreshCannotRetryIt() = runBlocking {
        val file = File(temporary.root, "forbidden.preferences_pb")
        withStore(file) { store ->
            store.setPermission(scope, ProviderUse.SEARCH, allowed)
            var calls = 0
            repeat(2) {
                assertBlocked(ProviderAccessFailure.DENIED, gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                    calls++
                    ProviderCallResult.HttpFailure(403)
                })
            }
            assertEquals(1, calls)
        }
        withStore(file) { store ->
            assertEquals(PermissionState.DENIED, gate(store).permissionState(scope, ProviderUse.SEARCH))
            store.setPermission(scope, ProviderUse.SEARCH, allowed)
            assertTrue(gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                ProviderCallResult.Value(Unit)
            } is ProviderAccessResult.Success)
        }
    }

    @Test(timeout = 10000)
    fun authRateLimitAndServerFailureReturnWithoutAutomaticRetry() = withStore { store ->
        store.setPermission(scope, ProviderUse.SEARCH, allowed)
        for ((code, reason) in listOf(401 to ProviderAccessFailure.AUTH_REQUIRED,
            429 to ProviderAccessFailure.RATE_LIMITED, 500 to ProviderAccessFailure.HTTP_FAILURE)) {
            var calls = 0
            assertBlocked(reason, gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                calls++
                ProviderCallResult.HttpFailure(code)
            })
            assertEquals(1, calls)
            assertEquals(PermissionState.ALLOWED, gate(store).permissionState(scope, ProviderUse.SEARCH))
        }
    }

    @Test(timeout = 10000)
    fun revokedOrExpiredPolicyDropsDelayedPayload() = withStore { store ->
        for (expiry in listOf(false, true)) {
            now = 100
            store.setPermission(scope, ProviderUse.SEARCH, allowed)
            assertBlocked(if (expiry) ProviderAccessFailure.EXPIRED else ProviderAccessFailure.DENIED,
                gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                    if (expiry) now = 200 else store.setPermission(scope, ProviderUse.SEARCH, UsePermission(PermissionState.DENIED))
                    ProviderCallResult.Value("must not release")
                })
        }
    }

    @Test(timeout = 10000)
    fun revokeThenRegrantDoesNotReleaseAnOldPayloadOrOverwriteTheNewGrant() = withStore { store ->
        for (response in listOf(ProviderCallResult.Value("old"), ProviderCallResult.HttpFailure(403))) {
            store.setPermission(scope, ProviderUse.SEARCH, allowed)
            assertBlocked(ProviderAccessFailure.STALE_POLICY,
                gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                    store.setPermission(scope, ProviderUse.SEARCH, UsePermission(PermissionState.DENIED))
                    store.setPermission(scope, ProviderUse.SEARCH, allowed)
                    response
                })
            assertEquals(PermissionState.ALLOWED, gate(store).permissionState(scope, ProviderUse.SEARCH))
        }
    }

    @Test(timeout = 10000)
    fun concurrentPurposeUpdatesAreTransactional() = withStore { store ->
        coroutineScope {
            ProviderUse.entries.map { use -> async { store.setPermission(scope, use, allowed) } }.awaitAll()
        }
        val snapshot = store.data.first()
        assertEquals(ProviderUse.entries.size.toLong(), snapshot.revision)
        assertEquals(ProviderUse.entries.toSet(), snapshot.policies.single().permissions.keys)
        assertFalse(store.setPermission(scope, ProviderUse.SEARCH, UsePermission(PermissionState.DENIED), 0))
        assertEquals(snapshot, store.data.first())
    }

    @Test(timeout = 10000)
    fun cancellationAndTimeoutCannotPublishLateSuccess() = withStore { store ->
        store.setPermission(scope, ProviderUse.SEARCH, allowed)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val pending = async {
                gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    ProviderCallResult.Value("late")
                }
            }
            entered.await()
            pending.cancel()
            release.complete(Unit)
            pending.join()
            assertTrue(pending.isCancelled)
        }
        assertBlocked(ProviderAccessFailure.TIMEOUT,
            ProviderAccessGate(store, { now }, 30).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                delay(200)
                ProviderCallResult.Value("late")
            })
    }

    @Test(timeout = 10000)
    fun ioRunsAwayFromCallerAndErrorsExposeNoRawDetails() = withStore { store ->
        store.setPermission(scope, ProviderUse.SEARCH, allowed)
        val caller = Thread.currentThread()
        assertBlocked(ProviderAccessFailure.IO,
            gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) {
                assertNotSame(caller, Thread.currentThread())
                throw IOException("https://token:secret@private.example")
            })
        assertBlocked(ProviderAccessFailure.DENIED,
            gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) { throw SecurityException("private") })
    }

    @Test(timeout = 10000)
    fun resolverAndPcmConsumersUseTheSameCurrentPolicy() = withStore { store ->
        val gate = gate(store)
        store.setPermission(scope, ProviderUse.DIRECT_PLAYBACK, allowed)
        val source = SourceRef(SourceId("s"), SourceKind.PROVIDER, scope.providerKey, "item", availability = Availability.PLAYABLE)
        val variant = TrackVariant(VariantId("v"), TrackId("t"), source)
        val context = ResolutionContext(PlaybackTarget("device", TargetKind.DEVICE), 1, TransitionId("transition"))
        val resolver = SourceResolver(
            mapOf(AdapterKey(SourceKind.PROVIDER, TargetKind.DEVICE, scope.providerKey) to SourceAdapter {
                AdapterResolution.Available(variant.variantId, source.sourceId,
                    SourceDescriptor.DeviceResource("private"), PlaybackCapabilities(canPlay = true, canAccessPcm = true))
            }), { context }, gate.playbackPermissionChecker { scope }, { now },
        )
        val result = resolver.resolve(ResolutionRequest(variant, context)) as SourceResolution.Resolved.Playback
        assertEquals(ExecutionMode.PARTNER_DIRECT, result.plan.mode)
        for (operation in PcmOperation.entries) {
            assertEquals(PlanRejection.PCM_POLICY_BLOCKED,
                gate.pcmRejectionFor(result.plan, scope, 1, context.transitionId, operation, capabilities))
        }
        store.setPermission(scope, ProviderUse.PCM_ANALYSIS, allowed)
        assertNull(gate.pcmRejectionFor(result.plan, scope, 1, context.transitionId, PcmOperation.ANALYSIS, capabilities))
        assertEquals(PlanRejection.PCM_POLICY_BLOCKED,
            gate.pcmRejectionFor(result.plan, scope, 1, context.transitionId, PcmOperation.ANALYSIS, ProviderCapabilities()))
        store.setPermission(scope, ProviderUse.DIRECT_PLAYBACK, UsePermission(PermissionState.DENIED))
        assertEquals(ResolutionFailure.POLICY_BLOCKED, resolver.rejectionFor(result))
        assertEquals(PlanRejection.PCM_POLICY_BLOCKED,
            gate.pcmRejectionFor(result.plan, scope, 1, context.transitionId, PcmOperation.ANALYSIS, capabilities))
    }

    @Test(timeout = 10000)
    fun resolverRejectsMissingAndMismatchedAccountScope() = withStore { store ->
        store.setPermission(scope, ProviderUse.DIRECT_PLAYBACK, allowed)
        val request = ResolutionRequest(
            TrackVariant(VariantId("v"), TrackId("t"), SourceRef(SourceId("s"), SourceKind.PROVIDER,
                "other", "item", availability = Availability.PLAYABLE)),
            ResolutionContext(PlaybackTarget("device", TargetKind.DEVICE), 0, TransitionId("t")),
        )
        assertEquals(PermissionState.UNREVIEWED,
            gate(store).playbackPermissionChecker { scope }.check(request, ResolutionUse.DIRECT_PLAYBACK))
        assertEquals(PermissionState.UNREVIEWED,
            gate(store).playbackPermissionChecker { null }.check(request, ResolutionUse.DIRECT_PLAYBACK))
    }

    @Test
    fun invalidEvidenceCannotBeAllowedAndLogRepresentationsAreRedacted() {
        assertThrows(IllegalArgumentException::class.java) { UsePermission(PermissionState.ALLOWED) }
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(expiresAtEpochMs = 10) }
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(reviewedAtEpochMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { evidence.copy(evidenceRef = " ") }
        assertThrows(IllegalArgumentException::class.java) { scope.copy(accountId = " ") }
        assertThrows(IllegalArgumentException::class.java) { ProviderPolicySnapshot(-1) }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderPolicySnapshot(policies = listOf(ProviderPolicy(scope), ProviderPolicy(scope)))
        }
        val logs = listOf(scope, evidence, allowed, ProviderPolicy(scope, mapOf(ProviderUse.SEARCH to allowed)),
            ProviderPolicySnapshot(policies = listOf(ProviderPolicy(scope))),
            ProviderCallResult.Value("private-payload"), ProviderAccessResult.Success("private-payload")).joinToString()
        for (privateValue in listOf("private-evidence-id", "private-conditions-id", "account-a", "contract-a", "private-payload")) {
            assertFalse(logs, logs.contains(privateValue))
        }
    }

    @Test(timeout = 10000)
    fun malformedUnknownVersionAndIncompleteAllowedRecordsArePreserved() = runBlocking {
        for (document in listOf("not json",
            ModelJson.encode(ProviderPolicySnapshot()).replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            ModelJson.encode(ProviderPolicySnapshot(policies = listOf(ProviderPolicy(scope,
                mapOf(ProviderUse.SEARCH to allowed))))).replace(
                    ModelJson.format.encodeToString(ProviderPermissionEvidence.serializer(), evidence), "null"),
            ModelJson.encode(ProviderPolicySnapshot(policies = listOf(ProviderPolicy(scope,
                mapOf(ProviderUse.SEARCH to allowed))))).replace("\"evidence\":{", "\"evidence\":{\"unknown\":true,"))) {
            val file = File(temporary.newFolder(), "invalid.preferences_pb")
            val job = SupervisorJob()
            val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            try {
                raw.edit { it[stringPreferencesKey("provider_policy_v1")] = document }
                val store = ProviderPolicyStore(raw)
                assertBlocked(ProviderAccessFailure.STORAGE_UNAVAILABLE,
                    gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) { error("must not acquire data") })
                try {
                    store.setPermission(scope, ProviderUse.SEARCH, allowed)
                    fail("must preserve invalid data")
                } catch (error: ProviderPolicyStorageException) {
                    assertEquals(ProviderPolicyFailure.INVALID_DATA, error.failure)
                    assertNull(error.cause)
                }
                assertEquals(document, raw.data.first()[stringPreferencesKey("provider_policy_v1")])
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test(timeout = 10000)
    fun corruptPreferencesFileIsPreservedWithoutFallbackPermission() = runBlocking {
        val file = File(temporary.root, "corrupt.preferences_pb")
        val original = byteArrayOf(-1, -2, -3)
        file.writeBytes(original)
        withStore(file) { store ->
            assertBlocked(ProviderAccessFailure.STORAGE_UNAVAILABLE,
                gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) { error("must not search") })
            try {
                store.data.first()
                fail("must reject corrupt storage")
            } catch (error: ProviderPolicyStorageException) {
                assertEquals(ProviderPolicyFailure.INVALID_DATA, error.failure)
                assertNull(error.cause)
            }
        }
        org.junit.Assert.assertArrayEquals(original, file.readBytes())
    }

    @Test(timeout = 10000)
    fun storageReadAndWriteFailuresStayBlockedUntilExplicitRetry() = withStore { real ->
        real.setPermission(scope, ProviderUse.SEARCH, allowed)
        var failRead = true
        var failWrite = false
        val raw = realRaw!!
        val boundary = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (failRead) throw IOException("private-path-and-secret")
                emit(raw.data.first())
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrite) throw IOException("private-path-and-secret")
                return raw.updateData(transform)
            }
        }
        val store = ProviderPolicyStore(boundary)
        assertBlocked(ProviderAccessFailure.STORAGE_UNAVAILABLE,
            gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) { error("must not acquire") })
        failRead = false
        failWrite = true
        assertBlocked(ProviderAccessFailure.STORAGE_UNAVAILABLE,
            gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) { ProviderCallResult.HttpFailure(403) })
        assertEquals(PermissionState.ALLOWED, gate(real).permissionState(scope, ProviderUse.SEARCH))
        failWrite = false
        assertBlocked(ProviderAccessFailure.DENIED,
            gate(store).execute<Any?>(scope, ProviderUse.SEARCH, capabilities) { ProviderCallResult.HttpFailure(403) })
        assertEquals(PermissionState.DENIED, gate(real).permissionState(scope, ProviderUse.SEARCH))
    }

    private var realRaw: DataStore<Preferences>? = null

    private fun gate(store: ProviderPolicyStore) = ProviderAccessGate(store, { now })

    private fun assertBlocked(reason: ProviderAccessFailure, result: ProviderAccessResult<*>) {
        assertEquals(ProviderAccessResult.Blocked(reason), result)
    }

    private fun withStore(block: suspend (ProviderPolicyStore) -> Unit) = runBlocking {
        withStore(File(temporary.root, "policy.preferences_pb"), block)
    }

    private suspend fun withStore(file: File, block: suspend (ProviderPolicyStore) -> Unit) {
        val job = SupervisorJob()
        val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
        realRaw = raw
        try {
            block(ProviderPolicyStore(raw))
        } finally {
            job.cancelAndJoin()
            realRaw = null
        }
    }
}
