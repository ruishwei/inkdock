package org.inkdock.reader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface CloudVoiceCredentialSource {
    fun <T> withKey(provider: CloudVoiceProvider, block: (CharArray) -> T): T
}

enum class CloudVoiceKeyStatus { MISSING, SAVED, UNAVAILABLE }

/** Native only: never register this object with addJavascriptInterface.
 * Only encrypted bytes go to noBackupFilesDir; the non-exportable AES key stays in AndroidKeyStore.
 * The caller must obtain the user's explicit consent to save a key in this app before save().
 * save() consumes and clears its CharArray even if validation/storage fails. No key getter exists.
 */
internal class CloudVoiceKeyStore(context: Context, private val storageNamespace: String = "") : CloudVoiceCredentialSource {
    init { require(storageNamespace.isEmpty() || Regex("-[a-z0-9-]{1,40}").matches(storageNamespace)) }
    private val directory = File(context.applicationContext.noBackupFilesDir, "cloud-voice-keys$storageNamespace")

    @Synchronized
    fun save(provider: CloudVoiceProvider, value: CharArray) {
        var plain: ByteArray? = null
        try {
            validateKey(value)
            plain = ByteArray(value.size) { value[it].code.toByte() }
            if (!directory.exists() && !directory.mkdirs()) throw CloudVoiceException(CloudVoiceError.STORAGE_FAILED)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(provider))
            cipher.updateAAD(aad(provider))
            val encrypted = cipher.doFinal(plain)
            val file = atomicFile(provider)
            val stream = file.startWrite()
            try {
                val out = DataOutputStream(stream)
                out.writeInt(MAGIC); out.writeInt(VERSION)
                out.writeInt(cipher.iv.size); out.write(cipher.iv)
                out.writeInt(encrypted.size); out.write(encrypted); out.flush()
                file.finishWrite(stream)
            } catch (_: Exception) {
                file.failWrite(stream)
                throw CloudVoiceException(CloudVoiceError.STORAGE_FAILED)
            }
        } catch (error: CloudVoiceException) { throw error }
        catch (_: Exception) { throw CloudVoiceException(CloudVoiceError.STORAGE_FAILED) }
        finally { value.fill('\u0000'); plain?.fill(0) }
    }

    @Synchronized
    fun status(provider: CloudVoiceProvider): CloudVoiceKeyStatus {
        if (!exists(provider)) return CloudVoiceKeyStatus.MISSING
        return try { decrypt(provider).also { it.fill('\u0000') }; CloudVoiceKeyStatus.SAVED }
        catch (_: Exception) { CloudVoiceKeyStatus.UNAVAILABLE }
    }

    @Synchronized
    fun remove(provider: CloudVoiceProvider) {
        try {
            atomicFile(provider).delete()
            val store = keyStore()
            if (store.containsAlias(alias(provider))) store.deleteEntry(alias(provider))
            if (exists(provider) || store.containsAlias(alias(provider))) throw CloudVoiceException(CloudVoiceError.STORAGE_FAILED)
        } catch (_: Exception) { throw CloudVoiceException(CloudVoiceError.STORAGE_FAILED) }
    }

    /** Native scoped callback; no return of a key to the UI or JavaScript. */
    override fun <T> withKey(provider: CloudVoiceProvider, block: (CharArray) -> T): T {
        val key = synchronized(this) { decrypt(provider) }
        return try { block(key) } finally { key.fill('\u0000') }
    }

    private fun decrypt(provider: CloudVoiceProvider): CharArray {
        if (!exists(provider)) throw CloudVoiceException(CloudVoiceError.KEY_MISSING)
        var plain: ByteArray? = null
        try {
            val secretKey = keyStore().getKey(alias(provider), null) as? SecretKey
                ?: throw CloudVoiceException(CloudVoiceError.KEY_UNAVAILABLE)
            DataInputStream(atomicFile(provider).openRead()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw CloudVoiceException(CloudVoiceError.KEY_UNAVAILABLE)
                val ivSize = input.readInt()
                if (ivSize != 12) throw CloudVoiceException(CloudVoiceError.KEY_UNAVAILABLE)
                val iv = ByteArray(ivSize).also { input.readFully(it) }
                val size = input.readInt()
                if (size !in 17..(MAX_KEY_BYTES + 16)) throw CloudVoiceException(CloudVoiceError.KEY_UNAVAILABLE)
                val encrypted = ByteArray(size).also { input.readFully(it) }
                if (input.read() != -1) throw CloudVoiceException(CloudVoiceError.KEY_UNAVAILABLE)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                cipher.updateAAD(aad(provider))
                plain = cipher.doFinal(encrypted)
                val chars = CharArray(plain!!.size) { (plain!![it].toInt() and 255).toChar() }
                try { validateKey(chars) } catch (_: Exception) { chars.fill('\u0000'); throw CloudVoiceException(CloudVoiceError.KEY_UNAVAILABLE) }
                return chars
            }
        } catch (error: CloudVoiceException) { throw error }
        catch (_: Exception) { throw CloudVoiceException(CloudVoiceError.KEY_UNAVAILABLE) }
        finally { plain?.fill(0) }
    }

    private fun encryptionKey(provider: CloudVoiceProvider): SecretKey {
        val store = keyStore()
        (store.getKey(alias(provider), null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias(provider), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(provider: CloudVoiceProvider) = "inkdock.cloudvoice${storageNamespace}.${provider.id}.aes.v1"
    private fun aad(provider: CloudVoiceProvider) = "inkdock-cloud-voice-v1:${provider.id}".toByteArray(Charsets.US_ASCII)
    private fun atomicFile(provider: CloudVoiceProvider) = AtomicFile(File(directory, "${provider.id}.key"))
    private fun exists(provider: CloudVoiceProvider): Boolean {
        val file = atomicFile(provider).baseFile
        return file.exists() || File(file.path + ".bak").exists()
    }

    private fun validateKey(value: CharArray) {
        if (value.size !in 1..MAX_KEY_BYTES || value.any { it.code !in 33..126 }) throw CloudVoiceException(CloudVoiceError.KEY_INVALID)
    }

    companion object {
        private const val MAGIC = 0x49444b31
        private const val VERSION = 1
        private const val MAX_KEY_BYTES = 8192
    }
}
