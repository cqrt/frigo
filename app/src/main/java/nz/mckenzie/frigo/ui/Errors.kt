package nz.mckenzie.frigo.ui

import android.content.Context
import nz.mckenzie.frigo.R
import nz.mckenzie.frigo.data.NotAnAdminException
import nz.mckenzie.frigo.data.FrigateException
import nz.mckenzie.frigo.data.words
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Turns an exception into something a person can act on.
 *
 * The words come from the app's own strings, in the app's own language, so a
 * failure is in the same language as the screen it appears on. What the server
 * itself said is passed through as it arrived: Frigate writes in English, and the
 * phone has no way to know what it meant.
 */
fun Throwable.friendlyMessage(context: Context): String = when (this) {
    is FrigateException -> message ?: context.words(R.string.err_something_wrong)

    is NotAnAdminException -> context.words(R.string.err_not_admin)

    // Both of these are IOExceptions, so they have to come first. A Frigate
    // server makes its own certificate unless it has been given a real one, and
    // a phone refuses a certificate it has no reason to trust.
    is SSLPeerUnverifiedException -> context.words(R.string.err_certificate_name)

    is SSLHandshakeException -> context.words(R.string.err_certificate_untrusted)

    is UnknownHostException -> context.words(R.string.err_no_address)
    is SocketTimeoutException -> context.words(R.string.err_timeout)
    is IOException -> context.words(R.string.err_unreachable)
    else -> message ?: context.words(R.string.err_something_wrong)
}
