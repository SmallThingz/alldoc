package org.smallthingz.alldoc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PdfFormCanvas(
    page: PdfFormPage,
    edits: Map<String, String>,
    enabled: Boolean,
    onEdit: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text("Fill this page", style = MaterialTheme.typography.titleLarge)
            Text(
                "Existing text fields only. Changes appear in your exported copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (page.fields.isEmpty())
            item {
                Text(
                    page.message ?: "There are no editable text fields on this page.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    PdfTools.FORM_LIMITATION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        items(page.fields, key = { it.id }) { field ->
            OutlinedTextField(
                value = edits[field.id] ?: field.value,
                onValueChange = { onEdit(field.id, it) },
                enabled = enabled,
                label = { Text(field.label) },
                singleLine = !field.multiline,
                minLines = if (field.multiline) 3 else 1,
                maxLines = 6,
                supportingText = {
                    Text("${(edits[field.id] ?: field.value).length} / ${field.maxLength}")
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = field.label },
            )
        }
    }
}
