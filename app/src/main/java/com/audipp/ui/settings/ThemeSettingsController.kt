package com.audipp.ui.settings

import com.audipp.data.preferences.PreferenceOperation
import com.audipp.data.preferences.PreferenceStorageException
import com.audipp.data.preferences.PreferenceStore
import com.audipp.domain.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ThemeSettingsState(
    val theme: ThemeMode? = null,
    val saving: Boolean = false,
    val failure: PreferenceOperation? = null,
    val failedTheme: ThemeMode? = null,
)

class ThemeSettingsController(private val store: PreferenceStore) {
    private val mutableState = MutableStateFlow(ThemeSettingsState())
    val state = mutableState.asStateFlow()

    suspend fun observe() {
        mutableState.update { it.copy(failure = null) }
        try {
            store.data.collect { preferences ->
                mutableState.update { it.copy(theme = preferences.theme) }
            }
        } catch (error: PreferenceStorageException) {
            mutableState.update { it.copy(failure = PreferenceOperation.READ) }
        }
    }

    suspend fun select(theme: ThemeMode) {
        val current = mutableState.value
        if (current.theme == null || current.saving || current.failure == PreferenceOperation.READ) return
        mutableState.update { it.copy(saving = true, failure = null, failedTheme = null) }
        try {
            store.setTheme(theme)
            mutableState.update { it.copy(theme = theme) }
        } catch (error: PreferenceStorageException) {
            mutableState.update { it.copy(failure = PreferenceOperation.WRITE, failedTheme = theme) }
        } finally {
            // A cancelled write may already be committed; the next observer reads the disk truth.
            mutableState.update { it.copy(saving = false) }
        }
    }
}
