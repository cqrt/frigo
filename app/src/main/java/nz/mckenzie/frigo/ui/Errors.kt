package nz.mckenzie.frigo.ui

import nz.mckenzie.frigo.data.FrigateException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Turns an exception into something a person can act on. */
fun Throwable.friendlyMessage(): String = when (this) {
    is FrigateException -> message

    // Both of these are IOExceptions, so they have to come first. A Frigate
    // server makes its own certificate unless it has been given a real one, and
    // a phone refuses a certificate it has no reason to trust.
    is SSLPeerUnverifiedException ->
        "That address doesn't match the server's certificate. Check it, including the port."

    is SSLHandshakeException ->
        "This phone doesn't trust the server's certificate. Frigate makes its own unless " +
            "it has been given a real one. Sign in through the address your proxy serves."

    is UnknownHostException -> "Can't find that address. Check it and try again."
    is SocketTimeoutException -> "The server took too long to answer."
    is IOException -> "Can't reach the server. Check your connection."
    else -> message ?: "Something went wrong."
}
