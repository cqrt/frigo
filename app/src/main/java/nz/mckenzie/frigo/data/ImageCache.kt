package nz.mckenzie.frigo.data

import android.graphics.Bitmap
import android.util.LruCache

/**
 * A small in-memory cache for stills and thumbnails. Without it, scrolling the
 * event list would re-fetch every thumbnail through the phone's connection.
 */
object ImageCache {

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun clear() {
        cache.evictAll()
    }
}
