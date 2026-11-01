package nz.mckenzie.frigo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    @Test
    fun aLaterPatchIsNewer() {
        assertTrue(laterVersion("0.7.27", "0.7.26"))
        assertFalse(laterVersion("0.7.26", "0.7.27"))
    }

    @Test
    fun theSameVersionIsNotNewer() {
        assertFalse(laterVersion("0.7.27", "0.7.27"))
    }

    @Test
    fun aTagAndASuffixAreIgnored() {
        assertTrue(laterVersion("v0.7.28", "0.7.27"))
        assertTrue(laterVersion("0.8.0-beta.1", "0.7.99"))
    }

    @Test
    fun aHundredthPatchIsLaterThanTheNinetyNinth() {
        // Counted as numbers, so the parts past ten do not read as text and swap places.
        assertTrue(laterVersion("0.7.100", "0.7.99"))
        assertFalse(laterVersion("0.7.99", "0.7.100"))
    }

    @Test
    fun aShorterVersionCountsItsMissingPartsAsNothing() {
        assertTrue(laterVersion("0.8", "0.7.99"))
        assertFalse(laterVersion("0.7", "0.7.1"))
    }

    @Test
    fun aReleaseKnowsWhetherItIsNewerThanThePhone() {
        val release = Update(version = "0.7.28", page = "", apk = "", bytes = 0)
        assertTrue(release.isNewerThan("0.7.27"))
        assertFalse(release.isNewerThan("0.7.28"))
    }
}
