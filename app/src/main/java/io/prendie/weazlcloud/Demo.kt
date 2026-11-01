package io.prendie.weazlcloud

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Explicit synthetic mode. Never selected as a fallback for a live connection. */
class Demo(private val context: Context, private val root: File) {
    private val disk = File(root, "demo-library.json")
    private val data: JSONObject
    private val selections = mutableMapOf<String, List<String>>()

    init {
        if (disk.exists()) data = readJson(disk)
        else {
            val fixture = JSONObject(context.assets.open("demo.json").bufferedReader().readText())
            val source = fixture.a("files").objects()
            fun path(row: JSONObject): String {
                val parent = source.firstOrNull { it.s("id") == row.s("parent") }
                return join(if (parent == null) "" else path(parent), row.s("name"))
            }
            val files = source.map {
                it.put("path", path(it))
                    .put("folder", it.s("type") == "folder")
                    .put("size", if (it.s("type") == "folder") 0 else 24576)
            }
            val photos =
                fixture.a("photos").objects().map {
                    it.put("name", it.s("name") + ".jpg")
                        .put("filename", it.s("name"))
                        .put("size", 9437184)
                        .put(
                            "captured_at",
                            it.s("captured")
                                .takeIf { date -> date.isNotBlank() }
                                ?.plus("T12:00:00Z") ?: "",
                        )
                }
            val collections =
                fixture.a("albums").objects().map { row ->
                    val kind = if (row.s("type") == "folder") "folder" else "album"
                    obj(
                        kind to
                            obj(
                                "id" to row.s("id"),
                                "title" to row.s("name"),
                                "parent_id" to
                                    row.s("parent").takeUnless { it == "root" }.orEmpty(),
                                "revision" to 1,
                                "count" to photos.count { row.s("id") in it.a("albums").strings() },
                            )
                    )
                }
            data =
                obj(
                    "files" to arr(files),
                    "photos" to arr(photos),
                    "collections" to arr(collections),
                    "grabs" to arr(emptyList<Any>()),
                )
            save()
        }
    }

    private fun save() = writeJson(disk, data)

    private fun matches(name: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val regex =
            query
                .split('*')
                .joinToString(".*") { it.split('?').joinToString(".") { s -> Regex.escape(s) } }
                .let { if ('*' !in query && '?' !in query) ".*$it.*" else it }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(name)
    }

    @Synchronized
    fun call(path: String, method: String, q: Map<String, String>, body: JSONObject?): JSONObject {
        val b = body ?: JSONObject()
        val photos = data.a("photos").objects()
        val files = data.a("files").objects()
        val collections = data.a("collections").objects()
        var result = JSONObject()
        when {
            path == "/api/quota" ->
                return obj("used" to 82543820800L, "available_bytes" to 915000000000L)
            path == "/api/v1/photos/capabilities" ->
                return obj(
                    "roots" to
                        arr(
                            listOf(
                                obj("id" to "photos-root", "name" to "Photos", "path" to "Photos")
                            )
                        )
                )
            path in listOf("/api/library/page", "/api/library/search/page", "/api/trash") -> {
                var rows = files.filter {
                    it.optBoolean("trashed") == (path == "/api/trash") &&
                        (path != "/api/library/page" ||
                            it.s("path").substringBeforeLast('/', "") == q["path"].orEmpty()) &&
                        matches(it.s("name"), q["q"].orEmpty())
                }
                rows =
                    when (q["sort"]) {
                        "size" -> rows.sortedByDescending { it.optLong("size") }
                        "modified" -> rows.sortedByDescending { it.s("modified") }
                        "type" -> rows.sortedBy { it.s("type") }
                        else ->
                            rows.sortedWith(
                                compareBy<JSONObject> { !it.optBoolean("folder") }
                                    .thenBy { it.s("name") }
                            )
                    }
                return obj((if (path == "/api/trash") "items" else "files") to arr(rows))
            }
            path == "/api/v1/files/sync" ->
                return obj(
                    "items" to
                        arr(
                            files.filter {
                                !it.optBoolean("trashed") &&
                                    (q["prefix"].isNullOrBlank() ||
                                        it.s("path") == q["prefix"] ||
                                        it.s("path").startsWith(q["prefix"] + "/"))
                            }
                        ),
                    "checkpoint" to "demo-checkpoint",
                )
            path.startsWith("/api/v1/files/") ->
                return files.first { it.s("id") == path.substringAfterLast('/') }
            path == "/api/library/folder" -> {
                require(files.none { it.s("path") == b.s("path") }) {
                    "A folder already exists here."
                }
                data
                    .a("files")
                    .put(
                        obj(
                            "id" to uuid(),
                            "path" to b.s("path"),
                            "name" to b.s("path").substringAfterLast('/'),
                            "folder" to true,
                            "revision" to "1",
                        )
                    )
            }
            path == "/api/library/rename" || path == "/api/library/copy" -> {
                require(files.none { it.s("path") == b.s("to") }) {
                    "An item already exists at this destination."
                }
                files
                    .filter {
                        it.s("path") == b.s("from") || it.s("path").startsWith(b.s("from") + "/")
                    }
                    .forEach { original ->
                        val row =
                            if (path.endsWith("copy"))
                                JSONObject(original.toString()).put("id", uuid()).also {
                                    data.a("files").put(it)
                                }
                            else original
                        val target = b.s("to") + row.s("path").removePrefix(b.s("from"))
                        row.put("path", target).put("name", target.substringAfterLast('/'))
                    }
            }
            path == "/api/library" && method == "DELETE" ->
                files
                    .filter {
                        it.s("path") == q["path"] || it.s("path").startsWith(q["path"] + "/")
                    }
                    .forEach { it.put("trashed", true) }
            path == "/api/trash/restore" ->
                files
                    .filter {
                        it.s("path") == b.s("path") || it.s("path").startsWith(b.s("path") + "/")
                    }
                    .forEach { it.put("trashed", false) }
            path in
                listOf(
                    "/api/v1/photos",
                    "/api/v1/photos/search",
                    "/api/v1/photos/seek",
                    "/api/v1/photos/trash",
                ) -> {
                val hidden = q["hidden"] == "1"
                val mode = q["mode"].orEmpty()
                val rows =
                    photos
                        .filter {
                            it.optBoolean("hidden") == hidden &&
                                it.optBoolean("trashed") == (mode == "trash") &&
                                (mode != "favorites" || it.optBoolean("favorite")) &&
                                (mode != "archived" || it.optBoolean("archived")) &&
                                (mode != "all" || !it.optBoolean("archived")) &&
                                (q["album"].isNullOrEmpty() ||
                                    q["album"] in it.a("albums").strings()) &&
                                matches(it.s("name"), q["q"].orEmpty()) &&
                                (q["at"].isNullOrEmpty() ||
                                    q["at"] == "unknown" && it.s("captured_at").isEmpty() ||
                                    it.s("captured_at").take(10) <= q["at"]!!)
                        }
                        .sortedByDescending { it.s("captured_at") }
                return obj("items" to arr(rows), "anchor_id" to rows.firstOrNull()?.s("id"))
            }
            path == "/api/v1/photos/dates" ->
                return obj(
                    "months" to
                        arr(
                            photos
                                .map { it.s("captured_at").take(7) }
                                .filter { it.isNotBlank() }
                                .distinct()
                                .sortedDescending()
                                .map { obj("month" to it) }
                        ),
                    "unknown_dates" to photos.count { it.s("captured_at").isBlank() },
                )
            path == "/api/v1/photos/collections" && method == "GET" ->
                return obj("nodes" to arr(collections))
            path == "/api/v1/photos/collections" -> {
                val kind = if (b.s("action").contains("folder")) "folder" else "album"
                val node = b.o(kind)
                if (b.s("action") == "delete-folder")
                    data.put(
                        "collections",
                        arr(collections.filter { Collection(it).id != node.s("id") }),
                    )
                else {
                    val old = collections.firstOrNull { Collection(it).id == node.s("id") }
                    if (old != null)
                        old.put(kind, node.put("revision", node.optLong("revision") + 1))
                    else
                        data
                            .a("collections")
                            .put(obj(kind to node.put("id", uuid()).put("revision", 1)))
                }
            }
            path == "/api/v1/photos/albums" -> {
                if (b.s("action") == "delete")
                    data.put(
                        "collections",
                        arr(collections.filter { Collection(it).id != b.s("id") }),
                    )
                else
                    photos.forEach { row ->
                        val ids = row.a("albums").strings().toMutableSet()
                        if (row.s("id") in b.a("add_ids").strings()) ids.add(b.s("id"))
                        if (row.s("id") in b.a("remove_ids").strings()) ids.remove(b.s("id"))
                        row.put("albums", arr(ids))
                    }
            }
            path == "/api/v1/photos/albums/memberships" ->
                return obj(
                    "asset_ids" to
                        arr(
                            photos.filter { q["id"] in it.a("albums").strings() }.map { it.s("id") }
                        )
                )
            path == "/api/v1/photos/selections" -> {
                val id = uuid()
                selections[id] =
                    if (b.has("ids")) b.a("ids").strings()
                    else
                        photos
                            .filter { b.o("filter").s("album") in it.a("albums").strings() }
                            .map { it.s("id") }
                return obj("id" to id)
            }
            path == "/api/v1/photos/selection-actions" -> {
                val ids = selections[b.s("selection_id")].orEmpty()
                photos
                    .filter { it.s("id") in ids }
                    .forEach { row ->
                        when (b.s("action")) {
                            "hide" -> row.put("hidden", true)
                            "unhide" -> row.put("hidden", false)
                            "set_archived" -> row.put("archived", true)
                            "unarchive" -> row.put("archived", false)
                            "delete" -> row.put("trashed", true)
                        }
                    }
            }
            path == "/api/v1/photos/trash/restore" ->
                photos.firstOrNull { it.s("id") == b.s("id") }?.put("trashed", false)
            path.startsWith("/api/v1/photos/assets/") -> {
                val row = photos.first { it.s("id") == path.substringAfterLast('/') }
                if (method == "POST") row.put("favorite", b.optBoolean("favorite")) else return row
            }
            path == "/api/v1/photos/duplicates" ->
                return obj("groups" to arr(emptyList<Any>()), "generation" to 1)
            path == "/api/capsules" && method == "GET" ->
                return obj(
                    "capsules" to data.a("grabs"),
                    "base" to "https://demo.weazlcloud.example",
                )
            path == "/api/capsules" && method == "DELETE" ->
                data
                    .a("grabs")
                    .objects()
                    .firstOrNull { it.s("id") == q["id"] }
                    ?.put("status", "revoked")
            path == "/api/capsules" || path == "/api/v1/photos/grabs" -> {
                val id = uuid()
                result =
                    obj(
                        "id" to id,
                        "url" to "https://demo.weazlcloud.example/g/$id",
                        "status" to "active",
                        "left" to b.optInt("grabs"),
                        "expiry" to b.s("expiry"),
                        "label" to "Phone link",
                    )
                data.a("grabs").put(result)
            }
            else -> error("This operation needs a live server connection.")
        }
        save()
        return result
    }

    fun thumbnail(item: Item): File {
        val file = File(root, "thumbnails/${item.cacheKey}.png")
        file.parentFile!!.mkdirs()
        if (!file.exists())
            context.assets.open("scene${item.wire.optInt("art")%36}.png").use { i ->
                file.outputStream().use { i.copyTo(it) }
            }
        return file
    }

    fun original(item: Item): File {
        if (item.photo || item.type == "image") return thumbnail(item)
        val file = File(root, "previews/${item.cacheKey}.${item.extension}")
        file.parentFile!!.mkdirs()
        if (!file.exists())
            when (item.type) {
                "pdf",
                "audio",
                "video" ->
                    context.assets
                        .open(
                            "Sample." +
                                when (item.type) {
                                    "pdf" -> "pdf"
                                    "audio" -> "wav"
                                    else -> "mp4"
                                }
                        )
                        .use { i -> file.outputStream().use { i.copyTo(it) } }
                else ->
                    file.writeText(
                        item.wire.s("content").ifBlank { "Synthetic demo document\n\n${item.name}" }
                    )
            }
        return file
    }
}
