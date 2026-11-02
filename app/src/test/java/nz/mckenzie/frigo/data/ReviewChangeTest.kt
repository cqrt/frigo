package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the settings page sends to Frigate, and what it offers to send.
 *
 * The shape matters more than it looks: these are edits to the file Frigate keeps
 * its own settings in, so a wrong nesting or a field sent when it didn't need to
 * be leaves a camera following settings it should have been inheriting.
 */
class ReviewChangeTest {

    private val asIs = ReviewFlags(
        alertsOn = true,
        alertLabels = listOf("person", "car"),
        detectionsOn = true,
        detectionLabels = null,
    )

    /** The server's file has far more in it than this app reads, so extra keys are ignored. */
    private val config = Json { ignoreUnknownKeys = true }

    private fun json(text: String): JsonObject = config.parseToJsonElement(text) as JsonObject

    /** Turning alerts off for every camera says only that, and names every camera. */
    @Test
    fun turningAlertsOffForEveryCameraSendsOneField() {
        val change = reviewChange(null, asIs, asIs.copy(alertsOn = false))
        assertEquals("config/cameras/*/review", change?.updateTopic)
        assertEquals(json("""{"review":{"alerts":{"enabled":false}}}"""), change?.configData)
    }

    /** One camera's change names that camera and sits under its own settings. */
    @Test
    fun oneCameraChangeIsNestedUnderThatCamera() {
        val change = reviewChange("north_cam", asIs, asIs.copy(detectionsOn = false))
        assertEquals("config/cameras/north_cam/review", change?.updateTopic)
        assertEquals(
            json("""{"cameras":{"north_cam":{"review":{"detections":{"enabled":false}}}}}"""),
            change?.configData,
        )
    }

    /** Flipping a switch must not write the label list back, or the camera stops inheriting it. */
    @Test
    fun aSwitchOnItsOwnLeavesTheLabelsAlone() {
        val change = requireNotNull(reviewChange(null, asIs, asIs.copy(alertsOn = false)))
        val alerts = change.configData["review"] as JsonObject
        assertEquals(setOf("enabled"), (alerts["alerts"] as JsonObject).keys)
    }

    @Test
    fun aNewLabelListIsSentAsAList() {
        val change = reviewChange(null, asIs, asIs.copy(alertLabels = listOf("person")))
        assertEquals(
            json("""{"review":{"alerts":{"labels":["person"]}}}"""),
            change?.configData,
        )
    }

    /** Detections back to every other object is the key removed, which Frigate reads as none. */
    @Test
    fun clearingTheDetectionListAsksFrigateToDropIt() {
        val change = reviewChange(null, asIs.copy(detectionLabels = listOf("person")), asIs)
        assertEquals(
            json("""{"review":{"detections":{"labels":null}}}"""),
            change?.configData,
        )
    }

    @Test
    fun nothingChangedMeansNothingIsSent() {
        assertNull(reviewChange(null, asIs, asIs))
    }

    /** The same names in another order are the same names. */
    @Test
    fun theSameLabelsInAnotherOrderSendNothing() {
        assertNull(reviewChange(null, asIs, asIs.copy(alertLabels = listOf("car", "person"))))
    }

    /**
     * The labels to pick from are Frigate's own, with what is already chosen and
     * what is already tracked among them, and the tracked ones first.
     *
     * Each camera arrives with its settings worked out, so the camera that was
     * never given any of its own reads the same as the server's.
     */
    @Test
    fun thePickerOffersFrigatesLabelsAndKeepsWhatIsInUse() {
        val parsed = json(
            """
            {
              "review": {"alerts": {"enabled": true, "labels": ["person"]}},
              "objects": {"track": ["person"]},
              "cameras": {
                "north_cam": {"review": {"detections": {"labels": ["glass"]}}},
                "south_cam": {"review": {"alerts": {"enabled": true, "labels": ["person"]}}}
              }
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        val settings = parsed.reviewSettings()

        assertEquals(FrigateLabels, settings.objects.sorted())
        assertEquals("person", settings.objects.first())
        assertEquals(setOf("person"), settings.tracked)
        assertEquals(settings.all, settings.cameras["south_cam"])
        assertEquals(listOf("glass"), settings.cameras["north_cam"]?.detectionLabels)
    }

    /**
     * The detector's label map is the model's ninety class names, not the set
     * Frigate flags, so it is not what the picker offers. A label already in use
     * is still offered, even though Frigate doesn't name it.
     */
    @Test
    fun theModelsClassNamesAreNotOfferedButWhatIsInUseIs() {
        val parsed = json(
            """
            {
              "review": {"detections": {"labels": ["dog"]}},
              "detectors": {"ov": {"model": {"labelmap": {"0": "background", "1": "person"}}}}
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        assertEquals((FrigateLabels + "dog").sorted(), parsed.reviewSettings().objects)
    }
}
