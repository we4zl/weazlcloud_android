package io.prendie.weazlcloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

fun readJson(file: File): JSONObject =
    if (file.exists()) JSONObject(file.readText()) else JSONObject()

fun writeJson(file: File, value: JSONObject) {
    file.parentFile!!.mkdirs()
    val atomic = AtomicFile(file)
    val out = atomic.startWrite()
    try {
        out.write(value.toString().toByteArray())
        atomic.finishWrite(out)
    } catch (t: Throwable) {
        atomic.failWrite(out)
        throw t
    }
}

class Vault(context: Context) {
    private val file = File(context.noBackupFilesDir, "credential")

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("weazlcloud-device", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "weazlcloud-device",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    @Synchronized
    fun save(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val encrypted = cipher.iv + cipher.doFinal(value.toString().toByteArray())
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(encrypted)
            atomic.finishWrite(stream)
        } catch (t: Throwable) {
            atomic.failWrite(stream)
            throw t
        }
    }

    @Synchronized
    fun load(): JSONObject? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    fun clear() {
        file.delete()
    }
}
