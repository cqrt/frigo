package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The people this phone stays quiet about, by the name Frigate knows them by.
 *
 * Frigate can't leave a name out of an alert, so the phone does it with the name
 * that arrives on the alert. An empty list is every name allowed, which is how the
 * app behaves without one.
 */
class AlertPeopleTest {

    @Test
    fun nobodyLeftOutLetsEveryNameThrough() {
        val people = AlertPeople()

        assertTrue(people.everyone)
        assertTrue(people.lets("Matt"))
        assertFalse(people.leavesOut(listOf("Matt", "Angie")))
    }

    @Test
    fun aNameOnTheListIsHeldBack() {
        val people = AlertPeople(setOf("Matt"))

        assertFalse(people.everyone)
        assertFalse(people.lets("Matt"))
        assertTrue(people.lets("Angie"))
    }

    /** Frigate marks a name it has verified itself, and the mark is never shown. */
    @Test
    fun aVerifiedNameMatchesThePlainOne() {
        val people = AlertPeople(setOf("Matt"))

        assertFalse(people.lets("Matt-verified"))
    }

    /** A review can carry more than one name, and one of them is enough. */
    @Test
    fun anyNameOnTheReviewCounts() {
        val people = AlertPeople(setOf("Matt"))

        assertTrue(people.leavesOut(listOf("Angie", "Matt")))
    }

    /** Frigate's sentence says the name, and that is enough on its own. */
    @Test
    fun aSentenceThatSaysTheNameCounts() {
        val people = AlertPeople(setOf("Matt"))

        assertTrue(people.mentions("Matt walking up the driveway."))
        assertTrue(people.mentions("A man is at the door. matt waves."))
    }

    /** A name inside a longer word is not the person, and another name is not them. */
    @Test
    fun aNameIsMatchedAsAWholeWord() {
        val people = AlertPeople(setOf("Matt"))

        assertFalse(people.mentions("A matte black car is parked."))
        assertFalse(people.mentions("Matthew is walking up the driveway."))
        assertFalse(people.mentions(""))
    }

    /** It is kept on the phone as one block, so it outlives a restart. */
    @Test
    fun theListSurvivesThePhone() {
        val people = AlertPeople(setOf("Matt", "Angie"))
        val json = Json { ignoreUnknownKeys = true }

        val back = json.decodeFromString(
            AlertPeople.serializer(),
            json.encodeToString(AlertPeople.serializer(), people),
        )

        assertEquals(people, back)
    }
}
