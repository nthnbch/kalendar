package ch.nthnbch.kalendar.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ch.nthnbch.kalendar.dav.DavAccount
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Account(
    val server: String,
    val username: String,
    val password: String,
    val homeUrl: String,
    val displayName: String?,
    val email: String?,
) {
    val dav: DavAccount get() = DavAccount(server, username, password)
    val label: String get() = displayName ?: email ?: username
}

/** Keeps the account on the device, with the password encrypted by a key that never leaves the Android Keystore. */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    fun load(): Account? {
        val server = prefs.getString("server", null) ?: return null
        val username = prefs.getString("username", null) ?: return null
        val home = prefs.getString("home", null) ?: return null
        val password = runCatching {
            decrypt(prefs.getString("password", null) ?: return null, prefs.getString("iv", null) ?: return null)
        }.getOrNull() ?: return null
        return Account(server, username, password, home, prefs.getString("name", null), prefs.getString("email", null))
    }

    fun save(account: Account) {
        val (cipherText, iv) = encrypt(account.password)
        prefs.edit()
            .putString("server", account.server)
            .putString("username", account.username)
            .putString("password", cipherText)
            .putString("iv", iv)
            .putString("home", account.homeUrl)
            .putString("name", account.displayName)
            .putString("email", account.email)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

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

    private fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(cipherText: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kalendar.account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
