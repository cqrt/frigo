package nz.mckenzie.frigo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NightColors = darkColorScheme(
    primary = Color(0xFF8FC2FF),
    onPrimary = Color(0xFF04223F),
    primaryContainer = Color(0xFF12395C),
    onPrimaryContainer = Color(0xFFD4E4FF),
    secondary = Color(0xFFB9C8DA),
    onSecondary = Color(0xFF14202D),
    secondaryContainer = Color(0xFF22313F),
    onSecondaryContainer = Color(0xFFD7E3F1),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE4EAF2),
    surface = Color(0xFF0F1826),
    onSurface = Color(0xFFE4EAF2),
    surfaceVariant = Color(0xFF1B2534),
    onSurfaceVariant = Color(0xFFAFBAC9),
    surfaceContainerLowest = Color(0xFF0A1119),
    surfaceContainerLow = Color(0xFF121B29),
    surfaceContainer = Color(0xFF16202F),
    surfaceContainerHigh = Color(0xFF1B2635),
    surfaceContainerHighest = Color(0xFF212C3C),
    outline = Color(0xFF3B4859),
)

private val DayColors = lightColorScheme(
    primary = Color(0xFF1E5C99),
    onPrimary = Color(0xFFF7FAFF),
    primaryContainer = Color(0xFFCFE0F2),
    onPrimaryContainer = Color(0xFF0A2742),
    secondary = Color(0xFF4B5866),
    onSecondary = Color(0xFFF7FAFF),
    secondaryContainer = Color(0xFFDCE4ED),
    onSecondaryContainer = Color(0xFF17202A),
    background = Color(0xFFE5EAF1),
    onBackground = Color(0xFF19222E),
    surface = Color(0xFFE5EAF1),
    onSurface = Color(0xFF19222E),
    surfaceVariant = Color(0xFFEFF3F8),
    onSurfaceVariant = Color(0xFF43505E),
    surfaceContainerLowest = Color(0xFFF9FBFD),
    surfaceContainerLow = Color(0xFFF2F5FA),
    surfaceContainer = Color(0xFFEBF0F6),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFDCE3EC),
    outline = Color(0xFFAEBAC7),
)

/**
 * Which colours the app uses. The order matches the numbers kept in
 * [nz.mckenzie.frigo.data.ServerPrefs], because the number is what is stored.
 */
enum class ThemeMode {
    /** Whatever the phone is set to, which is what the app did before. */
    System,

    /** Light colours, whatever the phone is set to. */
    Day,

    /** Dark colours, whatever the phone is set to. */
    Night,
}

@Composable
fun FrigoTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Day -> false
        ThemeMode.Night -> true
    }
    MaterialTheme(
        colorScheme = if (dark) NightColors else DayColors,
        content = content,
    )
}
