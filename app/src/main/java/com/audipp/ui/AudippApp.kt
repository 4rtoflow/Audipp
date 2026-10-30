package com.audipp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.audipp.R
import com.audipp.domain.model.ThemeMode
import com.audipp.ui.settings.ThemeSettingsScreen
import com.audipp.ui.settings.ThemeSettingsState
import com.audipp.ui.theme.AudippSpacing

private enum class Screen { WELCOME, SETTINGS, APPEARANCE }

@Composable
fun AudippApp(
    androidVersion: String,
    themeState: ThemeSettingsState,
    onTheme: (ThemeMode) -> Unit,
    onRetry: () -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.WELCOME) }
    val screenState = rememberSaveableStateHolder()
    val back = { screen = if (screen == Screen.APPEARANCE) Screen.SETTINGS else Screen.WELCOME }
    BackHandler(enabled = screen != Screen.WELCOME, onBack = back)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        screenState.SaveableStateProvider(screen) {
            ScreenColumn {
                when (screen) {
                    Screen.WELCOME -> WelcomeScreen(androidVersion) { screen = Screen.SETTINGS }
                    Screen.SETTINGS -> {
                        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
                        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineLarge)
                        TextButton(onClick = { screen = Screen.APPEARANCE }) {
                            Text(stringResource(R.string.appearance))
                        }
                    }
                    Screen.APPEARANCE -> {
                        TextButton(onClick = back) { Text(stringResource(R.string.back)) }
                        ThemeSettingsScreen(themeState, onTheme, onRetry)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .widthIn(max = AudippSpacing.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AudippSpacing.extraLarge, vertical = AudippSpacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(AudippSpacing.large),
            content = content,
        )
    }
}
