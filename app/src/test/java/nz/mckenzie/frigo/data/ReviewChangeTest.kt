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
     * The labels to pick from are what the server can actually report, with what is
     * already chosen kept among them and the tracked ones first.
     *
     * Each camera arrives with its settings worked out, so the camera that was
     * never given any of its own reads the same as the server's.
     */
    @Test
    fun thePickerOffersWhatTheServerCanReportAndKeepsWhatIsInUse() {
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

        // The one object the server tracks, then the label already in use that it
        // can't report, so it can be taken off.
        assertEquals(listOf("person", "glass"), settings.objects)
        assertEquals(setOf("person"), settings.tracked)
        assertEquals(settings.all, settings.cameras["south_cam"])
        assertEquals(listOf("glass"), settings.cameras["north_cam"]?.detectionLabels)
    }

    /**
     * The detector's label map is the model's ninety class names, not the set
     * Frigate reports, so it is not what the picker offers. A label already in use
     * is still offered, even though nothing on the server can produce it.
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

        assertEquals(listOf("dog"), parsed.reviewSettings().objects)
    }

    /** A sound is offered when a camera has audio on and listens for it. */
    @Test
    fun aSoundIsOfferedWhenACameraListensForIt() {
        val parsed = json(
            """
            {
              "audio": {"enabled": true, "listen": ["bark", "speech"]},
              "objects": {"track": ["person"]}
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        assertEquals(setOf("bark", "person", "speech"), parsed.reviewSettings().objects.toSet())
    }

    /** Audio has to be on for the camera as well as the server, or nothing is heard. */
    @Test
    fun aCameraWithAudioOffReportsNoSounds() {
        val parsed = json(
            """
            {
              "audio": {"enabled": true, "listen": ["bark"]},
              "objects": {"track": ["person"]},
              "cameras": {
                "door_cam": {"audio": {"enabled": false}},
                "garden_cam": {"audio": {"enabled": true}}
              }
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        // The garden follows the shared list, so the sound is still reported by it.
        assertEquals(setOf("bark", "person"), parsed.reviewSettings().objects.toSet())
    }

    /** A camera that tracks objects of its own adds them; the shared list is still there. */
    @Test
    fun aCameraTrackingOfItsOwnAddsToWhatIsOffered() {
        val parsed = json(
            """
            {
              "objects": {"track": ["person"]},
              "cameras": {
                "door_cam": {},
                "garden_cam": {"objects": {"track": ["dog"]}}
              }
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        assertEquals(setOf("person", "dog"), parsed.reviewSettings().objects.toSet())
    }

    /** Audio off for the whole server leaves every sound out, whatever is listened for. */
    @Test
    fun audioOffEverywhereLeavesSoundsOut() {
        val parsed = json(
            """
            {
              "audio": {"enabled": false, "listen": ["bark"]},
              "objects": {"track": ["person"]}
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        assertEquals(listOf("person"), parsed.reviewSettings().objects)
    }

    /** One camera's own answer is what it tracks and listens for, not the server's. */
    @Test
    fun whatOneCameraCanReportFollowsItsOwnLists() {
        val parsed = json(
            """
            {
              "audio": {"enabled": true, "listen": ["speech"]},
              "objects": {"track": ["person"]},
              "cameras": {
                "door_cam": {},
                "garden_cam": {
                  "objects": {"track": ["dog"]},
                  "audio": {"enabled": true, "listen": ["bark"]}
                },
                "shed_cam": {"audio": {"enabled": false}}
              }
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        assertEquals(setOf("person", "speech"), parsed.possibleLabels("door_cam"))
        assertEquals(setOf("dog", "bark"), parsed.possibleLabels("garden_cam"))
        // A camera with audio off hears none of the shared sounds.
        assertEquals(setOf("person"), parsed.possibleLabels("shed_cam"))
    }

    /**
     * A camera whose Alerts switch is off never makes one, so the phone's own list
     * has nothing to narrow for it.
     */
    @Test
    fun aCameraWithAlertsOffHasNoAlertObjects() {
        val parsed = json(
            """
            {
              "review": {"alerts": {"labels": ["person", "car"]}},
              "cameras": {
                "door_cam": {},
                "shed_cam": {"review": {"alerts": {"enabled": false}}}
              }
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        val settings = parsed.reviewSettings()

        assertEquals(listOf("person", "car"), settings.alertLabelsFor("door_cam"))
        assertEquals(emptyList<String>(), settings.alertLabelsFor("shed_cam"))
    }

    /** A camera left off the alert list has no alert objects either. */
    @Test
    fun aCameraWithNoAlertLabelsHasNoAlertObjects() {
        val parsed = json(
            """
            {
              "review": {"alerts": {"labels": ["person", "car"]}},
              "cameras": {"gate_cam": {"review": {"alerts": {"enabled": true, "labels": []}}}}
            }
            """,
        ).let { config.decodeFromJsonElement(FrigateConfig.serializer(), it) }

        assertEquals(emptyList<String>(), parsed.reviewSettings().alertLabelsFor("gate_cam"))
    }
}
