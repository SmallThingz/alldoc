package org.smallthingz.alldoc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun FormatSupport(close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Supported formats") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                FormatRow(
                    "PDF",
                    "Page preview · copy export",
                    "Fill existing text fields on Android 15+. Page text, checkboxes, signatures and XFA forms cannot be edited.",
                )
                FormatRow(
                    "DOCX",
                    "Formatted preview · text edits",
                    "Edit existing text. Fonts, pagination and complex shapes may differ from Word.",
                )
                FormatRow(
                    "XLSX · ODS · FODS",
                    "Cell grid · text edits",
                    "Existing cells only. Formulas and repeated ODS cells are read-only; no recalculation.",
                )
                FormatRow(
                    "PPTX · ODP · FODP",
                    "Simplified slides · text edits",
                    "Slide order and text positions where available. Images, charts, themes and transitions are not rendered.",
                )
                FormatRow(
                    "ODT · FODT",
                    "Reading view · text edits",
                    "Existing paragraphs and headings. Original page layout is not rendered.",
                )
                FormatRow(
                    "TXT · MD · CSV · XML +",
                    "Source view · source edits",
                    "Also JSON, YAML, TSV, LOG, HTML, CSS and SVG. Markup and scripts are not executed. XML is validated before saving.",
                )
                FormatRow(
                    "DOC · XLS · PPT",
                    "Read-only text extraction · copy export",
                    "Legacy Office text, not document layout. XLS shows addressed values, not a formatted grid; PPT may include master or older text. Convert in an office app for editing.",
                )
                FormatRow(
                    "RTF",
                    "Not yet supported",
                    "Convert to DOCX or plain text in a full office app first.",
                )
                Text(
                    "Every export creates a separate copy. Review edited Office files in a full office app before sharing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Up to 32 MB per file and 2 MB editable source text. Encrypted, signed and macro-enabled Office documents are not supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Got it") } },
    )
}

@Composable
private fun FormatRow(formats: String, capability: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(formats, style = MaterialTheme.typography.titleSmall)
        Text(
            capability,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
