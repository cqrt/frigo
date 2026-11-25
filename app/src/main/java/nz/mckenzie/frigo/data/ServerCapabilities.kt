package nz.mckenzie.frigo.data

import kotlinx.serialization.Serializable

/**
 * What a server can do, read from it once and kept on the phone.
 *
 * Frigo is written against 0.18, and a stranger may be on anything. Each part that
 * arrived after the oldest version Frigo supports is asked for once, so a tab or
 * a row is hidden rather than drawn over an empty page. A missing endpoint is the
 * answer, so the app says what it can't do instead of filling the screen with
 * "The server has no such endpoint".
 *
 * The version is [FrigateStats.service]'s own, which is what the System page shows.
 * Kept here so the phone can say what the server is running without asking again.
 */
@Serializable
data class ServerCapabilities(
    /** Frigate's own version, such as `0.18.2`, or blank when it wouldn't say. */
    val version: String = "",
    /** Review, which needs reviews and a config write. */
    val reviews: Boolean = true,
    /** Explore's filters, which need the labels endpoint. */
    val labels: Boolean = true,
    /** People to ignore, which needs the names Frigate has learned. */
    val subLabels: Boolean = true,
    /** AI descriptions, which need the model endpoint and the chat. */
    val genai: Boolean = true,
    /** The server's own pages, which save through the config endpoint. */
    val configSet: Boolean = true,
    /** When this was read, so a launch doesn't ask again. */
    val readAt: Long = 0L,
) {
    /**
     * Whether this server is older than the oldest version Frigo supports. A server
     * that wouldn't say its version is not called old on a guess.
     */
    val olderThanFrigo: Boolean
        get() = version.isNotBlank() && compareVersions(version, OldestFrigate) < 0

    /** Whether anything Frigo uses is missing, so the Settings card can say so. */
    val limited: Boolean
        get() = olderThanFrigo || !reviews || !labels || !subLabels || !genai || !configSet

    companion object {
        /**
         * The oldest Frigate Frigo supports: 0.16 is the first release with
         * `/api/review`, which the Review tab and the alerts both stand on.
         */
        const val OldestFrigate = "0.16.0"
    }
}
