package com.audipp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.audipp.ui.WelcomeScreen
import com.audipp.ui.formatAndroidVersion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WelcomeScreen(androidVersion = formatAndroidVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        }
    }
}
