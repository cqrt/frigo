package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dot on the Settings tab, and how often the app asks GitHub. */
class UpdateWatchTest {

    private val release = Update(version = "0.10.16", page = "", apk = "", bytes = 0)

    @Test
    fun aNewerReleaseWithNothingSeenIsWaiting() {
        assertTrue(updateWaiting(release, current = "0.10.15", seen = null))
    }

    @Test
    fun aReleaseThisAppAlreadyIsIsNotWaiting() {
        assertFalse(updateWaiting(release, current = "0.10.16", seen = null))
        assertFalse(updateWaiting(release, current = "0.10.17", seen = null))
    }

    @Test
    fun theOneAlreadySeenDoesNotBringTheDotBack() {
        assertFalse(updateWaiting(release, current = "0.10.15", seen = "0.10.16"))
    }

    @Test
    fun aReleaseLaterThanTheOneSeenBringsTheDotBack() {
        assertTrue(updateWaiting(release, current = "0.10.15", seen = "0.10.15"))
        assertTrue(updateWaiting(release, current = "0.10.15", seen = "0.10.9"))
    }

    @Test
    fun aVersionSeenFromFurtherAheadKeepsTheDotAway() {
        // A phone that went back a version still should not be told its own old
        // releases are news.
        assertFalse(updateWaiting(release, current = "0.10.15", seen = "0.11.0"))
    }

    @Test
    fun nothingFromGitHubMeansNothingWaiting() {
        assertFalse(updateWaiting(null, current = "0.10.15", seen = null))
    }

    @Test
    fun versionsAreCountedAsNumbersWhenDeciding() {
        val later = Update(version = "0.10.100", page = "", apk = "", bytes = 0)
        assertTrue(updateWaiting(later, current = "0.10.99", seen = "0.10.99"))
    }

    @Test
    fun anAnswerStandsForAnHour() {
        val asked = 1_000_000L
        assertFalse(answerStale(asked, now = asked))
        assertFalse(answerStale(asked, now = asked + 59 * 60 * 1000L))
        assertTrue(answerStale(asked, now = asked + UpdateWindowMillis))
        assertTrue(answerStale(asked, now = asked + 5 * UpdateWindowMillis))
    }

    @Test
    fun aPhoneThatHasNeverBeenAskedAsks() {
        // Nothing is stored until GitHub has answered once, so the time sits at zero
        // and every real clock is well past it.
        assertTrue(answerStale(checkedAt = 0L, now = 1_700_000_000_000L))
    }

    @Test
    fun aStoredAnswerSurvivesTheRoundTrip() {
        val json = Json { ignoreUnknownKeys = true }
        val stored = StoredAnswer(checkedAt = 1_000L, update = release)
        val back = json.decodeFromString(
            StoredAnswer.serializer(),
            json.encodeToString(StoredAnswer.serializer(), stored),
        )
        assertEquals(stored, back)
    }

    @Test
    fun anAnswerWithNothingInItIsStillAnAnswer() {
        val empty = Json.decodeFromString(StoredAnswer.serializer(), "{}")
        assertEquals(StoredAnswer(), empty)
        assertFalse(empty.checkedAt > 0)
    }
}
