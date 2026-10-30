package com.audipp.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.audipp.domain.model.ModelJson
import com.audipp.domain.model.ProviderPolicy
import com.audipp.domain.model.ProviderPolicySnapshot
import com.audipp.domain.model.ProviderScope
import com.audipp.domain.model.ProviderUse
import com.audipp.domain.model.UsePermission
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

enum class ProviderPolicyFailure { IO, INVALID_DATA }

class ProviderPolicyStorageException(val failure: ProviderPolicyFailure) :
    IOException("Provider policy: ${failure.name}")

class ProviderPolicyStore internal constructor(private val dataStore: DataStore<Preferences>) {
    val data: Flow<ProviderPolicySnapshot> = dataStore.data.map { decode(it) }.catch { error ->
        if (error is IOException) throw storageError(error)
        throw error
    }

    suspend fun setPermission(
        scope: ProviderScope,
        use: ProviderUse,
        permission: UsePermission,
        expectedRevision: Long? = null,
    ): Boolean {
        require(expectedRevision == null || expectedRevision >= 0)
        var updatedSuccessfully = false
        try {
            dataStore.edit { preferences ->
                val old = decode(preferences)
                if (expectedRevision != null && old.revision != expectedRevision) return@edit
                val policies = old.policies.toMutableList()
                val index = policies.indexOfFirst { it.scope == scope }
                val policy = policies.getOrNull(index) ?: ProviderPolicy(scope)
                val updated = policy.copy(permissions = policy.permissions + (use to permission))
                if (index < 0) policies.add(updated) else policies[index] = updated
                check(old.revision < Long.MAX_VALUE) { "Provider policy revision exhausted" }
                preferences[LEDGER] = ModelJson.encode(ProviderPolicySnapshot(old.revision + 1, policies))
                updatedSuccessfully = true
            }
        } catch (error: IOException) {
            throw storageError(error)
        }
        return updatedSuccessfully
    }

    private fun decode(preferences: Preferences): ProviderPolicySnapshot = try {
        val document = preferences[LEDGER]
        if (document == null) {
            if (preferences.asMap().isNotEmpty()) throw ProviderPolicyStorageException(ProviderPolicyFailure.INVALID_DATA)
            ProviderPolicySnapshot()
        } else {
            ModelJson.decode<ProviderPolicySnapshot>(document)
        }
    } catch (_: SerializationException) {
        throw ProviderPolicyStorageException(ProviderPolicyFailure.INVALID_DATA)
    } catch (_: IllegalArgumentException) {
        throw ProviderPolicyStorageException(ProviderPolicyFailure.INVALID_DATA)
    } catch (_: ClassCastException) {
        throw ProviderPolicyStorageException(ProviderPolicyFailure.INVALID_DATA)
    }

    private fun storageError(error: IOException): ProviderPolicyStorageException =
        ProviderPolicyStorageException(
            when (error) {
                is ProviderPolicyStorageException -> error.failure
                is CorruptionException -> ProviderPolicyFailure.INVALID_DATA
                else -> ProviderPolicyFailure.IO
            },
        )

    private companion object {
        val LEDGER = stringPreferencesKey("provider_policy_v1")
    }
}
