package nz.mckenzie.frigo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
     * The server's own setting for the two features a camera can't run without.
     * Its starting point is on, and only a server that says otherwise is read.
     */
    @SerialName("face_recognition") val faceRecognition: SwitchConfig = SwitchConfig(),
    val lpr: SwitchConfig = SwitchConfig(),
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

@Serializable
data class CameraConfig(
    val enabled: Boolean = true,
    @SerialName("friendly_name") val friendlyName: String? = null,
    val review: ReviewBlockConfig = ReviewBlockConfig(),
    val objects: TrackedObjects = TrackedObjects(),
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

/** A camera as the app shows it. */
data class Camera(
    val name: String,
    val title: String,
)

/**
 * The part of `/api/stats` this app reads. Frigate counts the frames each camera
 * has produced over the last few seconds, so a camera with nothing arriving counts
 * zero, which is how it says a camera has stopped.
 */
@Serializable
data class FrigateStats(
    val cameras: Map<String, CameraStats> = emptyMap(),
    val service: ServiceStats = ServiceStats(),
)

@Serializable
data class CameraStats(
    @SerialName("camera_fps") val cameraFps: Double = 0.0,
    /**
     * The rate the camera is set to send at, which does not dip the way a single
     * second's count does. Absent from a server that doesn't say.
     */
    @SerialName("expected_fps") val expectedFps: Double? = null,
) {
    /** The rate this camera sends at, as a whole number, preferring the one set for it. */
    val settingRate: Int
        get() = (expectedFps?.takeIf { it > 0 } ?: cameraFps).roundToInt()
}

/** How long the server has been up, in seconds. Absent from a server that doesn't say. */
@Serializable
data class ServiceStats(
    val uptime: Double? = null,
)

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
        cameras = cameras.mapValues { it.value.review.flags() },
        objects = objects.filter { it.isNotBlank() }.distinct()
            .sortedWith(compareBy({ it !in tracked }, { it })),
        tracked = tracked,
    )
}

private fun ReviewBlockConfig.flags() = ReviewFlags(
    alertsOn = alerts.enabled,
    alertLabels = alerts.labels ?: listOf("person", "car"),
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
