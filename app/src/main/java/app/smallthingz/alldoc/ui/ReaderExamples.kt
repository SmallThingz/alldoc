package app.smallthingz.alldoc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.sources.ExampleDocument
import app.smallthingz.alldoc.sources.ExampleDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** The catalog is metadata only; opening one item materializes only that asset on the reader worker. */
@Composable
internal fun ReaderExamplesScreen(onBack: () -> Unit, onOpen: (ExampleDocument) -> Unit) {
    val context = LocalContext.current.applicationContext
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberLazyListState()
    fun changeQuery(value: String) {
        val next = value.takeScalarSafe(128)
        if (next != query) { query = next; scroll.requestScrollToItem(0) }
    }
    var retry by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val examples by produceState<List<ExampleDocument>?>(null, context, retry) {
        failed = false
        try { value = withContext(Dispatchers.IO) { ExampleDocuments.list(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val groups = remember(examples, query) {
        val term = query.trim()
        examples.orEmpty().filter {
            term.isEmpty() || it.title.contains(term, true) || it.name.contains(term, true) ||
                it.extension.contains(term, true) || it.description.contains(term, true) || it.category.contains(term, true)
        }.groupBy { it.category }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    Column(Modifier.fillMaxSize().readerBackground()) {
        ReaderHeader("Examples", onBack = { dismissKeyboard(); onBack() })
        OutlinedTextField(query, ::changeQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true, label = { Text("Find a format") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) ReaderAction(Icons.Outlined.Close, "Clear search", onClick = { changeQuery("") }) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Examples unavailable", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { retry++ }) { Text("Retry") }
                }
                examples == null -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                groups.isEmpty() -> Text("No matches", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    groups.forEach { (category, items) ->
                        item(key = "category:$category", contentType = "heading") {
                            Text(category, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(items, key = { it.id }, contentType = { "example" }) { example ->
                            ExampleRow(example) { dismissKeyboard(); onOpen(example) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExampleRow(example: ExampleDocument, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.heightIn(min = 72.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                Box(Modifier.width(64.dp).heightIn(min = 44.dp).padding(6.dp), contentAlignment = Alignment.Center) {
                    Text(example.extension.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(example.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (example.description.isNotEmpty()) Text(example.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
