@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.prendie.weazlcloud

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun Utility(m: CloudModel) {
    when (val sheet = m.sheet.orEmpty()) {
        "Settings" -> SettingsPane(m)
        "Privacy policy" -> PrivacyPolicyPane()
        "Backup" -> BackupPane(m)
        "Transfers" -> TransfersPane(m)
        "Offline" -> OfflinePane(m)
        "Grabs" -> GrabsPane(m)
        "Grab link" -> GrabDetail(m)
        "Duplicates" -> DuplicatesPane(m)
        "Unlock vault" -> {
            var secret by remember { mutableStateOf("") }
            Form {
                Field("Vault passphrase", secret, true) { secret = it }
                Square("Unlock", true, !m.busy) {
                    val password = secret
                    secret = ""
                    m.unlock(password)
                }
            }
        }
        else -> if (sheet.startsWith("edit:")) EditPane(m, sheet.removePrefix("edit:"))
    }
}

@Composable
private fun PrivacyPolicyPane() {
    val context = LocalContext.current
    val text = remember {
        val html = context.assets.open("privacy-policy.html").bufferedReader().use { it.readText() }
        HtmlCompat.fromHtml(
            html.substringAfter("<main>").substringBefore("</main>"),
            HtmlCompat.FROM_HTML_MODE_LEGACY,
        ).toString().trim()
    }
    Form {
        SelectionContainer { Text(text, fontSize = 14.sp, lineHeight = 22.sp) }
        Square("View policy online") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://weazl.studio/privacy.html")))
        }
    }
}

@Composable
private fun Form(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, onChange)
    }
}

@Composable
fun Choice(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Hint(label)
        Box {
            OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) {
                Text(
                    choices.firstOrNull { it.second == value }?.first ?: value.ifBlank { "Choose…" }
                )
            }
            DropdownMenu(expanded, { expanded = false }) {
                choices.forEach { (title, id) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            expanded = false
                            onChange(id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPane(m: CloudModel) {
    var home by remember { mutableStateOf(m.repo.credential.s("home")) }
    var quota by remember { mutableStateOf(JSONObject()) }
    val version = m.prefsVersion
    LaunchedEffect(Unit) {
        try {
            quota = withContext(Dispatchers.IO) { m.repo.call("/api/quota") }
        } catch (e: Exception) {
            m.report(e)
        }
    }
    Form {
        Panel {
            Heading("Account")
            Text(
                m.repo.capabilities.o("profile").s("full_name").ifBlank {
                    m.repo.credential.s("username")
                }
            )
            Hint(m.repo.credential.s("origin").ifBlank { "Synthetic local demo" })
            Field("Home server address", home) { home = it }
            Square("Save and use home address", enabled = home.isNotBlank() && !m.busy) {
                m.io { m.repo.setRoute(home, true) }
            }
            Hint(
                "Home and remote addresses must identify the same server. Account sign-in uses HTTPS."
            )
            Square("Use remote address") { m.io { m.repo.setRoute(null, false) } }
            Square("Use home address", enabled = m.repo.credential.s("home").isNotEmpty()) {
                m.io { m.repo.setRoute(null, true) }
            }
            Hint("Credential expires: ${m.repo.credential.s("expires").take(10).ifBlank {"Demo"}}")
            Square("Enable uploads and content changes") { m.io { m.repo.requireWrites() } }
            Square("Rotate device credential") { m.io { m.repo.rotate() } }
            TextButton({
                m.connected = false
                m.sheet = null
            }) {
                Text("Sign in again")
            }
        }
        Policy(m)
        Panel {
            Heading("Device storage")
            Choice(
                "Upload staging limit",
                m.repo.prefs.optInt("stagingGiB", 2).toString(),
                listOf(1, 2, 4, 8, 16, 32, 64).map { "$it GiB" to "$it" },
            ) {
                m.setPref("stagingGiB", it.toInt())
            }
            Hint(
                "Upload originals are retained until the server confirms storage. Offline pins are stored separately."
            )
            Square("Clear preview cache") {
                m.io {
                    java.io.File(m.repo.root, "thumbnails").deleteRecursively()
                    java.io.File(m.repo.root, "previews").deleteRecursively()
                }
            }
        }
        Panel {
            Heading("Privacy")
            Square("Privacy policy") { m.sheet = "Privacy policy" }
            Toggle("Require device screen lock", m.repo.prefs.optBoolean("appLock")) {
                m.setPref("appLock", it)
            }
            Square("Lock app now") {
                m.locked = true
                m.sheet = null
            }
        }
        Panel {
            Heading("Server storage")
            Hint("Used: ${bytes(quota.optLong("used"))}")
            Hint("Available: ${bytes(quota.optLong("available_bytes"))}")
        }
        Square("Sign out") { m.edit("signOut") }
    }
}

@Composable
private fun Policy(m: CloudModel) {
    Panel {
        Heading("Backup policy")
        Choice(
            "Backup network",
            if (m.repo.prefs.optBoolean("cellular")) "cellular" else "wifi",
            listOf("Wi-Fi only" to "wifi", "Wi-Fi + cellular" to "cellular"),
        ) {
            m.setPref("cellular", it == "cellular")
        }
        Toggle("Prefer charging for scheduled backups", m.repo.prefs.optBoolean("charging", true)) {
            m.setPref("charging", it)
        }
    }
}

@Composable
private fun BackupPane(m: CloudModel) {
    val context = LocalContext.current
    var roots by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val version = m.prefsVersion
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            result ->
            val allowed = result.any {
                it.value && it.key != Manifest.permission.POST_NOTIFICATIONS
            }
            if (allowed)
                m.run {
                    withContext(Dispatchers.IO) { m.repo.requireWrites() }
                    m.setPref("photosBackup", true)
                    m.transfers.start()
                }
            else m.error = "Allow photo and video access to enable backup."
        }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                m.io { m.transfers.addFolder(uri, m.path) }
                m.prefsVersion++
            }
        }
    LaunchedEffect(Unit) {
        try {
            roots =
                withContext(Dispatchers.IO) {
                    m.repo.call("/api/v1/photos/capabilities").a("roots").objects()
                }
        } catch (e: Exception) {
            m.report(e)
        }
    }
    Form {
        Panel {
            Heading("Pixel photo library")
            Hint(
                "If you enable Photos backup, WeazlCloud reads accessible device photos and videos, including original contents, filenames, dates and embedded metadata, and uploads them to your chosen server even when the app is closed. Network and charging preferences apply. Server originals remain if a photo is deleted from the phone."
            )
            TextButton({ m.sheet = "Privacy policy" }) { Text("Privacy policy") }
            Choice(
                "Backup destination",
                m.repo.prefs.s("photoRoot"),
                roots.map {
                    (it.s("name").ifBlank { it.s("path") } +
                        if (it.optBoolean("hidden")) " (Hidden)" else "") to it.s("id")
                },
            ) {
                m.setPref("photoRoot", it)
                m.setPref(
                    "photoRootHidden",
                    roots.firstOrNull { r -> r.s("id") == it }?.optBoolean("hidden") ?: false,
                )
            }
            if (m.repo.prefs.optBoolean("photosBackup"))
                Toggle("Back up Photos library", true) { m.setPref("photosBackup", it) }
            else
                Square(
                    "Enable Photos backup",
                    true,
                    m.repo.prefs.s("photoRoot").isNotEmpty() && m.repo.demo == null,
                ) {
                    permission.launch(
                        if (Build.VERSION.SDK_INT >= 34)
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        else if (Build.VERSION.SDK_INT >= 33)
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.POST_NOTIFICATIONS,
                            )
                        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    )
                }
            Hint(
                "Selected-photo permission backs up only the items Android allows. Cloud-only Google Photos and Locked Folder content are not exposed by device media access."
            )
            Hint(
                "Once enabled, backup runs with the app closed and checks for new photos about every 15 minutes when Android allows. Your network and charging preferences still apply. Battery restrictions can delay it; force-stopping the app pauses background work until you open it again."
            )
            Square("Photo access settings") {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            }
        }
        Panel {
            Heading("Albums and collections")
            Hint(m.transfers.organization)
            Square("Sync albums and folders", enabled = m.repo.demo == null) {
                m.run { m.transfers.cycle(manual = true, scan = true) }
            }
        }
        Panel {
            Heading("Folder sources")
            Hint("New sources back up to ${m.path.ifEmpty {"Phone uploads"}} in Files.")
            m.repo.prefs.a("folders").objects().forEach { source ->
                Toggle(source.s("name"), source.optBoolean("enabled", true)) {
                    source.put("enabled", it)
                    m.repo.savePrefs()
                    m.prefsVersion++
                    m.transfers.schedule()
                }
                Hint(source.s("destinationPath"))
            }
            Square("Add folder source", enabled = m.repo.demo == null) { folder.launch(null) }
        }
        Policy(m)
        Square("Back Up Now", true, m.repo.demo == null) {
            m.transfers.start()
            m.sheet = "Transfers"
        }
        Hint(
            "Manual backup bypasses the charging preference and still respects the network choice."
        )
        Square("Reseed Photos after server reset", enabled = m.repo.demo == null) {
            m.edit("reseedPhotos")
        }
        if (m.repo.demo != null)
            Hint(
                "Demo shows synthetic content. Connect to your server to enable real device backup."
            )
    }
}

@Composable
private fun TransfersPane(m: CloudModel) {
    val stored = m.jobs.count { it.s("phase") == "stored" }
    val existing = m.jobs.count { it.s("phase") == "existing" }
    val version = m.prefsVersion
    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel {
                Heading("${stored+existing} of ${m.jobs.size} on server")
                LinearProgressIndicator(
                    progress = {
                        if (m.jobs.isEmpty()) 0f else (stored + existing).toFloat() / m.jobs.size
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.mint,
                )
                Hint("$stored uploaded · $existing already on server")
                Hint(
                    "${bytes(m.jobs.sumOf {it.optLong("sent")})} sent · ${bytes(m.jobs.sumOf {it.optLong("received")})} verified"
                )
                Hint(m.transfers.condition)
                Square(
                    if (m.repo.prefs.optBoolean("paused")) "Resume transfers" else "Pause transfers"
                ) {
                    val pause = !m.repo.prefs.optBoolean("paused")
                    m.setPref("paused", pause)
                    if (!pause && m.repo.demo == null) m.transfers.start()
                }
                Square("Retry items needing attention", enabled = m.repo.demo == null) {
                    m.run {
                        m.transfers.retryAll()
                        m.transfers.start()
                    }
                }
                Square("Cancel pending transfers", enabled = m.jobs.isNotEmpty()) {
                    m.edit("cancelTransfers")
                }
            }
        }
        item {
            Square("Clear phone upload queue", enabled = m.repo.demo == null) {
                m.edit("clearQueue")
            }
        }
        items(m.jobs.reversed(), key = { it.s("id") }) { job ->
            Panel {
                Text(job.s("name"), fontSize = 14.sp)
                Text(
                    job.s("phase").replaceFirstChar { it.uppercase() },
                    color =
                        if (job.s("phase") in listOf("stored", "existing")) Palette.mint
                        else Palette.violet,
                    fontSize = 13.sp,
                )
                if (job.s("error").isNotEmpty()) Hint(job.s("error"))
                val total = job.a("components").objects().sumOf { it.optLong("size") }
                Hint("${bytes(job.optLong("received"))} / ${bytes(total)} verified")
            }
        }
        if (m.jobs.isEmpty())
            item { Empty("No transfers", "Upload selected files or enable a backup source.") }
    }
}

@Composable
private fun OfflinePane(m: CloudModel) {
    var pins by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { pins = withContext(Dispatchers.IO) { m.repo.pins() } }
    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(pins, key = { it.s("id") }) { pin ->
            var expanded by remember { mutableStateOf(false) }
            Panel {
                TextButton({ expanded = !expanded }) { Text(pin.s("title")) }
                Hint(pin.s("state"))
                if (expanded) {
                    pin.a("items").objects().forEach { row ->
                        val item = Item(row.o("wire"), row.optBoolean("photo"))
                        TextButton({ m.preview = item }) { Text(item.name) }
                    }
                    Square("Remove local copy") {
                        m.io { m.repo.removePin(pin.s("id")) }
                        reload++
                    }
                }
            }
        }
        if (pins.isEmpty())
            item { Empty("Available offline", "Pin a file, folder, or album from its menu.") }
    }
}

@Composable
private fun GrabsPane(m: CloudModel) {
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(Unit) {
        try {
            rows = withContext(Dispatchers.IO) { m.repo.grabs() }
        } catch (e: Exception) {
            m.report(e)
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(rows, key = { it.s("id") }) { r ->
            Panel {
                Text(r.s("label").ifBlank { r.s("title").ifBlank { "Grab link" } })
                Hint("${r.s("status")} · ${r.optInt("left")} downloads remaining")
                Square("View link") {
                    m.grabbed = r
                    m.sheet = "Grab link"
                }
            }
        }
        if (rows.isEmpty())
            item {
                Empty("No grabs", "Create an expiring link from a file, photo selection, or album.")
            }
    }
}

@Composable
private fun GrabDetail(m: CloudModel) {
    val r = m.grabbed ?: return
    val context = LocalContext.current
    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(r.s("url")) { qr = withContext(Dispatchers.Default) { qrBitmap(r.s("url")) } }
    Form {
        Heading("Grab link")
        Text(r.s("url"), fontSize = 14.sp)
        if (qr != null) Image(qr!!, "QR code for grab link", Modifier.fillMaxWidth().height(240.dp))
        Hint("${r.s("status")} · ${r.optInt("left")} downloads remaining")
        Hint(r.s("expires_at").ifBlank { r.s("expiry") })
        Square("Copy link") {
            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(
                android.content.ClipData.newPlainText("WeazlCloud grab", r.s("url"))
            )
        }
        Square("Share link") {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, r.s("url")),
                    "Share grab",
                )
            )
        }
        Square("Revoke link") { m.edit("revokeGrab") }
    }
}

@Composable
private fun DuplicatesPane(m: CloudModel) {
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var cursor by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var generation by remember { mutableLongStateOf(-1) }
    fun load(more: Boolean = false) {
        scope.launch {
            try {
                val p =
                    withContext(Dispatchers.IO) {
                        m.repo.call(
                            "/api/v1/photos/duplicates",
                            query =
                                mapOf(
                                    "cursor" to if (more) cursor else "",
                                    "hidden" to if (m.photoMode == "hidden") "1" else "0",
                                ),
                            auth = Api.Auth.ACCOUNT,
                        )
                    }
                require(
                    !more ||
                        p.optLong("generation") == generation &&
                            (p.s("next_cursor").isEmpty() || p.s("next_cursor") != cursor)
                ) {
                    "The duplicate list changed. Reopen to refresh."
                }
                generation = p.optLong("generation")
                rows = if (more) rows + p.a("groups").objects() else p.a("groups").objects()
                cursor = p.s("next_cursor")
            } catch (e: Exception) {
                m.report(e)
            }
        }
    }
    LaunchedEffect(Unit) { load() }
    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Hint(
                "Groups have identical original bytes. Marking a preferred copy keeps all originals and album memberships."
            )
        }
        items(rows, key = { it.s("id") }) { group ->
            Panel {
                Heading("${group.optInt("count")} exact-original copies")
                group.a("items").objects().forEach { row ->
                    val item = Item(row.put("hidden", m.photoMode == "hidden"), true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumbnail(m, item, Modifier.size(56.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            TextButton({ m.preview = item }) { Text(item.name) }
                            if (row.optBoolean("preferred_photo")) Hint("Preferred copy")
                            else
                                TextButton({
                                    m.run {
                                        withContext(Dispatchers.IO) {
                                            m.repo.call(
                                                "/api/v1/photos/duplicates",
                                                "POST",
                                                query =
                                                    mapOf(
                                                        "hidden" to if (item.hidden) "1" else "0"
                                                    ),
                                                body = obj("id" to item.id),
                                                auth = Api.Auth.ACCOUNT,
                                            )
                                        }
                                        load()
                                    }
                                }) {
                                    Text("Mark preferred")
                                }
                        }
                    }
                    if (group.optBoolean("has_more_items"))
                        Hint("The server limits displayed copies in this group.")
                }
            }
        }
        if (rows.isEmpty())
            item {
                Empty(
                    "No exact duplicates",
                    "No exact-original duplicate groups in this visibility context.",
                )
            }
        if (cursor.isNotEmpty()) item { Square("Load more groups") { load(true) } }
    }
}

@Composable
private fun EditPane(m: CloudModel, kind: String) {
    var name by remember {
        mutableStateOf(
            m.editCollection?.title
                ?: if (kind == "rename") m.editItems.firstOrNull()?.name.orEmpty() else ""
        )
    }
    var parent by remember { mutableStateOf(m.editCollection?.parent ?: m.albumParent) }
    var album by remember { mutableStateOf(m.album) }
    var remove by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf("1") }
    var downloads by remember { mutableStateOf("3") }
    var password by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(java.time.LocalDate.now().toString()) }
    fun perform(block: () -> Unit) {
        m.run {
            withContext(Dispatchers.IO) { block() }
            m.sheet = null
            m.loadFiles()
            m.loadPhotos()
            m.loadCollections()
        }
    }
    Form {
        when (kind) {
            "folder",
            "rename",
            "album",
            "albumFolder",
            "collectionRename" -> {
                Field("Name", name) { name = it }
                if (kind in listOf("album", "albumFolder", "collectionRename"))
                    Choice(
                        "Parent folder",
                        parent,
                        listOf("Albums root" to "") +
                            m.collections
                                .filter { it.kind == "folder" && it.id != m.editCollection?.id }
                                .map { it.title to it.id },
                    ) {
                        parent = it
                    }
                Square("Save", true, !m.busy && name.isNotBlank()) {
                    perform {
                        if (kind in listOf("folder", "rename"))
                            m.repo.fileAction(kind, m.editItems, name, m.path)
                        else
                            m.repo.saveCollection(
                                name,
                                parent,
                                if (kind == "albumFolder") "folder"
                                else m.editCollection?.kind ?: "album",
                                m.editCollection,
                            )
                    }
                }
            }
            "move",
            "copy" -> {
                Field("Destination Library path", name) { name = it }
                Hint("An empty path selects Library root. Existing destinations must be folders.")
                Square(if (kind == "copy") "Copy items" else "Move items", true, !m.busy) {
                    perform { m.repo.fileAction(kind, m.editItems, name) }
                }
            }
            "membership" -> {
                Choice(
                    "Album",
                    album,
                    m.collections.filter { it.kind == "album" }.map { it.title to it.id },
                ) {
                    album = it
                }
                Toggle("Remove membership", remove) { remove = it }
                Square("Apply", true, album.isNotBlank() && !m.busy) {
                    perform {
                        m.repo.membership(
                            m.editItems,
                            m.collections.first { it.id == album },
                            remove,
                        )
                    }
                }
            }
            "date" -> {
                Field("Capture date (YYYY-MM-DD)", date) { date = it }
                Square("Jump to date", true) {
                    m.run {
                        java.time.LocalDate.parse(date)
                        m.loadPhotos(seek = date)
                        m.sheet = null
                    }
                }
                Square("Unknown capture dates") {
                    m.run {
                        m.loadPhotos(seek = "unknown")
                        m.sheet = null
                    }
                }
            }
            "grab" -> {
                Choice(
                    "Expires in",
                    days,
                    listOf("24 hours" to "1", "3 days" to "3", "7 days" to "7"),
                ) {
                    days = it
                }
                Choice("Allowed downloads", downloads, (1..20).map { "$it" to "$it" }) {
                    downloads = it
                }
                Field("Passphrase (optional)", password, true) { password = it }
                val hidden = m.photoMode == "hidden" || m.editItems.any { it.hidden }
                if (hidden) Toggle("Share these Hidden photos", confirmed) { confirmed = it }
                Square("Create link", true, !m.busy && (!hidden || confirmed)) {
                    val secret = password
                    password = ""
                    m.run {
                        m.grabbed =
                            withContext(Dispatchers.IO) {
                                m.repo.grab(
                                    m.editItems,
                                    m.editCollection,
                                    hidden,
                                    days.toInt(),
                                    downloads.toInt(),
                                    secret,
                                    confirmed,
                                )
                            }
                        m.sheet = "Grab link"
                    }
                }
            }
            "photoActions" -> {
                listOf(
                        "Favorite" to "favorite",
                        "Unfavorite" to "unfavorite",
                        "Archive" to "set_archived",
                        "Unarchive" to "unarchive",
                        "Hide" to "hide",
                        "Unhide" to "unhide",
                        "Move to Trash" to "delete",
                        "Restore" to "restore",
                    )
                    .forEach { (title, action) ->
                        Square(title) { perform { m.repo.photoAction(action, m.editItems) } }
                    }
            }
            "trash",
            "restore" -> {
                Heading(
                    if (kind == "trash") "Move ${m.editItems.size} items to Trash?"
                    else "Restore ${m.editItems.size} items?"
                )
                m.editItems.take(8).forEach { Hint(it.name) }
                Square("Confirm", true, !m.busy) {
                    perform {
                        if (m.editItems.firstOrNull()?.photo == true)
                            m.repo.photoAction(
                                if (kind == "trash") "delete" else "restore",
                                m.editItems,
                            )
                        else m.repo.fileAction(kind, m.editItems)
                    }
                }
            }
            "deleteCollection" -> {
                Hint("Delete ${m.editCollection?.title}? Originals remain in Photos.")
                Square("Delete collection", true) {
                    perform { m.repo.deleteCollection(m.editCollection!!) }
                }
            }
            "cancelTransfers" -> {
                Hint(
                    "Cancel pending transfers? Stored originals remain on the server. Pause photo backup to prevent a later scan from queuing these items again."
                )
                Square("Cancel pending transfers", true) {
                    m.run {
                        m.transfers.cancelAll()
                        m.sheet = "Transfers"
                    }
                }
            }
            "signOut" -> {
                Hint(
                    "Sign out and remove this account’s local originals, saved listings, and upload staging from this device?"
                )
                Square("Sign out", true) { m.signOut() }
            }
            "clearQueue",
            "reseedPhotos" -> {
                Hint(
                    if (kind == "reseedPhotos")
                        "Prepare a fresh photo seed only after a server reset. File uploads remain; photo receipts are archived, a new Photos source namespace is saved, and transfers pause. Server originals are not deleted."
                    else
                        "Clear this phone’s upload queue and staged upload bytes? Photos backup turns off and transfers pause. Server originals remain."
                )
                Square("Confirm reset", true, !m.busy) {
                    m.run {
                        m.transfers.stop()
                        withContext(Dispatchers.IO) {
                            QueueReset.begin(m.repo, kind == "reseedPhotos")
                        }
                        m.prefsVersion++
                        m.sheet = "Transfers"
                    }
                }
            }
            "revokeGrab" -> {
                Hint("Revoke this grab link? It will no longer allow downloads.")
                Square("Revoke link", true) { perform { m.repo.revoke(m.grabbed!!.s("id")) } }
            }
        }
        if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
