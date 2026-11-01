package io.prendie.weazlcloud

import java.io.File
import org.json.JSONObject

/** Recover organization only. This never resets upload identities or removes original bytes. */
object OrganizationRecovery {
    fun unique(
        matches: List<JSONObject>,
        kind: String,
        device: String,
        namespace: String,
    ): JSONObject? {
        val live = matches.filter { it.optBoolean("target_exists") }
        if (
            live.isEmpty() ||
                live.any { it.s("kind") != kind } ||
                live.map { it.s("server_id") }.toSet().size != 1 ||
                live.map { it.optLong("current_server_revision") }.toSet().size != 1
        )
            return null
        return live.firstOrNull { it.s("device_id") == device && it.s("namespace") == namespace }
            ?: live.sortedBy { it.s("device_id") + ":" + it.s("namespace") }.first()
    }

    fun adoption(match: JSONObject, namespace: String): JSONObject {
        require(match.optBoolean("target_exists") && match.optLong("current_server_revision") > 0)
        return obj(
            "from_device_id" to match.s("device_id"),
            "from_namespace" to match.s("namespace"),
            "from_source_id" to match.s("source_id"),
            "from_source_revision" to match.s("source_revision"),
            "from_server_revision" to match.optLong("server_revision"),
            "namespace" to namespace,
            "source_id" to match.s("source_id"),
            "server_id" to match.s("server_id"),
            "expected_server_revision" to match.optLong("current_server_revision"),
            "operation_id" to "recover-${uuid()}",
        )
    }

    fun prepare(
        repo: Repository,
        state: JSONObject,
        file: File,
        sources: Map<String, String>,
        namespace: String,
    ) {
        if (
            sources.isEmpty() ||
                !repo.capabilities.o("features").optBoolean("source_collection_recovery_write")
        )
            return
        val device = repo.credential.s("device")
        val knownWasPresent = state.has("_known")
        val known = state.a("_known").strings().toSet()
        val checked = state.a("_checked").strings().toMutableSet()
        if (checked.containsAll(sources.keys) && !state.has("_adoption")) return
        val fresh =
            state.keys().asSequence().none { !it.startsWith("_") && it != "pending" } &&
                !state.has("pending") &&
                !state.has("_known")
        val issues = JSONObject()
        fun save() {
            state.put("_checked", arr(checked))
            state.put("_issues", issues)
            writeJson(file, state)
        }
        fun accept(source: String, mapping: JSONObject) {
            val previous = state.o(source)
            val same = previous.s("server_id") == mapping.s("server_id") && source in checked
            val current =
                if (same) previous else obj("title" to "", "members" to arr(emptyList<String>()))
            current
                .put("server_id", mapping.s("server_id"))
                .put("revision", mapping.optLong("server_revision"))
                .put("source_revision", mapping.s("source_revision"))
            state.put(source, current)
            checked.add(source)
            if (state.o("pending").o("operation").s("source_id") == source) {
                state.put("_superseded", arr(state.a("_superseded").objects() + state.o("pending")))
                state.remove("pending")
            }
            if (mapping.s("kind") == "album") {
                val members = mutableSetOf<String>()
                for (hidden in listOf("0", "1")) {
                    var cursor = ""
                    val guard = PageGuard()
                    do {
                        val page =
                            repo.call(
                                "/api/v1/photos/albums/memberships",
                                query =
                                    mapOf(
                                        "id" to current.s("server_id"),
                                        "hidden" to hidden,
                                        "cursor" to cursor,
                                        "limit" to "200",
                                    ),
                            )
                        require(
                            page.s("id") == current.s("server_id") &&
                                page.optLong("revision") == current.optLong("revision") &&
                                page.has("asset_ids") &&
                                page.has("has_more")
                        ) {
                            "Album membership changed during recovery. Retry to reconcile."
                        }
                        val ids = page.a("asset_ids").strings()
                        require(ids.size <= 200 && ids.none { it.isBlank() })
                        members.addAll(ids)
                        cursor = page.s("next_cursor")
                    } while (guard.advance(cursor, page.optBoolean("has_more")))
                }
                current.put("members", arr(members))
            }
            state.remove("_adoption")
            save()
        }
        fun adopt(body: JSONObject) {
            val mapping =
                repo
                    .call("/api/v1/photos/source-collections/recover", "POST", body = body)
                    .o("mapping")
            require(
                mapping.s("device_id") == device &&
                    mapping.s("namespace") == namespace &&
                    mapping.s("source_id") == body.s("source_id") &&
                    mapping.s("server_id") == body.s("server_id") &&
                    mapping.optLong("server_revision") > 0 &&
                    mapping.s("source_revision").isNotEmpty()
            ) {
                "Album adoption could not be verified. Its exact retry request is retained."
            }
            accept(body.s("source_id"), mapping)
        }
        state.optJSONObject("_adoption")?.let { body ->
            try {
                adopt(body)
            } catch (e: ApiFailure) {
                if (e.status !in listOf(404, 409)) throw e
                state.put("_superseded", arr(state.a("_superseded").objects() + body))
                state.remove("_adoption")
                checked.remove(body.s("source_id"))
                save()
            }
        }
        val matches = mutableListOf<JSONObject>()
        for (ids in sources.keys.chunked(200)) {
            var cursor = ""
            val guard = PageGuard()
            do {
                val body = obj("source_ids" to arr(ids), "limit" to 200)
                if (cursor.isNotEmpty()) body.put("cursor", cursor)
                val page =
                    repo.call("/api/v1/photos/source-collections/lookup", "POST", body = body)
                require(page.has("matches") && page.has("has_more")) {
                    "Album recovery returned an incomplete response."
                }
                val rows = page.a("matches").objects()
                require(rows.size <= 200)
                for (row in rows) require(
                    row.s("source_id") in ids &&
                        row.s("device_id").isNotBlank() &&
                        row.s("namespace").isNotBlank() &&
                        row.s("server_id").isNotBlank() &&
                        row.s("source_revision").isNotBlank() &&
                        row.optLong("server_revision") > 0 &&
                        row.s("kind") in listOf("folder", "album") &&
                        row.has("target_exists") &&
                        (!row.optBoolean("target_exists") ||
                            row.optLong("current_server_revision") > 0)
                ) {
                    "An album recovery mapping is incomplete."
                }
                matches += rows
                cursor = page.s("next_cursor")
            } while (guard.advance(cursor, page.optBoolean("has_more")))
        }
        state.put("_known", arr(known + sources.keys))
        if (matches.isEmpty() && !fresh) {
            var empty = true
            for (hidden in listOf("0", "1")) {
                val page =
                    repo.call(
                        "/api/v1/photos/collections",
                        query = mapOf("hidden" to hidden, "limit" to "200"),
                    )
                require(page.optInt("schema") == 2 && page.has("nodes") && page.has("has_more")) {
                    "The empty collection catalog could not be verified."
                }
                if (
                    page.a("nodes").length() > 0 ||
                        page.optBoolean("has_more") ||
                        page.s("next").isNotBlank()
                )
                    empty = false
            }
            if (empty) {
                writeJson(
                    File(file.parentFile, "organization-before-empty-rebuild-${uuid()}.json"),
                    state,
                )
                state.keys().asSequence().toList().forEach { state.remove(it) }
                state.put("_known", arr(sources.keys)).put("_epoch", uuid())
                checked.clear()
                checked.addAll(sources.keys)
                save()
                return
            }
        }
        for ((source, kind) in sources) {
            val rows = matches.filter { it.s("source_id") == source }
            if (rows.isEmpty() && (fresh || knownWasPresent && source !in known)) {
                checked.add(source)
                continue
            }
            val chosen = unique(rows, kind, device, namespace)
            if (chosen == null) {
                checked.remove(source)
                issues.put(source, "Missing, deleted or ambiguous collection mapping")
                continue
            }
            try {
                if (chosen.s("device_id") == device && chosen.s("namespace") == namespace) {
                    require(
                        chosen.optLong("server_revision") ==
                            chosen.optLong("current_server_revision")
                    ) {
                        "The server collection changed. Reconcile its source mapping before overwriting it."
                    }
                    accept(source, chosen)
                } else {
                    val body = adoption(chosen, namespace)
                    state.put("_adoption", body)
                    save()
                    adopt(body)
                }
            } catch (e: Exception) {
                checked.remove(source)
                issues.put(source, e.message ?: "Collection recovery needs attention")
                save()
                throw e
            }
        }
        save()
        require(issues.length() == 0) {
            "${issues.length()} collections need recovery. Stored originals are retained."
        }
    }
}
