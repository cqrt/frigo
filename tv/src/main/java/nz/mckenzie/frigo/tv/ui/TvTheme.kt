package nz.mckenzie.frigo.tv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The phone app's night colours, on a television. There is no day scheme: a
 * television is watched in a room with the lamps off, and a white screen at
 * eleven at night is the whole room.
 */
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
    error = Color(0xFFFFB4AB),
)

/**
 * The phone's type, a size or two up. A television is watched from the sofa,
 * and a line that is comfortable in the hand is a line you lean forward for on
 * a 1080p screen.
 */
private val TvType = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun FrigoTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightColors,
        typography = TvType,
        content = content,
    )
}
