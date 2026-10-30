package com.audipp.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class ModelDocument<T>(
    val value: T,
    @Required val schemaVersion: Int = ModelJson.SCHEMA_VERSION,
) {
    init { require(schemaVersion == ModelJson.SCHEMA_VERSION) }
}

object ModelJson {
    const val SCHEMA_VERSION = 1

    val format: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    inline fun <reified T> encode(value: T): String = format.encodeToString(ModelDocument(value))

    inline fun <reified T> decode(document: String): T =
        format.decodeFromString<ModelDocument<T>>(document).value
}
