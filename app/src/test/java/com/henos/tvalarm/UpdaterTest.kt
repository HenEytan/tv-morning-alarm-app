package com.henos.tvalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun onlyBuildTagsHaveANumber() {
        assertEquals(118L, Updater.runNumberOf("build-118"))
        assertEquals(118L, Updater.runNumberOf(" build-118 "))
        assertNull(Updater.runNumberOf("v1.0.0"))
        assertNull(Updater.runNumberOf("build-"))
        assertNull(Updater.runNumberOf("build-118-rc1"))
    }

    private fun release(tag: String, assets: String) =
        """{"tag_name":"$tag","assets":$assets}"""

    @Test
    fun parsesTheApkAsset() {
        val body = release(
            "build-121",
            """[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
                {"name":"app-release-121.apk","browser_download_url":"https://x/app.apk","size":4200000}]""",
        )
        val r = Updater.parseRelease(body)!!
        assertEquals(121L, r.versionCode)
        assertEquals("build-121", r.tag)
        assertEquals("https://x/app.apk", r.url)
        assertEquals(4200000L, r.sizeBytes)
    }

    @Test
    fun refusesWhatItCannotNumberOrFind() {
        assertNull(Updater.parseRelease(release("v1.0.0", """[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]""")))
        assertNull(Updater.parseRelease(release("build-5", "[]")))
        assertNull(Updater.parseRelease("garbage"))
    }

    @Test
    fun checkDueHandlesAClockThatWentBackwards() {
        assertTrue(Updater.checkDue(0L, 10L))
        assertTrue(Updater.checkDue(20L, 10L))
        assertFalse(Updater.checkDue(10L, 10L + Updater.CHECK_EVERY_MS - 1))
        assertTrue(Updater.checkDue(10L, 10L + Updater.CHECK_EVERY_MS))
    }
}
