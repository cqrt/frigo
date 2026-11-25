package nz.mckenzie.frigo.tv.data

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
import nz.mckenzie.frigo.tv.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A release on GitHub, as far as this television needs to know about one. */
data class TvRelease(
    /** The version without its `v`, so `0.25.0`. */
    val version: String,
    /** The page the release lives on. */
    val page: String,
    /** The television's APK attached to it. */
    val apk: String,
    /** How big that APK is, so a half-downloaded file is never installed. */
    val bytes: Long,
) {
    fun isNewerThan(current: String): Boolean = laterThan(version, current)
}

/**
 * Asks GitHub for the newest Frigo TV, and hands the APK to Android's installer.
 *
 * The television kept falling behind the phone, and a set-top box is not a place
 * to fetch a file from a browser with a remote, so the app does it itself. The
 * repository is a build field rather than a constant, so a fork asks its own
 * repository for its own releases.
 *
 * The file it looks for is the television's own, named exactly
 * `frigo-tv-<version>.apk`. A release page carries the phone's APK as well, and
 * taking whichever file ends in `.apk` is how a television would install the
 * phone's app over itself.
 */
class TvUpdater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Where a downloaded APK waits. It is a cache: Android may clear it. */
    private val folder = File(context.cacheDir, "updates")

    /** The newest release, or null when GitHub has one with no television APK on it. */
    suspend fun latest(): TvRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val body = response.body?.string() ?: throw IOException("GitHub said nothing")
            val release = json.decodeFromString(Release.serializer(), body)
            val version = release.tag.trim().removePrefix("v")
            if (version.isEmpty()) return@use null
            val apk = release.assets.firstOrNull { it.name == "frigo-tv-$version.apk" }
                ?: return@use null
            TvRelease(version = version, page = release.page, apk = apk.url, bytes = apk.size)
        }
    }

    /**
     * Fetches the APK, reporting a whole percentage as it goes. A file of the right
     * size that is already there is kept rather than fetched again.
     */
    suspend fun download(release: TvRelease, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            folder.mkdirs()
            val apk = File(folder, "frigo-tv-${release.version}.apk")
            if (apk.isFile && apk.length() > 0 && (release.bytes <= 0 || apk.length() == release.bytes)) {
                return@withContext apk
            }

            val part = File(folder, "${apk.name}.part")
            val request = Request.Builder().url(release.apk).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
                val body = response.body ?: throw IOException("GitHub said nothing")
                val total = body.contentLength().takeIf { it > 0 } ?: release.bytes
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
                            val percent = if (total > 0) (received * 100 / total).toInt() else 0
                            if (percent != shown) {
                                shown = percent
                                // The row that says how far along it is is drawn by
                                // Compose, so the answer goes back on the main thread.
                                withContext(Dispatchers.Main.immediate) { onProgress(percent) }
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

    /** Whether Android will let this app hand an APK to its own installer. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** The screen where Android asks whether this app may install one. */
    fun askToInstall() {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Hands the downloaded APK to Android's installer. */
    fun install(apk: File) {
        val where = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(where, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** The version on this television, which a release is compared against. */
    val current: String get() = BuildConfig.VERSION_NAME
}

/** Whether this version is later than that one, counting parts as numbers. */
internal fun laterThan(one: String, other: String): Boolean {
    val left = versionParts(one)
    val right = versionParts(other)
    for (at in 0 until maxOf(left.size, right.size)) {
        val result = left.getOrElse(at) { 0 }.compareTo(right.getOrElse(at) { 0 })
        if (result != 0) return result > 0
    }
    return false
}

private fun versionParts(version: String): List<Int> = version
    .trim()
    .removePrefix("v")
    .substringBefore('-')
    .substringBefore('+')
    .split('.')
    .map { it.trim().toIntOrNull() ?: 0 }

/** Only the pieces of a GitHub release this app reads. */
@Serializable
private data class Release(
    @SerialName("tag_name") val tag: String = "",
    @SerialName("html_url") val page: String = "",
    val assets: List<Asset> = emptyList(),
)

@Serializable
private data class Asset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)
