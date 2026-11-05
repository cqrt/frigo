package nz.mckenzie.frigo.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.words

/**
 * Whether this phone can ask for anything at all.
 *
 * Android 11 and later puts the sensor and the PIN in one sheet, so one question
 * answers it. Before that the sheet is the sensor alone and the PIN is a screen
 * of its own, so a phone with a PIN and no fingerprint still counts: the screen
 * lock is the thing that has to exist.
 */
internal fun lockAvailable(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    } else {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS || deviceIsSecure(context)
    }

/** Whether the phone has a PIN, a pattern or a password on it at all. */
private fun deviceIsSecure(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

/**
 * What the phone will accept, asked once when the screen is built.
 *
 * The answer can change while the app is open, by someone taking the screen lock
 * off in the phone's settings, so the cover checks it again when it needs it.
 */
@Composable
internal fun rememberLockAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { lockAvailable(context) }
}

/**
 * The phone's own prompt, handed back as something to call.
 *
 * [onUnlocked] runs only when the phone says yes. On the versions that cannot
 * put the PIN in the same sheet, the sheet's second button opens the phone's
 * credential screen instead, and that screen's answer is the one that counts.
 */
@Composable
internal fun rememberAskForLock(onUnlocked: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val answered = rememberUpdatedState(onUnlocked)

    // The PIN's own screen, on the phones whose biometric sheet cannot hold it.
    val pinScreen = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) answered.value()
    }

    return remember(activity, context) {
        {
            if (activity == null) {
                answered.value()
            } else {
                askForLock(
                    activity = activity,
                    onUnlocked = { answered.value() },
                    onUsePin = {
                        val screen = credentialScreen(context)
                        // No credential screen means there is no screen lock left
                        // to ask for, so nothing is left to hold the app shut.
                        if (screen == null) answered.value() else pinScreen.launch(screen)
                    },
                )
            }
        }
    }
}

/** Puts the phone's prompt up. A fresh one each time, so it reads the current state. */
private fun askForLock(
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
    onUsePin: () -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // The second button on the older sheet is "Use my PIN".
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) onUsePin()
            }
        },
    )
    prompt.authenticate(promptInfo(activity))
}

/** What the sheet says, and what it will take. */
private fun promptInfo(context: Context): BiometricPrompt.PromptInfo {
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(context.words(R.string.lock_prompt_title))
        .setSubtitle(context.words(R.string.lock_prompt_subtitle))

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // One sheet, with its own cancel. It refuses a negative button beside it,
        // which is why there is none.
        builder
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
    } else {
        // The sensor alone, with the PIN behind a button of its own.
        builder
            .setNegativeButtonText(context.words(R.string.lock_use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
    }
}

/**
 * The phone's own credential screen, which is where the PIN goes on the versions
 * that will not put it in the biometric sheet.
 */
@Suppress("DEPRECATION")
private fun credentialScreen(context: Context): Intent? =
    context.getSystemService(KeyguardManager::class.java)
        ?.createConfirmDeviceCredentialIntent(
            context.words(R.string.lock_prompt_title),
            null,
        )
