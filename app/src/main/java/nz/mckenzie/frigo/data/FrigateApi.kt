package nz.mckenzie.frigo.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

/**
 * The handful of Frigate endpoints this app needs. Calls block, so everything
 * here runs on Dispatchers.IO from the repository.
 *
 * Written against Frigate 0.18. Sign-in takes a JSON body, and the session is a
 * JWT handed back in a cookie header, which is then sent as a bearer token.
 */
class FrigateApi(
    private val client: OkHttpClient,
    /** Same client settings but with no read timeout, for the endless MJPEG body. */
    private val streamClient: OkHttpClient = client,
    /** Roomier timeouts than the rest, for a clip that arrives slowly. */
    private val clipClient: OkHttpClient = client,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Signs in and returns the token. The fields are `user` and `password`;
     * sending `username` gets a 422 with no explanation of which field was wrong.
     */
    fun login(baseUrl: String, user: String, password: String): String {
        val body = buildJsonObject {
            put("user", user)
            put("password", password)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url(url(baseUrl, "/api/login"))
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            return response.jwtFromCookies()
                ?: throw FrigateException(0, "Your server accepted the sign-in but sent no token")
        }
    }

    /**
     * 202 means the token is still good. The server may also hand back a fresh
     * token near expiry, which is returned here so the caller can keep it.
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

    fun config(baseUrl: String, token: String): FrigateConfig {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/config"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return json.decodeFromString(FrigateConfig.serializer(), request.okBody())
    }

    /** Which cameras this user may watch, and which role they have. */
    fun profile(baseUrl: String, token: String): FrigateProfile {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/profile"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return json.decodeFromString(FrigateProfile.serializer(), request.okBody())
    }

    /**
     * What Frigate has recorded, newest first.
     *
     * `labels` and `subLabels` are sent as one comma-separated value. Repeated
     * parameters don't add up: a request with two of them is answered with the
     * last one alone, which was checked against 0.18.2.
     */
    fun events(
        baseUrl: String,
        token: String,
        camera: String?,
        labels: List<String>,
        subLabels: List<String>,
        limit: Int,
        after: Double?,
        before: Double?,
    ): List<FrigateEvent> {
        val builder = url(baseUrl, "/api/events").newBuilder()
            .addQueryParameter("limit", limit.toString())
        if (!camera.isNullOrBlank()) builder.addQueryParameter("camera", camera)
        if (labels.isNotEmpty()) builder.addQueryParameter("labels", labels.joinToString(","))
        if (subLabels.isNotEmpty()) builder.addQueryParameter("sub_labels", subLabels.joinToString(","))
        if (after != null) builder.addQueryParameter("after", after.toString())
        if (before != null) builder.addQueryParameter("before", before.toString())

        val request = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return json.decodeFromString(ListSerializer(FrigateEvent.serializer()), request.okBody())
    }

    /** The names Frigate has learned to recognise, which `sub_labels` filters by. */
    fun subLabels(baseUrl: String, token: String): List<String> {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/sub_labels"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return json.decodeFromString(ListSerializer(String.serializer()), request.okBody())
    }

    /**
     * The reviews Frigate has kept: the stretches of time it thought were worth a
     * look. `severity` narrows it to `alert` or `detection`.
     *
     * The endpoint is `/api/review`, singular, which is easy to get wrong when
     * the events endpoint sitting next to it is `/api/events`.
     *
     * `after` is sent as zero rather than left out. Left out, the server only
     * looks at the last day, and asking for older reviews would bring nothing
     * back because the day window never moves.
     */
    fun reviews(
        baseUrl: String,
        token: String,
        severity: String?,
        limit: Int,
        before: Double?,
        showReviewed: Boolean,
    ): List<FrigateReview> {
        val builder = url(baseUrl, "/api/review").newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("after", "0")
        if (!severity.isNullOrBlank()) builder.addQueryParameter("severity", severity)
        if (before != null) builder.addQueryParameter("before", before.toString())
        // Left out, the server sends reviewed and unreviewed together, which is
        // what "show reviewed" means. Asked for, it sends one kind or the other.
        if (!showReviewed) builder.addQueryParameter("reviewed", "0")

        val request = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return json.decodeFromString(ListSerializer(FrigateReview.serializer()), request.okBody())
    }

    /**
     * Tells Frigate a review has been watched, which is what takes it out of the
     * list for everyone, the web page included.
     */
    fun markReviewed(baseUrl: String, token: String, reviewId: String) {
        val body = """{"ids":["$reviewId"],"reviewed":true}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url(baseUrl, "/api/reviews/viewed"))
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        request.okBody()
    }

    /**
     * Copies a clip into a file as it arrives.
     *
     * Frigate sends a clip with no length and no support for ranges, cutting it
     * out of the recordings while the response goes out. There is nothing to skip
     * to and no way to resume, so this is a straight copy of the body, reporting
     * how much has landed.
     *
     * A response that goes quiet ends the copy rather than failing it. Frigate's
     * ffmpeg writes the clip as a series of fragments, each one playable on its
     * own, and it can leave the connection open after the last one has gone. What
     * arrived is a clip that plays to where the server got to.
     *
     * `shouldKeepGoing` is checked between chunks: closing the viewer stops the
     * download rather than leaving it running with nobody watching.
     */
    fun downloadClip(
        source: ClipSource,
        target: File,
        onProgress: (Long) -> Unit,
        shouldKeepGoing: () -> Boolean,
    ) {
        val request = Request.Builder()
            .url(source.url)
            .header("Authorization", "Bearer ${source.token}")
            .get()
            .build()

        clipClient.newCall(request).execute().use { response ->
            // These two would otherwise arrive as "the server has no such
            // endpoint" or "that username and password weren't accepted", neither
            // of which is what happened.
            if (response.code == 400 || response.code == 404) {
                throw FrigateException(response.code, NO_CLIP)
            }
            if (response.code == 401 || response.code == 403) {
                throw FrigateException(response.code, "Your session has expired. Sign in again.")
            }
            if (!response.isSuccessful) throw response.toException()

            val body = response.body
                ?: throw FrigateException(response.code, "The server sent nothing.")

            var total = 0L
            try {
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            total += read
                            if (total > MAX_CLIP_BYTES) throw FrigateException(0, CLIP_TOO_BIG)
                            if (!shouldKeepGoing()) throw CancellationException("clip download stopped")
                            onProgress(total)
                        }
                    }
                }
            } catch (quiet: SocketTimeoutException) {
                // The server stopped sending part way through. What arrived is a
                // clip; keeping it beats throwing it away.
                if (total == 0L) throw quiet
            }

            if (total == 0L) throw FrigateException(0, EMPTY_CLIP)
        }
    }

    /** A single still, used for the camera grid. */
    fun latestFrame(baseUrl: String, token: String, camera: String, height: Int): ByteArray {
        val target = url(baseUrl, "/api/$camera/latest.jpg").newBuilder()
            .addQueryParameter("height", height.toString())
            .build()
        return Request.Builder()
            .url(target)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
            .okBytes()
    }

    /** The objects this server looks for: `person`, `car`, and whatever else is set up. */
    fun labels(baseUrl: String, token: String): List<String> {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/labels"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return json.decodeFromString(ListSerializer(String.serializer()), request.okBody())
    }

    fun eventThumbnail(baseUrl: String, token: String, eventId: String): ByteArray {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/events/$eventId/thumbnail.jpg"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return request.okBytes()
    }

    fun eventSnapshot(baseUrl: String, token: String, eventId: String): ByteArray {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/events/$eventId/snapshot.jpg"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return request.okBytes()
    }

    /**
     * Opens the MJPEG feed. The response must be closed by the caller; its body
     * is a never-ending multipart stream of JPEG frames.
     */
    fun openMjpeg(baseUrl: String, token: String, camera: String, height: Int, fps: Int): Response {
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
            throw FrigateException(status, "Live view returned $status")
        }
        return response
    }

    private fun url(baseUrl: String, path: String): HttpUrl =
        (baseUrl.trimEnd('/') + path).toHttpUrlOrNull()
            ?: throw FrigateException(0, "That server address doesn't look right")

    /**
     * Reads the JWT out of Set-Cookie. The cookie's name is configurable on the
     * server, so the value is recognised by its shape instead: three parts
     * separated by dots.
     */
    private fun Response.jwtFromCookies(): String? {
        for (header in headers("Set-Cookie")) {
            val value = header.substringBefore(';').substringAfter('=', "").trim()
            if (value.count { it == '.' } == 2 && value.isNotEmpty()) return value
        }
        return null
    }

    private fun Request.okBody(): String {
        client.newCall(this).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            return response.body?.string().orEmpty()
        }
    }

    private fun Request.okBytes(): ByteArray {
        client.newCall(this).execute().use { response ->
            if (!response.isSuccessful) throw response.toException()
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun Response.toException(): FrigateException {
        val serverSaid = serverMessage()
        val text = when (code) {
            401, 403 -> "That username and password weren't accepted"
            404 -> "The server has no such endpoint. Is that the Frigate address?"
            422 -> "The server didn't understand the request" + (serverSaid?.let { ": $it" } ?: "")
            502, 503, 504 -> "The server is not answering right now"
            else -> serverSaid ?: "The server returned $code"
        }
        return FrigateException(code, text)
    }

    /** Frigate answers failures with `message`, and validation failures with `detail`. */
    private fun Response.serverMessage(): String? {
        val raw = runCatching { body?.string() }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return null

        textOf(obj["message"])?.let { return it }

        return when (val detail = obj["detail"]) {
            is JsonArray -> detail.joinToString("; ") { item ->
                val entry = item as? JsonObject ?: return@joinToString textOf(item).orEmpty()
                val field = (entry["loc"] as? JsonArray)
                    ?.mapNotNull { textOf(it) }
                    ?.filter { it != "body" }
                    ?.joinToString(".")
                val reason = textOf(entry["msg"])
                listOfNotNull(field, reason).joinToString(" ")
            }
            else -> textOf(detail)
        }
    }

    private fun textOf(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** A clip is cut from recordings, so it goes when they do. */
        const val NO_CLIP = "Frigate has no clip for this one any more. It cuts clips out " +
            "of your recordings, so an old one goes when the recordings do."

        /** Beyond this a review covers hours and the phone runs out of room. */
        const val MAX_CLIP_BYTES = 400L * 1024 * 1024
        const val CLIP_TOO_BIG = "This clip is too long to fetch, at over 400 MB. " +
            "Reviews stay open while something keeps being seen, so an old one can " +
            "cover hours of recording."

        const val EMPTY_CLIP = "The server sent an empty clip. Its recordings may not " +
            "cover that moment yet, so try again in a moment."
    }
}
