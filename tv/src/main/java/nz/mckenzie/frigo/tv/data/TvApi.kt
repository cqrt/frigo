package nz.mckenzie.frigo.tv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Something Frigate or the network said that the screen should repeat. */
class TvException(val status: Int, override val message: String) : IOException(message)

/**
 * The handful of Frigate endpoints a television needs: signing in, the list of
 * cameras, a still, the small MJPEG feed, and the camera's own H.264.
 *
 * It is written against Frigate 0.18, the same version the phone talks to. The
 * sign-in is a JSON body of `user` and `password`, and the session comes back as
 * a JWT in a `Set-Cookie` header, which is then sent as a bearer token.
 */
class TvApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** The MJPEG body never ends, so it gets a client with no read timeout. */
    private val streamClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Signs in and returns the token. */
    fun login(baseUrl: String, user: String, password: String): String {
        val body = """{"user":${quote(user)},"password":${quote(password)}}"""
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(url(baseUrl, "/api/login"))
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            return response.jwtFromCookies() ?: throw TvException(0, NO_TOKEN)
        }
    }

    /**
     * 202 means the token is still good. The server may hand back a fresh one
     * near expiry, which is returned so it can be kept.
     */
    fun checkToken(baseUrl: String, token: String): String? {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/auth"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            return response.jwtFromCookies()
        }
    }

    /**
     * The cameras this account may watch, named and in the order the phone puts
     * them in. `/api/config` lists every camera on the server whatever the
     * account is, so the profile is what narrows it.
     */
    fun cameras(baseUrl: String, token: String): List<Camera> {
        val allowed = json.decodeFromString(
            Profile.serializer(),
            open(baseUrl, token, "/api/profile").use { it.bodyText() },
        ).allowedCameras
        val config = json.decodeFromString(
            Config.serializer(),
            open(baseUrl, token, "/api/config").use { it.bodyText() },
        )
        return config.cameras
            .filterValues { it.enabled }
            .filter { (name, _) -> allowed == null || name in allowed }
            .map { (name, camera) -> Camera(name, camera.friendlyName ?: prettyName(name)) }
            .sortedBy { it.title.lowercase() }
    }

    /** One camera's most recent still, which stands in until the video arrives. */
    fun latestJpeg(baseUrl: String, token: String, camera: String, height: Int): ByteArray {
        val target = url(baseUrl, "/api/$camera/latest.jpg").newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .addQueryParameter("height", height.toString())
            .build()
        val request = Request.Builder()
            .url(target)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * The small MJPEG feed, kept open until it is closed. The caller owns the
     * response and closes it when it stops watching.
     */
    fun openMjpeg(
        baseUrl: String,
        token: String,
        camera: String,
        height: Int,
        fps: Int,
    ): Response {
        val target = url(baseUrl, "/api/$camera").newBuilder()
            .addQueryParameter("height", height.toString())
            .addQueryParameter("fps", fps.toString())
            .build()
        val request = Request.Builder()
            .url(target)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val response = streamClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw TvException(status, "The live picture returned $status.")
        }
        return response
    }

    /** The camera's own H.264, which is what the big picture plays. */
    fun streamUrl(baseUrl: String, camera: String): String =
        (baseUrl.trimEnd('/') + "/api/stream.mp4?src=" + camera).also {
            if (it.toHttpUrlOrNull() == null) throw TvException(0, "That address is not a web address.")
        }

    /**
     * The alerts Frigate has flagged since [after], newest first, and only the
     * ones nobody has watched yet.
     *
     * Frigate's endpoint is `/api/review`, singular, while the one that marks
     * them watched is `/api/reviews/viewed`, plural. That mismatch is the
     * server's, not this file's.
     */
    fun alerts(baseUrl: String, token: String, after: Long, limit: Int = 25): List<Alert> {
        val target = url(baseUrl, "/api/review").newBuilder()
            .addQueryParameter("severity", "alert")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("after", after.toString())
            .addQueryParameter("reviewed", "0")
            .build()
        val request = Request.Builder()
            .url(target)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val raw = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            response.bodyText()
        }
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { it.asAlert() }
        }.getOrDefault(emptyList())
    }

    /** The picture of the thing an alert first saw, which is the alert's picture. */
    fun eventThumbnail(baseUrl: String, token: String, eventId: String): ByteArray {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/events/$eventId/thumbnail.jpg"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Says an alert has been watched, which its own web page and the phone share. */
    fun markReviewed(baseUrl: String, token: String, id: String) {
        val body = """{"ids":["$id"],"reviewed":true}""".toRequestBody(JSON)
        val request = Request.Builder()
            .url(url(baseUrl, "/api/reviews/viewed"))
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
        }
    }

    /** Where a review's clip comes from. Frigate cuts it out of the recordings as it sends. */
    fun clipPath(id: String): String = "/api/review/$id/clip.mp4"

    fun open(baseUrl: String, token: String, path: String): Response {
        val response = client.newCall(
            Request.Builder()
                .url(url(baseUrl, path))
                .header("Authorization", "Bearer $token")
                .get()
                .build(),
        ).execute()
        if (!response.isSuccessful) {
            val status = response.code
            response.close()
            throw TvException(status, "The server returned $status.")
        }
        return response
    }

    private fun url(baseUrl: String, path: String) =
        (baseUrl.trimEnd('/') + path).toHttpUrlOrNull()
            ?: throw TvException(0, "That address is not a web address.")

    /** Turns `door_cam` into `Door cam`, for a camera with no name of its own. */
    private fun prettyName(raw: String): String = raw
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replaceFirstChar { it.uppercase() }

    private fun Response.bodyText(): String = body?.string().orEmpty()

    /**
     * Reads the JWT out of Set-Cookie. The cookie's name is configurable on the
     * server, so the value is recognised by its shape: three parts, dots between.
     */
    private fun Response.jwtFromCookies(): String? {
        for (header in headers("Set-Cookie")) {
            val value = header.substringBefore(';').substringAfter('=', "").trim()
            if (value.count { it == '.' } == 2 && value.isNotEmpty()) return value
        }
        return null
    }

    private fun Response.toException(): TvException {
        val said = runCatching { body?.string() }.getOrNull().orEmpty()
        val text = when (code) {
            401, 403 -> "Frigate refused the username or password."
            404 -> "That address has no Frigate on it."
            in 500..599 -> "The server is not answering."
            else -> said.ifBlank { "The server returned $code." }
        }
        return TvException(code, text)
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (character in text) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private companion object {
        const val NO_TOKEN = "Frigate signed in but sent no session."
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** One camera, as the television shows it. */
data class Camera(val name: String, val title: String)

/**
 * One moment Frigate flagged, as the television lists it.
 *
 * [eventId] is the first thing it saw, which is what its picture comes from on
 * Frigate's API. [title] and [words] are what the server wrote about it a minute
 * or two after it ended, and are empty until it has.
 */
data class Alert(
    val id: String,
    /** The camera as Frigate names it. */
    val cameraName: String,
    /** The camera as this television shows it. */
    val camera: String,
    val start: Long,
    val end: Long?,
    val title: String?,
    val words: String,
    val objects: List<String>,
    val eventId: String?,
)

private fun JsonElement.textOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement.asStrings(): List<String> =
    (this as? JsonArray)?.mapNotNull { it.textOrNull() }.orEmpty()

/** A scene the model wrote as a sentence, or as a list holding that sentence. */
private fun JsonElement.asScene(): String = when (this) {
    is JsonArray -> mapNotNull { it.textOrNull() }.joinToString(" ")
    else -> textOrNull().orEmpty()
}

private fun JsonElement.asAlert(): Alert? {
    val row = runCatching { jsonObject }.getOrNull() ?: return null
    val id = row["id"]?.textOrNull() ?: return null
    val data = row["data"] as? JsonObject
    val metadata = data?.get("metadata") as? JsonObject

    val title = metadata?.get("title")?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    val summary = metadata?.get("short_summary")?.textOrNull()?.trim().orEmpty()
    val scene = metadata?.get("scene")?.asScene()?.trim().orEmpty()
    val words = listOf(summary, scene).firstOrNull { it.isNotEmpty() }.orEmpty()
    val camera = row["camera"]?.textOrNull().orEmpty()

    return Alert(
        id = id,
        cameraName = camera,
        camera = camera,
        start = (row["start_time"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L,
        end = (row["end_time"] as? JsonPrimitive)?.doubleOrNull?.toLong(),
        title = title,
        words = words,
        objects = data?.get("objects")?.asStrings().orEmpty(),
        eventId = (data?.get("detections") as? JsonArray)?.firstNotNullOfOrNull { it.textOrNull() },
    )
}

@Serializable
private data class Config(val cameras: Map<String, CameraConfig> = emptyMap())

@Serializable
private data class CameraConfig(
    val enabled: Boolean = true,
    @SerialName("friendly_name") val friendlyName: String? = null,
)

@Serializable
private data class Profile(
    @SerialName("allowed_cameras") val allowedCameras: List<String>? = null,
)
