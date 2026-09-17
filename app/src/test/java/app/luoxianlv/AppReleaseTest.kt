package app.luoxianlv

import app.luoxianlv.update.parseAppRelease
import app.luoxianlv.update.validatedUpdateUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    private fun manifest() = JSONObject("""{
      "latestVersionCode": 5, "latestVersionName": "2.2.1", "apkSha256": "${"a".repeat(64)}",
      "apkSize": 100, "minSupportedVersionCode": 3,
      "releaseNotes": ["更新体验"],
      "channels": {"oss": {"url":"https://cdn.example.com/app.apk"}, "github": {"url":"/api/update/github"}}
    }""")
    @Test fun noDowngradeEvenWhenMinimumIsInconsistent() {
        assertNull(parseAppRelease(manifest().put("minSupportedVersionCode", 99), 5, "https://example.com", "oss", false))
        assertNull(parseAppRelease(manifest(), 6, "https://example.com", "oss", false))
    }
    @Test fun choosesPreferredSourceAndKeepsFallback() {
        val release = parseAppRelease(manifest(), 4, "https://example.com", "github", false)!!
        assertEquals(listOf("github", "oss"), release.sources.map { it.id })
        assertEquals("https://example.com/api/update/github", release.sources.first().url)
        assertFalse(release.mandatory)
    }
    @Test fun minimumVersionRequiresUpdate() {
        assertTrue(parseAppRelease(manifest(), 2, "https://example.com", "oss", false)!!.mandatory)
    }
    @Test fun disabledReleaseIsNotOffered() {
        assertNull(parseAppRelease(manifest().put("enabled", false), 1, "https://example.com", "oss", false))
    }
    @Test fun rejectsMissingChecksum() {
        assertThrows(IllegalArgumentException::class.java) { parseAppRelease(manifest().put("apkSha256", ""), 4, "https://example.com", "oss", false) }
    }
    @Test fun releaseDisallowsCleartextAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) { validatedUpdateUrl("http://cdn.example.com/app.apk", "https://example.com", true) }
        assertThrows(IllegalArgumentException::class.java) { validatedUpdateUrl("http://127.0.0.1/a.apk", "https://example.com", false) }
        assertThrows(IllegalArgumentException::class.java) { validatedUpdateUrl("https://token@example.com/a.apk", "https://example.com", false) }
        assertEquals("http://10.0.2.2:8787/a.apk", validatedUpdateUrl("/a.apk", "http://10.0.2.2:8787", true))
    }
}
