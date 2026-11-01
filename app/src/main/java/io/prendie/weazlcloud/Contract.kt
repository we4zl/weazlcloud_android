package io.prendie.weazlcloud

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

fun obj(vararg pairs: Pair<String, Any?>) =
    JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }

fun arr(values: Iterable<*>) = JSONArray().apply { values.forEach { put(it) } }

fun JSONObject.s(key: String) = if (isNull(key)) "" else optString(key, "")

fun JSONObject.o(key: String) = optJSONObject(key) ?: JSONObject()

fun JSONObject.a(key: String) = optJSONArray(key) ?: JSONArray()

fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray.strings() = (0 until length()).map { optString(it) }

fun sha(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun key(value: String) = sha(value.toByteArray())

fun sourceKey(namespace: String, id: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (s in listOf(namespace, id)) {
        val b = s.toByteArray()
        digest.update(ByteBuffer.allocate(8).putLong(b.size.toLong()).array())
        digest.update(b)
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
}

fun safeName(value: String): String {
    val n = value.trim()
    require(
        n.isNotEmpty() &&
            n !in listOf(".", "..") &&
            n.none { it == '/' || it == '\\' || it == '\u0000' } &&
            n.toByteArray().size <= 255
    ) {
        "Choose a name without slashes, at most 255 UTF-8 bytes."
    }
    return n
}

fun join(parent: String, name: String) =
    if (parent.isBlank()) name else "${parent.trimEnd('/')}/$name"

fun uuid() = UUID.randomUUID().toString()

fun bytes(n: Long): String =
    when {
        n >= 1073741824 -> "%.1f GB".format(n / 1073741824.0)
        n >= 1048576 -> "%.1f MB".format(n / 1048576.0)
        n >= 1024 -> "%.1f KB".format(n / 1024.0)
        else -> "$n bytes"
    }

class ApiFailure(val status: Int, val retrySeconds: Long = 0, message: String = safeError(status)) :
    Exception(message)

fun safeError(status: Int): String =
    when (status) {
        401 -> "Sign in again to verify server access."
        403 -> "This device needs permission for that action. Enable content changes in Settings."
        409 -> "The server content changed. Refresh before retrying."
        410 -> "Upload staging expired. The original and upload specification are retained."
        412 -> "The original changed. Reload before downloading."
        422 -> "Server verification failed. The original and receipt are retained."
        423 -> "The server vault is locked. Unlock it to continue."
        429 -> "The server is busy. This operation will wait before retrying."
        507 -> "More storage is needed to finish this operation."
        else -> "The operation could not finish (HTTP $status). Check the connection and retry."
    }

data class Item(val wire: JSONObject, val photo: Boolean = false) {
    val path = wire.s("path")
    val id = wire.s("id").ifEmpty { "path:$path" }
    val name =
        wire.s("name").ifEmpty { path.substringAfterLast('/').ifEmpty { wire.s("filename") } }
    val folder = wire.optBoolean("folder")
    val size = wire.optLong("size")
    val revision = wire.s("revision")
    val hidden = wire.optBoolean("hidden")
    val favorite = wire.optBoolean("favorite")
    val captured = wire.s("captured_at")
    val extension = name.substringAfterLast('.', "").lowercase()
    val type =
        when {
            folder -> "folder"
            extension in
                listOf(
                    "jpg",
                    "jpeg",
                    "png",
                    "heic",
                    "heif",
                    "webp",
                    "gif",
                    "avif",
                    "dng",
                    "tif",
                    "tiff",
                ) -> "image"
            extension in listOf("mp4", "mov", "m4v", "webm", "mkv") -> "video"
            extension in listOf("mp3", "m4a", "wav", "flac", "aac", "ogg") -> "audio"
            extension == "pdf" -> "pdf"
            extension in listOf("txt", "md", "json", "csv", "log") -> "text"
            else -> "file"
        }

    fun saved() = obj("wire" to wire, "photo" to photo)

    val cacheKey
        get() = key("$id:$revision:$hidden")
}

data class Collection(val wire: JSONObject) {
    val kind = if (wire.has("folder")) "folder" else "album"
    val data = if (wire.has(kind)) wire.o(kind) else wire
    val id = data.s("id")
    val title = data.s("title")
    val parent = data.s("parent_id")
    val revision = data.optLong("revision")
    val count = data.optLong("count")
}

class PageGuard {
    private val seen = mutableSetOf<String>()

    fun advance(cursor: String, more: Boolean): Boolean {
        if (!more) return false
        require(cursor.isNotEmpty() && seen.add(cursor)) {
            "The server returned a stalled page cursor."
        }
        return true
    }
}

object UploadContract {
    const val PART = 16 * 1024 * 1024

    fun receipt(v: JSONObject) =
        v.s("id").ifEmpty { v.s("receipt_id").ifEmpty { v.o("upload").s("id") } }

    fun missing(v: JSONObject, cursor: Long): List<Int> {
        require(!v.optBoolean("has_more") || v.optLong("next") > cursor) {
            "The missing-parts cursor did not advance."
        }
        return (0 until v.a("missing").length()).map {
            v.a("missing").getInt(it).also { n -> require(n >= 0) }
        }
    }

    fun stored(v: JSONObject): Boolean =
        v.s("status") == "stored" ||
            v.s("state") == "stored" ||
            v.o("result").s("status") == "stored"

    fun selection(ids: List<String>, hidden: Boolean) =
        obj(
            "ids" to arr(ids),
            "filter" to obj("hidden" to hidden),
            "mode" to if (hidden) "hidden" else "all",
        )

    fun resumeOffset(
        status: Int,
        requested: Long,
        range: String?,
        tag: String?,
        expected: String,
    ): Long {
        require(tag == expected) { "The original changed during download." }
        if (status == 200) return 0
        require(status == 206 && requested > 0 && range?.startsWith("bytes $requested-") == true) {
            "Invalid partial download response."
        }
        return requested
    }
}

object PhotoLookup {
    fun match(page: JSONObject, component: JSONObject, hidden: Boolean): JSONObject? {
        val results = page.a("results").objects()
        fun valid(condition: Boolean) {
            if (!condition)
                throw ApiFailure(
                    502,
                    message =
                        "The photo content lookup is incomplete. Backup will retry without uploading unchecked originals.",
                )
        }
        valid(page.optLong("generation", -1) >= 0 && results.size == 1)
        val result = results[0]
        val matches = result.a("matches").objects()
        val count = result.optInt("match_count", -1)
        valid(
            result.s("sha256") == component.s("sha256").lowercase() &&
                result.optLong("size") == component.optLong("size") &&
                matches.size <= 20 &&
                count >= matches.size &&
                result.optBoolean("exists") == (count > 0) &&
                result.optBoolean("has_more_matches") == (count > matches.size) &&
                (count == 0 || matches.isNotEmpty())
        )
        valid(
            matches.all {
                it.s("asset_id").isNotBlank() &&
                    it.s("component_asset_id").isNotBlank() &&
                    it.optLong("revision") > 0 &&
                    it.s("component_id") in listOf("original", "motion") &&
                    it.has("hidden")
            }
        )
        val match =
            matches
                .filter { it.s("component_id") == "original" }
                .sortedWith(
                    compareBy<JSONObject> { it.optBoolean("hidden") != hidden }
                        .thenBy { it.s("asset_id") }
                )
                .firstOrNull()
        valid(match != null || !result.optBoolean("has_more_matches"))
        return match
    }
}

/** Report the failing authentication boundary without including credentials or server response bodies. */
fun unauthorizedMessage(path: String, device: Boolean): String = when {
    path == "/api/login" -> "The server rejected the username or account password. Use your WeazlCloud account password; the vault passphrase is separate."
    path == "/api/unlock" -> "Vault unlock was rejected. Check the separate vault passphrase; if it is correct, reconnect to renew the account session."
    device -> "The server rejected this device credential. Reconnect your account to renew device access."
    path == "/api/v1/mobile/capabilities" -> "Account sign-in succeeded, but the server rejected the account session while checking mobile access. Reconnect; if this repeats, the session or server configuration needs checking."
    path == "/api/v1/devices" || path.endsWith("/reauthorize") -> "The server rejected the account session while registering this device. Reconnect to renew the session."
    else -> "The server rejected the account session. Reconnect to renew account access."
}
