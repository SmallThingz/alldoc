package app.smallthingz.alldoc.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.smallthingz.alldoc.sources.LibraryAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Optional library access never gates an explicitly opened document. */
class ReaderAccessModel internal constructor(application: Application, private val checkAccess: () -> Boolean,
    private val preferencesName: String) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, { LibraryAccess.hasAccess(application) }, "library-access")
    var granted by mutableStateOf(checkAccess()); private set
    var ready by mutableStateOf(false); private set
    var offering by mutableStateOf(false); private set
    var requesting by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var legacyRequested = false; private set
    private var introduced = false
    private fun preferences() = getApplication<Application>().getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    init {
        viewModelScope.launch {
            val saved = try { ReaderModel.orderedStateRead {
                preferences().let { it.getBoolean("introduced", false) to it.getBoolean("legacy-requested", false) }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false to false }
            introduced = saved.first; legacyRequested = saved.second
            offering = !introduced && !granted
            ready = true
            if (granted) introductionShown()
        }
    }
    fun shouldIntroduce(home: Boolean, external: Boolean, reading: Boolean) =
        ready && offering && !granted && home && !external && !reading

    fun introductionShown() {
        if (!introduced) { introduced = true; persist("introduced") }
    }
    fun dismissIntroduction() { introductionShown(); offering = false }
    fun beginRequest(legacy: Boolean): Boolean {
        if (!ready || requesting || granted) return false
        requesting = true; message = null
        if (legacy && !legacyRequested) { legacyRequested = true; persist("legacy-requested") }
        return true
    }
    fun refresh(): Boolean {
        val previous = granted
        granted = checkAccess()
        if (granted) {
            requesting = false; offering = false; message = null
            if (ready) introductionShown()
        }
        return granted != previous
    }
    fun returned(): Boolean {
        requesting = false
        val changed = refresh()
        if (!granted) message = "Access not granted. You can still choose folders."
        return changed
    }
    fun launchFailed() { requesting = false; message = "Device access unavailable. Choose a folder instead." }
    private fun persist(key: String) {
        ReaderModel.enqueueStateWrite("$preferencesName:$key") {
            try { check(preferences().edit().putBoolean(key, true).commit()) }
            catch (failure: Exception) { Log.w("AllDoc", "Could not save library access choice", failure)
                viewModelScope.launch { message = "Couldn’t save this choice." }
            }
        }
    }
}

@Composable
internal fun LibraryAccessSetting(access: ReaderAccessModel, onRequest: () -> Unit) {
    AnimatedVisibility(!access.granted, modifier = Modifier.readerInput(!access.granted)) {
        OutlinedCard(onClick = onRequest, enabled = access.ready && !access.requesting,
            modifier = Modifier.fillMaxWidth()) {
            ListItem(headlineContent = { Text("Device access") },
                supportingContent = { Text(access.message ?: if (access.requesting) "Opening settings…"
                    else if (!access.ready) "Checking access…" else "Find documents across this device") },
                leadingContent = { Icon(Icons.Outlined.Storage, null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, Modifier.size(16.dp)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
        }
    }
}

@Composable
internal fun ReaderAccessScreen(access: ReaderAccessModel, onRequest: () -> Unit,
    onChooseFolders: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxSize().readerBackground().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Storage, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Your library", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(access.message ?: "Find documents across this device.", textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onRequest, enabled = access.ready && !access.requesting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (access.requesting) "Opening settings…" else "Allow device access")
        }
        OutlinedButton(onChooseFolders, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose folders") }
        TextButton(onSkip, Modifier.heightIn(min = 48.dp)) { Text("Not now") }
    }
}
