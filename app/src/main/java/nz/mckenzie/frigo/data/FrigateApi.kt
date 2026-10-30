package nz.mckenzie.frigo.data

import kotlinx.serialization.builtins.ListSerializer
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

    fun events(
        baseUrl: String,
        token: String,
        camera: String?,
        labels: String?,
        limit: Int,
        before: Double?,
    ): List<FrigateEvent> {
        val builder = url(baseUrl, "/api/events").newBuilder()
            .addQueryParameter("limit", limit.toString())
        if (!camera.isNullOrBlank()) builder.addQueryParameter("camera", camera)
        if (!labels.isNullOrBlank()) builder.addQueryParameter("labels", labels)
        if (before != null) builder.addQueryParameter("before", before.toString())

        val request = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return json.decodeFromString(ListSerializer(FrigateEvent.serializer()), request.okBody())
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
    }
}
