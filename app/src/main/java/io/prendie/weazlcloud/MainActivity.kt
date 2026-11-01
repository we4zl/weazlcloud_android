package io.prendie.weazlcloud

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONObject

class CloudModel(application: android.app.Application) : AndroidViewModel(application) {
    private val app = application as CloudApplication
    val repo = app.repo
    val transfers = app.transfers
    val inbox = ShareInbox(application)
    var connected by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf("Ready")
    var locked by mutableStateOf(false)
    var vaultLocked by mutableStateOf(false)
    var mode by mutableStateOf("Files")
    var path by mutableStateOf("")
    var fileQuery by mutableStateOf("")
    var photoQuery by mutableStateOf("")
    var sort by mutableStateOf("name")
    var fileGrid by mutableStateOf(false)
    var fileTrash by mutableStateOf(false)
    var photoMode by mutableStateOf("all")
    var album by mutableStateOf("")
    var albumParent by mutableStateOf("")
    var showAlbums by mutableStateOf(false)
    var columns by mutableIntStateOf(4)
    var files by mutableStateOf<List<Item>>(emptyList())
    var photos by mutableStateOf<List<Item>>(emptyList())
    var collections by mutableStateOf<List<Collection>>(emptyList())
    var fileCursor by mutableStateOf("")
    var photoCursor by mutableStateOf("")
    var photoNavigating = false
    var photoReturnId by mutableStateOf("")
    var selectedFiles by mutableStateOf<Set<String>>(emptySet())
    var selectedPhotos by mutableStateOf<Set<String>>(emptySet())
    var selectingFiles by mutableStateOf(false)
    var selectingPhotos by mutableStateOf(false)
    var selecting: Boolean
        get() = if (mode == "Files") selectingFiles else selectingPhotos
        set(value) {
            if (mode == "Files") selectingFiles = value else selectingPhotos = value
        }

    var sheet by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Item?>(null)
    var editItems by mutableStateOf<List<Item>>(emptyList())
    var editCollection by mutableStateOf<Collection?>(null)
    var grabbed by mutableStateOf<JSONObject?>(null)
    var jobs by mutableStateOf<List<JSONObject>>(emptyList())
    var pendingShares by mutableStateOf<List<Uri>>(emptyList())
    var prefsVersion by mutableIntStateOf(0)
    private var fileGeneration = 0
    private var photoGeneration = 0

    init {
        run {
            pendingShares =
                withContext(Dispatchers.IO) { inbox.rows().map { Uri.parse(it.s("uri")) } }
            if (repo.demo == null && withContext(Dispatchers.IO) { repo.restoreIfNeeded() }) {
                connected = true
                restorePrefs()
                locked = repo.prefs.optBoolean("appLock")
                refresh()
            }
        }
        viewModelScope.launch {
            var lastCompleted = 0
            var lastRefresh = 0L
            while (isActive) {
                delay(1500)
                jobs = withContext(Dispatchers.IO) { transfers.jobs() }
                val completed = jobs.count { it.s("phase") in listOf("stored", "existing") }
                if (
                    connected &&
                        !locked &&
                        !vaultLocked &&
                        completed != lastCompleted &&
                        System.currentTimeMillis() - lastRefresh > 4000
                ) {
                    lastCompleted = completed
                    lastRefresh = System.currentTimeMillis()
                    loadFiles()
                    loadPhotos()
                    loadCollections()
                }
                status =
                    if (repo.demo != null) "Demo · synthetic local library"
                    else transfers.condition.ifBlank { "Connected" }
            }
        }
    }

    fun restorePrefs() {
        path = repo.prefs.s("filePath")
        fileQuery = repo.prefs.s("fileQuery")
        photoQuery = repo.prefs.s("photoQuery")
        sort = repo.prefs.s("fileSort").ifBlank { "name" }
        fileGrid = repo.prefs.optBoolean("fileGrid")
        photoMode = repo.prefs.s("photoMode").ifBlank { "all" }
        album = repo.prefs.s("album")
        columns = repo.prefs.optInt("columns", 4).coerceIn(2, 6)
    }

    fun save() {
        repo.prefs
            .put("filePath", path)
            .put("fileQuery", fileQuery)
            .put("photoQuery", photoQuery)
            .put("fileSort", sort)
            .put("fileGrid", fileGrid)
            .put("photoMode", photoMode)
            .put("album", album)
            .put("columns", columns)
        repo.savePrefs()
        prefsVersion++
    }

    fun setPref(key: String, value: Any) {
        repo.prefs.put(key, value)
        save()
        if (repo.demo == null && connected) transfers.schedule()
    }

    fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            } finally {
                busy = false
            }
        }
    }

    fun report(e: Exception) {
        error =
            if (e is java.io.IOException)
                "Connection interrupted. Check the server address and network, then retry."
            else e.message ?: "The operation could not finish."
        if (e is ApiFailure && e.status == 423) {
            vaultLocked = true
            preview = null
            sheet = null
            files = emptyList()
            photos = emptyList()
        }
    }

    fun io(block: () -> Unit) = run { withContext(Dispatchers.IO) { block() } }

    fun login(address: String, user: String, password: String) = run {
        transfers.stop()
        withContext(Dispatchers.IO) { repo.login(address, user, password) }
        connected = true
        vaultLocked = false
        transfers.stopped = false
        restorePrefs()
        refresh()
    }

    fun unlock(password: String) = run {
        withContext(Dispatchers.IO) { repo.unlock(password) }
        connected = true
        vaultLocked = false
        sheet = null
        restorePrefs()
        refresh()
    }

    fun demo() = run {
        transfers.stop()
        withContext(Dispatchers.IO) { repo.startDemo() }
        connected = true
        restorePrefs()
        refresh()
    }

    suspend fun refresh() {
        if (repo.demo == null)
            try {
                withContext(Dispatchers.IO) { repo.refreshAuth() }
                vaultLocked = !repo.capabilities.optBoolean("vault_unlocked")
            } catch (e: Exception) {
                report(e)
            }
        loadFiles()
        loadPhotos()
        loadCollections()
        withContext(Dispatchers.IO) { repo.reconcilePins() }
        if (repo.demo == null && !vaultLocked) {
            transfers.schedule()
            transfers.cycle(scan = true)
            jobs = withContext(Dispatchers.IO) { transfers.jobs() }
            resumeTransfers()
        }
    }

    fun resumeTransfers() {
        if (!connected || repo.demo != null || !app.inForeground || repo.prefs.optBoolean("paused"))
            return
        if (
            jobs.any { it.s("phase") !in listOf("stored", "existing", "cancelled") } ||
                repo.prefs.optBoolean("photosBackup") ||
                repo.prefs.a("folders").objects().any { it.optBoolean("enabled", true) }
        )
            transfers.start(manual = false)
    }

    suspend fun loadFiles(more: Boolean = false) {
        val generation = ++fileGeneration
        val p = path
        val q = fileQuery
        val s = sort
        val t = fileTrash
        val cursor = if (more) fileCursor else ""
        try {
            val result = withContext(Dispatchers.IO) { repo.files(p, q, s, t, cursor) }
            if (generation != fileGeneration) return
            val rows = result.a(if (t) "items" else "files").objects().map { Item(it) }
            files = if (more) (files + rows).distinctBy { it.id } else rows
            fileCursor = result.s("next_cursor")
            if (result.optBoolean("offline")) status = "Offline · saved listing"
        } catch (e: Exception) {
            if (generation == fileGeneration) report(e)
        }
    }

    suspend fun loadPhotos(more: Boolean = false, seek: String = "") {
        val generation = ++photoGeneration
        val m = photoMode
        val a = album
        val q = photoQuery
        val cursor = if (more) photoCursor else ""
        val navigation = if (more) photoNavigating else seek.isNotBlank()
        try {
            val result =
                withContext(Dispatchers.IO) { repo.photos(m, a, q, cursor, seek, navigation) }
            if (generation != photoGeneration) return
            val rows =
                result.a("items").objects().map { Item(it.put("hidden", m == "hidden"), true) }
            photos = if (more) (photos + rows).distinctBy { it.id } else rows
            photoCursor = result.s("next_cursor")
            photoNavigating = navigation
            if (seek.isNotBlank()) photoReturnId = result.s("anchor_id")
        } catch (e: Exception) {
            if (generation == photoGeneration) report(e)
        }
    }

    suspend fun loadCollections() {
        try {
            collections = withContext(Dispatchers.IO) { repo.collections(photoMode == "hidden") }
        } catch (e: Exception) {
            report(e)
        }
    }

    fun openFolder(value: String) {
        path = value
        fileQuery = ""
        fileTrash = false
        selectedFiles = emptySet()
        save()
        run { loadFiles() }
    }

    fun choosePhotoMode(value: String) {
        photoMode = value
        album = ""
        selectedPhotos = emptySet()
        save()
        run {
            loadPhotos()
            loadCollections()
        }
    }

    fun toggle(item: Item) {
        if (item.photo)
            selectedPhotos =
                selectedPhotos.toMutableSet().apply { if (!add(item.id)) remove(item.id) }
        else
            selectedFiles =
                selectedFiles.toMutableSet().apply { if (!add(item.id)) remove(item.id) }
    }

    fun selection() =
        if (mode == "Files") files.filter { it.id in selectedFiles }
        else photos.filter { it.id in selectedPhotos }

    fun edit(kind: String, items: List<Item> = selection(), collection: Collection? = null) {
        editItems = items
        editCollection = collection
        sheet = "edit:$kind"
    }

    fun action(action: String, items: List<Item> = selection()) = run {
        withContext(Dispatchers.IO) {
            if (items.firstOrNull()?.photo == true) repo.photoAction(action, items)
            else repo.fileAction(action, items)
        }
        selectedFiles = emptySet()
        selectedPhotos = emptySet()
        loadFiles()
        loadPhotos()
        loadCollections()
    }

    fun import(uris: List<Uri>) = run {
        try {
            withContext(Dispatchers.IO) {
                val destination = repo.uploadDestination(path)
                for (uri in uris) {
                    val shared = inbox.rows().firstOrNull { it.s("uri") == uri.toString() }
                    if (shared == null) transfers.enqueue(uri, destination.id)
                    else {
                        transfers.enqueue(
                            uri,
                            destination.id,
                            shared.s("name"),
                            source = "share-${shared.s("id")}",
                            relative = shared.s("name"),
                            revision = "1",
                        )
                        inbox.remove(shared.s("id"))
                    }
                }
            }
            transfers.start()
            sheet = "Transfers"
        } finally {
            pendingShares =
                withContext(Dispatchers.IO) { inbox.rows().map { Uri.parse(it.s("uri")) } }
        }
    }

    fun receiveShare(uris: List<Uri>) = run {
        try {
            withContext(Dispatchers.IO) { inbox.accept(uris) }
        } finally {
            pendingShares =
                withContext(Dispatchers.IO) { inbox.rows().map { Uri.parse(it.s("uri")) } }
        }
    }

    fun signOut() = run {
        transfers.stop()
        withContext(Dispatchers.IO) {
            repo.signOut()
            inbox.clear()
        }
        connected = false
        locked = false
        sheet = null
        preview = null
        files = emptyList()
        photos = emptyList()
        selectedFiles = emptySet()
        selectedPhotos = emptySet()
        jobs = emptyList()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: CloudModel
    private val auth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) model.locked = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle =
                androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle =
                androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        model = androidx.lifecycle.ViewModelProvider(this)[CloudModel::class.java]
        if (intent.getBooleanExtra("demo", false) && BuildConfig.DEBUG) model.demo()
        receive(intent)
        setContent {
            CloudTheme {
                CloudApp(
                    model,
                    unlockApp = {
                        val kg = getSystemService(KeyguardManager::class.java)
                        val request =
                            kg.createConfirmDeviceCredentialIntent(
                                "Unlock WeazlCloud",
                                "Use your device screen lock",
                            )
                        if (request != null) auth.launch(request)
                        else model.error = "Set up a device screen lock in Android Settings first."
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    @Suppress("DEPRECATION")
    private fun receive(intent: Intent) {
        val uris =
            when (intent.action) {
                Intent.ACTION_SEND ->
                    listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
                else -> emptyList()
            }
        if (uris.isNotEmpty()) model.receiveShare(uris)
    }

    override fun onPause() {
        super.onPause()
        (application as CloudApplication).inForeground = false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (::model.isInitialized && model.repo.prefs.optBoolean("appLock")) {
            model.locked = true
            model.preview = null
            model.sheet = null
        }
    }

    override fun onResume() {
        super.onResume()
        (application as CloudApplication).inForeground = true
        if (::model.isInitialized) model.resumeTransfers()
        if (::model.isInitialized && !model.repo.prefs.optBoolean("appLock"))
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
