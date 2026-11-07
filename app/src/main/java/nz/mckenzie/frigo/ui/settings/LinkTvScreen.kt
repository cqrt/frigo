package nz.mckenzie.frigo.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.AppContainer
import nz.mckenzie.frigo.data.PairOutcome
import nz.mckenzie.frigo.data.TvPairing
import nz.mckenzie.frigo.data.readQrCode
import nz.mckenzie.frigo.ui.FrigoIcons
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Linking a television.
 *
 * The television has no camera, so it is the phone that looks: the TV puts a
 * code on its screen, this page reads it through the camera, and then hands the
 * television the server and sign-in it should use. The password is asked for
 * here because the phone keeps a session rather than a password, and the
 * television needs one it can sign in with again tomorrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkTvScreen(
    container: AppContainer,
    baseUrl: String,
    username: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanned by remember { mutableStateOf<TvPairing?>(null) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    var paired by remember { mutableStateOf(false) }

    val askForCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!granted) askForCamera.launch(Manifest.permission.CAMERA)
    }

    fun pair() {
        val pairing = scanned ?: return
        if (busy || password.isBlank()) return
        scope.launch {
            busy = true
            said = null
            when (val outcome = container.tvPairing.pair(pairing, baseUrl, username, password)) {
                PairOutcome.Paired -> paired = true
                is PairOutcome.Said -> said = outcome.message
                PairOutcome.Silent -> said = context.getString(R.string.link_tv_no_answer)
            }
            busy = false
        }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.link_tv_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        if (paired) {
            Paired(onDone = onBack)
            return@Column
        }

        val shape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            when {
                !granted -> CameraOff(
                    asked = asked,
                    onAsk = { askForCamera.launch(Manifest.permission.CAMERA) },
                )

                else -> {
                    CameraLook(
                        scanning = scanned == null,
                        read = container.tvPairing::read,
                        onFound = { pairing -> scanned = pairing },
                    )
                    if (scanned == null) Viewfinder()
                }
            }
        }

        val pairing = scanned
        if (pairing == null) {
            Text(
                text = stringResource(R.string.link_tv_scan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.link_tv_tv_at, pairing.host),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.link_tv_signs_in,
                            username,
                            baseUrl.shortServer(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.link_tv_password),
                    )

                    if (said != null) {
                        Text(
                            text = said.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Button(
                        onClick = ::pair,
                        enabled = password.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.link_tv_pair))
                        }
                    }

                    TextButton(
                        onClick = {
                            scanned = null
                            said = null
                            password = ""
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(stringResource(R.string.link_tv_scan_again))
                    }
                }
            }
        }
    }
}

/** A tick, and the one line that says it worked. */
@Composable
private fun Paired(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.link_tv_paired),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone) { Text(stringResource(R.string.link_tv_done)) }
    }
}

/** The camera, and the reading of what it sees. */
@Composable
private fun CameraLook(
    scanning: Boolean,
    read: (String) -> TvPairing?,
    onFound: (TvPairing) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // A texture, so the rounded corners of the box above it apply.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val found = remember { AtomicBoolean(false) }
    val lastTry = remember { AtomicLong(0) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        found.set(false)
        val provider = try {
            context.cameraProvider()
        } catch (failed: Throwable) {
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
        val mainExecutor = ContextCompat.getMainExecutor(context)

        analysis.setAnalyzer(executor) { image ->
            try {
                val now = System.currentTimeMillis()
                if (now - lastTry.get() < 220) return@setAnalyzer
                lastTry.set(now)
                val text = readCode(image) ?: return@setAnalyzer
                // Not every QR code is a television's. Anything else is left
                // alone and the camera keeps looking.
                val pairing = read(text) ?: return@setAnalyzer
                if (found.compareAndSet(false, true)) {
                    mainExecutor.execute { onFound(pairing) }
                }
            } finally {
                image.close()
            }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycle,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        } catch (failed: Throwable) {
            // A phone with no camera at the back, or one another app is holding.
        }
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            provider.unbindAll()
        }
    }
}

/** Four corners around the middle of the picture, which is where the code goes. */
@Composable
private fun Viewfinder() {
    val colour = Color.White.copy(alpha = 0.9f)
    val dim = Color.Black.copy(alpha = 0.35f)
    Canvas(Modifier.fillMaxSize()) {
        val side = size.minDimension * 0.62f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side
        val arm = side * 0.18f
        val stroke = 3.dp.toPx()

        drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(dim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(dim, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(dim, topLeft = Offset(right, top), size = Size(size.width - right, side))

        listOf(
            Triple(Offset(left, top + arm), Offset(left, top), Offset(left + arm, top)),
            Triple(Offset(right - arm, top), Offset(right, top), Offset(right, top + arm)),
            Triple(Offset(left, bottom - arm), Offset(left, bottom), Offset(left + arm, bottom)),
            Triple(Offset(right - arm, bottom), Offset(right, bottom), Offset(right, bottom - arm)),
        ).forEach { (start, middle, end) ->
            drawLine(colour, start, middle, stroke, StrokeCap.Round)
            drawLine(colour, middle, end, stroke, StrokeCap.Round)
        }
    }
}

/** The camera has not been allowed, and the page says what to do about it. */
@Composable
private fun CameraOff(asked: Boolean, onAsk: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (asked) R.string.link_tv_camera_denied else R.string.link_tv_camera_off,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAsk) { Text(stringResource(R.string.link_tv_allow)) }
    }
}

/**
 * The password, drawn the way every field in the app is: a filled block with a
 * ring while the cursor is in it.
 */
@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String) {
    var focused by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ink.surfaceContainerHighest)
            .border(2.dp, if (focused) ink.primary else Color.Transparent, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = FrigoIcons.Lock,
            contentDescription = null,
            tint = if (focused) ink.primary else ink.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) ink.primary else ink.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (shown) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink.onSurface),
                cursorBrush = SolidColor(ink.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
        IconButton(onClick = { shown = !shown }, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (shown) FrigoIcons.EyeOff else FrigoIcons.Eye,
                contentDescription = if (shown) {
                    stringResource(R.string.login_hide_password)
                } else {
                    stringResource(R.string.login_show_password)
                },
                tint = ink.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** The code out of one camera frame, or null when there is no QR code in it. */
private fun readCode(image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride

    val data: ByteArray
    val dataWidth: Int
    if (pixelStride == 1) {
        data = ByteArray(buffer.remaining())
        buffer.get(data)
        dataWidth = rowStride
    } else {
        // A camera that pads between pixels is rare, and has to be packed
        // rather than handed over as it came.
        dataWidth = image.width
        data = ByteArray(dataWidth * image.height)
        var at = 0
        for (y in 0 until image.height) {
            var offset = y * rowStride
            for (x in 0 until dataWidth) {
                data[at++] = buffer.get(offset)
                offset += pixelStride
            }
        }
    }
    return readQrCode(data, dataWidth, image.width, image.height)
}

/** The address without its scheme, which is the part that names the server. */
private fun String.shortServer(): String =
    removePrefix("https://").removePrefix("http://").trimEnd('/')

private suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (failed: Throwable) {
                    continuation.resumeWithException(failed)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
