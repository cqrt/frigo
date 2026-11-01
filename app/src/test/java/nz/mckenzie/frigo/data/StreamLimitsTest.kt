package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLimitsTest {

    @Test
    fun aChoiceInsideWhatTheCameraHasIsKept() {
        val camera = StreamLimits(height = 720, rate = 15)
        assertEquals(720 to 15, fittingChoice(720 to 15, camera))
        assertEquals(480 to 10, fittingChoice(480 to 10, camera))
    }

    @Test
    fun aChoiceBiggerThanTheCameraIsBroughtDownToIt() {
        // A 640x360 camera that only sends five frames a second, asked for the biggest
        // the menu used to offer.
        val camera = StreamLimits(height = 360, rate = 5)
        assertEquals(360 to 5, fittingChoice(2160 to 30, camera))
    }

    @Test
    fun aChoiceBetweenTwoOfferedSizesBecomesTheOneBelowIt() {
        val camera = StreamLimits(height = 700, rate = 25)
        assertEquals(480 to 25, fittingChoice(2160 to 30, camera))
    }

    @Test
    fun aRateIsNeverRaised() {
        val camera = StreamLimits(height = 1080, rate = 5)
        assertEquals(2, rateWithin(2, camera))
        assertEquals(5, rateWithin(5, camera))
        assertEquals(5, rateWithin(30, camera))
    }

    @Test
    fun theMenuOffersOnlyWhatTheCameraHas() {
        val small = StreamLimits(height = 360, rate = 5)
        assertEquals(listOf(360), heightsFor(small))
        assertEquals(listOf(5, 2), ratesFor(small))

        val bigger = StreamLimits(height = 720, rate = 15)
        assertEquals(listOf(720, 480), heightsFor(bigger))
        assertEquals(listOf(15, 10, 5, 2), ratesFor(bigger))
    }

    @Test
    fun theMenuKeepsTheCameraSizeWhenItIsNotOneOfTheOfferedOnes() {
        // Door cameras are often 4:3, so their height is 960 rather than 720.
        val camera = StreamLimits(height = 960, rate = 5)
        assertEquals(listOf(960, 720, 480), heightsFor(camera))
    }

    @Test
    fun aFirstLookOpensAtTheDefaultWhenTheCameraCanDoIt() {
        assertEquals(720 to 5, openingChoice(StreamLimits(height = 1080, rate = 15)))
    }

    @Test
    fun aFirstLookOpensAtWhatASmallCameraHas() {
        assertEquals(360 to 5, openingChoice(StreamLimits(height = 360, rate = 5)))
    }

    @Test
    fun aCameraThatCannotBeAskedIsStillCappedAtAPhoneSize() {
        // Without an answer from the server the app still does not ask for a 4K stream:
        // a phone screen cannot show one, and the server would spend itself making it.
        assertEquals(1080 to 30, fittingChoice(2160 to 30, StreamLimits.Unknown))
        assertEquals(1080, StreamLimits.Unknown.height)
        assertEquals(30, StreamLimits.Unknown.rate)
    }

    @Test
    fun aCameraThatSaysNoSensibleRateStillOpens() {
        // Frigate can read a rate of zero on a camera that has just been plugged in,
        // and a stream asked for zero frames is a stream of nothing.
        val camera = StreamLimits(height = 720, rate = 0)
        assertEquals(2, rateWithin(30, camera))
    }
}
