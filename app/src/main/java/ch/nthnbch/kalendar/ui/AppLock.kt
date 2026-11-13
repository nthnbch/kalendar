package ch.nthnbch.kalendar.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ch.nthnbch.kalendar.R

/**
 * Locks the app behind the device's own fingerprint, face, PIN, pattern or password.
 * The state lives for the process: leaving the app locks it again, rotating the screen does not.
 */
object AppLock {

    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    var unlocked by mutableStateOf(false)

    /** False when the device has no screen lock at all, in which case there is nothing to unlock with. */
    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(activity: FragmentActivity, title: String, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTHENTICATORS).build(),
        )
    }
}

tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun LockScreen() {
    val activity = LocalContext.current.findActivity()
    val title = stringResource(R.string.lock_title)
    fun unlock() {
        activity?.let { AppLock.authenticate(it, title) { AppLock.unlocked = true } }
    }
    LaunchedEffect(Unit) { unlock() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.lock_text), Modifier.padding(top = 8.dp, bottom = 24.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = ::unlock) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}
