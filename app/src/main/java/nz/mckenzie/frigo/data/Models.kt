package nz.mckenzie.frigo.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The pair Frigate hands back from /api/login and /api/refresh. */
@Serializable
data class Tokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

/**
 * Only the pieces of /api/config this app reads. Everything else in the file is
 * ignored, so a Frigate release that adds config keys does not break parsing.
 */
@Serializable
data class FrigateConfig(
    val cameras: Map<String, CameraConfig> = emptyMap(),
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

/** A camera as the app shows it. */
data class Camera(
    val name: String,
    val title: String,
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
