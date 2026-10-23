package com.audipp.ui

internal fun formatAndroidVersion(release: String, apiLevel: Int): String {
    val version = release.trim().takeIf { it.isNotEmpty() }
    return if (version == null) "Android (API $apiLevel)" else "Android $version (API $apiLevel)"
}
