package app.luoxianlv

import app.luoxianlv.update.parseAppRelease
import app.luoxianlv.update.validatedUpdateUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    @Test
    fun malformedPresentDeliveryCannotDowngradeToUnsignedFull() {
        for (value in listOf("not-an-envelope", org.json.JSONArray(), JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) {
                parseAppRelease(
                    manifest().put("deliveryV1", value),
                    4,
                    "https://example.com",
                    "oss",
                    false,
                )
            }
        }
    }

    @Test
    fun signedDeliveryRequiresAllChannelsToMatchTarget() {
        val json = manifest().put("deliveryV1", JSONObject().put("schema", 1))
        json.getJSONObject("channels").getJSONObject("github").put("sha256", "b".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            parseAppRelease(json, 4, "https://example.com", "oss", false)
        }
        json.getJSONObject("channels").getJSONObject("github").put("sha256", "a".repeat(64))
        assertNotNull(parseAppRelease(json, 4, "https://example.com", "oss", false)!!.deliveryJson)
    }

    @Test
    fun signedDeliveryRejectsMalformedExplicitChannelIdentity() {
        for (field in listOf("sha256", "size")) {
            for (value in listOf("invalid", JSONObject.NULL, "", 0, -1)) {
                val json = manifest().put("deliveryV1", JSONObject().put("schema", 1))
                json.getJSONObject("channels").getJSONObject("oss").put(field, value)
                assertThrows(IllegalArgumentException::class.java) {
                    parseAppRelease(json, 4, "https://example.com", "oss", false)
                }
            }
        }
    }

    @Test
    fun signedDeliveryRejectsUnknownOrOversizedTargetLength() {
        for (size in listOf(0L, -1L, 512L * 1024 * 1024 + 1)) {
            val json =
                manifest().put("deliveryV1", JSONObject().put("schema", 1)).put("apkSize", size)
            assertThrows(IllegalArgumentException::class.java) {
                parseAppRelease(json, 4, "https://example.com", "oss", false)
            }
        }
    }

    @Test
    fun signedDeliveryRetainsTwoOfficialFullSourcesWithoutPeerDownloads() {
        val json = manifest().put("deliveryV1", JSONObject().put("schema", 1))
        json
            .getJSONObject("channels")
            .put("p2p", JSONObject().put("url", "https://peer.example.com/app.apk"))
        val release = parseAppRelease(json, 4, "https://example.com", "oss", false)!!
        assertEquals(listOf("oss", "github"), release.sources.map { it.id })
        assertEquals(100L, release.size)
        assertTrue(release.sources.all { it.sha256 == release.sha256 && it.size == release.size })
    }

    private fun manifest() =
        JSONObject(
            """{
      "latestVersionCode": 5, "latestVersionName": "2.2.1", "apkSha256": "${"a".repeat(64)}",
      "apkSize": 100, "minSupportedVersionCode": 3,
      "releaseNotes": ["更新体验"],
      "channels": {"oss": {"url":"https://cdn.example.com/app.apk"}, "github": {"url":"/api/update/github"}}
    }"""
        )

    @Test
    fun noDowngradeEvenWhenMinimumIsInconsistent() {
        assertNull(
            parseAppRelease(
                manifest().put("minSupportedVersionCode", 99),
                5,
                "https://example.com",
                "oss",
                false,
            )
        )
        assertNull(parseAppRelease(manifest(), 6, "https://example.com", "oss", false))
    }

    @Test
    fun choosesPreferredSourceAndKeepsFallback() {
        val release = parseAppRelease(manifest(), 4, "https://example.com", "github", false)!!
        assertEquals(listOf("github", "oss"), release.sources.map { it.id })
        assertEquals("https://example.com/api/update/github", release.sources.first().url)
        assertTrue(release.mandatory)
    }

    @Test
    fun officialSourceKeepsLegacyIdAndAcceptsSignedEdgeOneRedirect() {
        val url =
            "https://oss-eo.luoxianlv.cn/luoxianlv/release/fixture/app-release.apk?auth_key=1-2-0-abcd"
        val json = manifest()
        json.getJSONObject("channels").getJSONObject("oss").put("url", "/api/update/oss")
        val release = parseAppRelease(json, 4, "https://www.luoxianlv.cn", "oss", false)!!
        assertEquals("oss", release.sources.first().id)
        assertEquals("https://www.luoxianlv.cn/api/update/oss", release.sources.first().url)
        assertEquals(url, validatedUpdateUrl(url, release.sources.first().url, false))
    }

    @Test
    fun newerVersionIsMandatoryEvenWhenServerMarksItOptional() {
        assertTrue(
            parseAppRelease(
                    manifest().put("mandatory", false),
                    4,
                    "https://example.com",
                    "oss",
                    false,
                )!!
                .mandatory
        )
    }

    @Test
    fun minimumVersionRequiresUpdate() {
        assertTrue(parseAppRelease(manifest(), 2, "https://example.com", "oss", false)!!.mandatory)
    }

    @Test
    fun disabledReleaseIsNotOffered() {
        assertNull(
            parseAppRelease(
                manifest().put("enabled", false),
                1,
                "https://example.com",
                "oss",
                false,
            )
        )
    }

    @Test
    fun rejectsMissingChecksum() {
        assertThrows(IllegalArgumentException::class.java) {
            parseAppRelease(manifest().put("apkSha256", ""), 4, "https://example.com", "oss", false)
        }
    }

    @Test
    fun channelChecksumAndSizeOverrideTopLevel() {
        val githubSha = "b".repeat(64)
        val json =
            manifest()
                .put(
                    "channels",
                    JSONObject(
                        """{
          "oss": {"url":"https://cdn.example.com/app.apk"},
          "github": {"url":"/api/update/github", "sha256":"$githubSha", "size":200}
        }"""
                    ),
                )
        val release = parseAppRelease(json, 4, "https://example.com", "oss", false)!!
        val github = release.sources.first { it.id == "github" }
        assertEquals(githubSha, github.sha256)
        assertEquals(200L, github.size)
        // 老 manifest 渠道不带 sha256 时回退到顶层 apkSha256
        assertEquals("a".repeat(64), release.sources.first { it.id == "oss" }.sha256)
    }

    @Test
    fun releaseDisallowsCleartextAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            validatedUpdateUrl("http://cdn.example.com/app.apk", "https://example.com", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedUpdateUrl("http://127.0.0.1/a.apk", "https://example.com", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedUpdateUrl("https://token@example.com/a.apk", "https://example.com", false)
        }
        assertEquals(
            "http://10.0.2.2:8787/a.apk",
            validatedUpdateUrl("/a.apk", "http://10.0.2.2:8787", true),
        )
    }

    @Test
    fun parsesStructuredReleaseNotesAndLegacyNotes() {
        val structured =
            manifest()
                .put(
                    "releaseNotes",
                    JSONObject(
                        """
          {"sections":[{"title":"修复","items":["修复导入闪退"]},{"title":"体验","items":["新增播放诊断"]}]}
        """
                    ),
                )
        val release = parseAppRelease(structured, 4, "https://example.com", "oss", false)!!
        assertEquals(listOf("修复", "体验"), release.notes.map { it.title })
        assertEquals("修复导入闪退", release.notes[0].items.single())
        val legacy = parseAppRelease(manifest(), 4, "https://example.com", "oss", false)!!
        assertEquals("更新体验", legacy.notes.single().items.single())
    }
}
