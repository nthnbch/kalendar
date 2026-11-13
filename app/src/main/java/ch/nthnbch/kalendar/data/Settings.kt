package ch.nthnbch.kalendar.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** The few choices the user can make in the app's settings. Language is kept by AppCompat itself. */
object Settings {

    const val REPO_URL = "https://github.com/nthnbch/kalendar"

    /** Languages the app is translated into, as BCP 47 tags. */
    val LANGUAGES = listOf("en", "fr", "de", "es")

    enum class Theme { SYSTEM, LIGHT, DARK }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun theme(context: Context): Theme =
        runCatching { Theme.valueOf(prefs(context).getString("theme", null) ?: "SYSTEM") }.getOrDefault(Theme.SYSTEM)

    fun setTheme(context: Context, theme: Theme) {
        prefs(context).edit().putString("theme", theme.name).apply()
        applyTheme(theme)
    }

    /** Called at startup, and after a change: the whole app (including Compose) then follows the chosen mode. */
    fun applyTheme(context: Context) = applyTheme(theme(context))

    private fun applyTheme(theme: Theme) = AppCompatDelegate.setDefaultNightMode(
        when (theme) {
            Theme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Theme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Theme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        },
    )

    /** The language picked in the app, or null to follow the system. */
    fun language(): String? = AppCompatDelegate.getApplicationLocales().get(0)?.language?.takeIf { it in LANGUAGES }

    fun setLanguage(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    fun appLock(context: Context): Boolean = prefs(context).getBoolean("app_lock", false)
    fun setAppLock(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("app_lock", enabled).apply()

    fun notifyReplies(context: Context): Boolean = prefs(context).getBoolean("notify_replies", true)
    fun setNotifyReplies(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("notify_replies", enabled).apply()
}
