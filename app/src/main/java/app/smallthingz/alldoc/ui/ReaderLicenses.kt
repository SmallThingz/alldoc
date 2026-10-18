package app.smallthingz.alldoc.ui

import android.content.res.AssetManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import app.smallthingz.alldoc.core.LicenseEntry
import app.smallthingz.alldoc.core.licenseCatalog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared across retained/recreated screens; assets are immutable for this process version. */
private object BundledLicenses {
    @Volatile private var entries: List<LicenseEntry>? = null
    suspend fun load(assets: AssetManager): List<LicenseEntry> = withContext(Dispatchers.IO) {
        entries ?: synchronized(this) {
            entries ?: licenseCatalog(assets.list("").orEmpty()
                .filter { it.endsWith("notices.txt") || it.endsWith("license.txt") }
                .sorted().associateWith { assets.open(it).bufferedReader().use { reader -> reader.readText() } })
                .also { entries = it }
        }
    }
}

@Composable
internal fun ReaderLicensesScreen(onBack: () -> Unit, active: Boolean = true) {
    val assets = LocalContext.current.assets
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<LicenseEntry>>?>(null, assets, attempt) {
        value = null
        value = try { Result.success(BundledLicenses.load(assets)) }
            catch (error: IOException) { Result.failure(error) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = result?.getOrNull().orEmpty()
    val filtered = remember(entries, query) { entries.filter {
        query.isBlank() || it.name.contains(query.trim(), true) || it.id.contains(query.trim(), true) || it.summary.contains(query.trim(), true)
    } }
    val entry = entries.firstOrNull { it.id == selected }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val listActive = active && entry == null
    LaunchedEffect(listState, listActive) {
        if (!listActive) listState.stopScroll(MutatePriority.PreventUserInput)
    }
    var appliedQuery by rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != appliedQuery) { listState.scrollToItem(0); appliedQuery = query }
    }
    Box(Modifier.fillMaxSize().readerBackground()) {
        Column(Modifier.fillMaxSize().readerInput(listActive)) {
            ReaderHeader("Licenses", onBack = onBack)
            OutlinedTextField(query, { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                label = { Text("Search licenses") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { AnimatedVisibility(query.isNotEmpty(), modifier = Modifier.readerInput(query.isNotEmpty()),
                    enter = fadeIn(), exit = fadeOut()) {
                    ReaderAction(Icons.Outlined.Close, "Clear search") { query = "" }
                } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
            when {
                result == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                result?.isFailure == true -> Column(Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn’t load licenses")
                    TextButton(onClick = { attempt++ }) { Text("Retry") }
                }
                filtered.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No matching licenses") }
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, userScrollEnabled = listActive, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(filtered, key = { it.id }, contentType = { "license" }) { item ->
                        ListItem(headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(item.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null, Modifier.size(16.dp)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
                            modifier = Modifier.animateItem().clickable(role = Role.Button) {
                                keyboard?.hide(); selected = item.id
                            })
                    }
                }
            }
        }
        if (entry != null) key(entry.id) {
            ReaderMotionLayer(active = active, onBack = { selected = null }, modifier = Modifier.fillMaxSize()) { back ->
                LicenseDetails(entry, back, active)
            }
        }
    }
}

@Composable
private fun LicenseDetails(entry: LicenseEntry, onBack: () -> Unit, active: Boolean) {
    val scroll = rememberLazyListState()
    LaunchedEffect(scroll, active) {
        if (!active) scroll.stopScroll(MutatePriority.PreventUserInput)
    }
    val chunks by produceState<List<Pair<String, List<String>>>?>(null, entry) {
        value = withContext(Dispatchers.Default) { entry.notices.map { it.title to splitText(it.text) } }
    }
    Column(Modifier.fillMaxSize().readerBackground()) {
        ReaderHeader(entry.name, onBack = onBack)
        val loaded = chunks
        if (loaded == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = scroll, userScrollEnabled = active,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entry.version.isNotEmpty()) item { Text(entry.version, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                loaded.forEachIndexed { index, (title, text) ->
                    item("heading:$index") { Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall) }
                    items(text.size, key = { "text:$index:$it" }) { part ->
                        val annotated = remember(text[part]) { linkedLicenseText(visibleTextChunk(text[part], part == text.lastIndex)) }
                        Text(annotated, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private val licenseLink = Regex("https?://[^\\s<>\\)\\]]+")
private fun linkedLicenseText(text: String) = buildAnnotatedString {
    var offset = 0
    licenseLink.findAll(text).forEach { match ->
        val url = match.value.trimEnd('.', ',', ';')
        append(text.substring(offset, match.range.first))
        withLink(LinkAnnotation.Url(url)) { append(url) }
        offset = match.range.first + url.length
    }
    append(text.substring(offset))
}
