package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the System page makes of one reading of the server's stats: the numbers
 * said in words, the bars filled in, and the things worth noticing marked.
 */
@RunWith(RobolectricTestRunner::class)
class SystemPageTest {

    /** Every line on the page is a sentence from the app's own resources. */
    private val context = RuntimeEnvironment.getApplication()

    private fun reading(
        cpu: String? = "23.3",
        mem: String? = "65.2",
        version: String = "0.18.2-",
        latest: String = "0.18.2",
        uptime: Double = 60_818.0,
        cameras: Map<String, CameraStats> = emptyMap(),
        storage: Map<String, StorageStats> = emptyMap(),
    ) = FrigateStats(
        cameras = cameras,
        service = ServiceStats(
            uptime = uptime,
            version = version,
            latestVersion = latest,
            storage = storage,
        ),
        cpuUsages = mapOf("frigate.full_system" to CpuUsage(cpu = cpu, mem = mem)),
    )

    @Test
    fun uptimeIsSaidInHoursAndMinutes() {
        assertEquals("16 hours 53 minutes", uptimeText(context, 60_818.0))
    }

    @Test
    fun aLongUptimeIsSaidInDays() {
        assertEquals("3 days 4 hours", uptimeText(context, 3 * 86_400.0 + 4 * 3_600.0))
        assertEquals("1 day 0 hours", uptimeText(context, 86_400.0))
    }

    @Test
    fun aShortUptimeIsStillSayable() {
        assertEquals("2 minutes", uptimeText(context, 120.0))
        assertEquals("1 minute", uptimeText(context, 60.0))
        assertEquals("less than a minute", uptimeText(context, 30.0))
    }

    @Test
    fun storageIsCountedInGigabytes() {
        assertEquals("58.7 GB", sizeText(context, 58_669.1))
        assertEquals("200.5 GB", sizeText(context, 200_502.0))
    }

    @Test
    fun aSmallDiskStaysInMegabytes() {
        assertEquals("60 MB", sizeText(context, 60.4))
    }

    @Test
    fun theProcessorIsABarOfWhatItIsUsing() {
        val page = reading().systemPage(context, admin = false)

        assertEquals("23.3%", page.processor?.said)
        assertEquals(0.233f, page.processor?.fraction ?: 0f, 0.001f)
        assertEquals("65.2%", page.memory?.said)
        assertFalse(page.processor?.warn ?: true)
    }

    @Test
    fun aCameraDroppingFramesAsksForAttention() {
        val page = reading(
            cameras = mapOf(
                "door_cam" to CameraStats(cameraFps = 10.1, detectionFps = 2.0, connectionQuality = "excellent"),
                "south_cam" to CameraStats(cameraFps = 6.0, skippedFps = 1.5, connectionQuality = "poor"),
            ),
        ).systemPage(context, admin = false)

        val door = page.cameras.first { it.title == "Door cam" }
        assertEquals("10.1 frames a second", door.value)
        assertEquals("2.0 being detected · Excellent", door.said)
        assertFalse(door.warn)

        val south = page.cameras.first { it.title == "South cam" }
        assertTrue(south.warn)
        assertEquals("nothing being detected · Poor", south.said)
    }

    @Test
    fun theSameDiskUnderTwoPathsIsOneLine() {
        val disk = StorageStats(total = 200_502.0, used = 58_669.1, free = 131_576.9)
        val page = reading(
            storage = mapOf(
                "/media/frigate/recordings" to disk,
                "/media/frigate/clips" to disk,
            ),
        ).systemPage(context, admin = false)

        assertEquals(1, page.storage.size)
        assertEquals("Recordings", page.storage.first().title)
        assertEquals("58.7 GB of 200.5 GB", page.storage.first().said)
        assertFalse(page.storage.first().warn)
    }

    @Test
    fun sharedMemoryUnderItsMinimumAsksForAttention() {
        val page = reading(
            storage = mapOf(
                "/dev/shm" to StorageStats(
                    total = 7_926.2,
                    used = 347.9,
                    free = 7_578.3,
                    shmFrameCount = 50.0,
                    minShm = 260.0,
                ),
            ),
        ).systemPage(context, admin = false)

        val shm = page.storage.first()
        assertEquals("Shared memory", shm.title)
        assertTrue(shm.warn)
        assertTrue(shm.detail.orEmpty().contains("50 frames"))
    }

    @Test
    fun aFullDiskAsksForAttention() {
        val page = reading(
            storage = mapOf(
                "/media/frigate/recordings" to StorageStats(total = 100.0, used = 95.0, free = 5.0),
            ),
        ).systemPage(context, admin = false)

        assertTrue(page.storage.first().warn)
    }

    @Test
    fun theNewerReleaseIsOnlySaidWhenItDiffers() {
        assertNull(reading(version = "0.18.2-", latest = "0.18.2").systemPage(context, admin = false).newer)
        assertEquals("0.19.0", reading(version = "0.18.2-", latest = "0.19.0").systemPage(context, admin = false).newer)
    }

    @Test
    fun onlyAnAdminIsOfferedTheRestart() {
        assertTrue(reading().systemPage(context, admin = true).restartable)
        assertFalse(reading().systemPage(context, admin = false).restartable)
    }
}
