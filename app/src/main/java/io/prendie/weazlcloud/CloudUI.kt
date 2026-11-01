@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package io.prendie.weazlcloud

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

object Palette {
    val bg = Color(0xff111215)
    val panel = Color(0xff17181c)
    val raised = Color(0xff1c1d21)
    val line = Color(0xff2b2d33)
    val text = Color(0xffe9e8e1)
    val muted = Color(0xff9798a5)
    val lime = Color(0xffdeef64)
    val violet = Color(0xffb56aff)
    val mint = Color(0xff4ef2b0)
    val pink = Color(0xffef68c8)
}

@Composable
fun CloudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Palette.lime,
                onPrimary = Palette.bg,
                secondary = Palette.violet,
                background = Palette.bg,
                surface = Palette.panel,
                onSurface = Palette.text,
                onBackground = Palette.text,
                outline = Palette.line,
                error = Palette.pink,
            ),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(2.dp),
                small = RoundedCornerShape(3.dp),
                medium = RoundedCornerShape(4.dp),
                large = RoundedCornerShape(8.dp),
            ),
        content = content,
    )
}

@Composable
fun Square(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick,
        enabled = enabled,
        shape = RoundedCornerShape(1.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (primary) Palette.lime else Palette.raised,
                contentColor = if (primary) Palette.bg else Palette.text,
            ),
        border = BorderStroke(1.dp, Palette.line),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Text(text, fontSize = 14.sp)
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.panel).border(1.dp, Palette.line).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Hint(text: String) {
    Text(text, color = Palette.muted, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
fun Heading(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
}

@Composable
fun Tool(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(icon, label, tint = Palette.lime, modifier = Modifier.size(21.dp)) }
}

@Composable
fun Field(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
        ),
        visualTransformation =
            if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(3.dp),
    )
}

@Composable
fun Empty(title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.FolderOpen, null, tint = Palette.muted, modifier = Modifier.size(36.dp))
        Heading(title)
        Hint(detail)
    }
}

@Composable
fun CloudApp(m: CloudModel, unlockApp: () -> Unit) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            if (it.isNotEmpty()) m.import(it)
        }
    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val filesState = rememberLazyGridState()
    val photosState = rememberLazyGridState()
    LaunchedEffect(m.photoReturnId) {
        val index = m.photos.indexOfFirst { it.id == m.photoReturnId }
        if (index >= 0) photosState.scrollToItem(index)
    }
    LaunchedEffect(m.connected, m.fileQuery, m.sort) {
        if (m.connected) {
            delay(350)
            m.save()
            m.loadFiles()
        }
    }
    LaunchedEffect(m.connected, m.photoQuery) {
        if (m.connected) {
            delay(350)
            m.save()
            m.loadPhotos()
        }
    }
    BackHandler(
        m.connected &&
            m.sheet == null &&
            m.preview == null &&
            (m.path.isNotEmpty() ||
                m.selecting ||
                m.album.isNotEmpty() ||
                m.albumParent.isNotEmpty())
    ) {
        if (m.selecting) {
            m.selecting = false
            m.selectedFiles = emptySet()
            m.selectedPhotos = emptySet()
        } else if (m.mode == "Files") m.openFolder(m.path.substringBeforeLast('/', ""))
        else if (m.album.isNotBlank()) {
            m.album = ""
            m.showAlbums = true
        } else
            m.albumParent = m.collections.firstOrNull { it.id == m.albumParent }?.parent.orEmpty()
    }
    Surface(Modifier.fillMaxSize(), color = Palette.bg) {
        if (!m.connected) Connect(m)
        else if (m.locked)
            Column(
                Modifier.fillMaxSize().safeDrawingPadding(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    null,
                    tint = Palette.violet,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(16.dp))
                Heading("WeazlCloud is locked")
                Square("Unlock", true, onClick = unlockApp)
            }
        else
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.padding(horizontal = 18.dp).height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painterResource(R.drawable.brand_mark),
                        "WeazlCloud",
                        Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Heading("WeazlCloud")
                    Spacer(Modifier.weight(1f))
                    TextButton({ m.sheet = "Settings" }) {
                        Text(
                            if (m.repo.demo != null) "AM"
                            else
                                m.repo.capabilities
                                    .o("profile")
                                    .s("full_name")
                                    .split(' ')
                                    .mapNotNull { it.firstOrNull() }
                                    .take(2)
                                    .joinToString("")
                                    .ifEmpty { "Account" },
                            color = Palette.lime,
                        )
                    }
                }
                Row(
                    Modifier.padding(horizontal = 18.dp)
                        .background(Palette.line, RoundedCornerShape(24.dp))
                        .padding(3.dp)
                ) {
                    listOf("Files", "Photos").forEach { name ->
                        Box(
                            Modifier.weight(1f)
                                .height(34.dp)
                                .background(
                                    if (m.mode == name) Color(0xff606168) else Color.Transparent,
                                    RoundedCornerShape(24.dp),
                                )
                                .clickable { m.mode = name },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (m.repo.demo != null)
                    Text(
                        "DEMO",
                        Modifier.padding(start = 18.dp, top = 6.dp),
                        fontSize = 10.sp,
                        color = Palette.mint,
                        letterSpacing = 1.sp,
                    )
                if (m.vaultLocked)
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Heading("Server vault locked")
                        Square("Unlock vault", true) { m.sheet = "Unlock vault" }
                        TextButton({ m.connected = false }) { Text("Sign in again") }
                    }
                else
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            refreshScope.launch {
                                refreshing = true
                                try {
                                    if (m.mode == "Files") m.loadFiles()
                                    else {
                                        m.loadPhotos()
                                        m.loadCollections()
                                    }
                                } finally {
                                    refreshing = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        if (m.mode == "Files")
                            FilesPane(m, filesState) { picker.launch(arrayOf("*/*")) }
                        else PhotosPane(m, photosState)
                    }
                Column(Modifier.padding(horizontal = 18.dp)) {
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Palette.panel)
                            .border(1.dp, Palette.line)
                            .clickable { m.sheet = "Transfers" }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.SwapVert, null, tint = Palette.violet)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            val count =
                                m.jobs.count {
                                    it.s("phase") !in listOf("stored", "existing", "cancelled")
                                }
                            Text(
                                if (count == 0) "No transfers waiting"
                                else "$count transfers remaining",
                                fontSize = 14.sp,
                            )
                            Text(
                                m.status,
                                color = Palette.muted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            null,
                            Modifier.size(16.dp),
                            tint = Palette.muted,
                        )
                    }
                    Row {
                        listOf("Backup", "Offline", "Grabs").forEach { name ->
                            TextButton({ m.sheet = name }, Modifier.weight(1f)) {
                                Text(name, fontSize = 12.sp, color = Palette.text)
                            }
                        }
                    }
                }
            }
    }
    if (m.sheet != null && !m.locked)
        Modal(m.sheet!!.removePrefix("edit:"), { m.sheet = null }) { Utility(m) }
    if (m.preview != null && !m.locked) Viewer(m, m.preview!!) { m.preview = null }
    if (m.error != null)
        AlertDialog(
            onDismissRequest = { m.error = null },
            title = { Text("WeazlCloud") },
            text = { Text(m.error!!) },
            confirmButton = { TextButton({ m.error = null }) { Text("OK") } },
        )
}

@Composable
private fun Connect(m: CloudModel) {
    var address by remember { mutableStateOf("https://weazlcloud.prendie.io") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var vault by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Image(painterResource(R.drawable.brand_mark), null, Modifier.size(72.dp))
        Text("WeazlCloud", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Heading("Connect to your server")
        Field("Server address", address) { address = it }
        Field("Username", username) { username = it }
        Field("Password", password, true) { password = it }
        Square(
            if (m.busy) "Connecting…" else "Connect",
            true,
            !m.busy && username.isNotBlank() && password.isNotBlank(),
        ) {
            val secret = password
            password = ""
            m.login(address, username.trim(), secret)
        }
        if (m.vaultLocked) {
            Field("Separate vault passphrase", vault, true) { vault = it }
            Square("Unlock vault", true, !m.busy) {
                val secret = vault
                vault = ""
                m.unlock(secret)
            }
        }
        Hint("Your server account connects this device. Photo backup is enabled separately.")
        TextButton({ m.sheet = "Privacy policy" }) { Text("Privacy policy") }
        HorizontalDivider(color = Palette.line)
        Square("Explore Demo", enabled = !m.busy) { m.demo() }
        Hint("Synthetic local files and photos. No server connection.")
        if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.lime)
    }
}

@Composable
private fun FilesPane(m: CloudModel, state: LazyGridState, upload: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (m.path.isNotEmpty())
                Tool(Icons.AutoMirrored.Outlined.ArrowBack, "Parent folder") {
                    m.openFolder(m.path.substringBeforeLast('/', ""))
                }
            Text(
                if (m.fileTrash) "Trash" else m.path.substringAfterLast('/').ifBlank { "Library" },
                Modifier.weight(1f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton({
                m.selecting = !m.selecting
                m.selectedFiles = emptySet()
            }) {
                Text(if (m.selecting) "Cancel" else "Select", fontSize = 13.sp)
            }
            Box {
                Tool(Icons.Outlined.MoreHoriz, "File options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    MenuEntry("New folder") {
                        menu = false
                        m.edit("folder")
                    }
                    MenuEntry("Upload files") {
                        menu = false
                        upload()
                    }
                    if (m.pendingShares.isNotEmpty())
                        MenuEntry("Import ${m.pendingShares.size} shared files here") {
                            menu = false
                            m.import(m.pendingShares)
                        }
                    MenuEntry(if (m.fileGrid) "List view" else "Grid view") {
                        menu = false
                        m.fileGrid = !m.fileGrid
                        m.save()
                    }
                    listOf("name", "modified", "size", "type").forEach { sort ->
                        MenuEntry("Sort by $sort") {
                            menu = false
                            m.sort = sort
                            m.save()
                        }
                    }
                    MenuEntry("Trash") {
                        menu = false
                        m.fileTrash = true
                        m.run { m.loadFiles() }
                    }
                    MenuEntry("Library") {
                        menu = false
                        m.openFolder("")
                    }
                    MenuEntry("Refresh") {
                        menu = false
                        m.run { m.loadFiles() }
                    }
                }
            }
        }
        Search(
            "Search files (* and ? supported)",
            m.fileQuery,
            Modifier.padding(horizontal = 18.dp),
        ) {
            m.fileQuery = it
        }
        if (m.selectedFiles.isNotEmpty())
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hint("${m.selectedFiles.size} selected")
                TextButton({ m.edit("move") }) { Text("Move") }
                TextButton({ m.edit("copy") }) { Text("Copy") }
                TextButton({ m.edit(if (m.fileTrash) "restore" else "trash") }) {
                    Text(if (m.fileTrash) "Restore" else "Trash")
                }
            }
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (m.fileGrid) 2 else 1),
            state = state,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(m.files, key = { it.id }) { item ->
                var options by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .background(Palette.panel)
                            .border(1.dp, Palette.line)
                            .combinedClickable(
                                onClick = {
                                    if (m.selecting) m.toggle(item)
                                    else if (item.folder) m.openFolder(item.path)
                                    else m.preview = item
                                },
                                onLongClick = { options = true },
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (m.selecting)
                            Icon(
                                if (item.id in m.selectedFiles) Icons.Outlined.CheckCircle
                                else Icons.Outlined.RadioButtonUnchecked,
                                null,
                                tint = Palette.lime,
                                modifier = Modifier.size(22.dp),
                            )
                        Icon(
                            itemIcon(item),
                            null,
                            tint = Palette.violet,
                            modifier = Modifier.size(25.dp),
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                item.name,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (item.folder) "Folder" else bytes(item.size),
                                color = Palette.muted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    DropdownMenu(options, { options = false }) {
                        ItemMenu(m, item) { options = false }
                    }
                }
            }
            if (m.files.isEmpty())
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Empty("No files", "This folder or search has no items.")
                }
            if (m.fileCursor.isNotBlank())
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Square("Load more") { m.run { m.loadFiles(true) } }
                }
        }
    }
}

fun itemIcon(item: Item) =
    when (item.type) {
        "folder" -> Icons.Outlined.Folder
        "image" -> Icons.Outlined.Image
        "video" -> Icons.Outlined.Movie
        "audio" -> Icons.Outlined.MusicNote
        "pdf" -> Icons.Outlined.PictureAsPdf
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }

@Composable
fun MenuEntry(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontSize = 14.sp) }, onClick = onClick)
}

@Composable
private fun Search(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value,
        onChange,
        singleLine = true,
        textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
        cursorBrush = SolidColor(Palette.lime),
        modifier =
            modifier
                .fillMaxWidth()
                .background(Palette.raised, RoundedCornerShape(5.dp))
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty())
                    Text(label, color = Palette.muted, fontSize = 13.sp, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun ItemMenu(m: CloudModel, item: Item, close: () -> Unit) {
    MenuEntry("Select") {
        close()
        m.selecting = true
        m.toggle(item)
    }
    if (item.photo) {
        if (m.photoMode == "trash")
            MenuEntry("Restore") {
                close()
                m.action("restore", listOf(item))
            }
        else {
            MenuEntry(if (item.favorite) "Unfavorite" else "Favorite") {
                close()
                m.action(if (item.favorite) "unfavorite" else "favorite", listOf(item))
            }
            MenuEntry(if (item.wire.optBoolean("archived")) "Unarchive" else "Archive") {
                close()
                m.action(
                    if (item.wire.optBoolean("archived")) "unarchive" else "set_archived",
                    listOf(item),
                )
            }
            MenuEntry(if (item.hidden) "Unhide" else "Hide") {
                close()
                m.action(if (item.hidden) "unhide" else "hide", listOf(item))
            }
            MenuEntry("Add / remove album membership") {
                close()
                m.edit("membership", listOf(item))
            }
            MenuEntry("Move to Trash") {
                close()
                m.edit("trash", listOf(item))
            }
        }
    } else if (m.fileTrash)
        MenuEntry("Restore") {
            close()
            m.action("restore", listOf(item))
        }
    else
        listOf("Rename" to "rename", "Move" to "move", "Copy" to "copy", "Move to Trash" to "trash")
            .forEach { (title, action) ->
                MenuEntry(title) {
                    close()
                    m.edit(action, listOf(item))
                }
            }
    MenuEntry("Available offline") {
        close()
        m.io { m.repo.pin(item) }
    }
    MenuEntry("Create grab") {
        close()
        m.edit("grab", listOf(item))
    }
}

@Composable
private fun PhotosPane(m: CloudModel, state: LazyGridState) {
    var menu by remember { mutableStateOf(false) }
    var pinch by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            listOf(
                    "Timeline" to "all",
                    "Recent" to "recent",
                    "Favorites" to "favorites",
                    "Archive" to "archived",
                    "Hidden" to "hidden",
                    "Albums" to "albums",
                )
                .forEach { (title, value) ->
                    Box(
                        Modifier.heightIn(min = 44.dp).clickable {
                            m.showAlbums = value == "albums"
                            if (value != "albums") m.choosePhotoMode(value)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title,
                            fontSize = 12.sp,
                            color =
                                if (
                                    (value == "albums" && m.showAlbums) ||
                                        (!m.showAlbums && m.photoMode == value)
                                )
                                    Palette.lime
                                else Palette.muted,
                        )
                    }
                }
        }
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Search("Search photos", m.photoQuery, Modifier.weight(1f)) { m.photoQuery = it }
            TextButton({
                m.selecting = !m.selecting
                m.selectedPhotos = emptySet()
            }) {
                Text(if (m.selecting) "Cancel" else "Select", fontSize = 13.sp)
            }
            Box {
                Tool(Icons.Outlined.MoreHoriz, "Photo options") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    MenuEntry("New album") {
                        menu = false
                        m.edit("album")
                    }
                    MenuEntry("New album folder") {
                        menu = false
                        m.edit("albumFolder")
                    }
                    MenuEntry("Jump to date") {
                        menu = false
                        m.edit("date")
                    }
                    MenuEntry("Larger thumbnails") {
                        menu = false
                        m.columns = (m.columns - 1).coerceAtLeast(2)
                        m.save()
                    }
                    MenuEntry("Smaller thumbnails") {
                        menu = false
                        m.columns = (m.columns + 1).coerceAtMost(6)
                        m.save()
                    }
                    MenuEntry("Review duplicates") {
                        menu = false
                        m.sheet = "Duplicates"
                    }
                    MenuEntry("Trash") {
                        menu = false
                        m.showAlbums = false
                        m.choosePhotoMode("trash")
                    }
                    MenuEntry("Refresh") {
                        menu = false
                        m.run {
                            m.loadPhotos()
                            m.loadCollections()
                        }
                    }
                }
            }
        }
        if (m.album.isNotEmpty() || m.albumParent.isNotEmpty())
            Row(
                Modifier.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tool(Icons.AutoMirrored.Outlined.ArrowBack, "Back to albums") {
                    if (m.album.isNotEmpty()) {
                        m.album = ""
                        m.showAlbums = true
                    } else
                        m.albumParent =
                            m.collections.firstOrNull { it.id == m.albumParent }?.parent.orEmpty()
                }
                Hint(
                    m.collections
                        .firstOrNull { it.id == m.album || it.id == m.albumParent }
                        ?.title
                        .orEmpty()
                )
            }
        if (!m.showAlbums && m.photoMode != "trash") DateRail(m, state)
        if (m.selectedPhotos.isNotEmpty())
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Hint("${m.selectedPhotos.size} selected")
                TextButton({ m.edit("membership") }) { Text("Album") }
                TextButton({ m.edit("grab") }) { Text("Grab") }
                TextButton({ m.edit("photoActions") }) { Text("Actions") }
            }
        if (m.showAlbums)
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(m.collections.filter { it.parent == m.albumParent }, key = { it.id }) { c ->
                    var options by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(Palette.panel)
                                .border(1.dp, Palette.line)
                                .combinedClickable(
                                    onClick = {
                                        if (c.kind == "folder") m.albumParent = c.id
                                        else {
                                            m.album = c.id
                                            m.showAlbums = false
                                            m.save()
                                            m.run { m.loadPhotos() }
                                        }
                                    },
                                    onLongClick = { options = true },
                                )
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (c.kind == "folder") Icons.Outlined.Folder
                                else Icons.Outlined.PhotoAlbum,
                                null,
                                tint = Palette.violet,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, fontSize = 14.sp)
                                Hint(if (c.kind == "folder") "Folder" else "${c.count} photos")
                            }
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                null,
                                tint = Palette.muted,
                            )
                        }
                        DropdownMenu(options, { options = false }) {
                            MenuEntry("Rename / move") {
                                options = false
                                m.edit("collectionRename", collection = c)
                            }
                            MenuEntry("Delete collection") {
                                options = false
                                m.edit("deleteCollection", collection = c)
                            }
                            if (c.kind == "album") {
                                MenuEntry("Available offline") {
                                    options = false
                                    m.io { m.repo.pin(album = c, hidden = m.photoMode == "hidden") }
                                }
                                MenuEntry("Create album grab") {
                                    options = false
                                    m.edit("grab", collection = c)
                                }
                            }
                        }
                    }
                }
                if (m.collections.none { it.parent == m.albumParent })
                    item { Empty("No albums", "Create an album or back up device organization.") }
            }
        else
            LazyVerticalGrid(
                GridCells.Fixed(m.columns),
                state = state,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier.pointerInput(m.columns) {
                        mediaGestures { _, zoom ->
                            pinch *= zoom
                            if (pinch < 0.78f || pinch > 1.28f) {
                                val anchor = state.firstVisibleItemIndex
                                m.columns = (m.columns + if (pinch < 1) 1 else -1).coerceIn(2, 6)
                                pinch = 1f
                                m.save()
                                scope.launch { state.scrollToItem(anchor) }
                            }
                        }
                    },
            ) {
                items(m.photos, key = { it.id }) { item ->
                    var options by remember { mutableStateOf(false) }
                    Column {
                        Box(
                            Modifier.fillMaxWidth()
                                .aspectRatio(1f)
                                .combinedClickable(
                                    onClick = {
                                        if (m.selecting) m.toggle(item) else m.preview = item
                                    },
                                    onLongClick = { options = true },
                                )
                        ) {
                            Thumbnail(m, item, Modifier.fillMaxSize())
                            if (m.selecting)
                                Icon(
                                    if (item.id in m.selectedPhotos) Icons.Outlined.CheckCircle
                                    else Icons.Outlined.RadioButtonUnchecked,
                                    null,
                                    tint = Palette.lime,
                                    modifier =
                                        Modifier.align(Alignment.TopEnd)
                                            .background(Palette.bg.copy(alpha = .7f))
                                            .padding(4.dp)
                                            .size(if (m.columns >= 5) 16.dp else 22.dp),
                                )
                            else if (item.favorite)
                                Icon(
                                    Icons.Outlined.Favorite,
                                    null,
                                    tint = Palette.text,
                                    modifier =
                                        Modifier.align(Alignment.BottomStart)
                                            .padding(5.dp)
                                            .size(14.dp),
                                )
                            DropdownMenu(options, { options = false }) {
                                ItemMenu(m, item) { options = false }
                            }
                        }
                        if (m.columns <= 3)
                            Text(
                                item.captured.take(10).ifBlank { "Unknown date" },
                                fontSize = 10.sp,
                                color = Palette.muted,
                                maxLines = 1,
                            )
                    }
                }
                if (m.photos.isEmpty())
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Empty("No photos", "This collection has no accessible items.")
                    }
                if (m.photoCursor.isNotBlank())
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Square("Load more") { m.run { m.loadPhotos(true) } }
                    }
            }
    }
}

@Composable
fun Thumbnail(m: CloudModel, item: Item, modifier: Modifier = Modifier) {
    var bitmap by remember(item.cacheKey, m.repo.demo) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.cacheKey) {
        withContext(Dispatchers.IO) {
                runCatching { m.repo.thumbnail(item)?.let { decodeImage(it, 600) } }
            }
            .getOrNull()
            ?.let { bitmap = it }
    }
    Box(modifier.background(Palette.panel).clipToBounds(), contentAlignment = Alignment.Center) {
        if (bitmap != null)
            Image(bitmap!!, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(itemIcon(item), item.name, tint = Palette.violet)
    }
}

@Composable
fun Modal(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        close,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Palette.bg) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Heading(title.replaceFirstChar { it.uppercase() })
                    Spacer(Modifier.weight(1f))
                    TextButton(close) { Text("Done") }
                }
                HorizontalDivider(color = Palette.line)
                content()
            }
        }
    }
}

@Composable
private fun DateRail(m: CloudModel, state: LazyGridState) {
    var months by remember { mutableStateOf<List<String>>(emptyList()) }
    var unknown by remember { mutableIntStateOf(0) }
    var index by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(m.photoMode, m.album, m.photoQuery) {
        index = 0f
        runCatching {
            withContext(Dispatchers.IO) {
                m.repo.call(
                    "/api/v1/photos/dates",
                    query =
                        mapOf(
                            "mode" to m.photoMode,
                            "album" to m.album,
                            "q" to m.photoQuery,
                            "hidden" to if (m.photoMode == "hidden") "1" else "0",
                        ),
                )
            }
        }
            .getOrNull()
            ?.let {
                months = it.a("months").objects().map { r -> r.s("month") }
                unknown = it.optInt("unknown_dates")
            }
    }
    if (months.size > 1 || unknown > 0)
        Row(
            Modifier.padding(horizontal = 18.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (months.size > 1) {
                Slider(
                    index,
                    { index = it },
                    valueRange = 0f..months.lastIndex.toFloat(),
                    steps = (months.size - 2).coerceAtLeast(0),
                    onValueChangeFinished = {
                        m.run {
                            m.loadPhotos(
                                seek = months[index.toInt().coerceIn(months.indices)] + "-01"
                            )
                        }
                    },
                    modifier =
                        Modifier.weight(1f).semantics {
                            contentDescription = "Photo timeline month"
                        },
                    thumb = {
                        Box(
                            Modifier.size(26.dp)
                                .background(
                                    Palette.text,
                                    androidx.compose.foundation.shape.CircleShape,
                                )
                        )
                    },
                    track = { slider ->
                        Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                            val end =
                                androidx.compose.ui.geometry.Offset(size.width, size.height / 2)
                            val start = androidx.compose.ui.geometry.Offset(0f, size.height / 2)
                            drawLine(Palette.line, start, end, size.height, StrokeCap.Round)
                            val fraction = slider.value / months.lastIndex.coerceAtLeast(1)
                            drawLine(
                                Palette.lime,
                                start,
                                end.copy(x = size.width * fraction),
                                size.height,
                                StrokeCap.Round,
                            )
                        }
                    },
                )
                Text(
                    months[index.toInt().coerceIn(months.indices)],
                    fontSize = 12.sp,
                    color = Palette.muted,
                )
            }
            if (unknown > 0)
                TextButton({ m.run { m.loadPhotos(seek = "unknown") } }) {
                    Text("Unknown", fontSize = 12.sp)
                }
        }
}
