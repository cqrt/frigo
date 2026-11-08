package nz.mckenzie.frigo.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nz.mckenzie.frigo.tv.R
import nz.mckenzie.frigo.tv.data.Alert
import nz.mckenzie.frigo.tv.data.TvRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The list is a third of the width; the picture and its words get the rest. */
private val ListWidth = 440.dp

private sealed interface AlertsAnswer {
    data object Reading : AlertsAnswer
    data class Shows(val alerts: List<Alert>) : AlertsAnswer
    data object Failed : AlertsAnswer
}

/**
 * The alerts Frigate has flagged and nobody has watched, newest first.
 *
 * A page of its own rather than a strip on the cameras screen: the cameras
 * screen is for the present, and this is for the last day. Select plays a clip
 * on the television, and watching one marks it watched, so the list is the
 * things still to see rather than a log.
 */
@Composable
fun AlertsScreen(
    repository: TvRepository,
    onBack: () -> Unit,
    onPlay: (Alert) -> Unit,
) {
    BackHandler(onBack = onBack)

    var answer by remember { mutableStateOf<AlertsAnswer>(AlertsAnswer.Reading) }
    var attempt by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableIntStateOf(0) }
    val pictures = remember { mutableStateMapOf<String, ImageBitmap>() }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        answer = AlertsAnswer.Reading
        answer = runCatching { repository.alerts() }.fold(
            onSuccess = { alerts ->
                repository.markAlertsSeen()
                AlertsAnswer.Shows(alerts)
            },
            onFailure = { AlertsAnswer.Failed },
        )
        chosen = 0
    }

    LaunchedEffect(answer) {
        val shown = answer as? AlertsAnswer.Shows ?: return@LaunchedEffect
        if (shown.alerts.isEmpty()) return@LaunchedEffect
        delay(120)
        runCatching { firstFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 36.dp),
    ) {
        AlertsHeader()
        Spacer(Modifier.height(16.dp))

        when (val shown = answer) {
            AlertsAnswer.Reading -> Reading()
            AlertsAnswer.Failed -> Failed(onRetry = { attempt++ })
            is AlertsAnswer.Shows -> {
                if (shown.alerts.isEmpty()) {
                    Nothing()
                } else {
                    val selected = shown.alerts.getOrNull(chosen) ?: shown.alerts.first()
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        LazyColumn(
                            modifier = Modifier.width(ListWidth).fillMaxHeight(),
                            state = rememberLazyListState(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(shown.alerts, key = { _, alert -> alert.id }) { index, alert ->
                                val requester = remember(alert.id) { FocusRequester() }
                                AlertRow(
                                    repository = repository,
                                    alert = alert,
                                    pictures = pictures,
                                    modifier = Modifier
                                        .focusRequester(if (index == 0) firstFocus else requester)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                                    onFocused = { chosen = index },
                                    onClick = { onPlay(alert) },
                                )
                            }
                        }

                        AlertPreview(
                            repository = repository,
                            alert = selected,
                            pictures = pictures,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsHeader() {
    val clock = rememberClock()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.alerts_title).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = clock,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rememberClock(): String {
    var now by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = java.time.LocalTime.now()
        }
    }
    return remember(now) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(now)
    }
}

/** One alert in the list: its picture, when, where, and what was seen. */
@Composable
private fun AlertRow(
    repository: TvRepository,
    alert: Alert,
    pictures: MutableMap<String, ImageBitmap>,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .then(
                if (focused) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                },
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AlertPicture(
            repository = repository,
            alert = alert,
            pictures = pictures,
            modifier = Modifier.width(148.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = whenText(alert.start),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = alert.camera,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = alert.title ?: seenText(alert.objects),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The alert that is selected: its picture large, what Frigate made of it, and the way in. */
@Composable
private fun AlertPreview(
    repository: TvRepository,
    alert: Alert,
    pictures: MutableMap<String, ImageBitmap>,
    modifier: Modifier = Modifier,
) {
    val seen = stringResource(R.string.alerts_seen)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth(0.76f)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
        ) {
            AlertPicture(
                repository = repository,
                alert = alert,
                pictures = pictures,
                modifier = Modifier.fillMaxSize(),
            )
            PlayMark(Modifier.align(Alignment.Center))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = alert.title ?: seenText(alert.objects).ifBlank { seen },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (alert.words.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = alert.words,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = whenText(alert.start) + "  ·  " + alert.camera,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A circle with a play mark in it, over a clip's first picture. */
@Composable
internal fun PlayMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.18f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width * 0.18f, size.height)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}

/** An alert's picture, kept once it has been fetched so the list and the preview share it. */
@Composable
internal fun AlertPicture(
    repository: TvRepository,
    alert: Alert,
    pictures: MutableMap<String, ImageBitmap>,
    modifier: Modifier = Modifier,
) {
    val kept = pictures[alert.id]
    var picture by remember(alert.id) { mutableStateOf(kept) }

    LaunchedEffect(alert.id) {
        if (picture != null) return@LaunchedEffect
        val loaded = repository.alertThumbnail(alert)?.asImageBitmap()
        if (loaded != null) {
            pictures[alert.id] = loaded
            picture = loaded
        }
    }

    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        picture?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Reading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.alerts_reading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Nothing() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.alerts_empty),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Failed(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.alerts_failed),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            PillButton(stringResource(R.string.alerts_try_again), onClick = onRetry)
        }
    }
}

/** A press that wears a ring while the arrows are on it. */
@Composable
internal fun PillButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(ink.primary)
            .border(
                width = 2.dp,
                color = if (focused) ink.onPrimary else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 26.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = ink.onPrimary,
        )
    }
}

/** The objects Frigate saw, as a line: `Person, car`. */
private fun seenText(objects: List<String>): String = objects
    .joinToString(", ") { it.replaceFirstChar { first -> first.uppercase() } }

/** When it happened: a time today, `Yesterday 18:34`, or `Sun 18:34`. */
internal fun whenText(start: Long): String {
    if (start <= 0L) return ""
    val at = Instant.ofEpochSecond(start).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
    return when (at.toLocalDate()) {
        today -> time
        today.minusDays(1) -> "Yesterday $time"
        else -> DateTimeFormatter.ofPattern("EEE HH:mm").format(at)
    }
}
