package io.prendie.weazlcloud

import java.io.File

/** Explicit user-requested resets are journaled and completed before workers attach on relaunch. */
object QueueReset {
    fun begin(repo: Repository, reseedPhotos: Boolean) {
        val jobs = readJson(File(repo.root, "uploads.json")).a("jobs").objects()
        val removed = jobs.filter { !reseedPhotos || it.s("endpoint") == "/api/v1/photos/uploads" }
        val retained = jobs.filter { it !in removed }
        val intent =
            obj(
                "photosOnly" to reseedPhotos,
                "namespace" to if (reseedPhotos) "android-mediastore-${uuid()}" else "",
                "removed" to arr(removed.map { it.s("id") }),
                "retained" to arr(retained),
            )
        writeJson(
            File(repo.root, "archives/uploads-before-reset-${uuid()}.json"),
            obj("jobs" to arr(jobs)),
        )
        writeJson(File(repo.root, "queue-reset.json"), intent)
        recover(repo)
    }

    fun recover(repo: Repository) {
        val journal = File(repo.root, "queue-reset.json")
        if (!journal.exists()) return
        val intent = readJson(journal)
        require(intent.has("retained") && intent.has("removed"))
        writeJson(File(repo.root, "uploads.json"), obj("jobs" to intent.a("retained")))
        for (id in intent.a("removed").strings()) {
            require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid reset checkpoint." }
            File(repo.root, "staging/$id").deleteRecursively()
        }
        val organization = File(repo.root, "organization.json")
        if (organization.exists()) {
            writeJson(
                File(repo.root, "archives/organization-before-reset-${uuid()}.json"),
                readJson(organization),
            )
            organization.delete()
        }
        repo.prefs.put("paused", true)
        if (intent.optBoolean("photosOnly")) {
            require(intent.s("namespace").startsWith("android-mediastore-"))
            repo.prefs.put("photoNamespace", intent.s("namespace"))
        } else repo.prefs.put("photosBackup", false)
        repo.savePrefs()
        journal.delete()
    }
}
