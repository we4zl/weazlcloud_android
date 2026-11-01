package io.prendie.weazlcloud

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Native background code with synthetic media, credentials and a loopback-only destination. */
@RunWith(AndroidJUnit4::class)
class BackgroundBackupTest {
    private lateinit var app: CloudApplication
    private lateinit var previousRepo: Repository
    private lateinit var previousTransfers: Transfers
    private var previousCredential: JSONObject? = null
    private lateinit var root: File
    private val media = mutableListOf<Uri>()
    private val notifications = AtomicInteger()
    private val specs = ConcurrentHashMap<String, JSONObject>()
    private val received = ConcurrentHashMap<String, ByteArray>()
    private val collectionCalls = AtomicInteger()
    private val uploadIds = AtomicInteger()
    @Volatile private var failCollections = false
    private val requests = mutableListOf<String>()
    private lateinit var server: MockWebServer
    private lateinit var credential: JSONObject

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        previousRepo = app.repo
        previousTransfers = app.transfers
        previousCredential = Vault(app).load()
        server = MockWebServer()
        credential = obj(
            "instance" to "synthetic-background-${uuid()}", "owner" to "synthetic",
            "device" to "synthetic-device", "generation" to 1, "token" to "synthetic-token",
            "scopes" to arr(listOf("files:read", "files:write", "photos:read", "photos:write",
                "backup:write", "grabs:read", "grabs:write", "storage:read")),
        )
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                synchronized(requests) { requests.add(path) }
                fun json(value: JSONObject) = MockResponse()
                    .setHeader("Content-Type", "application/json").setBody(value.toString())
                return when {
                    path == "/api/v1/mobile/capabilities" -> json(obj(
                        "contract_version" to 1, "instance_id" to credential.s("instance"),
                        "owner_id" to credential.s("owner"), "device_id" to credential.s("device"),
                        "generation" to 1, "vault_unlocked" to true,
                        "features" to obj("parts_v1" to true, "auto_finalize" to true),
                        "limits" to obj("part_bytes" to UploadContract.PART),
                    ))
                    path.endsWith("/sources") -> json(obj("ok" to true))
                    path == "/api/v1/photos/lookup" -> {
                        val component = JSONObject(request.body.readUtf8()).a("items").getJSONObject(0)
                        json(obj("generation" to 1, "results" to arr(listOf(
                            obj("sha256" to component.s("sha256"), "size" to component.optLong("size"),
                                "exists" to false, "match_count" to 0, "has_more_matches" to false,
                                "matches" to arr(emptyList<String>()))))))
                    }
                    path == "/api/v1/photos/uploads" || path == "/api/v1/backups/uploads" -> {
                        val id = "%032x".format(uploadIds.incrementAndGet())
                        specs[id] = JSONObject(request.body.readUtf8())
                        json(obj("receipt" to obj("id" to id)))
                    }
                    path.contains("/components/original/parts/0") -> {
                        val id = path.substringBefore("/components").substringAfterLast('/')
                        val data = request.body.readByteArray()
                        if (sha(data) != request.getHeader("X-Weazl-SHA256"))
                            MockResponse().setResponseCode(422)
                        else { received[id] = data; json(JSONObject()) }
                    }
                    path.endsWith("/parts") -> {
                        val id = path.substringBeforeLast('/').substringAfterLast('/')
                        json(obj("missing" to arr(if (received.containsKey(id)) emptyList() else listOf(0)),
                            "has_more" to false))
                    }
                    specs.containsKey(path.substringAfterLast('/')) -> {
                        val id = path.substringAfterLast('/')
                        val stored = received.containsKey(id)
                        json(obj("transfer" to obj("id" to id, "status" to if (stored) "stored" else "uploading",
                            "result" to if (stored) obj("status" to "stored", "asset_id" to "asset-$id",
                                "revision" to 1, "file" to obj("id" to "file-$id", "revision" to 1)) else JSONObject())))
                    }
                    path == "/api/v1/photos/source-collections" || path == "/api/v1/photos/source-memberships" -> {
                        if (failCollections) return MockResponse().setResponseCode(503)
                        collectionCalls.incrementAndGet()
                        val operation = JSONObject(request.body.readUtf8()).a("operations").getJSONObject(0)
                        json(obj("outcomes" to arr(listOf(obj("operation_id" to operation.s("operation_id"),
                            "status" to "applied", "revision" to collectionCalls.get(),
                            "server_id" to "synthetic-album")))))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        credential.put("origin", server.url("/").toString())
        app.repo = Repository(app).apply {
            this.credential = JSONObject(this@BackgroundBackupTest.credential.toString())
            prefs = obj("cellular" to true, "charging" to false, "photosBackup" to false,
                "photoRoot" to "synthetic-background-destination")
            savePrefs()
        }
        root = app.repo.root
        app.transfers = Transfers(app.repo)
    }

    @After
    fun cleanup() {
        media.forEach { app.contentResolver.delete(it, null, null) }
        server.shutdown()
        root.deleteRecursively()
        if (previousCredential == null) Vault(app).clear() else Vault(app).save(previousCredential!!)
        app.repo = previousRepo
        app.transfers = previousTransfers
    }

    private fun worker(): BackupWorker = TestListenableWorkerBuilder<BackupWorker>(app)
        .setForegroundUpdater { _, _, info ->
            notifications.incrementAndGet()
            assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
            CallbackToFutureAdapter.getFuture<Void> { completer ->
                completer.set(null)
                "Synthetic background notification"
            }
        }.build()

    private fun coldRestore() {
        Vault(app).save(credential)
        app.repo = Repository(app)
        app.transfers = Transfers(app.repo)
        assertNull(app.repo.api)
        assertEquals(0, app.repo.capabilities.length())
    }

    @Test
    fun coldWorkerDiscoversPhotosAndDrainsMultipleBatchesWithoutActivity() = runBlocking {
        // No broad media permission: the app can only discover media it owns in this fixture.
        val image = app.assets.open("scene0.png").use { it.readBytes() }
        val originals = mutableMapOf<String, ByteArray>()
        repeat(35) { index ->
            val name = "synthetic-background-$index-${uuid()}.png"
            val bytes = image + byteArrayOf(index.toByte())
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WeazlCloudSyntheticBackground/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                })!!
            media.add(uri)
            app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            originals[name] = bytes
        }
        app.repo.prefs.put("photosBackup", true)
        app.repo.savePrefs()
        failCollections = true
        coldRestore()
        assertEquals(ListenableWorker.Result.success(), withTimeout(90_000) { worker().doWork() })
        val jobs = app.transfers.jobs()
        assertEquals(35, jobs.size)
        assertTrue(jobs.all { it.s("phase") == "stored" })
        assertEquals(35, received.size)
        assertEquals(1, notifications.get())
        for (job in jobs) {
            assertArrayEquals(originals[job.s("name")], received[job.s("receipt")])
            assertEquals("synthetic-background-destination", job.o("spec").s("root_id"))
            assertFalse(File(root, "staging/${job.s("id")}").exists())
            assertTrue(app.contentResolver.openInputStream(Uri.parse(job.s("sourceUri")))!!.use { it.read() } >= 0)
        }
        // A failed organization request does not undo Stored or conceal unfinished collection work.
        app.repo.prefs.put("photosBackup", false)
        app.repo.savePrefs()
        assertTrue(app.transfers.hasScheduledWork())
        assertEquals(0, collectionCalls.get())
        failCollections = false
        coldRestore()
        assertEquals(ListenableWorker.Result.success(), withTimeout(30_000) { worker().doWork() })
        assertEquals(35, specs.size)
        assertEquals(2, notifications.get())
        assertTrue(collectionCalls.get() > 0)
        assertTrue(app.transfers.organization.contains("synchronized"))
        assertEquals("/api/v1/mobile/capabilities", requests.first())
        assertFalse(requests.any { it.endsWith("/reauthorize") })
    }

    @Test
    fun pausedColdWorkerDoesNotContactServerOrChangeQueue() = runBlocking {
        app.repo.prefs.put("paused", true).put("photosBackup", true)
        app.repo.savePrefs()
        coldRestore()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, server.requestCount)
        assertEquals(0, notifications.get())
        assertTrue(app.transfers.jobs().isEmpty())
    }

    @Test
    fun disabledBackupDoesNotStartOrExpandDeviceGrants() = runBlocking {
        credential.put("scopes", arr(listOf("photos:read")))
        coldRestore()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, notifications.get())
        assertEquals(0, server.requestCount)
        assertThrows(IllegalStateException::class.java) {
            app.repo.requireWrites(allowReauthorization = false)
        }
        assertEquals(listOf("photos:read"), app.repo.credential.a("scopes").strings())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationKeepsReceiptSpecAndStagedOriginalForNextColdWorker() = runBlocking {
        app.repo.api = Api(server.url("/").toString()).apply { token = "synthetic-token" }
        app.repo.refreshAuth()
        val input = File(root, "synthetic-recovery.bin").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", input)
        app.transfers.enqueue(uri, "synthetic-background-destination", manual = false)
        val execution = launch { worker().doWork() }
        withTimeout(15_000) { while (received.isEmpty()) delay(50) }
        execution.cancelAndJoin()
        val before = app.transfers.jobs().single()
        assertEquals("uploading", before.s("phase"))
        assertTrue(File(root, "staging/${before.s("id")}/original").exists())
        val spec = before.o("spec").toString()
        coldRestore()
        assertEquals(ListenableWorker.Result.success(), withTimeout(30_000) { worker().doWork() })
        val after = app.transfers.jobs().single()
        assertEquals("stored", after.s("phase"))
        assertEquals(before.s("receipt"), after.s("receipt"))
        assertEquals(spec, after.o("spec").toString())
        assertEquals(1, specs.size)
        assertFalse(File(root, "staging/${after.s("id")}").exists())
    }
}
