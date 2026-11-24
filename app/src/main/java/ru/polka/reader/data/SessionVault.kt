package ru.polka.reader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class Credentials(val account: Account, val cookie: String)

internal class SessionVault(context: Context) {
    private val prefs = context.getSharedPreferences("polka_session", Context.MODE_PRIVATE)
    private val alias = "polka.reader.session.v1"

    fun account(): Account? = prefs.getString("account", null)?.let { encoded ->
        with(JSONObject(encoded)) {
            Account(getString("scope"), getString("server"), getLong("id"), getString("name"),
                prefs.contains("secret"), getBoolean("admin"), getBoolean("read_only"))
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    fun save(account: Account, cookie: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(account.scope.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(cookie.toByteArray(Charsets.US_ASCII))
        val metadata = JSONObject().put("scope", account.scope).put("server", account.server)
            .put("id", account.userId).put("name", account.username).put("admin", account.admin)
            .put("read_only", account.readOnly).toString()
        if (!prefs.edit().putString("account", metadata).putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("secret", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            throw IOException("Не удалось сохранить сеанс на устройстве")
        }
    }

    fun decrypt(account: Account): Credentials? {
        val encrypted = prefs.getString("secret", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.updateAAD(account.scope.toByteArray(Charsets.UTF_8))
        return Credentials(account, String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.US_ASCII))
    }

    fun clear(keepAccount: Boolean) {
        val editor = prefs.edit().remove("secret").remove("iv")
        if (!keepAccount) editor.remove("account")
        if (!editor.commit()) throw IOException("Не удалось завершить локальный сеанс")
    }
}
