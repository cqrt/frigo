package nz.mckenzie.frigo.tv.pair

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * What the code on the television says: where the TV is, which port its pairing
 * server is on, and the one-time answers it will accept.
 *
 * It is JSON rather than a web address, because the phone needs four things and
 * a web address would only have room for one of them without inventing a format
 * that looks like something a browser should open.
 */
@Serializable
data class PairingCode(
    val v: Int = 1,
    val host: String,
    val port: Int,
    val code: String,
    val secret: String,
) {
    fun text(): String = Json.encodeToString(serializer(), this)

    /** The number under the QR code, in two groups, which is easier to read out. */
    fun readOut(): String = code.chunked(3).joinToString(" ")
}

/**
 * The small server behind the code. It listens on the home network while the
 * pairing screen is up, and takes one thing: the address, username and password
 * the phone has just handed over.
 *
 * The code is good once and only while the screen is up. When the pairing is
 * accepted the socket is closed, so nothing is listening afterwards.
 *
 * Rejected: asking Frigate to hold the pairing. Frigate 0.18 has no way to issue
 * a key for another device, and a key stored on the server would be readable by
 * anyone with the address.
 */
class PairingServer(
    private val onPair: suspend (baseUrl: String, username: String, password: String) -> String?,
    private val onPaired: () -> Unit,
) {

    private val random = SecureRandom()
    val code: String = "%06d".format(random.nextInt(1_000_000))
    private val secret: String = ByteArray(16).also(random::nextBytes).toHex()

    private var socket: ServerSocket? = null
    @Volatile private var closed = false
    private var refusals = 0

    /** Binds the port the code will name. Fast enough to do before drawing the code. */
    fun open(host: String): PairingCode {
        val server = ServerSocket(0)
        socket = server
        return PairingCode(
            host = host,
            port = server.localPort,
            code = code,
            secret = secret,
        )
    }

    /** Blocks until the server is closed, or the pairing is accepted. */
    fun serve() {
        val server = socket ?: return
        while (!closed) {
            val client = try {
                server.accept()
            } catch (stopped: IOException) {
                break
            }
            try {
                handle(client)
            } catch (failed: Throwable) {
                // A phone that walked away mid-request is not worth a sentence.
            } finally {
                runCatching { client.close() }
            }
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream()
        val head = readHead(input) ?: return answer(client, 400, false, "That was not a request.")
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(" ").let { it.getOrNull(0) to it.getOrNull(1) }
        if (method != "POST" || path?.substringBefore('?') != "/pair") {
            return answer(client, 404, false, "Not here.")
        }

        val length = lines.drop(1)
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length <= 0 || length > 8_192) {
            return answer(client, 400, false, "That was not a pairing.")
        }
        val body = ByteArray(length)
        if (!readFully(input, body)) return

        val said = runCatching {
            RequestJson.decodeFromString(PairRequest.serializer(), body.decodeToString())
        }.getOrNull() ?: return answer(client, 400, false, "That was not a pairing.")

        if (said.v != 1 || said.code != code || said.secret != secret) {
            refusals++
            if (refusals > MAX_REFUSALS) {
                close()
            }
            return answer(client, 403, false, "That phone was not pairing with this TV.")
        }
        if (said.baseUrl.isBlank() || said.username.isBlank() || said.password.isEmpty()) {
            return answer(client, 400, false, "The phone sent no sign-in.")
        }

        // The repository signs in to Frigate before anything is kept, so a
        // password that does not work is refused here, with a sentence for the
        // phone to show, rather than being discovered on the television later.
        val refused = kotlinx.coroutines.runBlocking { onPair(said.baseUrl, said.username, said.password) }
        if (refused != null) {
            return answer(client, 401, false, refused)
        }
        answer(client, 200, true, "Paired.")
        close()
        onPaired()
    }

    private fun answer(client: Socket, status: Int, ok: Boolean, message: String) {
        val body = if (ok) {
            """{"ok":true}"""
        } else {
            """{"ok":false,"error":${quote(message)}}"""
        }.toByteArray()
        val head = buildString {
            append("HTTP/1.1 $status ${statusWord(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray()
        runCatching {
            client.getOutputStream().apply {
                write(head)
                write(body)
                flush()
            }
        }
    }

    private fun statusWord(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (head.length < 8_192) {
            val byte = input.read()
            if (byte == -1) return null
            head.append(byte.toChar())
            if (head.endsWith("\r\n\r\n")) return head.toString()
        }
        return null
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var at = 0
        while (at < into.size) {
            val read = input.read(into, at, into.size - at)
            if (read == -1) return false
            at += read
        }
        return true
    }

    private fun quote(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        /** Enough for a mistyped code or two, and too few to guess six digits. */
        const val MAX_REFUSALS = 20

        val RequestJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PairRequest(
    val v: Int = 1,
    val code: String = "",
    val secret: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
)

/**
 * The address of this television on the home network: the one a phone on the
 * same Wi-Fi can reach. A television can have several (a wired port, a wireless
 * one), and only a private address is any use to the phone.
 */
fun localIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
