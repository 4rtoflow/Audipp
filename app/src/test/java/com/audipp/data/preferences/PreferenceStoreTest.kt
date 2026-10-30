package com.audipp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.audipp.domain.model.AppPreferences
import com.audipp.domain.model.ConsentDecision
import com.audipp.domain.model.ConsentPurpose
import com.audipp.domain.model.SharingScope
import com.audipp.domain.model.ThemeMode
import com.audipp.domain.model.VisualizerPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreferenceStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test(timeout = 10000)
    fun firstUseAndReopenKeepSafeDefaults() = runBlocking {
        val file = File(temporary.root, "defaults.preferences_pb")
        repeat(2) {
            withStore(file) { raw ->
                val settings = PreferenceStore(raw).data.first()
                assertEquals(AppPreferences(), settings)
                for (purpose in ConsentPurpose.entries) {
                    assertFalse(settings.permitsExternalTransmission(purpose, 1))
                }
                PreferenceStore(raw).setTheme(ThemeMode.SYSTEM)
            }
        }
    }

    @Test(timeout = 10000)
    fun settingsAndPurposeSpecificConsentSurviveReopen() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        withStore(file) { raw ->
            val store = PreferenceStore(raw)
            store.setTheme(ThemeMode.DARK)
            store.setVisualizer(VisualizerPreferences(true, 60))
            store.setSharingScope(SharingScope.FRIENDS)
            store.setConsent(ConsentPurpose.ACOUSTIC_FINGERPRINT, ConsentDecision(2, true))
        }
        withStore(file) { raw ->
            val settings = PreferenceStore(raw).data.first()
            assertEquals(ThemeMode.DARK, settings.theme)
            assertEquals(VisualizerPreferences(true, 60), settings.visualizer)
            assertEquals(SharingScope.FRIENDS, settings.sharingScope)
            assertTrue(settings.permitsExternalTransmission(ConsentPurpose.ACOUSTIC_FINGERPRINT, 2))
            assertFalse(settings.permitsExternalTransmission(ConsentPurpose.ACOUSTIC_FINGERPRINT, 1))
            assertFalse(settings.permitsExternalTransmission(ConsentPurpose.ACOUSTIC_FINGERPRINT, 3))
            assertFalse(settings.permitsExternalTransmission(ConsentPurpose.METADATA_LOOKUP, 2))
            assertEquals(
                setOf("schema_version", "theme", "visualizer_enabled", "visualizer_fps", "sharing_scope",
                    "consent_ACOUSTIC_FINGERPRINT_version", "consent_ACOUSTIC_FINGERPRINT_granted"),
                raw.data.first().asMap().keys.map { it.name }.toSet(),
            )
        }
    }

    @Test(timeout = 10000)
    fun themeChoicesAndVisualizerRatesRoundTrip() = runBlocking {
        withStore { raw ->
            val store = PreferenceStore(raw)
            for (theme in ThemeMode.entries) {
                store.setTheme(theme)
                assertEquals(theme, store.data.first().theme)
            }
            for (fps in listOf(15, 30, 60)) {
                store.setVisualizer(VisualizerPreferences(false, fps))
                assertEquals(VisualizerPreferences(false, fps), store.data.first().visualizer)
            }
        }
    }

    @Test(timeout = 10000)
    fun revokedConsentRemainsDeniedAfterReopen() = runBlocking {
        val file = File(temporary.root, "consent.preferences_pb")
        withStore(file) { raw ->
            val store = PreferenceStore(raw)
            store.setConsent(ConsentPurpose.CLOUD_SYNC, ConsentDecision(3, true))
            store.setConsent(ConsentPurpose.CLOUD_SYNC, ConsentDecision(3, false))
        }
        withStore(file) { raw ->
            val settings = PreferenceStore(raw).data.first()
            assertEquals(ConsentDecision(3, false), settings.consents[ConsentPurpose.CLOUD_SYNC])
            assertFalse(settings.permitsExternalTransmission(ConsentPurpose.CLOUD_SYNC, 3))
        }
    }

    @Test(timeout = 10000)
    fun independentConcurrentEditsDoNotLoseSettings() = runBlocking {
        withStore { raw ->
            val store = PreferenceStore(raw)
            listOf(
                async { store.setTheme(ThemeMode.LIGHT) },
                async { store.setVisualizer(VisualizerPreferences(true, 15)) },
                async { store.setSharingScope(SharingScope.FRIENDS) },
                async { store.setConsent(ConsentPurpose.CLOUD_SYNC, ConsentDecision(1, true)) },
                async { store.setConsent(ConsentPurpose.SCROBBLING, ConsentDecision(2, true)) },
            ).awaitAll()
            val settings = store.data.first()
            assertEquals(ThemeMode.LIGHT, settings.theme)
            assertEquals(VisualizerPreferences(true, 15), settings.visualizer)
            assertEquals(SharingScope.FRIENDS, settings.sharingScope)
            assertEquals(2, settings.consents.size)
        }
    }

    @Test(timeout = 10000)
    fun unreadableFileReportsReadErrorAndCanBeRetried() = runBlocking {
        val file = temporary.newFolder("settings.preferences_pb")
        withStore(file) { raw ->
            val store = PreferenceStore(raw)
            assertStorageFailure(PreferenceOperation.READ, PreferenceFailure.IO) { store.data.first() }
            assertTrue(file.delete())
            assertEquals(AppPreferences(), store.data.first())
            store.setTheme(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, store.data.first().theme)
        }
    }

    @Test(timeout = 10000)
    fun corruptFileIsNeitherResetNorOverwritten() = runBlocking {
        val file = temporary.newFile("corrupt.preferences_pb")
        val broken = byteArrayOf(0x0A, 0x7F, 0x01)
        file.writeBytes(broken)
        withStore(file) { raw ->
            val store = PreferenceStore(raw)
            assertStorageFailure(PreferenceOperation.READ, PreferenceFailure.CORRUPT) { store.data.first() }
            assertStorageFailure(PreferenceOperation.WRITE, PreferenceFailure.CORRUPT) {
                store.setTheme(ThemeMode.LIGHT)
            }
            assertArrayEquals(broken, file.readBytes())
        }
    }

    @Test(timeout = 10000)
    fun malformedStoredValuesRejectReadsAndUnrelatedWrites() = runBlocking {
        val invalidSettings: List<suspend (DataStore<Preferences>) -> Unit> = listOf(
            { raw -> raw.edit { it[stringPreferencesKey("theme")] = "UNKNOWN" } },
            { raw -> raw.edit { it[intPreferencesKey("visualizer_fps")] = 0 } },
            { raw -> raw.edit { it[intPreferencesKey("theme")] = 1 } },
            { raw -> raw.edit { it[stringPreferencesKey("sharing_scope")] = "PUBLIC" } },
            { raw -> raw.edit { it[booleanPreferencesKey("consent_CLOUD_SYNC_granted")] = true } },
            { raw -> raw.edit {
                it[intPreferencesKey("consent_CLOUD_SYNC_version")] = 0
                it[booleanPreferencesKey("consent_CLOUD_SYNC_granted")] = true
            } },
        )
        for (inject in invalidSettings) {
            withStore { raw ->
                raw.edit { it[intPreferencesKey("schema_version")] = 1 }
                inject(raw)
                val before = raw.data.first()
                val store = PreferenceStore(raw)
                assertStorageFailure(PreferenceOperation.READ, PreferenceFailure.CORRUPT) { store.data.first() }
                assertStorageFailure(PreferenceOperation.WRITE, PreferenceFailure.CORRUPT) {
                    store.setVisualizer(VisualizerPreferences())
                }
                assertEquals(before, raw.data.first())
            }
        }
    }

    @Test(timeout = 10000)
    fun missingAndUnsupportedSchemaAreNotReset() = runBlocking {
        for (version in listOf(null, 0, 2)) {
            withStore { raw ->
                raw.edit {
                    it[stringPreferencesKey("theme")] = "DARK"
                    if (version != null) it[intPreferencesKey("schema_version")] = version
                }
                val expected = if (version == null) PreferenceFailure.CORRUPT else PreferenceFailure.UNSUPPORTED_VERSION
                val store = PreferenceStore(raw)
                assertStorageFailure(PreferenceOperation.READ, expected) { store.data.first() }
                assertStorageFailure(PreferenceOperation.WRITE, expected) { store.setTheme(ThemeMode.SYSTEM) }
                assertEquals("DARK", raw.data.first()[stringPreferencesKey("theme")])
            }
        }
    }

    @Test(timeout = 10000)
    fun failedWriteKeepsPreviousSettingsAndRetryCanSucceed() = runBlocking {
        withStore { raw ->
            val store = PreferenceStore(raw)
            store.setTheme(ThemeMode.DARK)
            val failing = PreferenceStore(object : DataStore<Preferences> by raw {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    transform(raw.data.first())
                    throw IOException("private/path/token")
                }
            })
            assertStorageFailure(PreferenceOperation.WRITE, PreferenceFailure.IO) {
                failing.setTheme(ThemeMode.LIGHT)
            }
            assertEquals(ThemeMode.DARK, store.data.first().theme)
            store.setTheme(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, store.data.first().theme)
        }
    }

    @Test(timeout = 10000)
    fun cancellationIsNotConvertedToStorageFailureOrConsent() = runBlocking {
        withStore { raw ->
            val entered = CompletableDeferred<Unit>()
            val blocked = PreferenceStore(object : DataStore<Preferences> by raw {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    return raw.updateData(transform)
                }
            })
            val job = launch { blocked.setConsent(ConsentPurpose.CLOUD_SYNC, ConsentDecision(1, true)) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(PreferenceStore(raw).data.first().permitsExternalTransmission(ConsentPurpose.CLOUD_SYNC, 1))
        }
    }

    @Test(timeout = 10000)
    fun readErrorsDoNotExposeExceptionDetails() = runBlocking {
        val store = PreferenceStore(object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("private/path/token") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
        })
        assertStorageFailure(PreferenceOperation.READ, PreferenceFailure.IO) { store.data.first() }
    }

    @Test
    fun invalidConsentVersionsAndVisualizerRatesAreRejected() {
        for (version in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) { ConsentDecision(version, true) }
            assertThrows(IllegalArgumentException::class.java) {
                AppPreferences().permitsExternalTransmission(ConsentPurpose.CLOUD_SYNC, version)
            }
        }
        for (fps in listOf(-1, 0, 14, 31, 61)) {
            assertThrows(IllegalArgumentException::class.java) { VisualizerPreferences(framesPerSecond = fps) }
        }
    }

    private suspend fun assertStorageFailure(
        operation: PreferenceOperation,
        failure: PreferenceFailure,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            throw AssertionError("Expected storage failure")
        } catch (error: PreferenceStorageException) {
            assertEquals(operation, error.operation)
            assertEquals(failure, error.failure)
            assertEquals("Preferences ${operation.name}: ${failure.name}", error.message)
            assertNull(error.cause)
        }
    }

    private suspend fun withStore(
        file: File = File(temporary.newFolder(), "settings.preferences_pb"),
        block: suspend (DataStore<Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        val raw = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        try {
            block(raw)
        } finally {
            job.cancelAndJoin()
        }
    }
}
