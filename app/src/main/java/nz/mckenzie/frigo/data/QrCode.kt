package nz.mckenzie.frigo.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Reads a QR code out of one camera frame.
 *
 * A camera hands over a plane of luminance, which is its Y channel. [dataWidth]
 * is how many bytes one row takes, which can be wider than the picture because
 * cameras pad the ends of their rows. [width] and [height] are the picture.
 *
 * The phone holds the camera the right way up, but the frame arrives in the
 * sensor's own turn. A QR code is found whichever way it is turned, so nothing
 * here rotates it first.
 */
internal fun readQrCode(
    data: ByteArray,
    dataWidth: Int,
    width: Int,
    height: Int,
): String? {
    if (width <= 0 || height <= 0 || dataWidth < width) return null
    val source = PlanarYUVLuminanceSource(
        data,
        dataWidth,
        height,
        0,
        0,
        width,
        height,
        false,
    )
    return runCatching {
        MultiFormatReader()
            .apply { setHints(HINTS) }
            .decode(BinaryBitmap(HybridBinarizer(source)))
            .text
    }.getOrNull()
}

private val HINTS = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
)
