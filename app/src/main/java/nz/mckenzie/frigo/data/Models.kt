package nz.mckenzie.frigo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Only the pieces of /api/config this app reads. Everything else in the file is
 * ignored, so a Frigate release that adds config keys does not break parsing.
 */
@Serializable
data class FrigateConfig(
    val cameras: Map<String, CameraConfig> = emptyMap(),
)

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

/** A camera as the app shows it. */
data class Camera(
    val name: String,
    val title: String,
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

/** A failure the UI can show as a sentence. */
class FrigateException(val status: Int, override val message: String) : Exception(message) {
    val isAuthFailure: Boolean get() = status == 401 || status == 403
}

/** Turns `door_cam` into `Door cam`, for cameras without a friendly name. */
fun prettyCameraName(raw: String): String = raw
    .replace('_', ' ')
    .replace('-', ' ')
    .trim()
    .replaceFirstChar { it.uppercase() }
