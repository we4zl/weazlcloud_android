package io.prendie.weazlcloud

import android.content.Context
import java.io.File
import org.json.JSONObject

class Repository(val context: Context) {
    val vault = Vault(context)
    @Volatile var credential = JSONObject()
    @Volatile var api: Api? = null
    @Volatile var capabilities = JSONObject()
    @Volatile var demo: Demo? = null
    val root: File
        get() =
            File(
                    context.filesDir,
                    "accounts/" +
                        if (demo != null) "demo"
                        else key(credential.s("instance") + ":" + credential.s("owner")),
                )
                .apply { mkdirs() }

    val prefFile
        get() = File(root, "preferences.json")

    @Volatile var prefs = JSONObject()

    fun savePrefs() = writeJson(prefFile, prefs)

    fun call(
        path: String,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        auth: Api.Auth = Api.Auth.DEVICE,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject =
        demo?.call(path, method, query, body)
            ?: requireNotNull(api).call(path, method, query, body, auth, headers)

    @Synchronized
    fun restoreIfNeeded(): Boolean = api != null || restore()

    private fun restore(): Boolean {
        credential = vault.load() ?: return false
        api =
            Api(credential.s("origin"), credential.s("remote")).apply {
                token = credential.s("token")
                cookie = credential.s("cookie")
                cookieExpires = credential.optLong("cookieExpires")
            }
        prefs = readJson(prefFile)
        QueueReset.recover(this)
        val replacement = credential.s("replacement")
        if (replacement.isNotEmpty()) {
            api!!.token = replacement
            try {
                val c = call("/api/v1/mobile/capabilities")
                checkIdentity(c)
                credential.put("token", replacement).remove("replacement")
                vault.save(credential)
            } catch (e: Exception) {
                api!!.token = credential.s("token")
            }
        }
        return true
    }

    fun startDemo() {
        demo = Demo(context, File(context.filesDir, "accounts/demo"))
        prefs = readJson(prefFile)
        QueueReset.recover(this)
        capabilities = obj("vault_unlocked" to true, "profile" to obj("full_name" to "Alex Morgan"))
    }

    fun login(address: String, username: String, password: String) {
        val origin = Api.validateOrigin(address, true)
        val a = Api(origin)
        val discovery = a.discover()
        a.call(
            "/api/login",
            "POST",
            body = obj("username" to username, "password" to password),
            auth = Api.Auth.ACCOUNT,
        )
        val caps = a.call("/api/v1/mobile/capabilities", auth = Api.Auth.ACCOUNT)
        require(
            caps.s("instance_id") == discovery.s("instance_id") && caps.s("owner_id").isNotEmpty()
        ) {
            "The server identity changed during sign-in."
        }
        api = a
        demo = null
        val old = vault.load()
        credential =
            if (old?.s("instance") == caps.s("instance_id") && old.s("owner") == caps.s("owner_id"))
                old
            else JSONObject()
        credential
            .put("origin", origin)
            .put("remote", origin)
            .put("username", username)
            .put("instance", caps.s("instance_id"))
            .put("owner", caps.s("owner_id"))
        capabilities = caps
        if (!caps.optBoolean("vault_unlocked")) {
            try {
                a.call(
                    "/api/unlock",
                    "POST",
                    body = obj("passphrase" to password),
                    auth = Api.Auth.ACCOUNT,
                )
            } catch (e: ApiFailure) {
                capabilities.put("vault_unlocked", false)
                throw ApiFailure(423)
            }
        }
        enroll()
    }

    fun unlock(passphrase: String) {
        call("/api/unlock", "POST", body = obj("passphrase" to passphrase), auth = Api.Auth.ACCOUNT)
        if (api!!.token.isEmpty()) enroll() else refreshAuth()
    }

    private fun enroll() {
        val a = api!!
        val scopes =
            credential.a("scopes").strings().ifEmpty {
                listOf("files:read", "photos:read", "storage:read")
            }
        if (credential.s("device").isEmpty()) {
            val result =
                a.call(
                    "/api/v1/devices",
                    "POST",
                    body = obj("name" to "WeazlCloud Android", "scopes" to arr(scopes)),
                    auth = Api.Auth.ACCOUNT,
                )
            require(result.s("token").isNotEmpty() && result.o("device").s("id").isNotEmpty())
            credential
                .put("token", result.s("token"))
                .put("device", result.o("device").s("id"))
                .put("generation", result.o("device").optLong("generation"))
                .put("expires", result.o("device").s("expires_at"))
        } else {
            a.token = credential.s("token")
            reauthorize(scopes)
        }
        credential.put("scopes", arr(scopes))
        persistCredentials()
        a.token = credential.s("token")
        prefs = readJson(prefFile)
        QueueReset.recover(this)
        refreshAuth()
    }

    fun persistCredentials() {
        api?.let { credential.put("cookie", it.cookie).put("cookieExpires", it.cookieExpires) }
        vault.save(credential)
    }

    private fun checkIdentity(c: JSONObject) {
        require(
            c.optInt("contract_version") == 1 &&
                c.s("instance_id") == credential.s("instance") &&
                c.s("owner_id") == credential.s("owner") &&
                c.s("device_id") == credential.s("device")
        ) {
            "The saved device does not match this account."
        }
    }

    fun refreshAuth() {
        if (demo != null) return
        val c = call("/api/v1/mobile/capabilities")
        checkIdentity(c)
        capabilities = c
        credential.put("generation", c.optLong("generation")).put("expires", c.s("expires_at"))
        if (c.has("grants")) credential.put("scopes", c.a("grants"))
        persistCredentials()
    }

    @Synchronized
    fun requireWrites(allowReauthorization: Boolean = true) {
        if (demo != null) return
        val scopes =
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
        if (!credential.a("scopes").strings().containsAll(scopes)) {
            check(allowReauthorization) { "Open WeazlCloud to authorize backup on this device." }
            reauthorize(scopes)
        }
    }

    private fun reauthorize(scopes: List<String>) {
        val replacement = key(uuid() + uuid())
        credential.put("replacement", replacement)
        persistCredentials()
        val result =
            call(
                "/api/v1/devices/${Api.segment(credential.s("device"))}/reauthorize",
                "POST",
                body = obj("replacement_token" to replacement, "scopes" to arr(scopes)),
                auth = Api.Auth.ACCOUNT,
            )
        credential
            .put("token", replacement)
            .put("scopes", arr(scopes))
            .put("generation", result.o("device").optLong("generation"))
            .put("expires", result.o("device").s("expires_at"))
            .remove("replacement")
        api!!.token = replacement
        persistCredentials()
    }

    @Synchronized
    fun rotate() {
        if (demo != null) return
        val intent =
            credential.optJSONObject("rotation")
                ?: obj(
                        "operation_id" to uuid(),
                        "expected_generation" to credential.optLong("generation"),
                        "replacement_token" to key(uuid() + uuid()),
                    )
                    .also {
                        credential.put("rotation", it).put("replacement", it.s("replacement_token"))
                        persistCredentials()
                    }
        val result =
            call(
                "/api/v1/devices/${Api.segment(credential.s("device"))}/rotate",
                "POST",
                body = intent,
            )
        credential
            .put("token", intent.s("replacement_token"))
            .put("generation", result.optLong("generation"))
        credential.remove("rotation")
        credential.remove("replacement")
        api!!.token = credential.s("token")
        persistCredentials()
    }

    fun setRoute(home: String?, useHome: Boolean) {
        if (demo != null) return
        val value =
            if (useHome) Api.validateOrigin(home ?: credential.s("home"))
            else credential.s("remote")
        val probe = Api(value)
        require(probe.discover().s("instance_id") == credential.s("instance")) {
            "This address identifies a different server."
        }
        if (useHome) credential.put("home", value)
        api!!.origin = value
        credential.put("origin", value)
        persistCredentials()
    }

    fun signOut() {
        if (demo == null) {
            runCatching { call("/api/logout", "POST", auth = Api.Auth.ACCOUNT) }
            vault.clear()
        }
        root.deleteRecursively()
        api = null
        credential = JSONObject()
        demo = null
        prefs = JSONObject()
    }

    fun files(
        path: String,
        query: String,
        sort: String,
        trash: Boolean,
        cursor: String = "",
    ): JSONObject {
        val q =
            mapOf(
                "path" to path,
                "scope" to path,
                "q" to query,
                "limit" to "100",
                "sort" to sort,
                "cursor" to cursor,
            )
        return cached("files:$path:$query:$sort:$trash:$cursor") {
            call(
                if (trash) "/api/trash"
                else if (query.isBlank()) "/api/library/page" else "/api/library/search/page",
                query = q,
            )
        }
    }

    fun photos(
        mode: String,
        album: String,
        query: String,
        cursor: String = "",
        seek: String = "",
        navigation: Boolean = false,
    ): JSONObject {
        val endpoint =
            if (mode == "trash") "/api/v1/photos/trash"
            else if (seek.isNotBlank() || navigation) "/api/v1/photos/seek"
            else if (query.isBlank()) "/api/v1/photos" else "/api/v1/photos/search"
        return cached("photos:$mode:$album:$query:$cursor:$seek") {
            call(
                endpoint,
                query =
                    mapOf(
                        "mode" to mode,
                        "album" to album,
                        "q" to query,
                        "hidden" to if (mode == "hidden") "1" else "0",
                        "favorite" to if (mode == "favorites") "1" else "0",
                        "archived" to if (mode == "archived") "1" else "0",
                        "cursor" to cursor,
                        "at" to seek,
                        "limit" to "100",
                    ),
            )
        }
    }

    private fun cached(id: String, block: () -> JSONObject): JSONObject {
        val file = File(root, "listings/${key(id)}.json")
        try {
            return block().also { writeJson(file, it) }
        } catch (e: java.io.IOException) {
            if (file.exists()) return readJson(file).put("offline", true)
            throw e
        }
    }

    fun collections(hidden: Boolean): List<Collection> {
        val rows = mutableListOf<Collection>()
        var cursor = ""
        val guard = PageGuard()
        do {
            val p =
                call(
                    "/api/v1/photos/collections",
                    query =
                        mapOf(
                            "hidden" to if (hidden) "1" else "0",
                            "limit" to "200",
                            "cursor" to cursor,
                        ),
                )
            rows += p.a("nodes").objects().map(::Collection)
            cursor = p.s("next")
        } while (guard.advance(cursor, p.optBoolean("has_more")))
        return rows
    }

    fun fileAction(action: String, items: List<Item>, value: String = "", path: String = "") {
        requireWrites()
        if (action == "folder") {
            call("/api/library/folder", "POST", body = obj("path" to join(path, safeName(value))))
            return
        }
        for (item in items) when (action) {
            "rename",
            "move",
            "copy" ->
                call(
                    if (action == "copy") "/api/library/copy" else "/api/library/rename",
                    "POST",
                    body =
                        obj(
                            "from" to item.path,
                            "to" to
                                if (action == "rename")
                                    join(item.path.substringBeforeLast('/', ""), safeName(value))
                                else join(value, item.name),
                        ),
                )
            "trash" -> call("/api/library", "DELETE", query = mapOf("path" to item.path))
            "restore" -> call("/api/trash/restore", "POST", body = obj("path" to item.path))
        }
    }

    fun photoAction(action: String, items: List<Item>) {
        requireWrites()
        val hidden = items.firstOrNull()?.hidden ?: false
        when (action) {
            "favorite",
            "unfavorite" ->
                items.forEach {
                    call(
                        "/api/v1/photos/assets/${Api.segment(it.id)}",
                        "POST",
                        query = mapOf("hidden" to if (it.hidden) "1" else "0"),
                        body = obj("favorite" to (action == "favorite")),
                    )
                }
            "restore" ->
                items.forEach {
                    call(
                        "/api/v1/photos/trash/restore",
                        "POST",
                        body = obj("id" to it.id),
                        query = mapOf("hidden" to if (it.hidden) "1" else "0"),
                    )
                }
            else -> {
                val selection =
                    call(
                        "/api/v1/photos/selections",
                        "POST",
                        body = UploadContract.selection(items.map { it.id }, hidden),
                    )
                require(selection.s("id").isNotEmpty())
                call(
                    "/api/v1/photos/selection-actions",
                    "POST",
                    body =
                        obj(
                            "selection_id" to selection.s("id"),
                            "action" to action,
                            "hidden" to hidden,
                        ),
                )
            }
        }
    }

    fun saveCollection(name: String, parent: String, kind: String, existing: Collection? = null) {
        requireWrites()
        val node = obj("title" to safeName(name), "parent_id" to parent)
        existing?.let { node.put("id", it.id).put("revision", it.revision) }
        call(
            "/api/v1/photos/collections",
            "POST",
            body =
                obj(
                    "action" to if (kind == "folder") "save-folder" else "save-album",
                    kind to node,
                    "parent_id" to parent,
                ),
        )
    }

    fun deleteCollection(c: Collection) {
        requireWrites()
        call(
            if (c.kind == "folder") "/api/v1/photos/collections" else "/api/v1/photos/albums",
            "POST",
            body =
                if (c.kind == "folder")
                    obj(
                        "action" to "delete-folder",
                        "folder" to obj("id" to c.id, "revision" to c.revision),
                    )
                else obj("action" to "delete", "id" to c.id, "revision" to c.revision),
        )
    }

    fun membership(items: List<Item>, c: Collection, remove: Boolean) {
        requireWrites()
        call(
            "/api/v1/photos/albums",
            "POST",
            body =
                obj(
                    "action" to "members",
                    "id" to c.id,
                    "revision" to c.revision,
                    (if (remove) "remove_ids" else "add_ids") to arr(items.map { it.id }),
                ),
        )
    }

    fun grabs(): List<JSONObject> {
        requireWrites()
        val p = call("/api/capsules")
        return p.a("capsules").objects().map { row ->
            row.put("url", p.s("base").trimEnd('/') + "/g/" + Api.segment(row.s("id")))
        }
    }

    fun grab(
        items: List<Item>,
        album: Collection?,
        hidden: Boolean,
        days: Int,
        downloads: Int,
        password: String,
        confirmed: Boolean,
    ): JSONObject {
        requireWrites()
        require(!hidden || confirmed) { "Confirm sharing Hidden photos first." }
        val photo = album != null || items.firstOrNull()?.photo == true
        val body =
            obj(
                "expiry" to "${days}d",
                "grabs" to downloads,
                "passphrase" to password,
                "gate" to if (password.isBlank()) "open" else "passphrase",
                (if (photo) "title" else "label") to "Phone link",
            )
        if (photo) {
            val selection =
                if (album == null) UploadContract.selection(items.map { it.id }, hidden)
                else
                    obj(
                        "filter" to obj("album" to album.id, "hidden" to hidden),
                        "mode" to if (hidden) "hidden" else "all",
                    )
            body
                .put(
                    "selection_id",
                    call("/api/v1/photos/selections", "POST", body = selection).s("id"),
                )
                .put("hidden", hidden)
                .put("confirm_hidden", confirmed)
        } else {
            require(items.size == 1)
            body.put("path", items[0].path).put("kind", if (items[0].folder) "folder" else "file")
        }
        val op = uuid()
        val journal = File(root, "pending-grab.json")
        writeJson(journal, obj("key" to op))
        val result =
            try {
                call(
                    if (photo) "/api/v1/photos/grabs" else "/api/capsules",
                    "POST",
                    body = body,
                    headers = mapOf("Idempotency-Key" to op),
                )
            } catch (e: Exception) {
                try {
                    call("/api/v1/grabs/operations", headers = mapOf("Idempotency-Key" to op))
                } catch (_: Exception) {
                    throw e
                }
            }
        require(result.s("url").startsWith("https://"))
        journal.delete()
        return result
    }

    fun revoke(id: String) {
        requireWrites()
        call("/api/capsules", "DELETE", query = mapOf("id" to id))
    }

    fun stable(item: Item): Item {
        if (item.photo) return item
        if (!item.id.startsWith("path:")) return Item(call("/api/v1/files/${Api.segment(item.id)}"))
        return snapshot(item.path).firstOrNull { it.path == item.path }
            ?: error("The stable file identity is unavailable.")
    }

    fun snapshot(prefix: String): List<Item> {
        val rows = linkedMapOf<String, Item>()
        var cursor = ""
        var delta = false
        val guard = PageGuard()
        repeat(10000) {
            val p =
                call(
                    "/api/v1/files/sync",
                    query = mapOf("prefix" to prefix, "cursor" to cursor, "limit" to "200"),
                )
            for (v in p.a("items").objects()) {
                val item = Item(v)
                if (v.optBoolean("deleted")) rows.remove(item.id) else rows[item.id] = item
            }
            for (change in p.a("changes").objects()) {
                if (change.optBoolean("deleted")) rows.remove(change.s("id"))
                else if (change.has("item")) {
                    val item = Item(change.o("item"))
                    rows[item.id] = item
                }
            }
            require(!p.optBoolean("resync_required")) {
                "Sync checkpoint expired. Refresh to reconcile."
            }
            val next = if (p.optBoolean("has_more")) p.s("next_cursor") else p.s("checkpoint")
            require(next.isNotEmpty()) { "The server did not return a sync checkpoint." }
            if (p.optBoolean("has_more")) {
                guard.advance(next, true)
                cursor = next
            } else if (delta) return rows.values.toList()
            else {
                delta = true
                cursor = next
            }
        }
        error("The library is changing too quickly. Retry synchronization.")
    }

    fun thumbnail(item: Item): File? {
        demo?.let {
            return it.thumbnail(item)
        }
        val file = File(root, "thumbnails/${item.cacheKey}.jpg")
        if (file.exists()) return file
        val path =
            if (item.photo) "/api/v1/photos/assets/${Api.segment(item.id)}/thumbnail"
            else "/api/library/thumbnail"
        val q =
            if (item.photo) mapOf("hidden" to if (item.hidden) "1" else "0")
            else mapOf("path" to item.path)
        api!!.execute(api!!.request(path, query = q)).use { r ->
            val data = r.body!!.byteStream().readNBytes(8 * 1024 * 1024 + 1)
            require(data.size <= 8 * 1024 * 1024)
            file.parentFile!!.mkdirs()
            file.writeBytes(data)
        }
        return file
    }

    fun originalFile(item: Item, pin: Boolean = true) =
        File(
            root,
            "${if(pin) "originals" else "previews"}/${item.cacheKey}.${item.extension.ifBlank { "bin" }}",
        )

    @Synchronized
    fun original(item: Item, pin: Boolean = false): File {
        demo?.let {
            return it.original(item)
        }
        originalFile(item, true).let { if (it.exists() && it.length() == item.size) return it }
        val destination = originalFile(item, pin)
        if (destination.exists() && destination.length() == item.size) return destination
        val resolved = stable(item)
        require(item.photo || item.id.startsWith("path:") || item.revision == resolved.revision) {
            "The original changed. Reload before downloading."
        }
        require(root.usableSpace > resolved.size + 32 * 1024 * 1024) {
            "The phone needs more free space."
        }
        api!!.download(
            if (item.photo) "/api/v1/photos/assets/${Api.segment(item.id)}/original"
            else "/api/v1/files/${Api.segment(resolved.id)}/content",
            if (item.photo) mapOf("hidden" to if (item.hidden) "1" else "0") else emptyMap(),
            destination,
            resolved.size,
            if (item.photo) "\"${item.id}-${item.revision}\"" else null,
        )
        return destination
    }

    fun pins() = readJson(File(root, "pins.json")).a("pins").objects()

    fun albumItems(id: String, hidden: Boolean): List<Item> {
        val items = mutableListOf<Item>()
        var cursor = ""
        val guard = PageGuard()
        do {
            val p =
                call(
                    "/api/v1/photos/albums/memberships",
                    query =
                        mapOf(
                            "id" to id,
                            "hidden" to if (hidden) "1" else "0",
                            "cursor" to cursor,
                            "limit" to "200",
                        ),
                )
            p.a("asset_ids").strings().forEach {
                items +=
                    Item(
                        call(
                                "/api/v1/photos/assets/${Api.segment(it)}",
                                query = mapOf("hidden" to if (hidden) "1" else "0"),
                            )
                            .put("hidden", hidden),
                        true,
                    )
            }
            cursor = p.s("next_cursor")
        } while (guard.advance(cursor, p.optBoolean("has_more")))
        return items
    }

    fun pin(item: Item? = null, album: Collection? = null, hidden: Boolean = false) {
        val kind = if (album != null) "album" else if (item!!.folder) "folder" else "item"
        val source = album?.id ?: if (item!!.folder) item.path else item.id
        val items =
            if (album != null) albumItems(album.id, hidden)
            else if (item!!.folder) snapshot(item.path).filter { !it.folder }
            else listOf(stable(item))
        val id = key("$kind:$source:$hidden")
        val p =
            obj(
                "id" to id,
                "title" to (album?.title ?: item!!.name),
                "kind" to kind,
                "source" to source,
                "hidden" to hidden,
                "items" to arr(items.map { it.saved() }),
                "state" to "pending",
            )
        val all = pins().filter { it.s("id") != id } + p
        writeJson(File(root, "pins.json"), obj("pins" to arr(all)))
        try {
            items.forEach { original(it, true) }
            p.put("state", "current")
        } catch (e: Exception) {
            p.put("state", "incomplete")
            throw e
        } finally {
            writeJson(File(root, "pins.json"), obj("pins" to arr(all)))
        }
    }

    @Synchronized
    fun reconcilePins() {
        for (pin in pins()) {
            try {
                val old =
                    pin.a("items").objects().map { Item(it.o("wire"), it.optBoolean("photo")) }
                val current =
                    when (pin.s("kind")) {
                        "folder" -> snapshot(pin.s("source")).filter { !it.folder }
                        "album" -> albumItems(pin.s("source"), pin.optBoolean("hidden"))
                        else ->
                            old.mapNotNull { item ->
                                try {
                                    if (item.photo)
                                        Item(
                                            call(
                                                    "/api/v1/photos/assets/${Api.segment(item.id)}",
                                                    query =
                                                        mapOf(
                                                            "hidden" to
                                                                if (item.hidden) "1" else "0"
                                                        ),
                                                )
                                                .put("hidden", item.hidden),
                                            true,
                                        )
                                    else stable(item)
                                } catch (e: ApiFailure) {
                                    if (e.status == 404) null else throw e
                                }
                            }
                    }
                val refreshed =
                    obj(
                        "id" to pin.s("id"),
                        "title" to pin.s("title"),
                        "kind" to pin.s("kind"),
                        "source" to pin.s("source"),
                        "hidden" to pin.optBoolean("hidden"),
                        "items" to arr(current.map { it.saved() }),
                        "state" to "pending",
                    )
                fun save() =
                    writeJson(
                        File(root, "pins.json"),
                        obj("pins" to arr(pins().filter { it.s("id") != pin.s("id") } + refreshed)),
                    )
                save()
                val retained =
                    pins()
                        .flatMap { it.a("items").objects() }
                        .map { Item(it.o("wire"), it.optBoolean("photo")).cacheKey }
                        .toSet()
                old.filter { it.cacheKey !in retained }.forEach { originalFile(it).delete() }
                try {
                    current.forEach { original(it, true) }
                    refreshed.put(
                        "state",
                        if (current.isEmpty() && pin.s("kind") == "item") "unavailable"
                        else "current",
                    )
                } catch (e: Exception) {
                    refreshed.put("state", "incomplete")
                    throw e
                } finally {
                    save()
                }
            } catch (e: Exception) {
                val state = if (e is ApiFailure && e.status == 404) "unavailable" else "incomplete"
                writeJson(
                    File(root, "pins.json"),
                    obj(
                        "pins" to
                            arr(
                                pins().map {
                                    if (it.s("id") == pin.s("id")) it.put("state", state) else it
                                }
                            )
                    ),
                )
            }
        }
    }

    @Synchronized
    fun evictPreviews(exclude: Set<String> = emptySet()) {
        val budget = prefs.optLong("cacheMiB", 256) * 1024 * 1024
        val files =
            listOf(File(root, "thumbnails"), File(root, "previews"))
                .flatMap {
                    it.walkTopDown()
                        .filter { f -> f.isFile && !f.name.endsWith(".partial") }
                        .toList()
                }
                .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (file in files) if (total > budget && file.path !in exclude) {
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    fun removePin(id: String) {
        val all = pins()
        val retained = all.filter { it.s("id") != id }
        val keep =
            retained
                .flatMap { it.a("items").objects() }
                .map { Item(it.o("wire"), it.optBoolean("photo")).cacheKey }
                .toSet()
        all.filter { it.s("id") == id }
            .flatMap { it.a("items").objects() }
            .forEach {
                val item = Item(it.o("wire"), it.optBoolean("photo"))
                if (item.cacheKey !in keep) originalFile(item).delete()
            }
        writeJson(File(root, "pins.json"), obj("pins" to arr(retained)))
    }

    fun uploadDestination(path: String): Item {
        requireWrites()
        val target = path.ifEmpty { "Phone uploads" }
        if (path.isEmpty())
            try {
                call("/api/library/folder", "POST", body = obj("path" to target))
            } catch (e: ApiFailure) {
                if (e.status != 409) throw e
            }
        return stable(Item(obj("path" to target, "folder" to true))).also {
            require(it.folder && !it.id.startsWith("path:"))
        }
    }
}
