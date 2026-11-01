package io.prendie.weazlcloud

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cookies and device authorization are deliberately routed separately. Redirects never receive
 * either.
 */
class Api(
    var origin: String,
    var remote: String = origin,
    private val client: OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build(),
) {
    @Volatile var token = ""
    @Volatile var cookie = ""
    @Volatile var cookieExpires = 0L

    enum class Auth {
        DEVICE,
        ACCOUNT,
        NONE,
    }

    companion object {
        fun validateOrigin(value: String, account: Boolean = false): String {
            val u = value.trim().toHttpUrl()
            val h = u.host
            val privateHost =
                h == "localhost" ||
                    h == "::1" ||
                    h.startsWith("127.") ||
                    h.startsWith("10.") ||
                    h.startsWith("192.168.") ||
                    (h.startsWith("172.") && h.split('.').getOrNull(1)?.toIntOrNull() in 16..31) ||
                    h.endsWith(".local")
            require(
                u.username.isEmpty() &&
                    u.password.isEmpty() &&
                    u.encodedPath == "/" &&
                    u.query == null &&
                    u.fragment == null &&
                    (u.isHttps || !account && privateHost)
            ) {
                "Use an HTTPS server origin, or a private home-network HTTP address."
            }
            return u.toString().trimEnd('/')
        }

        fun segment(value: String) =
            "https://local/"
                .toHttpUrl()
                .newBuilder()
                .addPathSegment(value)
                .build()
                .encodedPath
                .removePrefix("/")
    }

    fun request(
        path: String,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        auth: Auth = Auth.DEVICE,
        headers: Map<String, String> = emptyMap(),
    ): Request {
        require(path.startsWith("/api/") || path == "/.well-known/weazlcloud")
        require(!path.contains("..") && !path.contains('?') && !path.contains('#'))
        if (auth == Auth.DEVICE)
            require(
                path !in
                    listOf(
                        "/api/login",
                        "/api/logout",
                        "/api/unlock",
                        "/api/lock",
                        "/api/me",
                        "/api/uploads",
                    ) &&
                    !path.startsWith("/api/uploads/") &&
                    !path.endsWith("/reauthorize") &&
                    !(path == "/api/v1/devices" && method == "POST")
            )
        val base =
            if (auth == Auth.ACCOUNT) validateOrigin(remote, true) else validateOrigin(origin)
        val url =
            (base + path)
                .toHttpUrl()
                .newBuilder()
                .apply {
                    query
                        .filterValues { it.isNotEmpty() }
                        .forEach { (k, v) -> addQueryParameter(k, v) }
                }
                .build()
        return Request.Builder()
            .url(url)
            .method(
                method,
                body
                    ?: if (method in listOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody()
                    else null,
            )
            .apply {
                header("Accept", "application/json")
                if (path.startsWith("/api/v1/") || path.startsWith("/.well-known/"))
                    header("X-Weazl-Mobile-Contract", "1")
                if (method !in listOf("GET", "HEAD")) header("X-Weazl-Desk", "1")
                when (auth) {
                    Auth.DEVICE -> {
                        require(token.isNotEmpty()) { "Sign in to connect this device." }
                        header("Authorization", "Bearer $token")
                    }
                    Auth.ACCOUNT ->
                        if (cookie.isNotEmpty() && cookieExpires > System.currentTimeMillis())
                            header("Cookie", "weazl_session=$cookie")
                    Auth.NONE -> {}
                }
                headers.forEach { (k, v) ->
                    require(k !in listOf("Authorization", "Cookie"))
                    header(k, v)
                }
            }
            .build()
    }

    fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val status = response.code
            val retry =
                response
                    .header("Retry-After")
                    ?.let { value ->
                        value.toLongOrNull()
                            ?: runCatching {
                                    (java.time.ZonedDateTime.parse(
                                            value,
                                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
                                        )
                                        .toInstant()
                                        .toEpochMilli() - System.currentTimeMillis()) / 1000
                                }
                                .getOrDefault(15)
                    }
                    ?.coerceAtLeast(0) ?: 15
            response.close()
            throw ApiFailure(status, retry, if (status == 401) unauthorizedMessage(request.url.encodedPath, request.header("Authorization") != null) else safeError(status))
        }
        return response
    }

    fun call(
        path: String,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        auth: Auth = Auth.DEVICE,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val req =
            request(
                path,
                method,
                query,
                body?.toString()?.toRequestBody("application/json".toMediaType()),
                auth,
                headers,
            )
        execute(req).use { r ->
            if (auth == Auth.ACCOUNT && path == "/api/login") {
                val c =
                    Cookie.parseAll(req.url, r.headers).firstOrNull {
                        it.name == "weazl_session" &&
                            it.domain == req.url.host &&
                            it.path == "/" &&
                            it.persistent
                    }
                require(c != null && c.value.isNotEmpty() && c.expiresAt > System.currentTimeMillis()) {
                    "Account sign-in succeeded, but the server did not provide a usable session cookie. Check the server/proxy configuration and phone date and time."
                }
                cookie = c.value
                cookieExpires = c.expiresAt
            }
            val stream = r.body?.byteStream() ?: return JSONObject()
            val data = stream.readNBytes(8 * 1024 * 1024 + 1)
            require(data.size <= 8 * 1024 * 1024) {
                "The server response exceeds the mobile limit."
            }
            return if (data.isEmpty()) JSONObject() else JSONObject(String(data, Charsets.UTF_8))
        }
    }

    fun discover(): JSONObject =
        call("/.well-known/weazlcloud", auth = Auth.NONE).also {
            require(
                it.optInt("contract_version") == 1 &&
                    it.s("instance_id").isNotEmpty() &&
                    it.s("capabilities_path") == "/api/v1/mobile/capabilities"
            ) {
                "This server does not support mobile contract 1."
            }
        }

    fun download(
        path: String,
        query: Map<String, String>,
        destination: File,
        expectedSize: Long,
        photoETag: String? = null,
    ) {
        val head =
            execute(request(path, "HEAD", query, headers = mapOf("Accept-Encoding" to "identity")))
                .use { r ->
                    require(r.header("Content-Length")?.toLongOrNull() == expectedSize) {
                        "The original size changed. Reload before downloading."
                    }
                    r.header("ETag").also {
                        require(!it.isNullOrBlank() && (photoETag == null || photoETag == it)) {
                            "The original revision changed."
                        }
                    }!!
                }
        destination.parentFile!!.mkdirs()
        val partial = File(destination.path + ".partial")
        val tag = File(destination.path + ".etag")
        if (!tag.exists() || tag.readText() != head || partial.length() > expectedSize)
            partial.delete()
        val offset = partial.length()
        val headers =
            if (offset > 0) mapOf("Range" to "bytes=$offset-", "If-Range" to head) else emptyMap()
        execute(
                request(
                    path,
                    query = query,
                    headers = headers + mapOf("Accept-Encoding" to "identity"),
                )
            )
            .use { r ->
                val start =
                    UploadContract.resumeOffset(
                        r.code,
                        offset,
                        r.header("Content-Range"),
                        r.header("ETag"),
                        head,
                    )
                tag.writeText(head)
                RandomAccessFile(partial, "rw").use { out ->
                    if (start == 0L) out.setLength(0)
                    out.seek(start)
                    val input = r.body!!.byteStream()
                    val buffer = ByteArray(1024 * 1024)
                    var total = start
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= expectedSize) {
                            "The original exceeded its declared size."
                        }
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                    require(total == expectedSize) { "Download interrupted; retry to resume." }
                }
            }
        check(partial.renameTo(destination)) { "Could not save the downloaded original." }
        tag.delete()
    }
}
