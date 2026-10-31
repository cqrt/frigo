package nz.mckenzie.frigo.data

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToLong

/**
 * Makes a clip play straight through.
 *
 * Frigate cuts a clip out of your recordings and copies the camera's stream rather
 * than converting it, so the clip keeps the recordings' timing. Where a recording
 * stopped and started again, or a camera's clock jumped, the clip asks a player to
 * hold a frame for an hour or more, and every fragment starts its own clock at
 * zero. A player reads that as a long wait: it sits on the first frame, and then
 * gives up saying it made no progress.
 *
 * So each fragment is laid at the end of the one before it on its track, and a
 * sample that claims more than two seconds is given the length a frame normally
 * has. No picture or sound is touched, only the numbers that say when to show it.
 *
 * Nothing is written until the whole file has been read and understood, so a clip
 * this doesn't follow stays exactly as it arrived.
 */
internal object ClipRepair {

    /** A sample longer than this isn't a frame, it's a broken timestamp. */
    private const val TOO_LONG_SECONDS = 2.0

    /** Times the normal sample before a length counts as broken. */
    private const val OUTLIER = 4

    /** The largest fragment header worth reading into memory. */
    private const val MOST_OF_A_FRAGMENT = 1 shl 20

    private const val TRUN_DATA_OFFSET = 0x01
    private const val TRUN_FIRST_SAMPLE_FLAGS = 0x04
    private const val TRUN_SAMPLE_DURATION = 0x100
    private const val TRUN_SAMPLE_SIZE = 0x200
    private const val TRUN_SAMPLE_FLAGS = 0x400
    private const val TRUN_SAMPLE_OFFSET = 0x800

    private const val TFHD_BASE_DATA_OFFSET = 0x01
    private const val TFHD_SAMPLE_DESCRIPTION = 0x02
    private const val TFHD_SAMPLE_DURATION = 0x08

    /** Returns true when the clip had to be changed. */
    fun repair(clip: File): Boolean = runCatching {
        RandomAccessFile(clip, "rw").use { file -> Clip(file).run() }
    }.getOrDefault(false)

    /**
     * One clip, opened for reading and for the few writes it may need.
     *
     * A clip is a chain of boxes, each saying how long it is: `moov` describes the
     * movie and its tracks, then a `moof` and its `mdat` repeat for every fragment.
     * Inside a `moof`, each `traf` describes one track's share: `tfhd` says which
     * track and what its samples normally last, `tfdt` when it starts, and `trun`
     * lists the samples with their own lengths.
     */
    private class Clip(private val file: RandomAccessFile) {

        private val length = file.length()

        fun run(): Boolean {
            val top = boxes(0, length) ?: return false
            val moov = top.firstOrNull { it.type == "moov" } ?: return false
            val mvhd = find(moov, "mvhd") ?: return false
            val mvhdVersion = version(mvhd)
            val movieScale = readU32(mvhd.contentStart + if (mvhdVersion == 1) 20 else 12)
            if (movieScale <= 0) return false

            // Each track's own clock, and the boxes that hold the lengths it reports.
            val tracks = LinkedHashMap<Long, Track>()
            for (trak in children(moov)) {
                if (trak.type != "trak") continue
                val mdia = find(trak, "mdia") ?: continue
                val mdhd = find(mdia, "mdhd") ?: continue
                val tkhd = find(trak, "tkhd") ?: continue
                val mdhdVersion = version(mdhd)
                val scale = readU32(mdhd.contentStart + if (mdhdVersion == 1) 20 else 12)
                if (scale <= 0) continue
                val tkhdVersion = version(tkhd)
                val id = readU32(tkhd.contentStart + if (tkhdVersion == 1) 20 else 12)
                tracks[id] = Track(scale, mdhd, mdhdVersion, tkhd, tkhdVersion)
            }
            if (tracks.isEmpty()) return false

            // Every fragment, in the order it sits in the file, a moof at a time.
            // The tracks inside one moof cover the same span, which is how a
            // track that declares no sample lengths gets a believable one.
            val moofs = ArrayList<List<Fragment>>()
            val all = ArrayList<Fragment>()
            for (moof in top) {
                if (moof.type != "moof") continue
                val group = ArrayList<Fragment>()
                for (traf in children(moof)) {
                    if (traf.type != "traf") continue
                    val fragment = readTraf(traf) ?: continue
                    if (!tracks.containsKey(fragment.track)) continue
                    group += fragment
                    all += fragment
                }
                if (group.isNotEmpty()) moofs += group
            }
            if (all.isEmpty()) return false

            val typical = typicalDurations(all, tracks)

            val writes = ArrayList<Write>()
            val running = HashMap<Long, Long>()
            for (id in tracks.keys) running[id] = 0L
            var changed = false

            for (group in moofs) {
                val prepared = group.map { prepared(it, tracks.getValue(it.track), typical[it.track]) }
                val span = prepared
                    .filter { it.ticks >= 0 }
                    .maxOfOrNull { it.ticks.toDouble() / it.track.scale }
                    ?: 0.0

                for (fragment in prepared) {
                    if (fragment.ticks < 0) {
                        // A traf that leaves every length to its own default: the
                        // default is what a player believes the track lasts, and
                        // Frigate's can claim hours. Give it this moof's span.
                        val each = if (span > 0) {
                            minOf((span * fragment.track.scale).roundToLong(), fragment.longest)
                        } else {
                            minOf(fragment.fragment.defaultDuration, fragment.longest)
                                .takeIf { it > 0 }
                                ?: fragment.nominal
                        }
                        fragment.ticks = each * fragment.fragment.truns.sumOf { it.count.toLong() }
                        fragment.putDefault(each)
                    } else {
                        fragment.putDefault(fragment.nominal)
                    }

                    val now = running.getValue(fragment.fragment.track)
                    if (fragment.fragment.tfdtAt >= 0) {
                        val at = fragment.fragment.tfdtAt + 4
                        if (fragment.fragment.wideTfdt) putU64(fragment.body, at, now)
                        else putU32(fragment.body, at, now)
                    }
                    running[fragment.fragment.track] = now + fragment.ticks

                    if (!fragment.body.contentEquals(fragment.fragment.body)) {
                        writes += Write(fragment.fragment.bodyAt, fragment.body)
                        changed = true
                    }
                }
            }
            if (!changed) return false

            // The lengths the movie declares, so the scrub bar tells the truth.
            var seconds = 0.0
            for ((id, track) in tracks) {
                val end = running.getValue(id)
                seconds = maxOf(seconds, end.toDouble() / track.scale)
                val at = track.mdhd.contentStart + if (track.mdhdVersion == 1) 24 else 16
                writes += value(at, end, track.mdhdVersion == 1)
            }
            val movieLength = (seconds * movieScale).roundToLong()
            for (track in tracks.values) {
                val at = track.tkhd.contentStart + if (track.tkhdVersion == 1) 28 else 20
                writes += value(at, movieLength, track.tkhdVersion == 1)
            }
            writes += value(
                mvhd.contentStart + if (mvhdVersion == 1) 24 else 16,
                movieLength,
                mvhdVersion == 1,
            )
            val mehd = find(moov, "mvex")?.let { find(it, "mehd") }
            if (mehd != null) {
                writes += value(mehd.contentStart + 4, movieLength, version(mehd) == 1)
            }

            for (write in writes) {
                file.seek(write.at)
                file.write(write.bytes)
            }
            return true
        }

        /** One `traf`: which track, when it starts, and the lengths of its samples. */
        private fun readTraf(traf: Box): Fragment? {
            val size = traf.size.toInt()
            if (size <= traf.header || size > MOST_OF_A_FRAGMENT) return null
            val body = ByteArray(size - traf.header)
            read(traf.contentStart, body, body.size)

            val tfhd = find(traf, "tfhd") ?: return null
            val tfhdAt = (tfhd.contentStart - traf.contentStart).toInt()
            val tfhdFlags = u32(body, tfhdAt).toInt()
            val track = u32(body, tfhdAt + 4)
            var at = tfhdAt + 8
            if (tfhdFlags and TFHD_BASE_DATA_OFFSET != 0) at += 8
            if (tfhdFlags and TFHD_SAMPLE_DESCRIPTION != 0) at += 4
            val defaultAt = if (tfhdFlags and TFHD_SAMPLE_DURATION != 0) at else -1
            val default = if (defaultAt >= 0) u32(body, defaultAt) else 0L

            val truns = ArrayList<Trun>()
            for (box in children(traf)) {
                if (box.type != "trun") continue
                val start = (box.contentStart - traf.contentStart).toInt()
                val flags = u32(body, start).toInt()
                val count = u32(body, start + 4).toInt()
                var samples = start + 8
                if (flags and TRUN_DATA_OFFSET != 0) samples += 4
                if (flags and TRUN_FIRST_SAMPLE_FLAGS != 0) samples += 4
                truns += Trun(flags, count, samples)
            }
            if (truns.isEmpty()) return null

            val tfdt = find(traf, "tfdt")
            val tfdtAt = if (tfdt == null) -1 else (tfdt.contentStart - traf.contentStart).toInt()
            val wide = tfdtAt >= 0 && body[tfdtAt].toInt() == 1

            return Fragment(track, traf.contentStart, body, default, defaultAt, tfdtAt, wide, truns)
        }

        /**
         * A fragment with its impossible sample lengths replaced, and what it now
         * lasts. `ticks` is -1 when every sample leaves its length to the default,
         * in which case the whole fragment is a single stretch of time.
         */
        private fun prepared(fragment: Fragment, track: Track, typical: Long?): Prepared {
            val longest = (TOO_LONG_SECONDS * track.scale).toLong()
            val nominal = typical
                ?: fragment.defaultDuration.takeIf { it in 1..longest }
                ?: maxOf(1L, track.scale / 20)
            val ceiling = minOf(longest, nominal * OUTLIER)

            val body = fragment.body.copyOf()
            var ticks = -1L
            for (trun in fragment.truns) {
                if (trun.flags and TRUN_SAMPLE_DURATION == 0) continue
                if (ticks < 0) ticks = 0L
                var at = trun.samplesAt
                val step = stride(trun.flags)
                repeat(trun.count) {
                    val declared = u32(body, at)
                    if (declared > ceiling) {
                        putU32(body, at, nominal)
                        ticks += nominal
                    } else {
                        ticks += declared
                    }
                    at += step
                }
            }
            return Prepared(fragment, track, body, ticks, nominal, ceiling, longest)
        }

        /** The length a frame normally lasts on each track. */
        private fun typicalDurations(
            fragments: List<Fragment>,
            tracks: Map<Long, Track>,
        ): Map<Long, Long> {
            val seen = HashMap<Long, ArrayList<Long>>()
            for (fragment in fragments) {
                val longest = (TOO_LONG_SECONDS * tracks.getValue(fragment.track).scale).toLong()
                for (trun in fragment.truns) {
                    if (trun.flags and TRUN_SAMPLE_DURATION == 0) continue
                    var at = trun.samplesAt
                    val step = stride(trun.flags)
                    repeat(trun.count) {
                        val value = u32(fragment.body, at)
                        if (value in 1..longest) seen.getOrPut(fragment.track) { ArrayList() }.add(value)
                        at += step
                    }
                }
            }
            val typical = HashMap<Long, Long>()
            for ((track, values) in seen) {
                values.sort()
                typical[track] = values[values.size / 2]
            }
            return typical
        }

        /** How many bytes one sample's entry in a `trun` takes. */
        private fun stride(flags: Int): Int {
            var size = 0
            if (flags and TRUN_SAMPLE_DURATION != 0) size += 4
            if (flags and TRUN_SAMPLE_SIZE != 0) size += 4
            if (flags and TRUN_SAMPLE_FLAGS != 0) size += 4
            if (flags and TRUN_SAMPLE_OFFSET != 0) size += 4
            return if (size == 0) 4 else size
        }

        /** Every box from `from` to `to`, or null when the chain doesn't fit. */
        private fun boxes(from: Long, to: Long): List<Box>? {
            val found = ArrayList<Box>()
            var at = from
            while (at + 8 <= to) {
                val box = header(at) ?: return null
                found += box
                at = box.end
            }
            return if (at == to) found else null
        }

        private fun children(box: Box): List<Box> {
            val found = ArrayList<Box>()
            var at = box.contentStart
            while (at + 8 <= box.end) {
                val child = header(at) ?: break
                found += child
                at = child.end
            }
            return found
        }

        private fun find(box: Box, type: String): Box? = children(box).firstOrNull { it.type == type }

        private fun header(at: Long): Box? {
            if (at + 8 > length) return null
            val first = ByteArray(8)
            read(at, first, 8)
            var size = u32(first, 0)
            val type = String(first, 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                if (at + 16 > length) return null
                val wide = ByteArray(8)
                read(at + 8, wide, 8)
                size = u64(wide, 0)
                header = 16
            } else if (size == 0L) {
                size = length - at
            }
            if (size < header || at + size > length) return null
            return Box(type, at, size, header)
        }

        private fun version(box: Box): Int {
            file.seek(box.contentStart)
            return file.read()
        }

        private fun read(at: Long, into: ByteArray, count: Int) {
            file.seek(at)
            file.readFully(into, 0, count)
        }

        private fun readU32(at: Long): Long {
            val bytes = ByteArray(4)
            read(at, bytes, 4)
            return u32(bytes, 0)
        }

        private fun value(at: Long, number: Long, wide: Boolean): Write {
            val bytes = ByteArray(if (wide) 8 else 4)
            if (wide) putU64(bytes, 0, number) else putU32(bytes, 0, number)
            return Write(at, bytes)
        }
    }

    private class Box(val type: String, val start: Long, val size: Long, val header: Int) {
        val contentStart: Long get() = start + header
        val end: Long get() = start + size
    }

    private class Fragment(
        val track: Long,
        val bodyAt: Long,
        val body: ByteArray,
        val defaultDuration: Long,
        val defaultAt: Int,
        val tfdtAt: Int,
        val wideTfdt: Boolean,
        val truns: List<Trun>,
    )

    /** A fragment with its lengths made believable, ready to be laid in place. */
    private class Prepared(
        val fragment: Fragment,
        val track: Track,
        val body: ByteArray,
        var ticks: Long,
        val nominal: Long,
        val ceiling: Long,
        val longest: Long,
    ) {
        /** Replaces the length a player falls back on, when it is a broken one. */
        fun putDefault(value: Long) {
            val at = fragment.defaultAt
            if (at < 0) return
            if (fragment.defaultDuration > ceiling) putU32(body, at, value)
        }
    }

    private class Trun(val flags: Int, val count: Int, val samplesAt: Int)

    private class Track(
        val scale: Long,
        val mdhd: Box,
        val mdhdVersion: Int,
        val tkhd: Box,
        val tkhdVersion: Int,
    )

    private class Write(val at: Long, val bytes: ByteArray)

    private fun u32(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)

    private fun u64(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun putU32(bytes: ByteArray, at: Int, number: Long) {
        bytes[at] = (number ushr 24).toByte()
        bytes[at + 1] = (number ushr 16).toByte()
        bytes[at + 2] = (number ushr 8).toByte()
        bytes[at + 3] = number.toByte()
    }

    private fun putU64(bytes: ByteArray, at: Int, number: Long) {
        for (i in 0 until 8) bytes[at + i] = (number ushr (56 - i * 8)).toByte()
    }
}
