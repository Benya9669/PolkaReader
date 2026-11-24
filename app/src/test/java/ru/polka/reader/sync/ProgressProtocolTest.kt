package ru.polka.reader.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProgressProtocolTest {
    @Test fun noPositionAndAlternateCanArriveTogether() {
        val wire = JSONObject("""{"version":0,"alternate":{"file_id":15,"format":"pdf","percentage":0.625,"updated":"2026-11-24T00:00:00"}}""")
        val result = wire.serverProgress()
        assertNull(result.point)
        assertEquals(15L, result.alternate!!.fileId)
        assertEquals(0.625, result.alternate.percentage, 0.0)
    }

    @Test fun sendsExactLocatorAndExpectedVersion() {
        val point = ReadingPoint("epubcfi(/6/4!/4/2/1:18)", 0.32, "Введение", "Телефон")
        val json = ProgressRow("server/user", 10, point, 12, 4, 3, 0).payload()
        assertEquals(setOf("locator", "percentage", "version", "device", "chapter"), json.keys().asSequence().toSet())
        assertEquals(point.locator, json.getString("locator"))
        assertEquals(12L, json.getLong("version"))
        assertEquals(point, json.serverProgress().point)
    }

    @Test fun malformedServerProgressCannotBecomeAResumeTarget() {
        for (input in listOf(
            """{"version":2}""", """{"version":1,"locator":"0","percentage":0}""",
            """{"version":2,"locator":"1","percentage":101}""", """{}"""
        )) assertThrows(IOException::class.java) { JSONObject(input).serverProgress() }
    }

    @Test fun staleAcknowledgementAdvancesOnlyTheServerVersion() {
        val newer = ReadingPoint("9", 0.3, device = "Телефон")
        val accepted = ReadingPoint("17", 0.55, device = "Телефон")
        val sentThenMovedBack = ProgressRow("scope", 1, newer, 3, 9, 7, 0)
        val acknowledged = sentThenMovedBack.acknowledge(8, 4, accepted)
        assertEquals(newer, acknowledged.point)
        assertEquals(4L, acknowledged.serverVersion)
        assertTrue(acknowledged.dirty)
        assertFalse(acknowledged.acknowledge(9, 5, newer).dirty)
    }
}
