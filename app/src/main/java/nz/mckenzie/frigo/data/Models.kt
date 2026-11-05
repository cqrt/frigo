package nz.mckenzie.frigo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.roundToInt

/**
 * Only the pieces of /api/config this app reads. Everything else in the file is
 * ignored, so a Frigate release that adds config keys does not break parsing.
 */
@Serializable
data class FrigateConfig(
    val review: ReviewBlockConfig = ReviewBlockConfig(),
    val objects: TrackedObjects = TrackedObjects(),
    val cameras: Map<String, CameraConfig> = emptyMap(),
    /**
     * The server's own switch for telling anyone about an alert. Frigate starts it
     * off, and a camera whose switch is on still says nothing while this is off.
     */
    val notifications: SwitchConfig = SwitchConfig(enabled = false),
    /**
     * The server's own setting for the two features a camera can't run without.
     * Its starting point is on, and only a server that says otherwise is read.
     */
    @SerialName("face_recognition") val faceRecognition: SwitchConfig = SwitchConfig(),
    val lpr: SwitchConfig = SwitchConfig(),
    /**
     * The model services the server is set up with, by the name it files each one
     * under. Kept as it came rather than as a fixed shape: a service is a bundle
     * of fields this app only picks at, and a server set up before this app knew
     * about it must not fail to parse.
     */
    val genai: JsonObject = JsonObject(emptyMap()),
)

/** Whether a feature is on. Frigate keeps a lot more beside it that this app doesn't read. */
@Serializable
data class SwitchConfig(val enabled: Boolean = true)

/**
 * What the signed-in user is allowed to see. `/api/config` answers with every
 * camera on the server whatever the user's role, so this is the only place that
 * says which ones are theirs. `allowed_cameras` is absent or null for a user who
 * may see them all.
 */
@Serializable
data class FrigateProfile(
    val username: String = "",
    val role: String = "",
    @SerialName("allowed_cameras") val allowedCameras: List<String>? = null,
)

/**
 * A server's own settings as this phone last read them.
 *
 * The settings pages are built out of this, so they still open when the server
 * doesn't answer. [readAt] is when the copy was taken, and it is what the pages
 * say out loud so you know you are looking at the last answer rather than now.
 */
@Serializable
data class ServerSettings(
    val config: FrigateConfig = FrigateConfig(),
    val profile: FrigateProfile = FrigateProfile(),
    /** Epoch milliseconds, or 0 for a copy written before the phone kept one. */
    val readAt: Long = 0L,
)

@Serializable
data class CameraConfig(
    val enabled: Boolean = true,
    @SerialName("friendly_name") val friendlyName: String? = null,
    val review: ReviewBlockConfig = ReviewBlockConfig(),
    val objects: TrackedObjects = TrackedObjects(),
    /**
     * Whether this camera is one worth telling anyone about. Off until it is turned
     * on, which is what a camera nobody has touched says.
     */
    val notifications: SwitchConfig = SwitchConfig(enabled = false),
    val detect: SwitchConfig = SwitchConfig(),
    val motion: SwitchConfig = SwitchConfig(),
    val record: SwitchConfig = SwitchConfig(),
    val snapshots: SwitchConfig = SwitchConfig(),
    val audio: SwitchConfig = SwitchConfig(),
    val birdseye: SwitchConfig = SwitchConfig(),
    @SerialName("face_recognition") val faceRecognition: SwitchConfig = SwitchConfig(),
    val lpr: SwitchConfig = SwitchConfig(),
)

/** The objects a camera is set to follow. */
@Serializable
data class TrackedObjects(val track: List<String> = emptyList())

/** Alerts and detections, as Frigate writes them in its settings file. */
@Serializable
data class ReviewBlockConfig(
    val alerts: ReviewFlagConfig = ReviewFlagConfig(),
    val detections: ReviewFlagConfig = ReviewFlagConfig(),
    /** Whether the server writes a description of what it saw. Off until someone turns it on. */
    val genai: SwitchConfig = SwitchConfig(enabled = false),
)

@Serializable
data class ReviewFlagConfig(
    val enabled: Boolean = true,
    /**
     * Absent on a camera means it follows the camera above it. Absent on
     * detections means every other object, and absent on alerts means the list
     * Frigate starts with.
     */
    val labels: List<String>? = null,
)

@Serializable
data class FrigateEvent(
    val id: String,
    val camera: String = "",
    val label: String = "",
    @SerialName("sub_label") val subLabel: String? = null,
    @SerialName("start_time") val startTime: Double = 0.0,
    @SerialName("end_time") val endTime: Double? = null,
    @SerialName("top_score") val topScore: Double? = null,
    @SerialName("has_clip") val hasClip: Boolean = false,
    @SerialName("has_snapshot") val hasSnapshot: Boolean = false,
    val zones: List<String> = emptyList(),
    @SerialName("false_positive") val falsePositive: Boolean? = null,
) {
    /** Frigate reports times as epoch seconds, with fractional seconds. */
    val startedAtMillis: Long get() = (startTime * 1000).toLong()
}

/**
 * A stretch of time Frigate thought was worth a look, with what it saw in it.
 * Frigate groups its detections into these, so you read the moments rather than
 * every single one.
 *
 * `severity` is `alert` for something it judged worth telling you about,
 * `detection` for the rest, and `significant_motion` for motion alone.
 */
@Serializable
data class FrigateReview(
    val id: String,
    val camera: String = "",
    @SerialName("start_time") val startTime: Double = 0.0,
    @SerialName("end_time") val endTime: Double? = null,
    val severity: String = "",
    @SerialName("has_been_reviewed") val hasBeenReviewed: Boolean = false,
    val data: ReviewData = ReviewData(),
) {
    /** Frigate reports times as epoch seconds, with fractional seconds. */
    val startedAtMillis: Long get() = (startTime * 1000).toLong()

    /** The event ids this review covers, which is where its picture comes from. */
    val detections: List<String> get() = data.detections
}

@Serializable
data class ReviewData(
    val objects: List<String> = emptyList(),
    @SerialName("sub_labels") val subLabels: List<String> = emptyList(),
    val zones: List<String> = emptyList(),
    val detections: List<String> = emptyList(),
    val metadata: ReviewMetadata? = null,
)

/**
 * What the server made of a review once it had looked at the clip, which it
 * writes a few seconds after the review starts.
 *
 * The title is a short name for what happened and the summary is a sentence
 * about it. [threatLevel] is 0 for ordinary, 1 for suspicious and 2 for a
 * critical threat.
 */
@Serializable
data class ReviewMetadata(
    val title: String = "",
    val shortSummary: String = "",
    @SerialName("potential_threat_level") val threatLevel: Int = 0,
)

/**
 * One day of reviews, as `/api/review/summary` counts them: how many there were
 * and how many have been watched.
 *
 * The answer holds a day per key, plus one entry for the last twenty-four hours
 * which isn't a day at all. [day] is filled in from the key, and that entry is
 * left out by the caller.
 */
@Serializable
data class ReviewDayCount(
    val day: String = "",
    @SerialName("total_alert") val totalAlerts: Int = 0,
    @SerialName("reviewed_alert") val reviewedAlerts: Int = 0,
    @SerialName("total_detection") val totalDetections: Int = 0,
    @SerialName("reviewed_detection") val reviewedDetections: Int = 0,
)

/**
 * A clip Frigate has cut out of a camera's recordings and kept in its own folder,
 * where its clean-up does not reach.
 *
 * [date] is when the stretch itself starts, in epoch seconds, not when the clip was
 * kept: a clip cut from last night's recordings carries last night, however late it
 * was asked for. `video_path` and `thumb_path` are paths inside the server's media
 * folder, and each is served at the same path with that folder left off.
 */
@Serializable
data class Export(
    val id: String,
    val camera: String = "",
    val name: String = "",
    val date: Double = 0.0,
    @SerialName("video_path") val videoPath: String = "",
    @SerialName("thumb_path") val thumbPath: String = "",
    @SerialName("in_progress") val inProgress: Boolean = false,
) {
    /** Where the file is served from. */
    val videoUrlPath: String get() = servedPath(videoPath, "exports")

    /** Where its picture is served from. */
    val thumbUrlPath: String get() = servedPath(thumbPath, "clips/export")
}

/**
 * A media path as Frigate serves it.
 *
 * The files sit under the media folder inside the server, and the addresses that
 * serve them leave that folder off: `/media/frigate/exports/a.mp4` is served at
 * `/exports/a.mp4`. Where a path doesn't name that folder, the file is looked for
 * in the folder it would normally be in.
 */
internal fun servedPath(path: String, folder: String): String {
    val inside = path.substringAfter("/media/frigate", "")
    if (inside.startsWith("/") && inside.length > 1) return inside
    return "/$folder/${path.substringAfterLast('/')}"
}

/** What Frigate answers when it is asked to keep a stretch. */
@Serializable
data class StartedExport(
    val success: Boolean = false,
    val message: String = "",
    @SerialName("export_id") val exportId: String? = null,
)

/** A camera as the app shows it. */
data class Camera(
    val name: String,
    val title: String,
)

/**
 * The part of `/api/stats` this app reads. Frigate counts the frames each camera
 * has produced over the last few seconds, so a camera with nothing arriving counts
 * zero, which is how it says a camera has stopped.
 *
 * The System page reads the rest of it: the whole box's processor and memory, any
 * GPU, each camera's health, the disks, and the processes behind them.
 */
@Serializable
data class FrigateStats(
    val cameras: Map<String, CameraStats> = emptyMap(),
    val service: ServiceStats = ServiceStats(),
    val detectors: Map<String, DetectorStats> = emptyMap(),
    /** One entry per process on the box, keyed by pid. The whole system is in here too. */
    @SerialName("cpu_usages") val cpuUsages: Map<String, CpuUsage> = emptyMap(),
    @SerialName("gpu_usages") val gpuUsages: Map<String, GpuStats> = emptyMap(),
    /** Frigate's own workers, by name. */
    val processes: Map<String, ProcessStats> = emptyMap(),
)

@Serializable
data class CameraStats(
    @SerialName("camera_fps") val cameraFps: Double = 0.0,
    /**
     * The rate the camera is set to send at, which does not dip the way a single
     * second's count does. Absent from a server that doesn't say.
     */
    @SerialName("expected_fps") val expectedFps: Double? = null,
    @SerialName("process_fps") val processFps: Double = 0.0,
    /** Frames Frigate had to throw away, which is the surest sign the box is behind. */
    @SerialName("skipped_fps") val skippedFps: Double = 0.0,
    @SerialName("detection_fps") val detectionFps: Double = 0.0,
    /** Frigate's own word for the stream's health: excellent, good, fair or poor. */
    @SerialName("connection_quality") val connectionQuality: String? = null,
    @SerialName("detection_enabled") val detectionEnabled: Boolean = false,
) {
    /** The rate this camera sends at, as a whole number, preferring the one set for it. */
    val settingRate: Int
        get() = (expectedFps?.takeIf { it > 0 } ?: cameraFps).roundToInt()

    /** Whether this camera is being left behind: frames dropped, or a poor stream. */
    val struggling: Boolean
        get() = skippedFps > 0.0 || connectionQuality in PoorQualities
}

private val PoorQualities = setOf("fair", "poor", "bad")

/** How long the server has been up, in seconds. Absent from a server that doesn't say. */
@Serializable
data class ServiceStats(
    val uptime: Double? = null,
    val version: String? = null,
    @SerialName("latest_version") val latestVersion: String? = null,
    /** The disks Frigate keeps things on, in megabytes, by path. */
    val storage: Map<String, StorageStats> = emptyMap(),
)

@Serializable
data class StorageStats(
    val total: Double = 0.0,
    val used: Double = 0.0,
    val free: Double = 0.0,
    @SerialName("mount_type") val mountType: String? = null,
    /** On the shared memory mount: how many frames fit, against the least Frigate wants. */
    @SerialName("shm_frame_count") val shmFrameCount: Double? = null,
    @SerialName("min_shm") val minShm: Double? = null,
)

@Serializable
data class DetectorStats(
    /** Milliseconds for one inference. */
    @SerialName("inference_speed") val inferenceSpeed: Double = 0.0,
    val cpu: String? = null,
    val mem: String? = null,
)

@Serializable
data class CpuUsage(
    val cpu: String? = null,
    val mem: String? = null,
)

@Serializable
data class GpuStats(
    val vendor: String? = null,
    val gpu: String? = null,
    val mem: String? = null,
    val compute: String? = null,
    @SerialName("dec") val decode: String? = null,
)

@Serializable
data class ProcessStats(
    val cpu: String? = null,
    val mem: String? = null,
)

/**
 * The System page, worked out from one reading of the server's stats.
 *
 * Everything is already in words: a bar knows what fraction of itself is filled,
 * a line knows what it says. That leaves the screen to draw, and leaves the
 * numbers to be tried without a phone.
 */
data class SystemPage(
    val version: String,
    /** The newer release, when the server says there is one. */
    val newer: String?,
    val uptime: String,
    val processor: SystemMeter?,
    val memory: SystemMeter?,
    val gpu: List<SystemLine>,
    val cameras: List<SystemLine>,
    val storage: List<SystemMeter>,
    val processes: List<SystemLine>,
    val detectors: List<SystemLine>,
    val restartable: Boolean,
    /** When this came from the phone's own copy rather than the server, or null. */
    val readAt: Long? = null,
)

/** A line with a bar under it: how full something is. */
data class SystemMeter(
    val title: String,
    val said: String,
    val fraction: Float,
    val detail: String? = null,
    val warn: Boolean = false,
)

/** A line without a bar: a name, what it is doing, and whether it wants attention. */
data class SystemLine(
    val title: String,
    val said: String,
    val value: String? = null,
    val warn: Boolean = false,
)

/** The whole page, read off one `/api/stats` answer. */
fun FrigateStats.systemPage(admin: Boolean, readAt: Long? = null): SystemPage {
    val whole = cpuUsages["frigate.full_system"]

    return SystemPage(
        version = service.version?.takeIf { it.isNotBlank() } ?: "Not said",
        newer = service.latestVersion
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { latest -> latest != service.version?.trimEnd('-') },
        uptime = service.uptime?.let(::uptimeText) ?: "Not said",
        processor = whole?.cpu?.toDoubleOrNull()?.let { percent ->
            SystemMeter("Processor", percentText(percent), (percent / 100.0).toFloat())
        },
        memory = whole?.mem?.toDoubleOrNull()?.let { percent ->
            SystemMeter("Memory", percentText(percent), (percent / 100.0).toFloat())
        },
        gpu = gpuUsages.map { (name, stats) ->
            SystemLine(
                title = name,
                said = listOfNotNull(
                    stats.compute?.let { "$it computing" },
                    stats.decode?.let { "$it decoding" },
                ).joinToString(" · ").ifBlank { stats.vendor.orEmpty() },
                value = stats.gpu,
            )
        },
        cameras = cameras.entries
            .sortedBy { prettyCameraName(it.key) }
            .map { (name, stats) ->
                SystemLine(
                    title = prettyCameraName(name),
                    said = listOfNotNull(
                        detectedText(stats.detectionFps),
                        stats.connectionQuality?.replaceFirstChar { it.uppercase() },
                    ).joinToString(" · "),
                    value = rateText(stats.cameraFps),
                    warn = stats.struggling,
                )
            },
        storage = storageMeters(service.storage),
        detectors = detectors.map { (name, stats) ->
            SystemLine(
                title = "Detector $name",
                said = listOfNotNull(
                    stats.inferenceSpeed.takeIf { it > 0 }?.let { "an inference" },
                    stats.mem?.takeIf { it != "-%"}?.let { "$it of memory" },
                ).joinToString(" · "),
                value = stats.inferenceSpeed.takeIf { it > 0 }?.let { oneDecimal(it) + " ms" },
            )
        },
        processes = processes.entries
            .sortedByDescending { it.value.cpu?.toDoubleOrNull() ?: 0.0 }
            .map { (name, stats) ->
                SystemLine(
                    title = prettyProcessName(name),
                    said = stats.mem?.takeIf { it != "-%"}?.let { "$it of memory" }.orEmpty(),
                    value = stats.cpu?.let { "$it processor" },
                )
            },
        restartable = admin,
        readAt = readAt,
    )
}

/**
 * The disks, said in words. Two paths on the same disk are one line, because
 * recordings and clips live together and reading them twice says nothing twice.
 */
private fun storageMeters(storage: Map<String, StorageStats>): List<SystemMeter> =
    storage.entries
        .sortedBy { (path, _) ->
            val place = MountOrder.indexOfFirst { path.contains(it) }
            if (place < 0) MountOrder.size else place
        }
        .distinctBy { (_, stats) -> "${stats.total}/${stats.used}/${stats.free}" }
        .map { (path, stats) ->
            val shmShort = stats.shmFrameCount != null && stats.minShm != null &&
                stats.shmFrameCount < stats.minShm
            SystemMeter(
                title = mountName(path),
                said = "${sizeText(stats.used)} of ${sizeText(stats.total)}",
                fraction = if (stats.total > 0) (stats.used / stats.total).toFloat() else 0f,
                detail = listOfNotNull(
                    "${sizeText(stats.free)} free",
                    stats.shmFrameCount?.let { frames ->
                        val wanted = stats.minShm
                        if (wanted != null) {
                            "room for ${frames.roundToInt()} frames, ${wanted.roundToInt()} wanted"
                        } else {
                            "room for ${frames.roundToInt()} frames"
                        }
                    },
                ).joinToString(" · "),
                warn = (stats.total > 0 && stats.free / stats.total < 0.1) || shmShort,
            )
        }

private val MountOrder = listOf("recordings", "clips", "cache", "shm")

private fun mountName(path: String): String = when {
    path.contains("recordings") -> "Recordings"
    path.contains("clips") -> "Clips"
    path.contains("cache") -> "Cache"
    path.contains("shm") -> "Shared memory"
    else -> path
}

/** `review_segment` becomes "Review segment"; `go2rtc` keeps its name. */
private fun prettyProcessName(name: String): String = name
    .replace('_', ' ')
    .replaceFirstChar { it.uppercase() }

/** "16 hours 53 minutes", or "3 days 4 hours" once it is that long. */
fun uptimeText(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val days = total / 86_400
    val hours = (total % 86_400) / 3_600
    val minutes = (total % 3_600) / 60
    return when {
        days > 0 -> "$days ${plural(days, "day")} $hours ${plural(hours, "hour")}"
        hours > 0 -> "$hours ${plural(hours, "hour")} $minutes ${plural(minutes, "minute")}"
        minutes > 0 -> "$minutes ${plural(minutes, "minute")}"
        else -> "less than a minute"
    }
}

private fun plural(count: Long, word: String): String = if (count == 1L) word else "${word}s"

/** Frigate counts storage in megabytes: 58669.1 becomes "58.7 GB". */
fun sizeText(megabytes: Double): String = when {
    megabytes >= 1_000 -> oneDecimal(megabytes / 1_000) + " GB"
    else -> "${megabytes.roundToInt()} MB"
}

private fun percentText(percent: Double): String = oneDecimal(percent) + "%"

/** "10.1 frames a second", "1 frame a second", or "nothing" when it has stopped. */
fun rateText(fps: Double): String = when {
    fps <= 0.0 -> "nothing"
    fps == 1.0 -> "1 frame a second"
    else -> "${oneDecimal(fps)} frames a second"
}

/** "2.0 being detected", or "nothing being detected". */
private fun detectedText(fps: Double): String =
    if (fps <= 0.0) "nothing being detected" else "${oneDecimal(fps)} being detected"

/** One decimal place, with a dot whatever the phone's language is set to. */
private fun oneDecimal(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

/**
 * A day a camera kept recordings for, and the hours in it that have some. Frigate
 * reports the hours in the timezone it was asked about, so the day is a reading of
 * the server's clock rather than a moment.
 */
@Serializable
data class RecordingDay(
    val day: String = "",
    val events: Int = 0,
    val hours: List<RecordingHour> = emptyList(),
) {
    /** The hours with something in them, as numbers: 0 for midnight, 23 for eleven. */
    val hoursRecorded: Set<Int>
        get() = hours.mapNotNull { it.hour.toIntOrNull() }.toSet()
}

@Serializable
data class RecordingHour(
    val hour: String = "",
    val duration: Double = 0.0,
)

/** A stretch of recording on a camera, in epoch seconds. */
@Serializable
data class RecordingSpan(
    @SerialName("start_time") val startTime: Double = 0.0,
    @SerialName("end_time") val endTime: Double = 0.0,
)

/**
 * The stretches with their neighbours joined wherever less than [reach] apart.
 *
 * Frigate writes ten-second segments, and each one ends a fraction of a second
 * either side of the next one's start, so a camera that never stopped recording
 * comes back as a thousand pieces with slivers between them. Drawn one piece at a
 * time, every join leaves a hairline on the bar, hundreds of them across an hour.
 * A hole of a second or two is one nothing can be watched in, so it is filled in
 * and the bar shows the run that was there.
 */
fun List<RecordingSpan>.joined(reach: Double = 2.0): List<RecordingSpan> {
    if (size < 2) return this
    val ordered = sortedBy { it.startTime }
    val out = ArrayList<RecordingSpan>(ordered.size)
    var start = ordered.first().startTime
    var end = ordered.first().endTime
    for (span in ordered.subList(1, ordered.size)) {
        if (span.startTime - end <= reach) {
            if (span.endTime > end) end = span.endTime
        } else {
            out += RecordingSpan(start, end)
            start = span.startTime
            end = span.endTime
        }
    }
    out += RecordingSpan(start, end)
    return out
}

/** A failure the UI can show as a sentence. */
class FrigateException(val status: Int, override val message: String) : Exception(message) {
    val isAuthFailure: Boolean get() = status == 401 || status == 403
}

/**
 * True when the server couldn't be asked at all, rather than turning the answer
 * down: no network, no such host, or a proxy that says the box behind it is out.
 *
 * A certificate the phone won't accept is deliberately not one of these. It is a
 * wrong address rather than a server that is away, and the sign-in page says so
 * in its own words.
 */
fun Throwable.isUnreachable(): Boolean = when (this) {
    is FrigateException -> status == 502 || status == 503 || status == 504
    is javax.net.ssl.SSLException -> false
    is java.io.IOException -> true
    else -> false
}

/**
 * The server turned the change down because this account isn't an admin.
 *
 * Kept apart from [FrigateException] on purpose: a 403 there is read as an aged-out
 * token and the call is tried again with a fresh one, which does nothing for an
 * account that simply isn't allowed.
 */
class NotAnAdminException : Exception("Only an admin can change these.")

/** What Frigate flags on a camera, or on all of them. */
data class ReviewFlags(
    val alertsOn: Boolean = true,
    /** Frigate's own starting choice, used when the server names none. */
    val alertLabels: List<String> = listOf("person", "car"),
    val detectionsOn: Boolean = true,
    /** Null means every other object makes a detection. */
    val detectionLabels: List<String>? = null,
)

/** The two kinds of review worth showing. Motion on its own is not one of them. */
enum class ReviewKind(val apiValue: String, val label: String) {
    Alerts("alert", "Alerts"),
    Detections("detection", "Detections"),
}

/** Everything the Review settings page needs. */
data class ReviewSettings(
    val all: ReviewFlags,
    val cameras: Map<String, ReviewFlags>,
    /** Every label Frigate can flag, the tracked ones first. */
    val objects: List<String>,
    val tracked: Set<String>,
)

/** The settings page in one answer: what is set, which cameras to offer, and whether you may change it. */
data class ReviewPage(
    val settings: ReviewSettings,
    val cameras: List<Camera>,
    val admin: Boolean,
    /**
     * When this came from the phone's own copy rather than the server, that copy's
     * age in epoch milliseconds. Null means it is the server's answer, now.
     */
    val readAt: Long? = null,
)

/**
 * The labels one tab can be narrowed to, for all cameras or for one.
 *
 * A camera that names no labels of its own follows the shared list, and a
 * detection list that names none means every other object, so what is left of
 * Frigate's own names is what can turn up.
 */
fun ReviewSettings.labelsFor(kind: ReviewKind, camera: String?): List<String> {
    val flags = camera?.let { cameras[it] } ?: all
    val labels = when (kind) {
        ReviewKind.Alerts -> flags.alertLabels
        ReviewKind.Detections ->
            flags.detectionLabels?.takeIf { it.isNotEmpty() }
                ?: objects.filterNot { it in flags.alertLabels }
    }
    return labels.filter { it.isNotBlank() }.distinct()
}

/**
 * What the server says is worth telling you about, as one shape the service can
 * ask a single question of.
 *
 * Every part of it is set on the server: the notifications switch for a camera,
 * whether that camera's alerts are on, and the objects that make an alert on it.
 * The phone holds a copy rather than an opinion, so a phone that restarts with no
 * network still knows what to watch.
 *
 * The switch at the top of the server's notifications page is not one of these.
 * It turns the cameras on in a batch, and you can leave it off and turn them on
 * one at a time, so each camera's own answer is the one that counts.
 */
@Serializable
data class AlertRules(
    val cameras: Map<String, CameraAlerts> = emptyMap(),
) {
    /** True when the server says this camera seeing this object is worth a buzz. */
    fun buzzes(camera: String, label: String): Boolean {
        val found = cameras[camera] ?: return false
        return found.notify && found.alertsOn && label in found.labels
    }
}

/** One camera's three answers: tell me about it, its alerts are on, and what makes one. */
@Serializable
data class CameraAlerts(
    val notify: Boolean = false,
    val alertsOn: Boolean = true,
    val labels: List<String> = emptyList(),
)

/** The rules, read from the same settings file the alerts page reads. */
fun FrigateConfig.alertRules(): AlertRules {
    val settings = reviewSettings()
    return AlertRules(
        cameras = cameras.mapValues { (name, camera) ->
            CameraAlerts(
                notify = camera.notifications.enabled,
                alertsOn = camera.review.alerts.enabled,
                labels = settings.labelsFor(ReviewKind.Alerts, name),
            )
        },
    )
}

/**
 * The labels Frigate flags by itself: the objects it follows and the sounds it
 * listens for. Its own settings page offers the same names.
 */
val FrigateLabels = listOf(
    "bark", "car", "cat", "fire_alarm", "glass", "scream",
    "person", "smash", "smoke_detector", "speech", "yell",
).sorted()

/**
 * Alerts and detections, read from Frigate's settings file.
 *
 * The list to choose from is [FrigateLabels], plus anything already tracked or
 * already chosen. A label in use therefore never falls out of the picker, even
 * when Frigate doesn't offer it. The label map the detector runs is deliberately
 * not used: it holds the ninety names the model was trained on, which is not the
 * set Frigate acts on, and most of them no camera here can see.
 */
fun FrigateConfig.reviewSettings(): ReviewSettings {
    val tracked = (objects.track + cameras.values.flatMap { it.objects.track })
        .filter { it.isNotBlank() }
        .toSet()

    val objects = LinkedHashSet<String>()
    objects += FrigateLabels
    objects += tracked
    objects += review.alerts.labels.orEmpty()
    objects += review.detections.labels.orEmpty()
    cameras.values.forEach { camera ->
        objects += camera.review.alerts.labels.orEmpty()
        objects += camera.review.detections.labels.orEmpty()
    }

    return ReviewSettings(
        all = review.flags(),
        cameras = cameras.mapValues { it.value.review.flags(review) },
        objects = objects.filter { it.isNotBlank() }.distinct()
            .sortedWith(compareBy({ it !in tracked }, { it })),
        tracked = tracked,
    )
}

/**
 * A camera's flags, with what it doesn't name taken from the server-wide settings.
 *
 * A camera's alerts list is absent until that camera is given one of its own, and
 * Frigate reads absent as "whatever the server-wide list says". Filling in the
 * server's own starting list instead would quietly disagree with the server about
 * a camera nobody has touched.
 */
private fun ReviewBlockConfig.flags(shared: ReviewBlockConfig = this) = ReviewFlags(
    alertsOn = alerts.enabled,
    alertLabels = alerts.labels ?: shared.alerts.labels ?: listOf("person", "car"),
    detectionsOn = detections.enabled,
    detectionLabels = detections.labels,
)

/** A settings change ready to send: the part of Frigate to refresh, and the change itself. */
data class ConfigChange(val updateTopic: String, val configData: JsonObject)

/**
 * The request for whatever changed between [before] and [after], or null when
 * nothing did.
 *
 * Only the fields that changed are sent. A camera that follows the list for all
 * cameras would stop following it the moment this wrote that same list back, so
 * flipping a switch must not carry the list along with it.
 *
 * A detection list of null is sent as null, which Frigate reads as a key to drop.
 * That is how every other object is put back.
 *
 * The update topic names the part of Frigate that should hear about it. The review
 * worker listens on a camera's review settings, and `*` there means every camera,
 * which is how a change lands without a restart.
 */
fun reviewChange(camera: String?, before: ReviewFlags, after: ReviewFlags): ConfigChange? {
    val alerts = buildJsonObject {
        if (before.alertsOn != after.alertsOn) put("enabled", after.alertsOn)
        if (before.alertLabels.sorted() != after.alertLabels.sorted()) {
            putJsonArray("labels") { after.alertLabels.forEach { add(JsonPrimitive(it)) } }
        }
    }

    val detections = buildJsonObject {
        if (before.detectionsOn != after.detectionsOn) put("enabled", after.detectionsOn)
        if (before.detectionLabels?.sorted() != after.detectionLabels?.sorted()) {
            when (val labels = after.detectionLabels) {
                null -> put("labels", JsonNull)
                else -> putJsonArray("labels") { labels.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    if (alerts.isEmpty() && detections.isEmpty()) return null

    val review = buildJsonObject {
        if (alerts.isNotEmpty()) put("alerts", alerts)
        if (detections.isNotEmpty()) put("detections", detections)
    }

    return if (camera == null) {
        ConfigChange(
            updateTopic = "config/cameras/*/review",
            configData = buildJsonObject { put("review", review) },
        )
    } else {
        ConfigChange(
            updateTopic = "config/cameras/$camera/review",
            configData = buildJsonObject {
                put(
                    "cameras",
                    buildJsonObject { put(camera, buildJsonObject { put("review", review) }) },
                )
            },
        )
    }
}

/** The eight switches on a camera's page, in the order the page lists them. */
enum class CameraSwitch(val label: String, val section: String) {
    ObjectDetection("Object detection", "detect"),
    MotionDetection("Motion detection", "motion"),
    Recording("Recording", "record"),
    Snapshots("Snapshots", "snapshots"),
    AudioDetection("Audio detection", "audio"),
    Birdseye("Birdseye", "birdseye"),
    FaceRecognition("Face recognition", "face_recognition"),
    LicensePlate("License plate recognition", "lpr"),
}

/** What the eight switches are set to on one camera. */
data class CameraSwitches(
    val on: Map<CameraSwitch, Boolean> = CameraSwitch.entries.associateWith { true },
) {
    operator fun get(switch: CameraSwitch): Boolean = on[switch] ?: true

    fun with(switch: CameraSwitch, value: Boolean) = copy(on = on + (switch to value))
}

/** A camera's eight switches, read from Frigate's settings file. */
fun CameraConfig.switches(): CameraSwitches = CameraSwitches(
    mapOf(
        CameraSwitch.ObjectDetection to detect.enabled,
        CameraSwitch.MotionDetection to motion.enabled,
        CameraSwitch.Recording to record.enabled,
        CameraSwitch.Snapshots to snapshots.enabled,
        CameraSwitch.AudioDetection to audio.enabled,
        CameraSwitch.Birdseye to birdseye.enabled,
        CameraSwitch.FaceRecognition to faceRecognition.enabled,
        CameraSwitch.LicensePlate to lpr.enabled,
    ),
)

/** The switches page in one answer: which cameras, what each is set to, and whether you may change it. */
data class CameraSwitchPage(
    val cameras: List<Camera>,
    val switches: Map<String, CameraSwitches>,
    /** Frigate has these switched off for every camera, so a camera's own switch does nothing. */
    val faceRecognitionOff: Boolean = false,
    val licensePlateOff: Boolean = false,
    val admin: Boolean = false,
    /**
     * When this came from the phone's own copy rather than the server, that copy's
     * age in epoch milliseconds. Null means it is the server's answer, now.
     */
    val readAt: Long? = null,
) {
    /** Why a switch can't be moved, or null when it can. */
    fun blocked(switch: CameraSwitch): String? = when {
        switch == CameraSwitch.FaceRecognition && faceRecognitionOff -> ServerOff
        switch == CameraSwitch.LicensePlate && licensePlateOff -> ServerOff
        else -> null
    }

    private companion object {
        const val ServerOff = "Off for the whole server."
    }
}

/** How a camera's switches read on the Settings page. */
data class CameraState(
    val said: String,
    /** Something is off, so the line is worth reading. */
    val somethingOff: Boolean = false,
)

/**
 * What one camera's switches come to, said in a line.
 *
 * A switch the server holds off is left out. It is off on every camera, so
 * naming it under one would say nothing about that camera.
 */
fun CameraSwitchPage.cameraState(camera: String): CameraState {
    val set = switches[camera] ?: CameraSwitches()
    val movable = CameraSwitch.entries.filter { blocked(it) == null }
    val off = movable.filterNot { set[it] }

    if (off.isEmpty()) return CameraState("Everything on")

    val names = off.take(2).map { it.label }
    val said = when (off.size) {
        1 -> "${names[0]} off"
        2 -> "${names[0]} and ${names[1].lowerFirst()} off"
        else -> "${names[0]}, ${names[1].lowerFirst()} and ${off.size - 2} more off"
    }
    return CameraState(said, somethingOff = true)
}

private fun String.lowerFirst(): String = replaceFirstChar { it.lowercase() }

/**
 * A model service the server is set up with.
 *
 * [name] is the key the server files it under, which is how a change finds it
 * again. [roles] says what it is used for: `descriptions` writes the sentence on
 * a review, and `chat` answers the quick question this phone asks.
 */
data class GenaiService(
    val name: String,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val roles: List<String>,
) {
    val describes: Boolean get() = Roles.Descriptions in roles
    val chats: Boolean get() = Roles.Chat in roles

    /** Where it is, said the way a person would. */
    val where: String
        get() = when {
            provider == "ollama" && baseUrl.isNotBlank() ->
                "Ollama at ${baseUrl.removePrefix("http://").removePrefix("https://").trimEnd('/')}"
            baseUrl.isNotBlank() -> baseUrl.trimEnd('/')
            provider.isNotBlank() -> provider.replaceFirstChar { it.uppercase() }
            else -> ""
        }
}

/** What a model service is used for, in the words the server writes them in. */
object Roles {
    const val Descriptions = "descriptions"
    const val Chat = "chat"
}

/**
 * What a server is set up to describe alerts with, and whether it is doing it.
 *
 * [service] is the one holding the describing role, or null when the server has
 * nothing set up for it. [chats] is whether any service answers the quick
 * question this phone asks, which is how a new setup knows to lend the new
 * service that role too. [readAt] is when this was read, or null for the
 * server's answer just now.
 */
data class AiSetup(
    val service: GenaiService?,
    val on: Boolean,
    val chats: Boolean,
    val admin: Boolean,
    val readAt: Long? = null,
) {
    val ready: Boolean get() = service != null
}

/**
 * The model services a server is set up with, read out of its own settings file.
 * A service the server keeps for something else, such as embeddings, is left out.
 */
fun FrigateConfig.genaiServices(): List<GenaiService> = genai.mapNotNull { (name, value) ->
    val raw = value as? JsonObject ?: return@mapNotNull null
    val provider = raw.textOf("provider") ?: return@mapNotNull null
    GenaiService(
        name = name,
        provider = provider,
        baseUrl = raw.textOf("base_url").orEmpty(),
        model = raw.textOf("model").orEmpty(),
        roles = (raw["roles"] as? JsonArray)
            ?.mapNotNull { it.textOrNull() }
            .orEmpty(),
    )
}

private fun JsonObject.textOf(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

private fun kotlinx.serialization.json.JsonElement.textOrNull(): String? =
    (this as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

/**
 * Turns the server's own describing on or off. Only that one switch is sent, so
 * the model services themselves and everything else are left as they are.
 */
fun describeAlertsChange(on: Boolean): ConfigChange =
    ConfigChange(
        updateTopic = "config/review/genai",
        configData = buildJsonObject {
            put(
                "review",
                buildJsonObject {
                    put(
                        "genai",
                        buildJsonObject {
                            put("enabled", on)
                            // Turning it on is about alerts, which is the one thing
                            // this app shows. Descriptions on detections are a
                            // separate switch, and are left as they are.
                            if (on) put("alerts", true)
                        },
                    )
                },
            )
        },
    )

/**
 * Points a model service at an address and a model.
 *
 * A service already on the server keeps everything else it had: its key, and what
 * it is used for. Only a new one is given a provider and roles, and it takes the
 * describing role, plus the chatting one when nothing else has it, so that both
 * sentences can come from the model just set up.
 */
fun genaiModelChange(
    name: String?,
    baseUrl: String,
    model: String,
    giveChat: Boolean,
): ConfigChange {
    val body = buildJsonObject {
        put("base_url", baseUrl.trim().trimEnd('/'))
        put("model", model.trim())
        if (name == null) {
            put("provider", "ollama")
            putJsonArray("roles") {
                add(JsonPrimitive(Roles.Descriptions))
                if (giveChat) add(JsonPrimitive(Roles.Chat))
            }
        }
    }
    val service = name ?: "ollama"
    return ConfigChange(
        updateTopic = "config/genai/$service",
        configData = buildJsonObject {
            put("genai", buildJsonObject { put(service, body) })
        },
    )
}

/**
 * One switch, turned on or off on one camera.
 *
 * Only that switch is sent, so the rest of the camera's settings and the other
 * cameras are left as they are. Frigate saves the change into its own settings
 * file and applies it as it saves, so nothing restarts, and the setting is still
 * there after one.
 */
fun cameraSwitchChange(camera: String, switch: CameraSwitch, on: Boolean): ConfigChange =
    ConfigChange(
        updateTopic = "config/cameras/$camera/${switch.section}",
        configData = buildJsonObject {
            put(
                "cameras",
                buildJsonObject {
                    put(
                        camera,
                        buildJsonObject {
                            put(switch.section, buildJsonObject { put("enabled", on) })
                        },
                    )
                },
            )
        },
    )

/** Turns `door_cam` into `Door cam`, for cameras without a friendly name. */
fun prettyCameraName(raw: String): String = raw
    .replace('_', ' ')
    .replace('-', ' ')
    .trim()
    .replaceFirstChar { it.uppercase() }
