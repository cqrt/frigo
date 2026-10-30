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
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE4EAF2),
    surface = Color(0xFF0F1826),
    onSurface = Color(0xFFE4EAF2),
    surfaceVariant = Color(0xFF1B2534),
    onSurfaceVariant = Color(0xFFAFBAC9),
    outline = Color(0xFF3B4859),
)

private val DayColors = lightColorScheme(
    primary = Color(0xFF23629F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF52606F),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF16202B),
    surface = Color.White,
    onSurface = Color(0xFF16202B),
    surfaceVariant = Color(0xFFE3E8EF),
    onSurfaceVariant = Color(0xFF44505E),
    outline = Color(0xFFB4BEC9),
)

@Composable
fun FrigoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) NightColors else DayColors,
        content = content,
    )
}
