package nz.mckenzie.frigo.ui

import nz.mckenzie.frigo.data.FrigateException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Turns an exception into something a person can act on. */
fun Throwable.friendlyMessage(): String = when (this) {
    is FrigateException -> message
    is UnknownHostException -> "Can't find that address. Check it and try again."
    is SocketTimeoutException -> "The server took too long to answer."
    is IOException -> "Can't reach the server. Check your connection."
    else -> message ?: "Something went wrong."
}
