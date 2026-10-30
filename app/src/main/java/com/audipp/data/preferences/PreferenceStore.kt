package com.audipp.data.preferences

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class PreferenceOperation { READ, WRITE }

enum class PreferenceFailure { IO, CORRUPT, UNSUPPORTED_VERSION }

class PreferenceStorageException(
    val operation: PreferenceOperation,
    val failure: PreferenceFailure,
) : IOException("Preferences ${operation.name}: ${failure.name}")

class PreferenceStore internal constructor(private val dataStore: DataStore<Preferences>) {
    val data: Flow<AppPreferences> = dataStore.data
        .map { decode(it) }
        .catch { error ->
            if (error is IOException) throw storageError(PreferenceOperation.READ, error)
            throw error
        }

    suspend fun setTheme(theme: ThemeMode) = update { it[THEME] = theme.name }

    suspend fun setVisualizer(visualizer: VisualizerPreferences) = update {
        it[VISUALIZER_ENABLED] = visualizer.enabled
        it[VISUALIZER_FPS] = visualizer.framesPerSecond
    }

    suspend fun setSharingScope(scope: SharingScope) = update { it[SHARING_SCOPE] = scope.name }

    suspend fun setConsent(purpose: ConsentPurpose, decision: ConsentDecision) = update {
        it[consentVersion(purpose)] = decision.version
        it[consentGranted(purpose)] = decision.granted
    }

    private suspend fun update(change: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit {
                // Invalid or newer settings must survive an unrelated setting change.
                decode(it)
                change(it)
                it[SCHEMA_VERSION] = 1
            }
        } catch (error: IOException) {
            throw storageError(PreferenceOperation.WRITE, error)
        }
    }

    private fun decode(preferences: Preferences): AppPreferences {
        try {
            val schema = preferences[SCHEMA_VERSION]
            if (schema == null && preferences.asMap().isNotEmpty()) {
                throw CorruptionException("Missing preferences version")
            }
            if (schema != null && schema != 1) {
                throw PreferenceStorageException(PreferenceOperation.READ, PreferenceFailure.UNSUPPORTED_VERSION)
            }
            return AppPreferences(
                theme = preferences[THEME]?.let { ThemeMode.valueOf(it) } ?: ThemeMode.SYSTEM,
                visualizer = VisualizerPreferences(
                    enabled = preferences[VISUALIZER_ENABLED] ?: false,
                    framesPerSecond = preferences[VISUALIZER_FPS] ?: 30,
                ),
                sharingScope = preferences[SHARING_SCOPE]?.let { SharingScope.valueOf(it) } ?: SharingScope.PRIVATE,
                consents = buildMap {
                    for (purpose in ConsentPurpose.entries) {
                        val version = preferences[consentVersion(purpose)]
                        val granted = preferences[consentGranted(purpose)]
                        if (version == null && granted == null) continue
                        if (version == null || granted == null) throw CorruptionException("Incomplete consent")
                        put(purpose, ConsentDecision(version, granted))
                    }
                },
            )
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("Invalid preferences value")
        } catch (error: ClassCastException) {
            throw CorruptionException("Invalid preferences type")
        }
    }

    private fun storageError(operation: PreferenceOperation, error: IOException): PreferenceStorageException =
        PreferenceStorageException(
            operation,
            when (error) {
                is PreferenceStorageException -> error.failure
                is CorruptionException -> PreferenceFailure.CORRUPT
                else -> PreferenceFailure.IO
            },
        )

    private companion object {
        val SCHEMA_VERSION = intPreferencesKey("schema_version")
        val THEME = stringPreferencesKey("theme")
        val VISUALIZER_ENABLED = booleanPreferencesKey("visualizer_enabled")
        val VISUALIZER_FPS = intPreferencesKey("visualizer_fps")
        val SHARING_SCOPE = stringPreferencesKey("sharing_scope")

        fun consentVersion(purpose: ConsentPurpose) = intPreferencesKey("consent_${purpose.name}_version")
        fun consentGranted(purpose: ConsentPurpose) = booleanPreferencesKey("consent_${purpose.name}_granted")
    }
}
