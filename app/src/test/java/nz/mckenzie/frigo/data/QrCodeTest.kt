package nz.mckenzie.frigo.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading side of the pairing: the code on the television, as the phone's
 * camera sees it.
 *
 * The frames here are luminance planes with no padding, which is the shape a
 * camera hands over when its rows are the width of the picture. The padded case
 * is the same arithmetic and is covered by the data width being passed through.
 */
class QrCodeTest {

    private val payload = """{"v":1,"host":"192.168.0.139","port":45111,""" +
        """"code":"482916","secret":"46bbcc2179f1fd797b0860cb0f6c4492"}"""

    @Test
    fun `the code on the television is read back whole`() {
        val picture = qr(payload)
        assertEquals(payload, readQrCode(picture, SQUARE, SQUARE, SQUARE))
    }

    @Test
    fun `the code is read when the camera is turned`() {
        val upright = qr(payload)
        val turned = ByteArray(upright.size)
        for (y in 0 until SQUARE) {
            for (x in 0 until SQUARE) {
                turned[y * SQUARE + x] = upright[(SQUARE - 1 - x) * SQUARE + y]
            }
        }
        assertEquals(payload, readQrCode(turned, SQUARE, SQUARE, SQUARE))
    }

    @Test
    fun `a frame of nothing has no code in it`() {
        val grey = ByteArray(SQUARE * SQUARE) { 0x80.toByte() }
        assertNull(readQrCode(grey, SQUARE, SQUARE, SQUARE))
    }

    @Test
    fun `a blocked frame is left alone`() {
        val picture = qr(payload)
        assertNull(readQrCode(picture, SQUARE / 2, SQUARE / 2, SQUARE / 2))
    }

    /** A QR code as dark and light bytes, the way a camera's Y plane holds it. */
    private fun qr(text: String): ByteArray {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            SQUARE,
            SQUARE,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return ByteArray(SQUARE * SQUARE) { at ->
            val x = at % SQUARE
            val y = at / SQUARE
            if (matrix[x, y]) 0.toByte() else 255.toByte()
        }
    }

    private companion object {
        const val SQUARE = 480
    }
}
