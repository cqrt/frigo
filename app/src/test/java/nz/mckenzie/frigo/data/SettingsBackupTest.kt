package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The settings file: what it carries, and what it refuses.
 *
 * The point of it is a new phone, so the file has to be complete enough to set one
 * up and plain enough to keep. It holds no sign-in, so a file left in a shared
 * folder is not a key to the house.
 */
class SettingsBackupTest {

    private fun backup() = SettingsBackup(
        savedAt = 1_793_420_000_000L,
        version = "0.9.0",
        from = Where(address = "https://northcam.example", username = "matt"),
        alerts = AlertsBlock(
            on = true,
            quietTimes = QuietTimes(on = true, from = 21 * 60, until = 6 * 60, cameras = setOf("drive")),
            objects = AlertObjects(cameras = mapOf("drive" to setOf("person"))),
            people = AlertPeople(setOf("Angie")),
        ),
        cameras = CameraList(
            hidden = setOf("printer"),
            names = mapOf("door_cam" to "Door"),
            order = listOf("drive", "door_cam"),
        ),
        looks = LooksBlock(
            layout = 1,
            theme = 2,
            language = "de",
            liveTiles = true,
            showReviewed = true,
        ),
        sizes = mapOf("drive" to PictureSize(height = 720, rate = 5)),
    )

    /** What went in comes back out, whole. */
    @Test
    fun aFileReadBackSaysWhatWentIn() {
        val written = backup()

        val read = SettingsFile.read(String(SettingsFile.write(written)))

        assertEquals(written, read)
    }

    /** It is a text file, because a settings file on a shelf should be readable. */
    @Test
    fun theFileIsWordsAnyoneCanOpen() {
        val text = String(SettingsFile.write(backup()))

        assertTrue(text.contains("\"app\": \"frigo\""))
        assertTrue(text.contains("\"door_cam\": \"Door\""))
    }

    /** Any other JSON is turned down rather than half-read. */
    @Test
    fun aFileFromSomewhereElseIsTurnedDown() {
        assertNull(SettingsFile.read("""{"app":"something-else","format":1}"""))
        assertNull(SettingsFile.read("""{"savedAt":0}"""))
        assertNull(SettingsFile.read("not a file at all"))
        assertNull(SettingsFile.read(""))
    }

    /**
     * A file from a newer Frigo is read for the parts this version knows, and a
     * group it doesn't carry is left out rather than guessed at.
     */
    @Test
    fun aNewerFileIsReadForWhatThisVersionKnows() {
        val text = """
            {
              "app": "frigo",
              "format": 9,
              "savedAt": 1,
              "looks": {"layout": 1, "theme": 2, "language": "fr", "liveTiles": true},
              "weather": {"today": "rain"}
            }
        """.trimIndent()

        val read = SettingsFile.read(text)

        assertEquals(1, read?.looks?.layout)
        assertEquals("fr", read?.looks?.language)
        assertNull(read?.alerts)
        assertNull(read?.cameras)
        assertNull(read?.sizes)
    }

    /** The name offered in the file picker carries the day, so two saves do not collide. */
    @Test
    fun theFileIsNamedForTheDayItWasSaved() {
        assertEquals(
            "frigo-settings-2026-11-09.json",
            SettingsFile.suggestedName(LocalDate.of(2026, 11, 9)),
        )
    }

    /** An empty answer is not worth showing on the row, so nothing is claimed. */
    @Test
    fun aSaveThatNeverHappenedIsNotSaid() {
        assertFalse(LastSave().any)
        assertFalse(LastSave(at = 1_793_420_000_000L).any)
        assertTrue(LastSave(at = 1_793_420_000_000L, name = "frigo-settings-2026-11-09.json").any)
    }
}
