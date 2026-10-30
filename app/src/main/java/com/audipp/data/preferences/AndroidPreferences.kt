package com.audipp.data.preferences

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
private val Context.providerPolicyDataStore by preferencesDataStore(name = "provider_policy")

val Context.preferenceStore: PreferenceStore
    get() = PreferenceStore(applicationContext.settingsDataStore)

val Context.providerPolicyStore: ProviderPolicyStore
    get() = ProviderPolicyStore(applicationContext.providerPolicyDataStore)
