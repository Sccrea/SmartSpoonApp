package com.smartspoon.l2.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 应用主题：**Material You 动态取色**，与本机 Gramophone 的做法一致
 * （`Theme.Material3.DynamicColors.DayNight.NoActionBar`）。
 *
 * - Android 12（API 31）及以上：从系统壁纸取色（`dynamicLight/DarkColorScheme`），
 *   每台机器的观感都不同——这是 Material 3 的预期行为。
 * - 更低版本 / 取色失败：退回 Gramophone `colors.xml` 里那套固定蓝 palette，
 *   所以任何设备上都不会出现没有主题色的情况。
 */
@Composable
fun SmartSpoonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 动态取色在个别 ROM 上会失败（缺少 system_accent 资源），失败就用兜底配色。
    val dynamic = remember(darkTheme, context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }.getOrNull()
        } else {
            null
        }
    }
    MaterialTheme(
        colorScheme = dynamic ?: if (darkTheme) FallbackDark else FallbackLight,
        content = content,
    )
}

/* ---------------------------------------------------- 兜底配色（Gramophone colors.xml） */

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF38608F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD2E4FF),
    onPrimaryContainer = Color(0xFF001C37),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6C5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF4D9FF),
    onTertiaryContainer = Color(0xFF261431),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
    inverseSurface = Color(0xFF2E3035),
    inverseOnSurface = Color(0xFFEFF0F7),
    inversePrimary = Color(0xFFA2C9FE),
    surfaceTint = Color(0xFF38608F),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA2C9FE),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF1C4875),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3C4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD8BDE4),
    onTertiary = Color(0xFF3C2947),
    tertiaryContainer = Color(0xFF533F5F),
    onTertiaryContainer = Color(0xFFF4D9FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF2E3035),
    inversePrimary = Color(0xFF38608F),
    surfaceTint = Color(0xFFA2C9FE),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)
