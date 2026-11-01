package io.prendie.weazlcloud

import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferIntegrationTest {
    @Test
    fun verifiedPartsSurviveEngineRestartAndOnlyStoredCleansBytes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<CloudApplication>()
        val repo = Repository(context)
        repo.credential =
            obj(
                "instance" to "test-${uuid()}",
                "owner" to "synthetic",
                "device" to "device",
                "generation" to 1,
                "scopes" to
                    arr(
                        listOf(
                            "files:read",
                            "files:write",
                            "photos:read",
                            "photos:write",
                            "backup:write",
                            "grabs:read",
                            "grabs:write",
                            "storage:read",
                        )
                    ),
            )
        repo.capabilities =
            obj(
                "features" to obj("parts_v1" to true, "auto_finalize" to true),
                "limits" to obj("part_bytes" to UploadContract.PART),
            )
        repo.prefs = obj("cellular" to true, "charging" to false)
        val input =
            File(repo.root, "synthetic.bin").apply {
                writeBytes(ByteArray(UploadContract.PART + 17) { (it % 251).toByte() })
            }
        val originalHash = sha(input.readBytes())
        val parts = mutableMapOf<Int, ByteArray>()
        var stored = false
        var createSpec = ""
        var creates = 0
        MockWebServer().use { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.requestUrl!!.encodedPath
                        fun json(value: JSONObject) =
                            MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(value.toString())
                        return when {
                            path.endsWith("/sources") -> json(obj("ok" to true))
                            path == "/api/v1/backups/uploads" -> {
                                creates++
                                createSpec = request.body.readUtf8()
                                json(obj("receipt" to obj("id" to "receipt")))
                            }
                            path.endsWith("/receipt") ->
                                json(
                                    obj(
                                        "transfer" to
                                            obj(
                                                "id" to "receipt",
                                                "status" to
                                                    if (stored) "stored"
                                                    else if (parts.size == 2) "verifying"
                                                    else "uploading",
                                                "components" to
                                                    arr(
                                                        listOf(
                                                            obj(
                                                                "id" to "original",
                                                                "received_bytes" to
                                                                    parts.values.sumOf { it.size },
                                                            )
                                                        )
                                                    ),
                                                "result" to
                                                    if (stored)
                                                        obj(
                                                            "status" to "stored",
                                                            "file" to
                                                                obj(
                                                                    "id" to "server-file",
                                                                    "revision" to 1,
                                                                ),
                                                        )
                                                    else JSONObject(),
                                            )
                                    )
                                )
                            path.endsWith("/parts") ->
                                json(
                                    obj(
                                        "missing" to arr((0..1).filter { it !in parts }),
                                        "has_more" to false,
                                    )
                                )
                            path.contains("/components/original/parts/") -> {
                                val data = request.body.readByteArray()
                                if (sha(data) != request.getHeader("X-Weazl-SHA256"))
                                    MockResponse().setResponseCode(422)
                                else {
                                    parts[path.substringAfterLast('/').toInt()] = data
                                    json(JSONObject())
                                }
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
            server.start()
            repo.api = Api(server.url("/").toString()).apply { token = "synthetic-device-token" }
            val transfers = Transfers(repo)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", input)
            transfers.enqueue(uri, "destination", "synthetic.bin")
            transfers.cycle(manual = true)
            assertEquals(2, parts.size)
            assertEquals(originalHash, sha(parts[0]!! + parts[1]!!))
            val job = transfers.jobs().single()
            assertEquals("receipt", job.s("receipt"))
            assertTrue(File(repo.root, "staging/${job.s("id")}/original").exists())
            assertNotEquals("stored", job.s("phase"))
            val before = JSONObject(createSpec)
            assertEquals(originalHash, before.s("sha256"))
            assertEquals("parts-v1", before.s("transport"))
            assertEquals(1, creates)
            stored = true
            val restarted = Transfers(repo)
            // Expire the scheduled poll delay; persisted receipt/spec remain intact.
            val saved = readJson(File(repo.root, "uploads.json"))
            saved.a("jobs").getJSONObject(0).put("retryAt", 0)
            writeJson(File(repo.root, "uploads.json"), saved)
            restarted.cycle(manual = true)
            assertEquals("stored", restarted.jobs().single().s("phase"))
            assertEquals(1, creates)
            assertFalse(File(repo.root, "staging/${job.s("id")}").exists())
        }
        repo.root.deleteRecursively()
        Unit
    }

    @Test
    fun encryptedCredentialsAndPreferencesRestoreWithoutPasswords() {
        val context = ApplicationProvider.getApplicationContext<CloudApplication>()
        val vault = Vault(context)
        val previous = vault.load()
        try {
            val synthetic =
                obj(
                    "token" to "SYNTHETIC-DEVICE-TOKEN",
                    "cookie" to "synthetic-cookie",
                    "owner" to "test",
                )
            vault.save(synthetic)
            assertEquals(synthetic.toString(), vault.load()!!.toString())
            val raw =
                File(context.noBackupFilesDir, "credential")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("SYNTHETIC-DEVICE-TOKEN"))
            assertFalse(vault.load()!!.has("password"))
        } finally {
            if (previous == null) vault.clear() else vault.save(previous)
        }
    }
}
