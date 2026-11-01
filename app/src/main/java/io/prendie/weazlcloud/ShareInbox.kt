package io.prendie.weazlcloud

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** Share grants can disappear with the sending activity; copy before accepting the handoff. */
class ShareInbox(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "share-inbox").apply { mkdirs() }
    private val manifest = File(root, "manifest.json")

    @Synchronized fun rows() = readJson(manifest).a("items").objects()

    @Synchronized
    fun accept(uris: List<Uri>) {
        for (uri in uris) {
            val name =
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Shared file"
            val id = uuid()
            val file = File(root, id)
            var count = 0L
            val existing = root.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            require(
                                existing + count < 2L * 1024 * 1024 * 1024 &&
                                    root.usableSpace > 256 * 1024 * 1024
                            ) {
                                "Shared-file staging is full. Import pending files first."
                            }
                            out.write(buffer, 0, n)
                        }
                        out.fd.sync()
                    }
                }
                writeJson(
                    manifest,
                    obj(
                        "items" to
                            arr(
                                rows() +
                                    obj(
                                        "id" to id,
                                        "name" to safeName(name),
                                        "uri" to Uri.fromFile(file).toString(),
                                    )
                            )
                    ),
                )
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    }

    @Synchronized
    fun remove(id: String) {
        writeJson(manifest, obj("items" to arr(rows().filter { it.s("id") != id })))
        File(root, id).delete()
    }

    @Synchronized
    fun clear() {
        root.deleteRecursively()
    }
}
