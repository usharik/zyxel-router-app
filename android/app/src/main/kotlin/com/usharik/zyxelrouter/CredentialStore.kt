package com.usharik.zyxelrouter

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the address and login in preferences; the password is encrypted with an Android Keystore key. */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("router", Context.MODE_PRIVATE)

    var address: String
        get() = prefs.getString("address", "192.168.1.1")!!
        set(value) = prefs.edit { putString("address", value) }
    var username: String
        get() = prefs.getString("username", "admin")!!
        set(value) = prefs.edit { putString("username", value) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun savePassword(password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(password.toByteArray())
        prefs.edit {
            putString("pw", Base64.encodeToString(data, Base64.NO_WRAP))
            putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    fun readPassword(): String? {
        val data = prefs.getString("pw", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)))
        }.getOrNull()
    }

    fun clearPassword() = prefs.edit { remove("pw"); remove("iv") }

    private companion object { const val ALIAS = "router_password" }
}
