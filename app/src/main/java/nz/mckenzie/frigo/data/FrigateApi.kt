package nz.mckenzie.frigo.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The handful of Frigate endpoints this app needs. Calls block, so everything
 * here runs on Dispatchers.IO from the repository.
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

    fun login(baseUrl: String, username: String, password: String): Tokens {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder()
            .url(url(baseUrl, "/api/login"))
            .post(body)
            .build()
        return json.decodeFromString(Tokens.serializer(), request.okBody())
    }

    fun refresh(baseUrl: String, refreshToken: String): Tokens {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/refresh"))
            .header("Authorization", "Bearer $refreshToken")
            .post(FormBody.Builder().build())
            .build()
        return json.decodeFromString(Tokens.serializer(), request.okBody())
    }

    /** 200 means the access token is still good. */
    fun checkToken(baseUrl: String, token: String) {
        val request = Request.Builder()
            .url(url(baseUrl, "/api/auth"))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        request.okBody()
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
            .addQueryParameter("h", height.toString())
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
            .addQueryParameter("h", height.toString())
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
        val raw = runCatching { body?.string() }.getOrNull().orEmpty()
        val detail = runCatching {
            (json.parseToJsonElement(raw) as JsonObject)["message"]?.jsonPrimitive?.content
        }.getOrNull()
        return FrigateException(code, detail ?: describe(code))
    }

    private fun describe(code: Int): String = when (code) {
        401, 403 -> "That username and password weren't accepted"
        404 -> "The server has no such endpoint. Is that the Frigate address?"
        502, 503, 504 -> "The server is not answering right now"
        else -> "The server returned $code"
    }
}
