package nz.mckenzie.frigo.tv.ring

import nz.mckenzie.frigo.tv.pair.localIpv4
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * The door Home Assistant knocks on when someone rings the bell.
 *
 * A television cannot watch a chime: nothing in Frigate's API says a button was
 * pressed, and the bell itself lives in Home Assistant. So the television keeps
 * one port open for as long as the app is running, and the automation that
 * already knows about the bell posts to it.
 *
 * The address carries a key the television made for itself once and keeps, so a
 * neighbour's automation on the same Wi-Fi cannot ring the bell of a house it is
 * not in. The key is not a password: it is read off this television's own screen.
 *
 * Rejected: a port chosen at random each start, as the pairing code does. That is
 * right for a code that is good once and wrong for an address pasted into an
 * automation, which would break every time the television was turned off.
 */
class RingServer(
    private val key: String,
    private val onRing: (camera: String?) -> Unit,
) {

    private var socket: ServerSocket? = null
    @Volatile private var closed = false

    /** The port the server took, which is the first free one from [FirstPort] up. */
    var port: Int? = null
        private set

    /**
     * Binds a port and returns the address to hand out, or null when the network
     * has nothing for it to bind on. A port already in use is passed over rather
     * than reported: the television has no way to ask anyone what to do about it.
     */
    fun open(): String? {
        val host = localIpv4() ?: return null
        for (candidate in FirstPort until FirstPort + 8) {
            val bound = runCatching {
                ServerSocket(candidate).also {
                    // A television that is restarted should not wait out the old
                    // connections before it can listen again.
                    it.reuseAddress = true
                }
            }.getOrNull() ?: continue
            socket = bound
            port = bound.localPort
            return "http://$host:${bound.localPort}/ring/$key"
        }
        return null
    }

    /** Blocks until the server is closed. */
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
                // A post that walked away is not worth a sentence.
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
        val (method, target) = head.split("\r\n").first().split(" ").let {
            it.getOrNull(0) to it.getOrNull(1)
        }
        if (method != "GET" && method != "POST") {
            return answer(client, 405, false, "Not here.")
        }
        val path = target?.substringBefore('?') ?: return answer(client, 400, false, "Not here.")
        if (!path.startsWith("/ring/") || path.removePrefix("/ring/") != key) {
            return answer(client, 403, false, "That key is not this television's.")
        }
        val query = target.substringAfter('?', "")
        val camera = query.split('&')
            .firstOrNull { it.startsWith("camera=") }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        answer(client, 200, true, "Ringing.")
        onRing(camera)
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
        403 -> "Forbidden"
        405 -> "Method Not Allowed"
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

    private fun quote(text: String): String = "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""

    companion object {
        /** The first port the doorbell address can be on. */
        const val FirstPort = 8972
    }
}
