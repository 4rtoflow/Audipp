package com.audipp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.audipp.R

@Composable
fun WelcomeScreen(androidVersion: String) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Color(0xFFF6F8F7),
            onBackground = Color.Black,
            surface = Color(0xFFF6F8F7),
            onSurface = Color.Black,
            outlineVariant = Color(0xFFE5E5E5),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 33.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp),
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                    HorizontalDivider()
                    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge)
                    Text(androidVersion, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    WelcomeScreen(androidVersion = "Android 16 (API 36)")
}
