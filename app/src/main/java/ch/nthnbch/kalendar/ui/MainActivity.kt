package ch.nthnbch.kalendar.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.nthnbch.kalendar.data.InfomaniakAuth
import ch.nthnbch.kalendar.data.Repository

class MainActivity : ComponentActivity() {

    /** Destination asked by a widget or a notification, until the screen has acted on it. */
    private var link by mutableStateOf<Link?>(null)

    /** Result of the Infomaniak sign-in page, until the login screen has acted on it. */
    private var redirect by mutableStateOf<InfomaniakAuth.Redirect?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            link = Links.parse(intent)
            redirect = InfomaniakAuth.redirectFrom(this, intent)
        }
        val repository = Repository.get(this)
        setContent {
            KalendarTheme {
                val account by repository.account.collectAsStateWithLifecycle()
                if (account == null) LoginScreen(repository, redirect, onRedirectHandled = { redirect = null })
                else HomeScreen(repository, link, onLinkHandled = { link = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Links.parse(intent)?.let { link = it }
        InfomaniakAuth.redirectFrom(this, intent)?.let { redirect = it }
    }
}

@Composable
fun KalendarTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8DC8FF), secondary = Color(0xFFFF8FB6))
        else -> lightColorScheme(primary = Color(0xFF0A5FD8), secondary = Color(0xFFD81B60))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
