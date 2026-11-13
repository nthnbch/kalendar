package ch.nthnbch.kalendar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.data.InfomaniakAuth
import ch.nthnbch.kalendar.data.Repository
import ch.nthnbch.kalendar.data.SyncOutcome
import ch.nthnbch.kalendar.data.SyncWorker
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(repository: Repository, redirect: InfomaniakAuth.Redirect?, onRedirectHandled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(Repository.DEFAULT_SERVER) }
    val oauth = InfomaniakAuth.configured
    var manual by rememberSaveable { mutableStateOf(!oauth) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    suspend fun finish(outcome: SyncOutcome) {
        when (outcome) {
            SyncOutcome.AuthFailed -> error = context.getString(R.string.login_refused)
            // Signed in but the first download failed: the home screen will retry on its own.
            is SyncOutcome.Failed -> if (repository.account.value == null) error = context.getString(R.string.login_failed, outcome.message)
            else -> Unit
        }
        if (repository.account.value != null) SyncWorker.schedule(context)
        busy = false
    }

    // The browser came back from the Infomaniak sign-in page.
    LaunchedEffect(redirect) {
        val result = redirect ?: return@LaunchedEffect
        onRedirectHandled()
        when (result) {
            is InfomaniakAuth.Redirect.Failure -> {
                error = context.getString(R.string.login_cancelled)
                busy = false
            }
            is InfomaniakAuth.Redirect.Code -> {
                busy = true
                error = null
                finish(repository.signInWithInfomaniak(result.value))
            }
        }
    }

    fun signInWithInfomaniak() {
        error = null
        if (!InfomaniakAuth.configured) {
            error = context.getString(R.string.login_not_configured)
        } else if (!InfomaniakAuth.start(context)) {
            error = context.getString(R.string.login_no_browser)
        }
    }

    fun submitManual() {
        if (busy || username.isBlank() || password.isBlank()) return
        busy = true
        error = null
        scope.launch { finish(repository.signIn(server, username, password)) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.login_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                if (oauth) {
                    Button(onClick = ::signInWithInfomaniak, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.login_with_infomaniak))
                    }
                    Text(stringResource(R.string.login_with_infomaniak_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

                if (!manual) {
                    TextButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_manual)) }
                }
                AnimatedVisibility(manual) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (oauth) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(stringResource(R.string.login_manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = username, onValueChange = { username = it },
                            label = { Text(stringResource(R.string.login_username)) },
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it },
                            label = { Text(stringResource(R.string.login_password)) },
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = server, onValueChange = { server = it },
                            label = { Text(stringResource(R.string.login_server)) },
                            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitManual() }),
                        )
                        Button(
                            onClick = ::submitManual, enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.login_action))
                        }
                        TextButton(
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://config.infomaniak.com/"))) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.login_open_assistant)) }
                    }
                }
            }
        }
    }
}
