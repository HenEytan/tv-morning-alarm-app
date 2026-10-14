package com.henos.tvalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WolMacsTest {

    @Test
    fun parsesEveryMacInTheField() {
        assertEquals(
            listOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            WebOsClient.parseMacs("aa:bb:cc:dd:ee:ff, 11-22-33-44-55-66")
        )
    }

    @Test
    fun singleMacStillWorks() {
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), WebOsClient.parseMacs("AABBCCDDEEFF"))
    }

    @Test
    fun dropsInvalidEntriesAndDuplicates() {
        assertEquals(
            listOf("AA:BB:CC:DD:EE:FF"),
            WebOsClient.parseMacs("aa:bb:cc:dd:ee:ff;nonsense\nAA-BB-CC-DD-EE-FF")
        )
        assertEquals(emptyList<String>(), WebOsClient.parseMacs(""))
    }

    @Test
    fun normalizeRejectsWrongLength() {
        assertNull(WebOsClient.normalizeMac("AA:BB:CC:DD:EE"))
        assertNull(WebOsClient.normalizeMac("GG:BB:CC:DD:EE:FF"))
    }

    @Test
    fun fieldValidation() {
        assertTrue(WebOsClient.isValidMacField(""))
        assertTrue(WebOsClient.isValidMacField("AA:BB:CC:DD:EE:FF, 11:22:33:44:55:66"))
        assertFalse(WebOsClient.isValidMacField("AA:BB:CC:DD:EE:FF, oops"))
    }
}
