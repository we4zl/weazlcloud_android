package io.prendie.weazlcloud

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContractTest {
    @Test
    fun authenticationFailuresIdentifyTheRejectedBoundary() {
        MockWebServer().use { server ->
            server.start()
            val api = Api(server.url("/").toString())
            for ((path, device, expected) in listOf(
                Triple("/api/login", false, "username or account password"),
                Triple("/api/v1/mobile/capabilities", false, "Account sign-in succeeded"),
                Triple("/api/v1/mobile/capabilities", true, "device credential"),
                Triple("/api/unlock", false, "vault passphrase"),
                Triple("/api/v1/devices", false, "registering this device"),
            )) {
                server.enqueue(MockResponse().setResponseCode(401).setBody("private server details"))
                val request = okhttp3.Request.Builder().url(server.url(path)).apply {
                    if (device) header("Authorization", "Bearer synthetic")
                }.build()
                val failure = assertThrows(ApiFailure::class.java) { api.execute(request) }
                assertEquals(401, failure.status)
                assertTrue(failure.message!!.contains(expected))
                assertFalse(failure.message!!.contains("private server details"))
            }
        }
    }

    @Test
    fun originRejectsCredentialAndPathInjection() {
        for (value in
            listOf(
                "https://user:pass@example.com",
                "https://example.com/api",
                "http://public.example.com",
                "https://example.com?token=secret",
                "https://example.com/#x",
            )) assertThrows(IllegalArgumentException::class.java) { Api.validateOrigin(value) }
        assertEquals("http://192.168.1.2:7272", Api.validateOrigin("http://192.168.1.2:7272"))
        assertThrows(IllegalArgumentException::class.java) {
            Api.validateOrigin("http://192.168.1.2", true)
        }
    }

    @Test
    fun bearerNeverAuthorizesAccountRoutes() {
        val api =
            Api("https://example.com").apply {
                token = "device"
                cookie = "account"
                cookieExpires = Long.MAX_VALUE
            }
        for (path in
            listOf(
                "/api/login",
                "/api/unlock",
                "/api/logout",
                "/api/uploads",
                "/api/v1/devices/d/reauthorize",
            )) assertThrows(IllegalArgumentException::class.java) { api.request(path, "POST") }
        assertNull(api.request("/api/v1/photos").header("Cookie"))
        assertNull(
            api.request("/api/login", "POST", auth = Api.Auth.ACCOUNT).header("Authorization")
        )
        assertNull(
            api.request("/.well-known/weazlcloud", auth = Api.Auth.NONE).header("Authorization")
        )
        assertEquals("1", api.request("/api/v1/photos").header("X-Weazl-Mobile-Contract"))
        assertEquals("1", api.request("/api/library/folder", "POST").header("X-Weazl-Desk"))
    }

    @Test
    fun redirectsNeverForwardTokens() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .setHeader("Location", "https://other.invalid/api")
            )
            val api = Api(server.url("/").toString()).apply { token = "synthetic" }
            assertThrows(ApiFailure::class.java) { api.call("/api/v1/photos") }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun queryAndIdentifiersAreEncodedAsData() {
        val api = Api("https://example.com").apply { token = "synthetic" }
        val request = api.request("/api/library/page", query = mapOf("path" to "photos/a & b?#"))
        assertEquals("photos/a & b?#", request.url.queryParameter("path"))
        assertEquals("one%2Ftwo%3F", Api.segment("one/two?"))
    }

    @Test
    fun receiptShapesAndStoredState() {
        assertEquals("photo", UploadContract.receipt(obj("upload" to obj("id" to "photo"))))
        assertEquals("file", UploadContract.receipt(obj("id" to "file")))
        assertTrue(UploadContract.stored(obj("state" to "stored")))
        assertFalse(UploadContract.stored(obj("status" to "verifying", "received_bytes" to 100)))
    }

    @Test
    fun emptyMissingPageStillAdvances() {
        assertEquals(
            emptyList<Int>(),
            UploadContract.missing(
                obj("missing" to arr(emptyList<Int>()), "has_more" to true, "next" to 12),
                0,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            UploadContract.missing(
                obj("missing" to arr(listOf(1)), "has_more" to true, "next" to 0),
                0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UploadContract.missing(obj("missing" to arr(listOf(-1))), 0)
        }
    }

    @Test
    fun resumedDownloadsNeverMixRevisions() {
        assertEquals(100L, UploadContract.resumeOffset(206, 100, "bytes 100-199/200", "v1", "v1"))
        assertEquals(0L, UploadContract.resumeOffset(200, 100, null, "v1", "v1"))
        assertThrows(IllegalArgumentException::class.java) {
            UploadContract.resumeOffset(206, 100, "bytes 0-99/200", "v1", "v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UploadContract.resumeOffset(206, 100, "bytes 100-199/200", "v2", "v1")
        }
    }

    @Test
    fun paginationRejectsRepeats() {
        val guard = PageGuard()
        assertTrue(guard.advance("a", true))
        assertThrows(IllegalArgumentException::class.java) { guard.advance("a", true) }
        assertFalse(guard.advance("", false))
    }

    @Test
    fun namesRejectTraversalButPreserveUnicode() {
        assertEquals("été ☁.jpg", safeName(" été ☁.jpg "))
        for (n in listOf("..", "a/b", "a\\b", "a\u0000b", "")) assertThrows(
            IllegalArgumentException::class.java
        ) {
            safeName(n)
        }
    }

    @Test
    fun sourceIdentityUsesUnambiguousLengthPrefix() {
        assertNotEquals(sourceKey("ab", "c"), sourceKey("a", "bc"))
        assertEquals(sourceKey("android", "42"), sourceKey("android", "42"))
    }

    @Test
    fun hiddenSelectionCarriesContext() {
        val selection = UploadContract.selection(listOf("a", "b"), true)
        assertEquals("hidden", selection.s("mode"))
        assertTrue(selection.o("filter").optBoolean("hidden"))
        assertEquals(listOf("a", "b"), selection.a("ids").strings())
    }

    @Test
    fun serverBodiesAreNeverEchoedInErrors() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret password"))
            val api = Api(server.url("/").toString()).apply { token = "synthetic" }
            try {
                api.call("/api/v1/photos")
                fail()
            } catch (e: ApiFailure) {
                assertFalse(e.message!!.contains("secret"))
                assertEquals(403, e.status)
            }
        }
    }

    @Test
    fun collectionRecoveryNeverChoosesBetweenDistinctTargets() {
        fun match(id: String, device: String = "old") =
            obj(
                "target_exists" to true,
                "server_id" to id,
                "current_server_revision" to 2,
                "server_revision" to 2,
                "kind" to "album",
                "device_id" to device,
                "namespace" to "android-mediastore",
                "source_id" to "source",
                "source_revision" to "v1",
            )
        assertNull(
            OrganizationRecovery.unique(
                listOf(match("one"), match("two")),
                "album",
                "current",
                "android-mediastore",
            )
        )
        val preferred =
            OrganizationRecovery.unique(
                listOf(match("one"), match("one", "current")),
                "album",
                "current",
                "android-mediastore",
            )
        assertEquals("current", preferred!!.s("device_id"))
        assertNull(
            OrganizationRecovery.unique(
                listOf(match("one")),
                "folder",
                "current",
                "android-mediastore",
            )
        )
        assertNull(
            OrganizationRecovery.unique(
                listOf(match("one").put("target_exists", false)),
                "album",
                "current",
                "android-mediastore",
            )
        )
    }

    @Test
    fun adoptionPreservesExpectedServerAndSourceRevisions() {
        val mapping =
            obj(
                "target_exists" to true,
                "device_id" to "prior",
                "namespace" to "old",
                "source_id" to "source",
                "source_revision" to "r7",
                "server_revision" to 4,
                "current_server_revision" to 5,
                "server_id" to "collection",
            )
        val body = OrganizationRecovery.adoption(mapping, "new")
        assertEquals("prior", body.s("from_device_id"))
        assertEquals("r7", body.s("from_source_revision"))
        assertEquals(4L, body.optLong("from_server_revision"))
        assertEquals(5L, body.optLong("expected_server_revision"))
        assertEquals("collection", body.s("server_id"))
        assertTrue(body.s("operation_id").startsWith("recover-"))
    }

    @Test
    fun lookupRejectsUncorrelatedAndTruncatedResults() {
        val component = obj("sha256" to "a".repeat(64), "size" to 32)
        val missing =
            obj(
                "sha256" to "a".repeat(64),
                "size" to 32,
                "exists" to false,
                "match_count" to 0,
                "has_more_matches" to false,
                "matches" to arr(emptyList<String>()),
            )
        assertNull(
            PhotoLookup.match(
                obj("generation" to 1, "results" to arr(listOf(missing))),
                component,
                false,
            )
        )
        val invalid = JSONObject(missing.toString()).put("sha256", "b".repeat(64))
        assertThrows(ApiFailure::class.java) {
            PhotoLookup.match(
                obj("generation" to 1, "results" to arr(listOf(invalid))),
                component,
                false,
            )
        }
        val hidden =
            obj(
                "asset_id" to "asset",
                "component_asset_id" to "asset",
                "component_id" to "original",
                "revision" to 3,
                "hidden" to true,
            )
        val existing =
            JSONObject(missing.toString())
                .put("exists", true)
                .put("match_count", 1)
                .put("matches", arr(listOf(hidden)))
        assertTrue(
            PhotoLookup.match(
                    obj("generation" to 1, "results" to arr(listOf(existing))),
                    component,
                    false,
                )!!
                .optBoolean("hidden")
        )
        existing.put("match_count", 2)
        assertThrows(ApiFailure::class.java) {
            PhotoLookup.match(
                obj("generation" to 1, "results" to arr(listOf(existing))),
                component,
                false,
            )
        }
    }
}
