package com.audipp.ui

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.audipp.R
import com.audipp.ui.theme.AudippTheme

@Composable
fun WelcomeScreen(androidVersion: String, onSettings: () -> Unit = {}) {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
    HorizontalDivider()
    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge)
    Text(androidVersion, style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = onSettings) { Text(stringResource(R.string.settings)) }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    AudippTheme {
        ScreenColumn { WelcomeScreen(androidVersion = "Android 16 (API 36)") }
    }
}
