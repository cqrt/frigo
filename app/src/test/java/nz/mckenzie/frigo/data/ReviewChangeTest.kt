package nz.mckenzie.frigo.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the settings page sends to Frigate.
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

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

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
     * The objects to pick from are the model's names, with what is already chosen
     * and what is already tracked among them, and the tracked ones first.
     *
     * Each camera arrives with its settings worked out, so the camera that was
     * never given any of its own reads the same as the server's.
     */
    @Test
    fun thePickerOffersTheModelsNamesAndKeepsWhatIsInUse() {
        val config = json(
            """
            {
              "review": {"alerts": {"enabled": true, "labels": ["person"]}},
              "objects": {"track": ["person"]},
              "cameras": {
                "north_cam": {"review": {"detections": {"labels": ["glass"]}}},
                "south_cam": {"review": {"alerts": {"enabled": true, "labels": ["person"]}}}
              },
              "detectors": {"ov": {"model": {"labelmap": {"0": "background", "1": "person", "2": "car", "3": "dog"}}}}
            }
            """,
        ).let { Json.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        val settings = config.reviewSettings()

        assertEquals(listOf("person", "car", "dog", "glass"), settings.objects)
        assertEquals(setOf("person"), settings.tracked)
        assertEquals(settings.all, settings.cameras["south_cam"])
        assertEquals(listOf("glass"), settings.cameras["north_cam"]?.detectionLabels)
    }

    /** Zero in a model's label map is the backdrop, not something you can flag. */
    @Test
    fun theBackdropIsNotOfferedAsAnObject() {
        val config = json(
            """
            {
              "detectors": {"ov": {"model": {"labelmap": {"0": "background", "1": "person"}}}}
            }
            """,
        ).let { Json.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        assertEquals(listOf("person"), config.reviewSettings().objects)
    }
}
