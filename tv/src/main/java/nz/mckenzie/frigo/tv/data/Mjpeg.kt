package nz.mckenzie.frigo.tv.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads JPEG frames out of Frigate's MJPEG feed.
 *
 * The feed is a multipart body of back-to-back JPEGs. Rather than trusting the
 * multipart headers, this walks the bytes looking for the start marker (FF D8)
 * and end marker (FF D9), which is what the frames actually are.
 */
class MjpegReader(input: InputStream) {

    private val stream = BufferedInputStream(input, 64 * 1024)
    private val buffer = ByteArrayOutputStream(256 * 1024)
    private var previous = -1

    /** Blocks until a whole frame arrives, or returns null when the feed ends. */
    fun nextFrame(): ByteArray? {
        if (!scanToStartOfFrame()) return null

        buffer.reset()
        buffer.write(0xFF)
        buffer.write(0xD8)
        previous = 0xD8

        while (true) {
            val byte = stream.read()
            if (byte == -1) return null
            buffer.write(byte)
            if (previous == 0xFF && byte == 0xD9) break
            previous = byte
            // A frame this big means we lost sync; better to stop than to eat memory.
            if (buffer.size() > MAX_FRAME_BYTES) return null
        }
        return buffer.toByteArray()
    }

    private fun scanToStartOfFrame(): Boolean {
        while (true) {
            val byte = stream.read()
            if (byte == -1) return false
            if (previous == 0xFF && byte == 0xD8) return true
            previous = byte
        }
    }

    companion object {
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024

        fun decode(frame: ByteArray): Bitmap? =
            BitmapFactory.decodeByteArray(frame, 0, frame.size)
    }
}
