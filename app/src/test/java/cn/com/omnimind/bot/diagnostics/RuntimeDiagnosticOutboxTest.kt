package cn.com.omnimind.bot.diagnostics

import cn.com.omnimind.baselib.util.RuntimeLogEntry
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDiagnosticOutboxTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun retainsMoreThanSnapshotWindowAcrossRestartAndOnlyAcknowledgesSelectedBatch() {
        val directory = temp.newFolder()
        val queue = RuntimeDiagnosticOutbox(directory)
        repeat(230) { queue.append(RuntimeLogEntry(id = "entry-$it", message = "line $it")) }
        queue.append(RuntimeLogEntry(id = "entry-1", message = "duplicate"))
        assertEquals(230, queue.size())
        val restored = RuntimeDiagnosticOutbox(directory)
        val batch = restored.pending()
        assertEquals(100, batch.size)
        assertEquals(batch, restored.pending()) // Failed delivery changes nothing.
        restored.acknowledge(batch)
        assertEquals(130, RuntimeDiagnosticOutbox(directory).size())
        assertTrue(restored.pending().none { entry -> batch.any { it.id == entry.id } })
    }

    @Test fun sensitiveFieldsAreFilteredBeforeDiskAndQueueLimitHasPersistentGapCount() {
        val directory = temp.newFolder()
        val queue = RuntimeDiagnosticOutbox(directory, maxEntries = 2)
        queue.append(RuntimeLogEntry(id = "one", message = "authorization: Bearer fake-secret-value"))
        assertTrue(directory.listFiles()!!.filter { it.extension == "json" }.none { it.readText().contains("fake-secret-value") })
        repeat(3) { queue.append(RuntimeLogEntry(id = "later-$it", message = "normal")) }
        assertEquals(2, queue.size())
        assertEquals(2L, queue.dropped)
        assertEquals(2L, RuntimeDiagnosticOutbox(directory, maxEntries = 2).dropped)
    }

    @Test fun frameSizeSplittingLeavesUnsentRecordsInQueue() {
        val queue = RuntimeDiagnosticOutbox(temp.newFolder())
        repeat(100) { queue.append(RuntimeLogEntry(id = "big-$it", message = "测".repeat(32_768), stackTrace = "堆".repeat(32_768))) }
        val batch = RuntimeDiagnosticReport.batch("fixture", emptyMap(), queue.pending(), 0)
        assertTrue(batch.json.toByteArray().size <= 1_048_576)
        assertTrue(batch.entries.size in 1..99)
        val json = JsonParser.parseString(batch.json).asJsonObject
        assertEquals(batch.entries.size, json["logs"].asJsonArray.size())
        queue.acknowledge(batch.entries)
        assertEquals(100 - batch.entries.size, queue.size())
    }

    @Test fun parsesAllLogcatLevelsAndReplayedLinesHaveSameIdentity() {
        for ((code, level) in mapOf("V" to "VERBOSE", "D" to "DEBUG", "I" to "INFO", "W" to "WARN", "E" to "ERROR", "F" to "ASSERT")) {
            val line = "1792700000.123456  1234  1235 $code flutter : message: details"
            val entry = requireNotNull(RuntimeDiagnosticLogcat.parse(line))
            assertEquals(level, entry.level)
            assertEquals(1792700000123, entry.createdAt)
            assertEquals(entry.id, RuntimeDiagnosticLogcat.parse(line)?.id)
            assertEquals("flutter", entry.tag)
            assertTrue(entry.message.contains("message: details"))
        }
        assertNull(RuntimeDiagnosticLogcat.parse("--------- beginning of main"))
        val link = "omnibotdiagnostics://connect#fixture-private-pairing-value"
        assertFalse(RuntimeDiagnosticReport.redact("open $link").contains("fixture-private-pairing-value"))
        val header = "[pid=123 tid=456] Cookie: first=one; second=two"
        val redacted = RuntimeDiagnosticReport.redact(header)
        assertFalse(redacted.contains("first=one"))
        assertFalse(redacted.contains("second=two"))
    }
}
