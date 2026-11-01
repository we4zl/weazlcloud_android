@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package io.prendie.weazlcloud

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import kotlinx.coroutines.*

fun decodeImage(file: File, max: Int = 2048): ImageBitmap? {
    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, info)
    var sample = 1
    while (info.outWidth / sample > max * 2 || info.outHeight / sample > max * 2) sample *= 2
    return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
        ?.asImageBitmap()
}

fun qrBitmap(url: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600)
    val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
    for (y in 0 until 600) for (x in 0 until 600) bitmap.setPixel(
        x,
        y,
        if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
    )
    return bitmap.asImageBitmap()
}

@Composable
fun Viewer(m: CloudModel, initial: Item, close: () -> Unit) {
    val entries =
        remember(initial.id) {
            if (initial.photo && m.photos.any { it.id == initial.id }) m.photos.toList()
            else listOf(initial)
        }
    val pager =
        rememberPagerState(
            initialPage = entries.indexOfFirst { it.id == initial.id }.coerceAtLeast(0),
            pageCount = { entries.size },
        )
    var controls by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val item = entries[pager.currentPage]
    val context = LocalContext.current
    DisposableEffect(Unit) { onDispose { m.io { m.repo.evictPreviews() } } }
    val exitViewer = {
        m.photoReturnId = item.id
        close()
    }
    Dialog(
        exitViewer,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Palette.text) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                HorizontalPager(
                    pager,
                    userScrollEnabled = !zoomed,
                    modifier = Modifier.fillMaxSize().testTag("photo-viewer-pager"),
                ) { page ->
                    MediaContent(
                        m,
                        entries[page],
                        onTap = { controls = !controls },
                        onZoom = { if (page == pager.currentPage) zoomed = it },
                    )
                }
                if (controls) {
                    Row(
                        Modifier.fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = .8f))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tool(Icons.AutoMirrored.Outlined.ArrowBack, "Close viewer", exitViewer)
                        Text(item.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
                        Tool(Icons.Outlined.Info, "Photo information") { details = !details }
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = .85f))
                            .padding(12.dp)
                    ) {
                        if (details) {
                            Hint(
                                "${item.captured.ifBlank {"Unknown capture date"}} · ${bytes(item.size)}"
                            )
                            Hint(item.path)
                            Hint("Revision ${item.revision}")
                            if (item.photo)
                                TextButton({
                                    m.run {
                                        val motion =
                                            withContext(Dispatchers.IO) {
                                                val asset =
                                                    m.repo.call(
                                                        "/api/v1/photos/assets/${Api.segment(item.id)}",
                                                        query =
                                                            mapOf(
                                                                "hidden" to
                                                                    if (item.hidden) "1" else "0"
                                                            ),
                                                    )
                                                val component =
                                                    asset.a("components").objects().firstOrNull {
                                                        it.s("id") == "motion" ||
                                                            it.s("kind") == "motion"
                                                    }
                                                        ?: error(
                                                            "This photo has no accessible motion component."
                                                        )
                                                Item(
                                                    m.repo
                                                        .call(
                                                            "/api/v1/photos/assets/${Api.segment(component.s("asset_id"))}",
                                                            query =
                                                                mapOf(
                                                                    "hidden" to
                                                                        if (item.hidden) "1"
                                                                        else "0"
                                                                ),
                                                        )
                                                        .put("hidden", item.hidden),
                                                    true,
                                                )
                                            }
                                        m.preview = motion
                                    }
                                }) {
                                    Text("Load Live Photo motion")
                                }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            if (item.photo)
                                Tool(Icons.Outlined.FavoriteBorder, "Toggle favorite") {
                                    m.action(
                                        if (
                                            m.photos.firstOrNull { it.id == item.id }?.favorite ==
                                                true
                                        )
                                            "unfavorite"
                                        else "favorite",
                                        listOf(item),
                                    )
                                }
                            Tool(Icons.Outlined.OfflinePin, "Available offline") {
                                m.io { m.repo.pin(item) }
                            }
                            Tool(Icons.Outlined.Share, "Share downloaded original") {
                                m.run {
                                    val file = withContext(Dispatchers.IO) { m.repo.original(item) }
                                    shareFile(context, file)
                                }
                            }
                            if (item.photo)
                                Tool(Icons.Outlined.PhotoAlbum, "Album membership") {
                                    close()
                                    m.edit("membership", listOf(item))
                                }
                        }
                        if (entries.size > 1)
                            Text(
                                "${pager.currentPage+1} / ${entries.size}",
                                Modifier.align(Alignment.CenterHorizontally),
                                fontSize = 12.sp,
                                color = Palette.muted,
                            )
                    }
                }
            }
        }
    }
}

fun shareFile(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType(
                    android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
                )
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share original",
        )
    )
}

@Composable
private fun MediaContent(m: CloudModel, item: Item, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    var file by remember(item.id) { mutableStateOf<File?>(null) }
    var bitmap by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    var issue by remember(item.id) { mutableStateOf<String?>(null) }
    var loading by remember(item.id) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(item.id, retry) {
        loading = true
        try {
            file = withContext(Dispatchers.IO) { m.repo.original(item) }
            if (item.type == "image") bitmap = withContext(Dispatchers.IO) { decodeImage(file!!) }
            issue = null
        } catch (e: Exception) {
            issue = e.message ?: "Could not download this original."
        } finally {
            loading = false
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (file == null) Thumbnail(m, item, Modifier.fillMaxWidth().height(300.dp))
        when (item.type) {
            "image" ->
                if (bitmap != null)
                    Image(
                        bitmap!!,
                        item.name,
                        Modifier.fillMaxSize()
                            .pointerInput(item.id) {
                                detectTapGestures(
                                    onTap = { onTap() },
                                    onDoubleTap = {
                                        scale = if (scale > 1f) 1f else 2.5f
                                        offset = Offset.Zero
                                        onZoom(scale > 1)
                                    },
                                )
                            }
                            .pointerInput(item.id) {
                                mediaGestures(isZoomed = { scale > 1f }) { pan, zoom ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    offset =
                                        if (scale == 1f) Offset.Zero
                                        else
                                            Offset(
                                                (offset.x + pan.x).coerceIn(
                                                    -size.width * (scale - 1) / 2,
                                                    size.width * (scale - 1) / 2,
                                                ),
                                                (offset.y + pan.y).coerceIn(
                                                    -size.height * (scale - 1) / 2,
                                                    size.height * (scale - 1) / 2,
                                                ),
                                            )
                                    onZoom(scale > 1)
                                }
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        contentScale = ContentScale.Fit,
                    )
            "video",
            "audio" ->
                file?.let { local ->
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.fromFile(local))
                                setMediaController(
                                    MediaController(ctx).also { it.setAnchorView(this) }
                                )
                                setOnPreparedListener { start() }
                            }
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(if (item.type == "audio") 180.dp else 420.dp),
                        onRelease = { it.stopPlayback() },
                    )
                }
            "pdf" -> file?.let { PdfContent(it) }
            "text" ->
                file?.let { local ->
                    var text by remember { mutableStateOf("") }
                    LaunchedEffect(local) {
                        text =
                            withContext(Dispatchers.IO) {
                                local.inputStream().use { String(it.readNBytes(512 * 1024)) }
                            }
                    }
                    Text(
                        text,
                        Modifier.fillMaxSize()
                            .padding(top = 65.dp, bottom = 110.dp, start = 18.dp, end = 18.dp)
                            .verticalScroll(rememberScrollState()),
                        fontSize = 14.sp,
                        color = Palette.text,
                    )
                }
            else ->
                file?.let { local ->
                    val ctx = LocalContext.current
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Heading(item.name)
                        Hint(bytes(local.length()))
                        Square("Open in another app") {
                            val uri =
                                FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", local)
                            runCatching {
                                ctx.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(uri, "application/octet-stream")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "Open original",
                                    )
                                )
                            }
                                .onFailure {
                                    m.error = "No app is available to open this file type."
                                }
                        }
                    }
                }
        }
        if (loading) CircularProgressIndicator(color = Palette.lime)
        if (issue != null)
            Column(
                Modifier.background(Palette.panel).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Hint(issue!!)
                Square("Retry download") { retry++ }
            }
    }
}

@Composable
private fun PdfContent(file: File) {
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(file) {
        count =
            withContext(Dispatchers.IO) {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                    .use { it.pageCount }
            }
    }
    LazyColumn(
        contentPadding = PaddingValues(top = 60.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count) { index ->
            var bitmap by remember(file, index) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(file, index) {
                bitmap =
                    withContext(Dispatchers.IO) {
                        PdfRenderer(
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            )
                            .use { r ->
                                r.openPage(index).use { p ->
                                    val width = 1080
                                    val b =
                                        Bitmap.createBitmap(
                                            width,
                                            width * p.height / p.width,
                                            Bitmap.Config.ARGB_8888,
                                        )
                                    b.eraseColor(android.graphics.Color.WHITE)
                                    p.render(
                                        b,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                    )
                                    b.asImageBitmap()
                                }
                            }
                    }
            }
            if (bitmap != null)
                Image(
                    bitmap!!,
                    "Page ${index+1}",
                    Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            else
                Box(Modifier.fillMaxWidth().height(500.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
        }
    }
}
