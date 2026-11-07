package nz.mckenzie.frigo.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What the television's code says: where the TV is, and the one-time answers its
 * pairing server will take. The phone reads it out of the QR code.
 */
@Serializable
data class TvPairing(
    val v: Int = 1,
    val host: String = "",
    val port: Int = 0,
    val code: String = "",
    val secret: String = "",
) {
    /**
     * A code from somewhere else is a QR code all the same, and the scanner will
     * meet one eventually. Only the whole shape is worth acting on.
     */
    fun isWhole(): Boolean =
        v == 1 &&
            host.isNotBlank() &&
            port in 1..65_535 &&
            code.length == 6 &&
            secret.isNotBlank()
}

/** What became of a handover. */
sealed interface PairOutcome {
    data object Paired : PairOutcome

    /** The television, or Frigate behind it, said this, and it is worth reading. */
    data class Said(val message: String) : PairOutcome

    /** Nothing answered: the wrong network, or a television that is off. */
    data object Silent : PairOutcome
}

/**
 * Hands a television the address and sign-in it should use.
 *
 * The television is on the same home network as this phone, so the request goes
 * straight to it rather than through Frigate. It carries the one-time code and
 * secret out of the QR code, so a phone that has not seen the television's
 * screen cannot use it.
 *
 * The television checks the sign-in with Frigate before it keeps anything, so a
 * password that is wrong comes back here as a sentence rather than being found
 * on the television tomorrow.
 */
class TvPairingClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** The pairing in a scanned code, or null for any other QR code. */
    fun read(text: String): TvPairing? = runCatching {
        json.decodeFromString(TvPairing.serializer(), text)
    }.getOrNull()?.takeIf { it.isWhole() }

    suspend fun pair(
        pairing: TvPairing,
        baseUrl: String,
        username: String,
        password: String,
    ): PairOutcome = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            Handover.serializer(),
            Handover(
                code = pairing.code,
                secret = pairing.secret,
                baseUrl = baseUrl,
                username = username,
                password = password,
            ),
        )
        val request = Request.Builder()
            .url("http://${pairing.host}:${pairing.port}/pair")
            .post(body.toRequestBody(JSON))
            .build()

        val outcome = try {
            client.newCall(request).execute()
        } catch (failed: IOException) {
            return@withContext PairOutcome.Silent
        }
        outcome.use { response ->
            if (response.isSuccessful) return@withContext PairOutcome.Paired
            val raw = response.body?.string().orEmpty()
            val said = runCatching {
                json.decodeFromString(Answer.serializer(), raw).error
            }.getOrNull()
            return@withContext if (said.isNullOrBlank()) {
                PairOutcome.Silent
            } else {
                PairOutcome.Said(said)
            }
        }
    }

    @Serializable
    private data class Handover(
        val v: Int = 1,
        val code: String,
        val secret: String,
        val baseUrl: String,
        val username: String,
        val password: String,
    )

    @Serializable
    private data class Answer(val error: String = "")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
