package nz.mckenzie.frigo.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * What this account has set on this phone, as one file you can keep and bring to
 * the next one.
 *
 * It is the phone's answers rather than the server's: the alert lists, the quiet
 * hours, which cameras are hidden and what they are called, how the app looks,
 * and the picture size picked for each camera. Nothing on Frigate is in here, and
 * no sign-in is either: a file that signs you in is a file you cannot leave in a
 * shared folder, and the password this phone keeps is not the file's business.
 *
 * The groups are nullable so a file can leave one out, which is how a file from a
 * Frigo that never wrote that group leaves it alone on the phone. Our own files
 * always fill every one of them in, so a restore gives the same answer every time.
 */
@Serializable
data class SettingsBackup(
    /** Who wrote the file. A file that doesn't say Frigo is not read at all. */
    val app: String = SettingsFile.App,
    /** The shape it was written in, which is how a newer file is read for what this version knows. */
    val format: Int = SettingsFile.Format,
    /** Epoch milliseconds. */
    val savedAt: Long = 0L,
    /** The Frigo that wrote it, so a file on a shelf can say where it came from. */
    val version: String = "",
    /** The server and the name on it, as a label to read. Never a match. */
    val from: Where = Where(),
    val alerts: AlertsBlock? = null,
    val cameras: CameraList? = null,
    val looks: LooksBlock? = null,
    val sizes: Map<String, PictureSize>? = null,
)

/**
 * The server the file came from, and the name signed in to it.
 *
 * It is a label and nothing else: the file lands on whatever server you are
 * signed in to when you restore, which is the only server a new phone has. It is
 * here so a file can say where it was made, and so a restore can say when the two
 * are not the same.
 */
@Serializable
data class Where(
    val address: String = "",
    val username: String = "",
)

/**
 * What this phone watches for: the switch, the quiet hours, the objects it lets
 * buzz and the people it stays quiet about.
 *
 * The hush is left out of the quiet hours, because it is about the next hour
 * rather than about how the phone is set up.
 */
@Serializable
data class AlertsBlock(
    val on: Boolean = false,
    val quietTimes: QuietTimes = QuietTimes(),
    val objects: AlertObjects = AlertObjects(),
    val people: AlertPeople = AlertPeople(),
)

/** How the app looks and how the cameras are laid out: the colours, the words, the tiles. */
@Serializable
data class LooksBlock(
    val layout: Int = 0,
    val theme: Int = 0,
    /** The language's tag, empty for following the phone. */
    val language: String = "",
    val liveTiles: Boolean = false,
    val showReviewed: Boolean = false,
)

/** The height and frame rate chosen for one camera in the player's menu. */
@Serializable
data class PictureSize(
    val height: Int = 0,
    val rate: Int = 0,
)

/** What came of reading one of these files back onto the phone. */
data class RestoreResult(
    /** False when the file carried nothing this version could put back. */
    val applied: Boolean = false,
    /** The language to pick, or null when the file says nothing about it. */
    val language: String? = null,
    /**
     * The name the file was saved by, when it is not the one signed in here. It is
     * what the screen says instead of putting one person's settings on another's.
     */
    val savedBy: String = "",
)

/** The last file a backup was written to from this phone: when, and what it was called. */
@Serializable
data class LastSave(
    val at: Long = 0L,
    val name: String = "",
) {
    /** Whether there is a save worth saying out loud. */
    val any: Boolean get() = at > 0L && name.isNotBlank()
}

/**
 * Whether this file was made by the account named [username].
 *
 * Two people can share one phone, and each keeps their own answers on a server: the
 * cameras they hid, what they call them, the hours they keep quiet. The file names
 * the account that wrote it, so one person's settings are not put back over
 * another's by picking the wrong file.
 *
 * A file that names nobody is one this version cannot place, so it is taken as
 * yours. The name is compared without case, because a sign-in that differs only in
 * capitals is the same person.
 */
fun SettingsBackup.belongsTo(username: String): Boolean {
    val saved = from.username.trim()
    if (saved.isEmpty()) return true
    return saved.equals(username.trim(), ignoreCase = true)
}

/**
 * Reading and writing the settings file.
 *
 * It is plain JSON with a readable name, so a file kept on a computer can be
 * opened and looked at. There is no Android here, so a file can be written and
 * read back without a phone.
 */
object SettingsFile {
    /** What the file says it is. */
    const val App = "frigo"

    /** The shape this version writes, and reads up to. */
    const val Format = 1

    /** What Android should call the file. */
    const val Mime = "application/json"

    /** The file as Android reads it: the whole thing is not needed to be pretty, but it is. */
    private val Writing = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * A key this version has never heard of is left where it is: a file from a
     * newer Frigo restores the parts this one knows and the rest stays in the file.
     */
    private val Reading = Json { ignoreUnknownKeys = true }

    fun write(backup: SettingsBackup): ByteArray =
        Writing.encodeToString(SettingsBackup.serializer(), backup).toByteArray()

    /**
     * The backup in [text], or null when it isn't one of ours.
     *
     * The name on the front is checked before the body is read, so a JSON file
     * from somewhere else is turned down rather than half-read.
     */
    fun read(text: String): SettingsBackup? {
        val root = runCatching {
            Json.parseToJsonElement(text.ifBlank { "{}" }) as? JsonObject
        }.getOrNull() ?: return null
        val app = (root["app"] as? JsonPrimitive)?.contentOrNull
        if (app != App) return null
        return runCatching {
            Reading.decodeFromString(SettingsBackup.serializer(), text)
        }.getOrNull()
    }

    /** The name a new file is offered under: `frigo-settings-2026-11-09.json`. */
    fun suggestedName(today: LocalDate = LocalDate.now()): String =
        "frigo-settings-${today.format(Stamp)}.json"

    private val Stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
}
