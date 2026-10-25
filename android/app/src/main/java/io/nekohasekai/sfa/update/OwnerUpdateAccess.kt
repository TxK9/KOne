package io.nekohasekai.sfa.update

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.nekohasekai.sfa.BuildConfig
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A console-login enrollment grants only owner update reads, never administrator actions. */
object OwnerUpdateAccess {
    private fun prefs(context: Context) = context.getSharedPreferences("kone_owner_updates", Context.MODE_PRIVATE)
    @Synchronized fun deviceId(context: Context): String = prefs(context).getString("device", null) ?: UUID.randomUUID().toString().also {
        check(prefs(context).edit().putString("device", it).commit())
    }
    fun credential(context: Context): String? = if (!BuildConfig.KNINE_OWNER) null else
        prefs(context).getString("credential", null)?.let(OwnerUpdateVault::open)
    fun accept(context: Context, payload: String) {
        require(BuildConfig.KNINE_OWNER && payload.length <= 2048)
        val data = JSONObject(payload)
        require(data.getString("feed") == BuildConfig.KONE_UPDATE_FEED && BuildConfig.KONE_UPDATE_FEED.isNotBlank())
        val token = data.getString("credential")
        require(Regex("kou_[A-Za-z0-9_-]{43}").matches(token))
        check(prefs(context).edit().putString("credential", OwnerUpdateVault.seal(token)).commit())
    }
    fun forget(context: Context) { prefs(context).edit().remove("credential").apply() }
}

internal object OwnerUpdateVault {
    private const val ALIAS = "kone_owner_update_credential"
    private const val KEYSTORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    fun forget() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}
