package com.audipp.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

val Context.preferenceStore: PreferenceStore
    get() = PreferenceStore(applicationContext.settingsDataStore)
