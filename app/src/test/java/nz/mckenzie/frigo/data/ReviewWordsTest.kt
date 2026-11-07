package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words Frigate wrote about a review: the title the list shows, the whole
 * story the card under the clip reads, and how a fresher copy of a review joins
 * the one already on screen.
 */
class ReviewWordsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun review(metadata: ReviewMetadata?) = FrigateReview(
        id = "1793418289.280371-abcdef",
        camera = "door_cam",
        startTime = 1793418289.280371,
        endTime = 1793418319.0,
        severity = "alert",
        data = ReviewData(
            objects = listOf("person"),
            detections = listOf("1793418289.280371-abcdef"),
            metadata = metadata,
        ),
    )

    @Test
    fun theTitleIsTheOneTheServerWrote() {
        val said = review(ReviewMetadata(title = "  Person at the door  ")).aiTitle()
        assertEquals("Person at the door", said)
    }

    @Test
    fun aBlankOrMissingTitleIsNoTitle() {
        assertNull(review(ReviewMetadata(title = "   ")).aiTitle())
        assertNull(review(metadata = null).aiTitle())
    }

    @Test
    fun theDescriptionIsTheSceneWhenThereIsOne() {
        val said = review(
            ReviewMetadata(
                title = "Person at the door",
                shortSummary = "A person is walking up the driveway.",
                scene = "  A person walked up the driveway and stopped at the door.  ",
            ),
        ).description()
        assertEquals("A person walked up the driveway and stopped at the door.", said)
    }

    /** Older servers write a title and a summary, and the summary stands in. */
    @Test
    fun theSummaryStandsInWhenThereIsNoScene() {
        val said = review(
            ReviewMetadata(shortSummary = "A person is walking up the driveway."),
        ).description()
        assertEquals("A person is walking up the driveway.", said)
    }

    @Test
    fun aReviewWithNoWordsHasNoDescription() {
        assertNull(review(ReviewMetadata()).description())
        assertNull(review(metadata = null).description())
    }

    @Test
    fun aSceneIsKeptWhenTheServerWritesOne() {
        val read = json.decodeFromString(
            FrigateReview.serializer(),
            """
            {
              "id": "1793418289.280371-abcdef",
              "camera": "door_cam",
              "start_time": 1793418289.280371,
              "end_time": 1793418319.0,
              "severity": "alert",
              "has_been_reviewed": false,
              "data": {
                "objects": ["person"],
                "detections": ["1793418289.280371-abcdef"],
                "metadata": {
                  "title": "Person at the door",
                  "shortSummary": "A person is walking up the driveway.",
                  "scene": "A person walked up the driveway and stopped at the door.",
                  "potential_threat_level": 1,
                  "other_concerns": ["a car passing on the street"]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("Person at the door", read.aiTitle())
        assertEquals("A person walked up the driveway and stopped at the door.", read.description())
        assertEquals(1, read.data.metadata?.threatLevel)
        assertEquals(listOf("a car passing on the street"), read.data.metadata?.otherConcerns)
    }

    /** A server from before scenes existed sends no such key, and still reads. */
    @Test
    fun aServerWithNoSceneStillReads() {
        val read = json.decodeFromString(
            FrigateReview.serializer(),
            """
            {
              "id": "1793418289.280371-abcdef",
              "camera": "door_cam",
              "start_time": 1793418289.280371,
              "end_time": 1793418319.0,
              "severity": "alert",
              "data": {
                "objects": ["person"],
                "metadata": {
                  "title": "Person at the door",
                  "shortSummary": "A person is walking up the driveway.",
                  "potential_threat_level": 0
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("Person at the door", read.aiTitle())
        assertEquals("A person is walking up the driveway.", read.description())
    }

    /**
     * The answer to a single-review call has no `has_been_reviewed`: the server
     * keeps that per user and joins it in only on the list. Taking the words from
     * it must not lose the flag the list carried.
     */
    @Test
    fun theFreshWordsComeAndWhatWasWatchedStays() {
        val onScreen = review(ReviewMetadata()).copy(hasBeenReviewed = true)
        val fresh = review(
            ReviewMetadata(
                title = "Car in the street",
                shortSummary = "A car pulled up.",
                scene = "A car pulled up outside and someone got out.",
            ),
        )

        val merged = onScreen.withWordsFrom(fresh)

        assertEquals("Car in the street", merged.aiTitle())
        assertEquals("A car pulled up outside and someone got out.", merged.description())
        assertTrue(merged.hasBeenReviewed)
        assertEquals(onScreen.id, merged.id)
        assertEquals(onScreen.severity, merged.severity)
    }

    /**
     * Now and then the server keeps the scene as a list holding the words rather
     * than one string, and the list has one entry. Reading one review that is
     * written this way is what a whole page of them does.
     */
    @Test
    fun aSceneWrittenAsAListReadsAsWords() {
        val read = json.decodeFromString(
            FrigateReview.serializer(),
            """
            {
              "id": "1793998489.291657-ec1l5w",
              "camera": "door_cam",
              "start_time": 1793998489.291657,
              "end_time": 1793998500.934841,
              "severity": "alert",
              "data": {
                "objects": ["cat"],
                "metadata": {
                  "title": "Cat moving in hallway",
                  "shortSummary": "A cat is observed moving and crouching on a mat.",
                  "scene": [
                    "The sequence begins with a cat standing on a mat.",
                    "The cat then lowers its body and crouches."
                  ],
                  "potential_threat_level": 0,
                  "other_concerns": null
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("Cat moving in hallway", read.aiTitle())
        assertEquals(
            "The sequence begins with a cat standing on a mat. The cat then lowers its body and crouches.",
            read.description(),
        )
    }

    /** A whole list reads, which is what the Review list and its mark-all walk ask for. */
    @Test
    fun aPageHoldingOneOfThemReads() {
        val page = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(FrigateReview.serializer()),
            """
            [
              {
                "id": "1793998489.291657-ec1l5w",
                "camera": "door_cam",
                "start_time": 1793998489.291657,
                "severity": "alert",
                "data": {"objects": ["cat"], "metadata": {"scene": ["A cat on a mat."]}}
              },
              {
                "id": "1794071549.342566-n4x7zr",
                "camera": "door_cam",
                "start_time": 1794071549.342566,
                "severity": "alert",
                "data": {"objects": ["person"], "metadata": {"scene": "A person at the door."}}
              }
            ]
            """.trimIndent(),
        )

        assertEquals("A cat on a mat.", page[0].description())
        assertEquals("A person at the door.", page[1].description())
    }
}
