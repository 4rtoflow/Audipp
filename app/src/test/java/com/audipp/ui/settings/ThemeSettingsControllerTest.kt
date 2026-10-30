package com.audipp.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.audipp.data.preferences.PreferenceOperation
import com.audipp.data.preferences.PreferenceStore
import com.audipp.domain.model.ThemeMode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeSettingsControllerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test(timeout = 10000)
    fun systemDefaultAndAllSelectionsSurviveControllerAndStoreRecreation() = runBlocking {
        val file = File(temporary.root, "settings.preferences_pb")
        for (mode in ThemeMode.entries) {
            withStore(file) { raw ->
                val controller = ThemeSettingsController(PreferenceStore(raw))
                observing(controller) {
                    if (mode == ThemeMode.SYSTEM) assertEquals(ThemeMode.SYSTEM, controller.state.value.theme)
                    controller.select(mode)
                    assertEquals(mode, controller.state.value.theme)
                    assertFalse(controller.state.value.saving)
                    assertNull(controller.state.value.failure)
                }
            }
            withStore(file) { raw ->
                val reopened = ThemeSettingsController(PreferenceStore(raw))
                observing(reopened) { assertEquals(mode, reopened.state.value.theme) }
            }
        }
    }

    @Test(timeout = 10000)
    fun writeFailureKeepsSavedChoiceAndCanBeRetried() = runBlocking {
        withStore { raw ->
            PreferenceStore(raw).setTheme(ThemeMode.DARK)
            var fail = true
            val injected = object : DataStore<Preferences> by raw {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    if (fail) throw IOException("private/path")
                    return raw.updateData(transform)
                }
            }
            val controller = ThemeSettingsController(PreferenceStore(injected))
            observing(controller) {
                controller.select(ThemeMode.LIGHT)
                assertEquals(ThemeMode.DARK, controller.state.value.theme)
                assertEquals(PreferenceOperation.WRITE, controller.state.value.failure)
                assertEquals(ThemeMode.LIGHT, controller.state.value.failedTheme)
                assertFalse(controller.state.value.saving)
                fail = false
                controller.select(controller.state.value.failedTheme!!)
                assertEquals(ThemeMode.LIGHT, controller.state.value.theme)
                assertNull(controller.state.value.failure)
            }
        }
    }

    @Test(timeout = 10000)
    fun readFailureIsNotAnEmptyStoreAndRetryLoadsSavedChoice() = runBlocking {
        withStore { raw ->
            PreferenceStore(raw).setTheme(ThemeMode.DARK)
            var fail = true
            val injected = object : DataStore<Preferences> by raw {
                override val data: Flow<Preferences> = flow {
                    if (fail) throw IOException("private/path")
                    emitAll(raw.data)
                }
            }
            val controller = ThemeSettingsController(PreferenceStore(injected))
            controller.observe()
            assertNull(controller.state.value.theme)
            assertEquals(PreferenceOperation.READ, controller.state.value.failure)
            controller.select(ThemeMode.LIGHT)
            assertEquals(ThemeMode.DARK, PreferenceStore(raw).data.first().theme)
            fail = false
            observing(controller) {
                assertEquals(ThemeMode.DARK, controller.state.value.theme)
                assertNull(controller.state.value.failure)
            }
        }
    }

    @Test(timeout = 10000)
    fun invalidOrFutureSettingsArePreservedAndSelectionIsRefused() = runBlocking {
        for ((version, value) in listOf(1 to "invalid", 2 to "DARK")) {
            withStore { raw ->
                raw.edit {
                    it[intPreferencesKey("schema_version")] = version
                    it[stringPreferencesKey("theme")] = value
                }
                val before = raw.data.first()
                val controller = ThemeSettingsController(PreferenceStore(raw))
                controller.observe()
                controller.select(ThemeMode.LIGHT)
                assertNull(controller.state.value.theme)
                assertEquals(PreferenceOperation.READ, controller.state.value.failure)
                assertEquals(before, raw.data.first())
            }
        }
    }

    @Test(timeout = 10000)
    fun pendingWriteDoesNotReportSuccessOrAcceptAnotherSelectionAndCancellationReloadsTruth() = runBlocking {
        withStore { raw ->
            val entered = CompletableDeferred<Unit>()
            val blocked = object : DataStore<Preferences> by raw {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    return raw.updateData(transform)
                }
            }
            val controller = ThemeSettingsController(PreferenceStore(blocked))
            observing(controller) {
                coroutineScope {
                    val write = launch { controller.select(ThemeMode.DARK) }
                    entered.await()
                    assertTrue(controller.state.value.saving)
                    assertEquals(ThemeMode.SYSTEM, controller.state.value.theme)
                    controller.select(ThemeMode.LIGHT)
                    write.cancelAndJoin()
                    assertTrue(write.isCancelled)
                    assertFalse(controller.state.value.saving)
                    assertNull(controller.state.value.failure)
                }
            }
            val reopened = ThemeSettingsController(PreferenceStore(raw))
            observing(reopened) { assertEquals(ThemeMode.SYSTEM, reopened.state.value.theme) }
        }
    }

    @Test(timeout = 10000)
    fun cancellationAfterDiskCommitIsRecoveredByNewObserver() = runBlocking {
        withStore { raw ->
            val committed = CompletableDeferred<Unit>()
            val delayed = object : DataStore<Preferences> by raw {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val result = raw.updateData(transform)
                    committed.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    return result
                }
            }
            val controller = ThemeSettingsController(PreferenceStore(delayed))
            observing(controller) {
                coroutineScope {
                    val write = launch { controller.select(ThemeMode.DARK) }
                    committed.await()
                    write.cancelAndJoin()
                    assertNull(controller.state.value.failure)
                }
            }
            val reopened = ThemeSettingsController(PreferenceStore(raw))
            observing(reopened) { assertEquals(ThemeMode.DARK, reopened.state.value.theme) }
        }
    }

    private suspend fun observing(controller: ThemeSettingsController, block: suspend () -> Unit) = coroutineScope {
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { controller.observe() }
        try {
            controller.state.first { it.theme != null || it.failure != null }
            block()
        } finally {
            observer.cancelAndJoin()
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
