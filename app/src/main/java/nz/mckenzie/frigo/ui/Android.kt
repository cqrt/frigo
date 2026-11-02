package nz.mckenzie.frigo.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * The two things Android keeps to itself: whether Frigo may put something in the
 * shade, and whether it is allowed to keep running. Both are asked about in the
 * walkthrough and in Settings, so both are read and opened from one place.
 */

/** True on a phone where Android will show what Frigo puts in the shade. */
fun Context.notificationsAllowed(): Boolean {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    // The permission can be granted while the whole app's notifications are off.
    return granted && NotificationManagerCompat.from(this).areNotificationsEnabled()
}

/** True when Android has been told to leave Frigo's battery alone. */
fun Context.batteryIsFree(): Boolean =
    (getSystemService(Context.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(packageName) == true

fun Context.openNotificationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Frigo's own page in Android's settings, where "App battery usage" is the row
 * to pick. The list of every app would leave you scrolling to find Frigo.
 */
fun Context.openBatterySettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
