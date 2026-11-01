package io.prendie.weazlcloud

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.*
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class CloudApplication : Application() {
    @Volatile var inForeground = false
    lateinit var repo: Repository
    lateinit var transfers: Transfers

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
        transfers = Transfers(repo)
    }
}

class Transfers(val repo: Repository) {
    private val mutex = Mutex()
    private val lock = Any()
    private val executionJob = ThreadLocal<Job?>()
    @Volatile var condition = ""
    @Volatile var organization = "Albums and folders have not been checked."
    @Volatile var stopped = false
    private val file
        get() = File(repo.root, "uploads.json")

    fun jobs(): List<JSONObject> = synchronized(lock) { readJson(file).a("jobs").objects() }

    private fun update(job: JSONObject) =
        synchronized(lock) {
            val all = jobs().filter { it.s("id") != job.s("id") } + job
            writeJson(file, obj("jobs" to arr(all)))
        }

    fun remaining() = jobs().count { it.s("phase") !in listOf("stored", "existing", "cancelled") }

    fun permitted(manual: Boolean): Boolean {
        executionJob.get()?.ensureActive()
        if (stopped || repo.prefs.optBoolean("paused")) {
            condition = "Transfers paused"
            return false
        }
        val cm = repo.context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            condition = "Waiting for connection"
            return false
        }
        if (
            !repo.prefs.optBoolean("cellular") &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            condition = "Waiting for Wi-Fi"
            return false
        }
        if (!manual && repo.prefs.optBoolean("charging", true)) {
            val state =
                repo.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if ((state?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) == 0) {
                condition = "Waiting for charging"
                return false
            }
        }
        condition = "Connected"
        return true
    }

    private fun stage(uri: Uri, id: String, name: String): JSONObject {
        val f = repo.capabilities.o("features")
        require(
            f.optBoolean("parts_v1") &&
                f.optBoolean("auto_finalize") &&
                repo.capabilities.o("limits").optInt("part_bytes") == UploadContract.PART
        ) {
            "The server does not advertise compatible verified parts uploads."
        }
        val dir = File(repo.root, "staging/$id").apply { mkdirs() }
        val dest = File(dir, "original")
        val budget = repo.prefs.optLong("stagingGiB", 2) * 1024 * 1024 * 1024
        val used =
            File(repo.root, "staging").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            repo.context.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        executionJob.get()?.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(
                            used + total + 128 * 1024 * 1024 < budget &&
                                repo.root.usableSpace > 256 * 1024 * 1024
                        ) {
                            "Staging is full. Wait for uploads or raise the staging limit."
                        }
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                }
            }
            return obj(
                "id" to "original",
                "filename" to safeName(name),
                "size" to total,
                "sha256" to digest.digest().joinToString("") { "%02x".format(it) },
            )
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    fun enqueue(
        uri: Uri,
        destination: String,
        name: String? = null,
        source: String? = null,
        relative: String? = null,
        manual: Boolean = true,
        revision: String? = null,
    ): Boolean {
        repo.requireWrites(allowReauthorization = manual)
        val sid = source ?: "import-${key(destination)}"
        val item = relative ?: uuid()
        if (
            revision != null &&
                jobs().any {
                    it.o("spec").s("source_id") == sid &&
                        it.o("spec").s("source_item_id") == item &&
                        it.s("localRevision") == revision &&
                        it.s("phase") != "cancelled"
                }
        )
            return false
        val filename =
            name
                ?: repo.context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: "Shared file"
        val id = uuid()
        val component = stage(uri, id, filename)
        try {
            repo.call(
                "/api/v1/backups/sources",
                "POST",
                body =
                    obj(
                        "source_id" to sid,
                        "destination_id" to destination,
                        "name" to if (source == null) "Phone imports" else "Phone folder backup",
                    ),
            )
            val spec =
                obj(
                    "source_id" to sid,
                    "source_item_id" to item,
                    "source_revision" to component.s("sha256"),
                    "relative_path" to (relative ?: filename),
                    "kind" to "file",
                    "size" to component.optLong("size"),
                    "sha256" to component.s("sha256"),
                    "mtime" to Instant.now().toString(),
                    "transport" to "parts-v1",
                    "commit_when_complete" to true,
                )
            val prior =
                jobs()
                    .lastOrNull {
                        it.o("spec").s("source_id") == sid &&
                            it.o("spec").s("source_item_id") == item &&
                            it.s("phase") == "stored"
                    }
                    ?.o("result")
                    ?.let { it.optJSONObject("file") ?: it.optJSONObject("entry") }
            if (prior != null && prior.s("id").isNotEmpty())
                spec
                    .put("expected_entry_id", prior.s("id"))
                    .put("expected_revision", prior.optLong("revision"))
            update(
                obj(
                    "id" to id,
                    "name" to filename,
                    "endpoint" to "/api/v1/backups/uploads",
                    "spec" to spec,
                    "components" to arr(listOf(component)),
                    "phase" to "queued",
                    "manual" to manual,
                    "localRevision" to revision,
                )
            )
            return true
        } catch (t: Throwable) {
            File(repo.root, "staging/$id").deleteRecursively()
            throw t
        }
    }

    private fun scanPhotos(manual: Boolean) {
        if (!repo.prefs.optBoolean("photosBackup") || !permitted(manual)) return
        val root = repo.prefs.s("photoRoot")
        require(root.isNotEmpty()) { "Choose a photo backup destination." }
        val uri = MediaStore.Files.getContentUri("external")
        val fields =
            arrayOf(
                "_id",
                "_display_name",
                "date_modified",
                "datetaken",
                "media_type",
                "bucket_display_name",
                "bucket_id",
                "relative_path",
                "generation_modified",
            )
        val projection =
            if (Build.VERSION.SDK_INT >= 29) fields else fields.dropLast(1).toTypedArray()
        val existing =
            jobs()
                .filter { it.s("phase") != "cancelled" }
                .map {
                    it.s("sourceUri") +
                        ":" +
                        it.s("localRevision") +
                        ":" +
                        it.o("spec").s("root_id") +
                        ":" +
                        it.o("spec").optBoolean("hidden")
                }
                .toSet()
        repo.context.contentResolver
            .query(
                uri,
                projection,
                "media_type=? OR media_type=?",
                arrayOf("1", "3"),
                "date_modified DESC",
            )
            ?.use { c ->
                var prepared = 0
                while (c.moveToNext()) {
                    if (!permitted(manual) || prepared >= 32) break
                    if (
                        File(repo.root, "staging")
                            .walkTopDown()
                            .filter { it.isFile }
                            .sumOf { it.length() } >
                            repo.prefs.optLong("stagingGiB", 2) * 1024 * 1024 * 1024 -
                                256 * 1024 * 1024
                    )
                        break
                    val id = c.getLong(0)
                    val source = ContentUris.withAppendedId(uri, id)
                    val revision = "${c.getLong(2)}:${c.getLong(8)}"
                    if (
                        "$source:$revision:$root:${repo.prefs.optBoolean("photoRootHidden")}" in
                            existing
                    )
                        continue
                    val name = c.getString(1) ?: "$id.bin"
                    val jobId = uuid()
                    val component = stage(source, jobId, name)
                    val spec =
                        obj(
                            "device_id" to repo.credential.s("device"),
                            "device_asset_id" to
                                sourceKey(
                                    repo.prefs.s("photoNamespace").ifBlank { "android-mediastore" },
                                    source.toString(),
                                ),
                            "source_namespace" to
                                repo.prefs.s("photoNamespace").ifBlank { "android-mediastore" },
                            "source_asset_id" to source.toString(),
                            "source_revision" to
                                key(
                                    revision +
                                        component.s("sha256") +
                                        root +
                                        repo.prefs.optBoolean("photoRootHidden")
                                ),
                            "root_id" to root,
                            "components" to arr(listOf(component)),
                            "transport" to "parts-v1",
                            "commit_when_complete" to true,
                            "offset_known" to false,
                            "hidden" to repo.prefs.optBoolean("photoRootHidden"),
                        )
                    val date = c.getLong(3)
                    if (date > 0) spec.put("captured_at", Instant.ofEpochMilli(date).toString())
                    update(
                        obj(
                            "id" to jobId,
                            "name" to name,
                            "endpoint" to "/api/v1/photos/uploads",
                            "spec" to spec,
                            "components" to arr(listOf(component)),
                            "phase" to "queued",
                            "sourceUri" to source.toString(),
                            "localRevision" to revision,
                            "bucket" to (c.getString(6) ?: ""),
                            "bucketName" to (c.getString(5) ?: "Photos"),
                            "bucketPath" to (if (c.columnCount > 7) c.getString(7) ?: "" else ""),
                            "manual" to manual,
                        )
                    )
                    prepared++
                }
            }
    }

    fun addFolder(uri: Uri, path: String) {
        val destination = repo.uploadDestination(path)
        val source =
            obj(
                "id" to uuid(),
                "uri" to uri.toString(),
                "name" to (DocumentFile.fromTreeUri(repo.context, uri)?.name ?: "Folder"),
                "destination" to destination.id,
                "destinationPath" to destination.path,
                "enabled" to true,
            )
        repo.prefs.put("folders", arr(repo.prefs.a("folders").objects() + source))
        repo.savePrefs()
        schedule()
    }

    private fun scanFolders(manual: Boolean) {
        for (source in
            repo.prefs.a("folders").objects().filter { it.optBoolean("enabled", true) }) {
            val root =
                DocumentFile.fromTreeUri(repo.context, Uri.parse(source.s("uri")))
                    ?: error("Select the folder again to renew access.")
            val sid = "folder-${source.s("id")}"
            fun walk(dir: DocumentFile, parent: String) {
                if (!permitted(manual)) return
                for (child in dir.listFiles()) {
                    if (!permitted(manual)) return
                    val path = join(parent, safeName(child.name ?: "Untitled"))
                    if (child.isDirectory) {
                        if (
                            jobs().none {
                                it.o("spec").s("source_id") == sid &&
                                    it.o("spec").s("relative_path") == path &&
                                    it.s("phase") != "cancelled"
                            }
                        ) {
                            repo.call(
                                "/api/v1/backups/sources",
                                "POST",
                                body =
                                    obj(
                                        "source_id" to sid,
                                        "destination_id" to source.s("destination"),
                                        "name" to source.s("name"),
                                    ),
                            )
                            update(
                                obj(
                                    "id" to uuid(),
                                    "name" to path,
                                    "endpoint" to "/api/v1/backups/uploads",
                                    "phase" to "queued",
                                    "manual" to manual,
                                    "components" to arr(emptyList<Any>()),
                                    "spec" to
                                        obj(
                                            "source_id" to sid,
                                            "source_item_id" to path,
                                            "source_revision" to "folder-v1",
                                            "relative_path" to path,
                                            "kind" to "folder",
                                            "size" to 0,
                                            "sha256" to "",
                                            "transport" to "parts-v1",
                                            "commit_when_complete" to true,
                                        ),
                                )
                            )
                        }
                        walk(child, path)
                    } else if (child.isFile)
                        enqueue(
                            child.uri,
                            source.s("destination"),
                            child.name,
                            sid,
                            path,
                            manual,
                            "${child.lastModified()}:${child.length()}",
                        )
                }
            }
            walk(root, "")
        }
    }

    suspend fun cycle(manual: Boolean = false, scan: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO + executionJob.asContextElement(currentCoroutineContext()[Job])) {
            if (repo.api == null || repo.demo != null || !permitted(true)) return@withContext
            if (scan) {
                try {
                    scanPhotos(manual)
                    scanFolders(manual)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    condition = e.message ?: "Backup scan needs attention"
                }
            }
            pollStoredBatch()
            coroutineScope {
                jobs()
                    .filter {
                        it.s("phase") !in listOf("stored", "existing", "cancelled", "failed") &&
                            it.optLong("retryAt") <= System.currentTimeMillis()
                    }
                    .chunked(8)
                    .firstOrNull()
                    ?.map { job -> async { advance(job) } }
                    ?.awaitAll()
            }
            if (scan)
                try {
                    syncOrganization()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    organization = e.message ?: "Album sync needs attention"
                }
        }
    }

    private fun pollStoredBatch() {
        if (!repo.capabilities.o("features").optBoolean("upload_status_batch_v1")) return
        val pending =
            jobs()
                .filter {
                    it.s("endpoint") == "/api/v1/photos/uploads" &&
                        it.s("receipt").matches(Regex("[0-9a-fA-F]{32}")) &&
                        it.s("phase") in listOf("queued", "verifying") &&
                        it.optLong("retryAt") <= System.currentTimeMillis()
                }
                .take(100)
        if (pending.isEmpty()) return
        try {
            val ids = pending.map { it.s("receipt") }
            val result =
                repo.call(
                    "/api/v1/photos/uploads/status",
                    "POST",
                    body = obj("upload_ids" to arr(ids)),
                )
            val rows = result.a("items").objects()
            require(rows.size == ids.size) { "Incomplete upload status batch." }
            rows.forEachIndexed { i, row ->
                require(row.s("upload_id") == ids[i]) { "Upload status identities did not match." }
                val transfer = row.o("transfer")
                require(
                    transfer.length() == 0 &&
                        row.s("code") in
                            listOf(
                                "not_found",
                                "staging_expired",
                                "staging_corrupt",
                                "status_unavailable",
                            ) || transfer.s("id") == ids[i] && transfer.s("transport") == "parts-v1"
                ) {
                    "Invalid upload status member."
                }
                if (transfer.s("status") == "stored")
                    require(
                        transfer.o("result").s("status") == "stored" &&
                            transfer.o("result").s("asset_id").isNotEmpty()
                    ) {
                        "Incomplete Stored result."
                    }
            }
            rows.forEachIndexed { i, row ->
                val job = pending[i]
                val transfer = row.o("transfer")
                if (transfer.length() > 0) {
                    if (!complete(job, transfer)) {
                        job.put("phase", transfer.s("status").ifEmpty { "verifying" })
                            .put("retryAt", System.currentTimeMillis() + 12000)
                        update(job)
                    }
                } else {
                    job.put(
                            "error",
                            "Server status needs recovery; the receipt and original are retained.",
                        )
                        .put(
                            "phase",
                            if (row.s("code") == "status_unavailable") "waiting" else "failed",
                        )
                        .put("retryAt", System.currentTimeMillis() + 15000)
                    update(job)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is ApiFailure && e.status in listOf(404, 405))
                repo.capabilities.o("features").put("upload_status_batch_v1", false)
            pending.forEach {
                it.put(
                    "retryAt",
                    System.currentTimeMillis() +
                        maxOf(15L, (e as? ApiFailure)?.retrySeconds ?: 0L) * 1000,
                )
                update(it)
            }
        }
    }

    private fun advance(job: JSONObject) {
        if (!permitted(job.optBoolean("manual"))) return
        val path = job.s("endpoint")
        val id = job.s("id")
        try {
            if (job.s("phase") == "cancel pending") {
                cancelJob(job)
                return
            }
            if (job.s("receipt").isEmpty()) {
                if (path.endsWith("photos/uploads") && !job.optBoolean("creationStarted")) {
                    val component = job.a("components").getJSONObject(0)
                    val leader =
                        jobs()
                            .filter { other ->
                                other.s("endpoint") == path &&
                                    other.s("phase") !in
                                        listOf(
                                            "stored",
                                            "existing",
                                            "cancelled",
                                            "failed",
                                            "cancel pending",
                                        ) &&
                                    other.a("components").objects().firstOrNull()?.s("sha256") ==
                                        component.s("sha256") &&
                                    other.o("spec").optBoolean("hidden") ==
                                        job.o("spec").optBoolean("hidden")
                            }
                            .minByOrNull { it.s("id") }
                    if (leader != null && leader.s("id") != id) {
                        job.put("phase", "waiting")
                            .put("retryAt", System.currentTimeMillis() + 10000)
                        update(job)
                        return
                    }

                    val lookup =
                        repo.call(
                            "/api/v1/photos/lookup",
                            "POST",
                            body =
                                obj(
                                    "include_hidden" to true,
                                    "items" to
                                        arr(
                                            listOf(
                                                obj(
                                                    "sha256" to component.s("sha256"),
                                                    "size" to component.optLong("size"),
                                                )
                                            )
                                        ),
                                ),
                        )
                    val hidden = job.o("spec").optBoolean("hidden")
                    var match = PhotoLookup.match(lookup, component, hidden)
                    if (hidden && match != null && !match.optBoolean("hidden")) {
                        repo.photoAction(
                            "hide",
                            listOf(Item(obj("id" to match.s("asset_id"), "hidden" to false), true)),
                        )
                        val checked =
                            repo.call(
                                "/api/v1/photos/lookup",
                                "POST",
                                body =
                                    obj(
                                        "include_hidden" to true,
                                        "items" to
                                            arr(
                                                listOf(
                                                    obj(
                                                        "sha256" to component.s("sha256"),
                                                        "size" to component.optLong("size"),
                                                    )
                                                )
                                            ),
                                    ),
                            )
                        match = PhotoLookup.match(checked, component, true)
                        require(match != null && match.optBoolean("hidden")) {
                            "The existing original still needs Hidden reconciliation."
                        }
                    }
                    if (match != null) {
                        job.put("phase", "existing")
                            .put(
                                "result",
                                obj(
                                    "asset_id" to match.s("asset_id"),
                                    "revision" to match.optLong("revision"),
                                    "status" to "existing",
                                    "hidden" to match.optBoolean("hidden"),
                                ),
                            )
                        update(job)
                        File(repo.root, "staging/$id").deleteRecursively()
                        return
                    }
                }
                job.put("creationStarted", true)
                update(job)
                val response = repo.call(path, "POST", body = job.o("spec"))
                val receipt = response.o("receipt")
                val rid = UploadContract.receipt(receipt)
                require(rid.isNotBlank()) { "The server returned no durable upload identity." }
                job.put("receipt", rid)
                    .put("creationStarted", false)
                    .put("generation", repo.credential.optLong("generation"))
                update(job)
                if (complete(job, receipt)) return
            }
            val endpoint = "$path/${Api.segment(job.s("receipt"))}"
            if (job.optLong("generation") != repo.credential.optLong("generation")) {
                val receipt = repo.call(path, "POST", body = job.o("spec")).o("receipt")
                require(UploadContract.receipt(receipt) == job.s("receipt")) {
                    "The server changed the upload identity."
                }
                job.put("generation", repo.credential.optLong("generation"))
                update(job)
            }
            val status = repo.call(endpoint)
            val transfer = status.optJSONObject("transfer") ?: status
            if (complete(job, transfer)) return
            if (transfer.s("status") == "failed" || transfer.s("error_code").isNotEmpty())
                throw ApiFailure(422)
            job.put("phase", transfer.s("status").ifEmpty { "uploading" })
                .put(
                    "received",
                    transfer.a("components").objects().sumOf { it.optLong("received_bytes") },
                )
            update(job)
            if (transfer.s("status") in listOf("queued", "verifying")) {
                job.put("retryAt", System.currentTimeMillis() + 12000)
                update(job)
                return
            }
            var submitted = 0
            for (component in job.a("components").objects()) {
                if (
                    component.optLong("size") == 0L ||
                        transfer.a("components").objects().any {
                            it.s("id") == component.s("id") &&
                                it.optLong("received_bytes") >= component.optLong("size")
                        }
                )
                    continue
                var cursor = 0L
                do {
                    if (!permitted(job.optBoolean("manual"))) return
                    val missing =
                        repo.call(
                            "$endpoint/parts",
                            query =
                                mapOf(
                                    "component" to component.s("id"),
                                    "cursor" to cursor.toString(),
                                    "limit" to "8",
                                ),
                        )
                    val parts = UploadContract.missing(missing, cursor)
                    for (index in parts) {
                        if (submitted >= 8 || !permitted(job.optBoolean("manual"))) return
                        val offset = index.toLong() * UploadContract.PART
                        val size =
                            minOf(UploadContract.PART.toLong(), component.optLong("size") - offset)
                        require(size > 0) { "Invalid part index." }
                        val original = File(repo.root, "staging/$id/${component.s("id")}")
                        require(original.length() == component.optLong("size")) {
                            "The staged original is unavailable."
                        }
                        val data = ByteArray(size.toInt())
                        RandomAccessFile(original, "r").use {
                            it.seek(offset)
                            it.readFully(data)
                        }
                        val request =
                            repo.api!!.request(
                                "$endpoint/components/${component.s("id")}/parts/$index",
                                "PUT",
                                body = data.toRequestBody("application/octet-stream".toMediaType()),
                                headers = mapOf("X-Weazl-SHA256" to sha(data)),
                            )
                        repo.api!!.execute(request).use { r ->
                            val raw = r.body?.string().orEmpty()
                            if (raw.isNotBlank()) {
                                val response = JSONObject(raw)
                                val t = response.optJSONObject("transfer") ?: response
                                if (complete(job, t)) return
                            }
                        }
                        submitted++
                        job.put("sent", job.optLong("sent") + size)
                        update(job)
                    }
                    cursor = missing.optLong("next")
                } while (missing.optBoolean("has_more") && submitted < 8)
            }
            job.put("retryAt", System.currentTimeMillis() + 5000)
            update(job)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val code = (e as? ApiFailure)?.status ?: 0
            if (job.s("phase") == "cancel pending") {
                job.put(
                    "error",
                    "Cancellation is not confirmed. The original and receipt are retained.",
                )
                update(job)
                return
            }
            if (code == 410) {
                job.put("previousReceipt", job.s("receipt"))
                    .put("receipt", "")
                    .put("phase", "waiting")
                    .put("retryAt", System.currentTimeMillis() + 2000)
                update(job)
                return
            }
            val attempts = job.optInt("attempts") + 1
            job.put(
                "error",
                if (e is ApiFailure) e.message
                else if (e is java.io.IOException)
                    "Connection interrupted. Retry will resume accepted parts."
                else e.message ?: "Transfer needs attention",
            )
            val retry =
                code == 0 && e is java.io.IOException ||
                    code in listOf(401, 423, 429, 500, 502, 503, 504, 507)
            job.put("phase", if (retry) "waiting" else "failed")
                .put("attempts", attempts)
                .put(
                    "retryAt",
                    System.currentTimeMillis() +
                        maxOf(
                            (e as? ApiFailure)?.retrySeconds ?: 0,
                            minOf(300L, 2L shl minOf(attempts, 7)),
                        ) * 1000,
                )
            update(job)
        }
    }

    private fun complete(job: JSONObject, value: JSONObject): Boolean {
        if (!UploadContract.stored(value)) return false
        val result = value.optJSONObject("result") ?: value
        require(
            if (job.s("endpoint").contains("photos"))
                result.s("asset_id").isNotEmpty() && result.optLong("revision") > 0
            else
                result.o("file").s("id").isNotEmpty() ||
                    result.o("entry").s("id").isNotEmpty() ||
                    result.s("entry_id").isNotEmpty()
        ) {
            "Incomplete stored receipt."
        }
        job.put("phase", "stored")
            .put("result", result)
            .put("received", job.a("components").objects().sumOf { it.optLong("size") })
            .remove("error")
        update(job)
        File(repo.root, "staging/${job.s("id")}").deleteRecursively()
        return true
    }

    suspend fun retryAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            jobs()
                .filter { it.s("phase") in listOf("failed", "waiting", "cancel pending") }
                .forEach { job ->
                    if (job.s("receipt").isNotBlank())
                        try {
                            repo.call(
                                job.s("endpoint") + "/${Api.segment(job.s("receipt"))}/retry",
                                "POST",
                            )
                        } catch (e: ApiFailure) {
                            if (e.status == 410) job.put("receipt", "")
                        }
                    job.put("phase", "queued").put("retryAt", 0).remove("error")
                    update(job)
                }
        }
    }

    suspend fun cancelAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            jobs()
                .filter { it.s("phase") !in listOf("stored", "existing", "cancelled") }
                .forEach {
                    it.put("phase", "cancel pending")
                    update(it)
                    runCatching { cancelJob(it) }
                }
        }
    }

    private fun cancelJob(job: JSONObject) {
        if (job.s("receipt").isEmpty() && job.optBoolean("creationStarted")) {
            val r = repo.call(job.s("endpoint"), "POST", body = job.o("spec")).o("receipt")
            job.put("receipt", UploadContract.receipt(r))
            update(job)
            if (complete(job, r)) return
        }
        if (job.s("receipt").isNotBlank()) {
            val result =
                try {
                    repo.call(job.s("endpoint") + "/${Api.segment(job.s("receipt"))}", "DELETE")
                } catch (e: ApiFailure) {
                    if (e.status != 409) throw e
                    val status = repo.call(job.s("endpoint") + "/${Api.segment(job.s("receipt"))}")
                    if (complete(job, status.optJSONObject("transfer") ?: status)) return
                    throw e
                }
            if (complete(job, result.optJSONObject("transfer") ?: result)) return
        }
        job.put("phase", "cancelled")
        update(job)
        File(repo.root, "staging/${job.s("id")}").deleteRecursively()
    }

    private fun syncOrganization() {
        val file = File(repo.root, "organization.json")
        val state = readJson(file)
        fun send(intent: JSONObject) {
            executionJob.get()?.ensureActive()
            val operation = intent.o("operation")
            val result =
                repo
                    .call(
                        intent.s("endpoint"),
                        "POST",
                        body = obj("operations" to arr(listOf(operation))),
                    )
                    .a("outcomes")
                    .objects()
                    .firstOrNull() ?: error("Album sync returned no outcome.")
            require(
                result.s("operation_id") == operation.s("operation_id") &&
                    result.s("status") == "applied" &&
                    result.optLong("revision") > 0
            ) {
                "Album sync needs conflict recovery. Stored originals are retained."
            }
            val source = operation.s("source_id")
            val mapping =
                state
                    .o(source)
                    .put("revision", result.optLong("revision"))
                    .put("server_id", result.s("server_id"))
            if (operation.has("add_ids"))
                mapping.put(
                    "members",
                    arr(
                        (mapping.a("members").strings() + operation.a("add_ids").strings())
                            .distinct()
                    ),
                )
            else
                mapping
                    .put("title", operation.s("title"))
                    .put("parent", operation.s("parent_source_id"))
                    .put("source_revision", operation.s("source_revision"))
            state.put(source, mapping)
            state.put("_checked", arr((state.a("_checked").strings() + source).distinct()))
            state.remove("pending")
            writeJson(file, state)
        }
        val grouped =
            jobs()
                .filter {
                    it.s("bucket").isNotEmpty() && it.s("phase") in listOf("stored", "existing")
                }
                .groupBy { it.s("bucket") }
        val sources = linkedMapOf<String, String>()
        for ((bucket, group) in grouped) {
            val path = group.first().s("bucketPath").trim('/').substringBeforeLast('/', "")
            var parent = ""
            for (segment in path.split('/').filter { it.isNotBlank() }) {
                parent = join(parent, segment)
                sources[sourceKey("android-mediastore-folder", parent)] = "folder"
            }
            sources[sourceKey("android-mediastore-bucket", bucket)] = "album"
        }
        OrganizationRecovery.prepare(
            repo,
            state,
            file,
            sources,
            repo.prefs.s("photoNamespace").ifBlank { "android-mediastore" },
        )
        state.optJSONObject("pending")?.let(::send)
        fun upsert(source: String, name: String, kind: String, parent: String) {
            if (state.o(source).s("source_revision") == key("$name:$parent:$kind")) {
                state.o(source).put("title", name).put("parent", parent)
                writeJson(file, state)
                return
            }
            if (state.o(source).s("title") == name && state.o(source).s("parent") == parent) return
            val operation =
                obj(
                    "namespace" to repo.prefs.s("photoNamespace").ifBlank { "android-mediastore" },
                    "source_id" to source,
                    "source_revision" to key("$name:$parent:$kind"),
                    "kind" to kind,
                    "parent_source_id" to parent,
                    "title" to name,
                    "position" to 0,
                    "expected_revision" to state.o(source).optLong("revision"),
                    "operation_id" to uuid(),
                )
            val intent =
                obj("endpoint" to "/api/v1/photos/source-collections", "operation" to operation)
            state.put("pending", intent)
            writeJson(file, state)
            send(intent)
        }
        fun ensureFolder(path: String): String {
            if (path.isBlank()) return ""
            val parent = ensureFolder(path.substringBeforeLast('/', ""))
            val id = sourceKey("android-mediastore-folder", path)
            upsert(id, path.substringAfterLast('/'), "folder", parent)
            return id
        }
        for ((bucket, group) in grouped.toSortedMap()) {
            executionJob.get()?.ensureActive()
            val name = group.first().s("bucketName")
            val source = sourceKey("android-mediastore-bucket", bucket)
            val parentPath = group.first().s("bucketPath").trim('/').substringBeforeLast('/', "")
            val parent = ensureFolder(parentPath)
            upsert(source, name, "album", parent)
            val known = state.o(source).a("members").strings().toSet()
            group
                .map { it.o("result").s("asset_id") }
                .filter { it.isNotBlank() && it !in known }
                .distinct()
                .chunked(200)
                .forEach { members ->
                    val operation =
                        obj(
                            "namespace" to
                                repo.prefs.s("photoNamespace").ifBlank { "android-mediastore" },
                            "source_id" to source,
                            "source_revision" to key(members.joinToString("|")),
                            "kind" to "album",
                            "title" to name,
                            "expected_revision" to state.o(source).optLong("revision"),
                            "operation_id" to uuid(),
                            "add_ids" to arr(members),
                        )
                    val intent =
                        obj(
                            "endpoint" to "/api/v1/photos/source-memberships",
                            "operation" to operation,
                        )
                    state.put("pending", intent)
                    writeJson(file, state)
                    send(intent)
                }
        }
        organization = "${grouped.size} device albums synchronized"
    }

    fun schedule() {
        val manager = WorkManager.getInstance(repo.context)
        if (repo.demo != null || repo.prefs.optBoolean("paused") ||
            !hasScheduledWork()) {
            manager.cancelUniqueWork("weazlcloud-backup")
            return
        }
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (repo.prefs.optBoolean("cellular")) NetworkType.CONNECTED
                    else NetworkType.UNMETERED
                )
                .setRequiresCharging(repo.prefs.optBoolean("charging", true))
                .build()
        val request =
            PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
        manager
            .enqueueUniquePeriodicWork(
                "weazlcloud-backup",
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun hasBackupSources() = repo.prefs.optBoolean("photosBackup") ||
        repo.prefs.a("folders").objects().any { it.optBoolean("enabled", true) }

    fun hasScheduledWork(): Boolean {
        if (hasBackupSources() || pendingJobs().isNotEmpty()) return true
        // Original storage can finish while collection requests still need replay.
        val state = readJson(File(repo.root, "organization.json"))
        if (state.has("pending") || state.has("_adoption")) return true
        return jobs().any {
            it.s("bucket").isNotEmpty() && it.s("phase") in listOf("stored", "existing") &&
                it.o("result").s("asset_id").let { asset ->
                    asset.isNotEmpty() && asset !in
                        state.o(sourceKey("android-mediastore-bucket", it.s("bucket")))
                            .a("members").strings()
                }
        }
    }

    private fun pendingJobs() = jobs().filter {
        it.s("phase") !in listOf("stored", "existing", "cancelled", "failed")
    }

    /** Drain successive upload batches, rescan for the next 32 photos, then reconcile collections.
     * The worker owns the execution window; cancellation leaves specs, receipts and bytes intact.
     */
    suspend fun runScheduledBackup() {
        do {
            currentCoroutineContext().ensureActive()
            if (repo.demo != null || !permitted(false)) return
            cycle(scan = true)
            if (pendingJobs().isEmpty()) return
            // Respect receipt polling/backoff without keeping the UI or a busy loop alive.
            delay(5000)
        } while (true)
    }

    fun start(manual: Boolean = true) {
        stopped = false
        schedule()
        ContextCompat.startForegroundService(
            repo.context,
            Intent(repo.context, TransferService::class.java).putExtra("manual", manual),
        )
    }

    suspend fun stop() {
        stopped = true
        mutex.withLock {}
        repo.context.stopService(Intent(repo.context, TransferService::class.java))
        WorkManager.getInstance(repo.context).cancelUniqueWork("weazlcloud-backup")
    }
}

class TransferService : Service() {
    @Volatile private var manualRequested = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "transfers",
                "Backup and transfers",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(this, "transfers")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("WeazlCloud")
                .setContentText("Backing up and verifying originals")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, notification)
        scope.launch {
            val app = application as CloudApplication
            try {
                var tick = 0
                do {
                    app.transfers.cycle(manual = manualRequested, scan = tick++ % 4 == 0)
                    delay(5000)
                } while (
                    isActive &&
                        !app.transfers.stopped &&
                        !app.repo.prefs.optBoolean("paused") &&
                        (app.transfers.remaining() > 0 || tick < 5)
                )
            } finally {
                stopSelf()
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        manualRequested = manualRequested || intent?.getBooleanExtra("manual", false) == true
        return START_NOT_STICKY
    }
}
