package com.audipp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.audipp.R
import com.audipp.data.preferences.PreferenceOperation
import com.audipp.domain.model.ThemeMode
import com.audipp.ui.ScreenColumn
import com.audipp.ui.theme.AudippSpacing
import com.audipp.ui.theme.AudippTheme

@Composable
fun ThemeSettingsScreen(state: ThemeSettingsState, onTheme: (ThemeMode) -> Unit, onRetry: () -> Unit) {
    Text(
        stringResource(R.string.appearance),
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleLarge)
    Column(Modifier.selectableGroup()) {
        for (mode in ThemeMode.entries) {
            val label = when (mode) {
                ThemeMode.SYSTEM -> R.string.theme_system
                ThemeMode.DARK -> R.string.theme_dark
                ThemeMode.LIGHT -> R.string.theme_light
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AudippSpacing.minimumTouchTarget)
                    .selectable(
                        selected = state.theme == mode,
                        enabled = state.theme != null && !state.saving && state.failure != PreferenceOperation.READ,
                        role = Role.RadioButton,
                        onClick = { onTheme(mode) },
                    )
                    .padding(vertical = AudippSpacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AudippSpacing.medium),
            ) {
                RadioButton(
                    selected = state.theme == mode,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(unselectedColor = MaterialTheme.colorScheme.outline),
                )
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    val status = when {
        state.failure == PreferenceOperation.READ -> R.string.theme_read_failed
        state.failure == PreferenceOperation.WRITE -> R.string.theme_write_failed
        state.saving -> R.string.theme_saving
        state.theme == null -> R.string.theme_loading
        else -> null
    }
    if (status != null) {
        Text(
            stringResource(status),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.failure != null) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Preview(name = "Light / English / 200%", locale = "en", fontScale = 2f, widthDp = 320)
@Preview(name = "Dark / Japanese / 200%", locale = "ja", fontScale = 2f, widthDp = 320, uiMode = 0x20)
@Composable
private fun ThemeSettingsPreview() {
    AudippTheme {
        ScreenColumn { ThemeSettingsScreen(ThemeSettingsState(theme = ThemeMode.SYSTEM), {}, {}) }
    }
}
