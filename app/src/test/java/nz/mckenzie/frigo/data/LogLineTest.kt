package nz.mckenzie.frigo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What one line of a Frigate log comes to: its day, its second, its level, and
 * the message left over. The samples are real lines from a 0.18.2 server.
 */
class LogLineTest {

    @Test
    fun aProcessedLineHasItsDayAndSecondReadOff() {
        val line = "2026-11-08 08:29:43.777006  [INFO] Preparing Frigate...".asLogLine()

        assertEquals("2026-11-08", line.date)
        assertEquals("08:29:43", line.time)
        assertEquals(LogLevel.Info, line.level)
        assertEquals("Preparing Frigate...", line.message)
    }

    @Test
    fun theDateBracketInsideAMessageGoes() {
        val line = ("2026-11-08 08:29:49.695709  [2026-11-08 08:29:49] " +
            "frigate.util.config            INFO    : Checking if frigate config needs migration...")
            .asLogLine()

        assertEquals(LogLevel.Info, line.level)
        assertEquals(
            "frigate.util.config            INFO    : Checking if frigate config needs migration...",
            line.message,
        )
    }

    @Test
    fun aWarningWrittenBareIsAWarning() {
        val line = ("2026-11-08 08:29:54.461891  [2026-11-08 08:29:54] " +
            "frigate.comms.webpush          WARNING : Email must be provided for push notifications " +
            "to be sent.").asLogLine()

        assertEquals(LogLevel.Warning, line.level)
    }

    @Test
    fun aDebugWrittenBareByAContainerIsADebug() {
        val line = "DEBUG   : Copied a recording file in 0.03 seconds.".asLogLine()

        assertNull(line.date)
        assertNull(line.time)
        assertEquals(LogLevel.Debug, line.level)
        assertEquals("Copied a recording file in 0.03 seconds.", line.message)
    }

    @Test
    fun nginxWritesItsLevelInBrackets() {
        val line = ("2026-11-08 08:29:48.486806  2026/11/08 08:29:48 [error] 387#387: *1 " +
            "connect() failed (111: Connection refused) while connecting to upstream").asLogLine()

        assertEquals("2026-11-08", line.date)
        assertEquals("08:29:48", line.time)
        assertEquals(LogLevel.Error, line.level)
        // The bracket is inside the message rather than at its start, so the line
        // keeps the whole of what nginx wrote.
        assertEquals(
            "2026/11/08 08:29:48 [error] 387#387: *1 " +
                "connect() failed (111: Connection refused) while connecting to upstream",
            line.message,
        )
    }

    @Test
    fun nginxWritesSomeLinesWithNoLevelAtAll() {
        val line = ("2026-11-08 08:33:08.051580  192.168.0.103 - - [08/Nov/2026:08:33:08 +1300] " +
            "\"GET /api/profile HTTP/1.1\" 200 102 \"-\" \"okhttp/4.12.0\"").asLogLine()

        assertNull(line.level)
    }

    @Test
    fun aBareWordBeforeAColonIsALevel() {
        assertEquals(LogLevel.Info, "INFO  : a word that is not in brackets".asLogLine().level)
    }

    @Test
    fun go2rtcWritesALevelThisAppDoesNotKnow() {
        val line = ("2026-11-08 08:29:46.864775  08:29:46.864 INF go2rtc platform=linux/amd64 " +
            "revision=b5948cf version=1.9.14").asLogLine()

        assertNull(line.level)
        assertEquals(
            "08:29:46.864 INF go2rtc platform=linux/amd64 revision=b5948cf version=1.9.14",
            line.message,
        )
    }

    @Test
    fun aLineWithNoTimestampKeepsItsWholeText() {
        val line = "Something the container said with no prefix".asLogLine()

        assertNull(line.date)
        assertNull(line.time)
        assertNull(line.level)
        assertEquals("Something the container said with no prefix", line.message)
    }

    @Test
    fun everyLevelTheServerWritesIsRead() {
        assertEquals(LogLevel.Error, "2026-11-08 08:29:43.1  [ERROR] no".asLogLine().level)
        assertEquals(LogLevel.Warning, "2026-11-08 08:29:43.1  [WARNING] careful".asLogLine().level)
        assertEquals(LogLevel.Info, "2026-11-08 08:29:43.1  [LOGGING] starting".asLogLine().level)
        assertEquals(LogLevel.Debug, "2026-11-08 08:29:43.1  [DEBUG] details".asLogLine().level)
        assertEquals(LogLevel.Error, "2026/11/08 [crit] fatal".asLogLine().level)
        assertEquals(LogLevel.Error, "2026/11/08 [alert] worse".asLogLine().level)
        assertEquals(LogLevel.Info, "2026/11/08 [notice] a note".asLogLine().level)
    }

    @Test
    fun theOldestLinesGoAtFiveThousand() {
        val lines = (1..5_001).map { LogLine(message = "line $it") }

        val kept = lines.newestLines()

        assertEquals(MostLogLines, kept.size)
        assertEquals("line 2", kept.first().message)
        assertEquals("line 5001", kept.last().message)
    }

    @Test
    fun theChipsAndTheSearchNarrowTogether() {
        val lines = listOf(
            LogLine(level = LogLevel.Error, message = "camera north_cam went offline"),
            LogLine(level = LogLevel.Error, message = "config write failed"),
            LogLine(level = LogLevel.Warning, message = "camera took a while"),
            LogLine(level = LogLevel.Info, message = "camera started"),
            LogLine(level = null, message = "camera line with no level"),
        )

        assertEquals(
            listOf("camera north_cam went offline"),
            lines.narrowed(setOf(LogLevel.Error), "camera").map { it.message },
        )
        assertEquals(
            listOf("camera north_cam went offline", "camera took a while", "camera started", "camera line with no level"),
            lines.narrowed(emptySet(), "camera").map { it.message },
        )
        assertEquals(5, lines.narrowed(emptySet(), "").size)
    }

    @Test
    fun moreThanOneChipCanBeTicked() {
        val lines = listOf(
            LogLine(level = LogLevel.Error, message = "camera failed"),
            LogLine(level = LogLevel.Warning, message = "camera behind"),
            LogLine(level = LogLevel.Info, message = "camera started"),
            LogLine(level = LogLevel.Warning, message = "disk behind"),
        )

        assertEquals(
            listOf("camera failed", "camera behind"),
            lines.narrowed(setOf(LogLevel.Error, LogLevel.Warning), "camera").map { it.message },
        )
        assertEquals(
            listOf("camera failed", "camera behind", "disk behind"),
            lines.narrowed(setOf(LogLevel.Error, LogLevel.Warning), "").map { it.message },
        )
    }

    @Test
    fun aLineWithNoLevelShowsUnderAllOnly() {
        val line = LogLine(message = "no level here")

        assertEquals(listOf(line), listOf(line).narrowed(emptySet(), ""))
        assertEquals(emptyList<LogLine>(), listOf(line).narrowed(setOf(LogLevel.Info), ""))
    }
}
