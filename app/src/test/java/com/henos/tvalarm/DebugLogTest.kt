package com.henos.tvalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The pairing key must not survive into a log that has Copy and Share buttons. */
class DebugLogTest {

    @Test
    fun registeredFrameLosesItsKey() {
        val frame = """{"type":"registered","id":"x","payload":{"client-key":"abc123DEF456"}}"""
        val out = DebugLog.redact(frame)
        assertFalse(out.contains("abc123DEF456"))
        assertEquals("""{"type":"registered","id":"x","payload":{"client-key":"<redacted>"}}""", out)
    }

    @Test
    fun spacedJsonAndSiblingsSurvive() {
        val out = DebugLog.redact("""{"payload": {"client-key" : "k1", "pairingType":"PROMPT"}}""")
        assertEquals("""{"payload": {"client-key" : "<redacted>", "pairingType":"PROMPT"}}""", out)
    }

    @Test
    fun freeTextFormsAreCovered() {
        assertEquals("sent client-key=<redacted>, rest", DebugLog.redact("sent client-key=SECRET, rest"))
        assertEquals("Client_Key: <redacted>}", DebugLog.redact("Client_Key: SECRET}"))
    }

    @Test
    fun linesWithoutAKeyAreUntouched() {
        val line = "ws://192.168.1.10:3000: onOpen (HTTP 101) - sending register request"
        assertEquals(line, DebugLog.redact(line))
    }
}
