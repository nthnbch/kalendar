package ch.nthnbch.kalendar.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.nthnbch.kalendar.BuildConfig
import ch.nthnbch.kalendar.R
import ch.nthnbch.kalendar.data.Settings
import ch.nthnbch.kalendar.data.Updater
import kotlinx.coroutines.launch
import ch.nthnbch.kalendar.data.Settings.Theme

/** Native names: a language must stay recognisable whatever the app is currently set to. */
private val LANGUAGE_NAMES = mapOf("en" to "English", "fr" to "Français", "de" to "Deutsch", "es" to "Español")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    var theme by remember { mutableStateOf(Settings.theme(context)) }
    var language by remember { mutableStateOf(Settings.language()) }
    var lock by remember { mutableStateOf(Settings.appLock(context)) }
    var lockUnavailable by remember { mutableStateOf(false) }
    var replies by remember { mutableStateOf(Settings.notifyReplies(context)) }
    var autoUpdate by remember { mutableStateOf(Settings.updateCheck(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cancel)) } },
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(R.string.settings_appearance)
            Choice(
                stringResource(R.string.settings_language),
                language?.let { LANGUAGE_NAMES.getValue(it) } ?: stringResource(R.string.settings_system),
                listOf<Pair<String?, String>>(null to stringResource(R.string.settings_system)) + Settings.LANGUAGES.map { it to LANGUAGE_NAMES.getValue(it) },
            ) {
                language = it
                Settings.setLanguage(it)
            }
            Choice(
                stringResource(R.string.settings_theme),
                stringResource(themeName(theme)),
                Theme.entries.map { it to stringResource(themeName(it)) },
            ) {
                theme = it
                Settings.setTheme(context, it)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(R.string.settings_notifications)
            Toggle(stringResource(R.string.settings_replies), stringResource(R.string.settings_replies_hint), replies) {
                replies = it
                Settings.setNotifyReplies(context, it)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(R.string.settings_security)
            Toggle(
                stringResource(R.string.settings_lock),
                if (lockUnavailable) stringResource(R.string.settings_lock_unavailable) else stringResource(R.string.settings_lock_hint),
                lock,
                error = lockUnavailable,
            ) { wanted ->
                val activity = context.findActivity()
                when {
                    !wanted -> {
                        lock = false
                        Settings.setAppLock(context, false)
                    }
                    activity == null || !AppLock.available(context) -> lockUnavailable = true
                    else -> {
                        lockUnavailable = false
                        // Prove it works before turning it on, so nobody locks themselves out.
                        AppLock.authenticate(activity, context.getString(R.string.lock_title)) {
                            lock = true
                            AppLock.unlocked = true
                            Settings.setAppLock(context, true)
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(R.string.settings_updates)
            Toggle(stringResource(R.string.settings_update_auto), stringResource(R.string.settings_update_auto_hint), autoUpdate) {
                autoUpdate = it
                Settings.setUpdateCheck(context, it)
            }
            UpdateRow()

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Section(R.string.settings_about)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.settings_version), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinkRow(stringResource(R.string.settings_latest), Settings.LATEST_RELEASE_URL)
            LinkRow(stringResource(R.string.settings_source), Settings.REPO_URL)
        }
    }
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data object Downloading : UpdateState
    data object Failed : UpdateState
}

/** "Check for updates", then "Download and install" when GitHub has a newer release. */
@Composable
private fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading
    val title = when (val s = state) {
        is UpdateState.Available -> stringResource(R.string.update_install)
        else -> stringResource(R.string.update_check)
    }
    val status = when (val s = state) {
        UpdateState.Idle -> null
        UpdateState.Checking -> stringResource(R.string.update_checking)
        UpdateState.UpToDate -> stringResource(R.string.update_uptodate)
        is UpdateState.Available -> stringResource(R.string.update_version_available, s.release.version)
        UpdateState.Downloading -> stringResource(R.string.update_downloading)
        UpdateState.Failed -> stringResource(R.string.update_error)
    }
    Column(
        Modifier.fillMaxWidth().clickable(enabled = !busy) {
            val current = state
            scope.launch {
                if (current is UpdateState.Available) {
                    state = UpdateState.Downloading
                    state = runCatching { Updater.install(context, Updater.download(context, current.release)) }
                        .fold({ current }, { UpdateState.Failed })
                } else {
                    state = UpdateState.Checking
                    val latest = Updater.latest()
                    state = when {
                        latest == null -> UpdateState.Failed
                        Updater.isNewer(latest) -> UpdateState.Available(latest)
                        else -> UpdateState.UpToDate
                    }
                }
            }
        }.padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (status != null) {
            Text(
                status, style = MaterialTheme.typography.bodyMedium,
                color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, url: String) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth()
            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun themeName(theme: Theme): Int = when (theme) {
    Theme.SYSTEM -> R.string.settings_system
    Theme.LIGHT -> R.string.settings_theme_light
    Theme.DARK -> R.string.settings_theme_dark
}

@Composable
private fun Section(title: Int) {
    Text(
        stringResource(title), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Toggle(title: String, hint: String, checked: Boolean, error: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint, style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choice(title: String, value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        // Anchored to the right edge, under the row, where the value is.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((key, label) in options) {
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        open = false
                        onPick(key)
                    })
                }
            }
        }
    }
}
