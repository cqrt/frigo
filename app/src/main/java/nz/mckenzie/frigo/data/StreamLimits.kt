package nz.mckenzie.frigo.data

/**
 * The biggest picture and the fastest rate a camera can actually give.
 *
 * Frigate makes whatever stream it is asked for. Ask a 640x360 camera for 2160p at 30
 * frames a second and it scales every frame to 3840 wide and sends it thirty times a
 * second, which is pixels the camera never saw and frames it never sent. The server
 * spends its time inventing them, the phone spends its time decoding them, and every
 * other camera on the box gets slower while it does.
 */
data class StreamLimits(
    /** The height of the picture the camera itself sends, in pixels. */
    val height: Int,
    /** The frames a second the camera itself sends. */
    val rate: Int,
) {
    companion object {
        /**
         * The tallest picture a phone asks for. A phone screen is about 1080 tall in
         * its longest direction, and a 4K still is four times the data for a picture
         * that is shown at the same size.
         */
        const val MostHeight = 1080

        /** The most frames a second worth asking for. */
        const val MostRate = 30

        /** Below this a stream is a slideshow rather than a picture. */
        const val LeastRate = 2

        /** What is assumed when the server cannot say, which is everything it has. */
        val Unknown = StreamLimits(MostHeight, MostRate)
    }
}

/** The sizes offered in the player, biggest first. */
internal val OfferedHeights = listOf(2160, 1080, 720, 480)

/** The rates offered in the player, fastest first. */
internal val OfferedRates = listOf(30, 15, 10, 5, 2)

/**
 * A height brought inside what the camera has. A size that fits is kept as it is; one
 * that does not becomes the biggest offered size that fits, or the camera's own size
 * when that is smaller than anything offered.
 */
fun heightWithin(wanted: Int, limits: StreamLimits): Int {
    if (wanted <= limits.height) return wanted
    return OfferedHeights.firstOrNull { it <= limits.height } ?: limits.height
}

/** A rate brought inside what the camera sends. */
fun rateWithin(wanted: Int, limits: StreamLimits): Int {
    val top = limits.rate.coerceAtLeast(StreamLimits.LeastRate)
    return wanted.coerceAtMost(top)
}

/** The sizes the menu offers this camera: everything it has, and nothing it hasn't. */
fun heightsFor(limits: StreamLimits): List<Int> =
    (OfferedHeights.filter { it <= limits.height } + limits.height)
        .distinct()
        .sortedDescending()

/** The rates the menu offers this camera. */
fun ratesFor(limits: StreamLimits): List<Int> =
    (OfferedRates.filter { it <= limits.rate } + limits.rate)
        .distinct()
        .sortedDescending()

/**
 * What a camera opens at the first time it is watched: 720p at five frames a second,
 * or less when the camera has less to give.
 */
fun openingChoice(limits: StreamLimits): Pair<Int, Int> =
    heightWithin(720, limits) to rateWithin(5, limits)

/** A saved choice, brought inside what the camera has now. */
fun fittingChoice(saved: Pair<Int, Int>?, limits: StreamLimits): Pair<Int, Int> =
    if (saved == null) {
        openingChoice(limits)
    } else {
        heightWithin(saved.first, limits) to rateWithin(saved.second, limits)
    }
