package com.audipp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.audipp.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudippThemeTest {
    @Test
    fun onlySystemFollowsOsChanges() {
        for (systemDark in listOf(false, true, false)) {
            assertEquals(systemDark, ThemeMode.SYSTEM.isDark(systemDark))
            assertTrue(ThemeMode.DARK.isDark(systemDark))
            assertEquals(false, ThemeMode.LIGHT.isDark(systemDark))
        }
    }

    @Test
    fun textAndControlsHaveContrastInBothPalettes() {
        for (colors in listOf(AudippLightColors, AudippDarkColors)) {
            for (background in listOf(colors.background, colors.surface, colors.surfaceContainer)) {
                contrast(colors.onSurface, background, 4.5)
                contrast(colors.onSurfaceVariant, background, 4.5)
                contrast(colors.primary, background, 4.5)
                contrast(colors.outline, background, 3.0)
            }
            contrast(colors.onBackground, colors.background, 4.5)
            contrast(colors.onPrimary, colors.primary, 4.5)
            contrast(colors.onSecondary, colors.secondary, 4.5)
        }
    }

    private fun contrast(foreground: Color, background: Color, minimum: Double) {
        val light = maxOf(foreground.luminance(), background.luminance())
        val dark = minOf(foreground.luminance(), background.luminance())
        val ratio = (light + 0.05) / (dark + 0.05)
        assertTrue("$foreground on $background: $ratio < $minimum", ratio >= minimum)
    }
}
