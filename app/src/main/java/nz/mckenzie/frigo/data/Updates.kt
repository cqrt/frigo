package nz.mckenzie.frigo.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A release on GitHub, as far as this app needs to know about one. */
@Serializable
data class Update(
    /** The version without its `v`, so `0.7.28`. */
    val version: String,
    /** The page the release lives on, for what changed. */
    val page: String,
    /** The APK attached to it. */
    val apk: String,
    /** How big that APK is, for the progress and to tell a finished file from a half one. */
    val bytes: Long,
) {
    /** Whether this release is later than the version on the phone. */
    fun isNewerThan(current: String): Boolean = laterVersion(version, current)
}

/**
 * The answer the phone is holding on to, and when it arrived. Kept between launches
 * so the Settings tab knows about a newer release before GitHub has been asked again.
 */
@Serializable
data class StoredAnswer(
    val checkedAt: Long = 0L,
    val update: Update? = null,
)

/**
 * Asks GitHub what the newest release of Frigo is, and fetches its APK.
 *
 * The releases are public, so this needs no token and no account: it is the same file
 * anyone gets from the release page. It is asked for without a sign-in because the app
 * updates whether or not you are signed in to your Frigate server.
 */
class Updater(
    private val client: OkHttpClient,
    cacheDir: File,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Where a downloaded APK waits to be installed. It is a cache: Android may clear it. */
    private val folder = File(cacheDir, "updates")

    /**
     * The newest release, or null when GitHub has one without an APK attached.
     * Throws when GitHub can't be reached or doesn't answer sensibly.
     *
     * Every call asks. How often that is worth doing is [UpdateWatch]'s business, since
     * it is the thing holding the answer the rest of the app reads.
     */
    suspend fun latest(): Update? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$ReleasesUrl/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()

        val found = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub answered ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("GitHub said nothing")
            json.decodeFromString(Release.serializer(), body).toUpdate()
        }
        found
    }

    /**
     * Fetches the APK and returns the file it landed in, reporting how much has
     * arrived as it goes. A file of the right size that is already there is kept
     * rather than fetched again, so a second tap is free.
     *
     * It downloads beside the real name and is renamed at the end, so a download
     * that stops half way is never mistaken for a whole one.
     */
    suspend fun download(update: Update, onProgress: suspend (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            folder.mkdirs()
            val apk = File(folder, "frigo-${update.version}.apk")
            if (apk.isFile && apk.length() > 0 && (update.bytes <= 0 || apk.length() == update.bytes)) {
                withContext(Dispatchers.Main.immediate) {
                    onProgress(apk.length(), apk.length())
                }
                return@withContext apk
            }

            val part = File(folder, "${apk.name}.part")
            val request = Request.Builder().url(update.apk).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub answered ${response.code}")
                }
                val body = response.body ?: throw IOException("GitHub said nothing")
                val total = body.contentLength().takeIf { it > 0 } ?: update.bytes
                var received = 0L
                var shown = -1
                part.outputStream().use { sink ->
                    body.byteStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = source.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            received += read
                            // Once a percent, rather than once every chunk: the screen
                            // has nothing to say about the difference.
                            val percent = if (total > 0) (received * 100 / total).toInt() else 0
                            if (percent != shown) {
                                shown = percent
                                withContext(Dispatchers.Main.immediate) { onProgress(received, total) }
                            }
                        }
                    }
                }
            }

            if (!part.renameTo(apk)) {
                part.delete()
                throw IOException("Couldn't keep the download")
            }
            apk
        }

    /** A downloaded APK for this version, if one is still on the phone. */
    fun downloaded(version: String): File? =
        File(folder, "frigo-$version.apk").takeIf { it.isFile && it.length() > 0 }

    private companion object {
        const val ReleasesUrl = "https://api.github.com/repos/cqrt/frigo/releases"
    }
}

/** How old an answer can get before GitHub is asked again. */
const val UpdateWindowMillis = 60L * 60L * 1000L

/** Whether this version is later than that one. `v` and a suffix are ignored. */
fun laterVersion(one: String, other: String): Boolean = compareVersions(one, other) > 0

/**
 * Whether a release is waiting to be noticed, which is what the dot on the Settings
 * tab means. A version you have already seen is not news, so only one later than it
 * brings the dot back.
 */
fun updateWaiting(release: Update?, current: String, seen: String?): Boolean =
    release != null &&
        release.isNewerThan(current) &&
        (seen == null || compareVersions(release.version, seen) > 0)

/** Whether the answer on the phone is old enough to be worth asking GitHub again. */
fun answerStale(checkedAt: Long, now: Long): Boolean = now - checkedAt >= UpdateWindowMillis

/**
 * The version parts counted as numbers rather than as text, so 0.7.100 is later than
 * 0.7.99 rather than earlier. A part that isn't a number counts as nothing.
 */
internal fun compareVersions(one: String, other: String): Int {
    val left = versionParts(one)
    val right = versionParts(other)
    for (at in 0 until maxOf(left.size, right.size)) {
        val result = left.getOrElse(at) { 0 }.compareTo(right.getOrElse(at) { 0 })
        if (result != 0) return result
    }
    return 0
}

private fun versionParts(version: String): List<Int> = version
    .trim()
    .removePrefix("v")
    .substringBefore('-')
    .substringBefore('+')
    .split('.')
    .map { it.trim().toIntOrNull() ?: 0 }

/** Whether Android will let Frigo hand an APK to its own installer. */
fun canInstallApps(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

/**
 * The screen where Android asks whether Frigo may install an app. There is no way to
 * grant this from inside the app, and no dialog to show instead: the switch belongs to
 * the phone rather than to the app.
 */
fun askToInstallApps(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Hands a downloaded APK to Android's installer. */
fun installUpdate(context: Context, apk: File) {
    val where = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(where, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Only the pieces of a GitHub release this app reads. */
@Serializable
private data class Release(
    @SerialName("tag_name") val tag: String = "",
    @SerialName("html_url") val page: String = "",
    val assets: List<Asset> = emptyList(),
) {
    fun toUpdate(): Update? {
        val version = tag.trim().removePrefix("v")
        if (version.isEmpty()) return null
        // The workflow names it after the version, and a release page can hold other
        // files besides, so the name is what picks the APK out.
        val apk = assets.firstOrNull { it.name == "frigo-$version.apk" }
            ?: assets.firstOrNull { it.name.endsWith(".apk") }
            ?: return null
        return Update(version = version, page = page, apk = apk.url, bytes = apk.size)
    }
}

@Serializable
private data class Asset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)
