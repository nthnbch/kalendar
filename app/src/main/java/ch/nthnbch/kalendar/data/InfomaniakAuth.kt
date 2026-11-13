package ch.nthnbch.kalendar.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import ch.nthnbch.kalendar.BuildConfig
import ch.nthnbch.kalendar.dav.CalDavClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** What the Infomaniak account gives us to talk CalDAV: the short login and a password made just for this app. */
data class DavCredentials(val login: String, val password: String)

/**
 * "Sign in with Infomaniak": OAuth 2.0 authorization code with PKCE, as the official apps do.
 * The access token is only used right here, to read the profile and to ask the API for a CalDAV
 * password; it is never stored. Afterwards the app talks to the server with that password alone.
 */
object InfomaniakAuth {

    const val REDIRECT_URI = "ch.nthnbch.kalendar://oauth2redirect"
    private const val LOGIN = "https://login.infomaniak.com"
    private const val API = "https://api.infomaniak.com/2"
    private const val PREFS = "oauth"

    /**
     * Shown only when an OAuth application is registered (client id in local.properties) and `infomaniakOAuth=true`.
     * The calendar API needs the scopes user_info and user_password, which Infomaniak does not give to third-party apps.
     */
    val configured: Boolean get() = BuildConfig.INFOMANIAK_OAUTH && BuildConfig.INFOMANIAK_CLIENT_ID.isNotBlank()

    fun authorizationUrl(context: Context): Uri {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("verifier", verifier).putString("state", state).apply()
        return Uri.parse("$LOGIN/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", BuildConfig.INFOMANIAK_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge(verifier))
            .appendQueryParameter("state", state)
            .build()
    }

    /** Opens the Infomaniak sign-in page in the browser. Returns false when no browser could handle it. */
    fun start(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, authorizationUrl(context)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    sealed interface Redirect {
        data class Code(val value: String) : Redirect
        data class Failure(val reason: String) : Redirect
    }

    /** Reads the browser's return trip. Null when [intent] is not one, so unrelated intents are ignored. */
    fun redirectFrom(context: Context, intent: Intent?): Redirect? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "ch.nthnbch.kalendar" || uri.authority != "oauth2redirect") return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expected = prefs.getString("state", null)
        val received = uri.getQueryParameter("state")
        uri.getQueryParameter("error")?.let { return Redirect.Failure(uri.getQueryParameter("error_description") ?: it) }
        // A return trip we did not start (or a replay) must not be able to sign anyone in.
        if (expected == null || received != expected) return Redirect.Failure("state")
        val code = uri.getQueryParameter("code") ?: return Redirect.Failure("code")
        return Redirect.Code(code)
    }

    /** Exchanges the code for a token, then asks the API for the CalDAV login and a fresh password. */
    fun credentials(context: Context, code: String): DavCredentials {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val verifier = prefs.getString("verifier", null) ?: throw IOException("Sign-in was not started from this app")
        prefs.edit().clear().apply()

        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("grant_type", "authorization_code")
            .addFormDataPart("client_id", BuildConfig.INFOMANIAK_CLIENT_ID)
            .addFormDataPart("code", code)
            .addFormDataPart("code_verifier", verifier)
            .addFormDataPart("redirect_uri", REDIRECT_URI)
        if (BuildConfig.INFOMANIAK_CLIENT_SECRET.isNotBlank()) form.addFormDataPart("client_secret", BuildConfig.INFOMANIAK_CLIENT_SECRET)

        val token = call(Request.Builder().url("$LOGIN/token").post(form.build()).build()).optString("access_token")
            .ifBlank { throw IOException("No access token received") }

        val profile = call(Request.Builder().url("$API/profile").header("Authorization", "Bearer $token").get().build())
        val login = profile.optJSONObject("data")?.optString("login").orEmpty().ifBlank { throw IOException("No login in profile") }

        val body = JSONObject().put("name", "Kalendar").toString().toRequestBody("application/json".toMediaType())
        val created = call(
            Request.Builder().url("$API/profile/applications/passwords").header("Authorization", "Bearer $token").post(body).build(),
        )
        val password = created.optJSONObject("data")?.optString("password").orEmpty().ifBlank { throw IOException("No password received") }
        return DavCredentials(login, password)
    }

    private fun call(request: Request): JSONObject {
        CalDavClient.sharedHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val detail = json?.optString("error_description")?.takeIf { it.isNotBlank() }
                    ?: json?.optJSONObject("error")?.optString("description")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                throw IOException("${response.code}" + (detail?.let { ": $it" } ?: ""))
            }
            return json ?: throw IOException("Unreadable answer from Infomaniak")
        }
    }

    private fun randomUrlSafe(bytes: Int): String {
        val raw = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
