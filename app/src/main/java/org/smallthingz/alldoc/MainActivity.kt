package org.smallthingz.alldoc

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AlldocTheme { val model: DocumentModel = viewModel(); LaunchedEffect(Unit) { intent?.data?.let(model::open) }; Workspace(model) } }
    }
}
@Composable private fun AlldocTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary=Color(0xFFD6EDAE),secondary=Color(0xFFA5CEC4),background=Color(0xFF121A18),surface=Color(0xFF18221F))
    else lightColorScheme(primary=Color(0xFF235A49),secondary=Color(0xFF45685C),background=Color(0xFFF6F7F2),surface=Color(0xFFFCFDF8),primaryContainer=Color(0xFFD9EDBD),secondaryContainer=Color(0xFFE7ECE3))
    MaterialTheme(colorScheme=colors, content=content)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Workspace(model: DocumentModel) {
    val context=LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var pendingOpen by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    var showCapabilities by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var pdfZoom by rememberSaveable { mutableFloatStateOf(1f) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { search="";editMode=false;model.open(it) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let(model::export) }
    val hasDocument=model.name.isNotEmpty()
    BackHandler(model.edits.isNotEmpty()) { exitDialog=true }
    Scaffold(containerColor=MaterialTheme.colorScheme.background,topBar={
        TopAppBar(title={Column{Text("alldoc",fontWeight=FontWeight.Black,letterSpacing=(-1).sp);Text(if(hasDocument)"YOUR DOCUMENT, YOUR CONTROL" else "A QUIETER PLACE FOR DOCUMENTS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)}},actions={TextButton(onClick={showCapabilities=true}){Text("Formats")}})
    }, bottomBar={ if(hasDocument) Surface(tonalElevation=3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick={if(model.edits.isNotEmpty())pendingOpen=true else open.launch(arrayOf("*/*"))},enabled=!model.busy,modifier=Modifier.weight(1f)){Text("Open")}
            Button(onClick={showExport=true},enabled=!model.busy,modifier=Modifier.weight(1f)){Text(if(model.edits.isNotEmpty())"Export edits (${model.edits.size})" else "Export copy")}
        }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if(model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.error?.let { message -> Notice(message,true){model.error=null} }
            model.status?.let { message -> Notice(message,false){model.status=null} }
            if(!hasDocument) {
                LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                    item { Spacer(Modifier.height(18.dp));Text("Open it.\nMake it yours.",style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Bold,letterSpacing=(-2).sp) }
                    item { Text("Read PDFs. Refine document text. Update a cell. Everything stays on your device.",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.secondary) }
                    item { Button(onClick={open.launch(arrayOf("*/*"))},enabled=!model.busy,modifier=Modifier.fillMaxWidth().height(56.dp),shape=RoundedCornerShape(18.dp)){Text("Open a document",style=MaterialTheme.typography.titleMedium)} }
                    item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("A safer kind of edit",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge);Text("Your original stays untouched. Changes leave as a new copy, with clear format limits before you export.")}} }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){listOf("Offline","No ads","No account").forEach { AssistChip(onClick={},label={Text(it)}) }} }
                    item { Text("PDF · DOCX · XLSX · PPTX · ODT · ODS · ODP · TXT · CSV",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.secondary) }
                    item { Text("Office files open as structured content, not page-layout previews. Original formatting remains in the exported package, but edits can change layout. DOC, XLS, PPT and RTF require another app.",style=MaterialTheme.typography.bodySmall) }
                }
            } else {
                Column(Modifier.padding(horizontal=20.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(model.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(if(model.pdfPages>0)"PDF · visual preview · read-only" else model.session?.description.orEmpty(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.secondary)
                }
                if(model.pdfPages>0) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                        TextButton(onClick={model.render(model.pdfPage-1)},enabled=model.pdfPage>0&&!model.pdfLoading){Text("Previous")}
                        Text("${model.pdfPage+1} / ${model.pdfPages}")
                        TextButton(onClick={model.render(model.pdfPage+1)},enabled=model.pdfPage+1<model.pdfPages&&!model.pdfLoading){Text("Next")}
                    }
                    Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){Text("Zoom");Slider(value=pdfZoom,onValueChange={pdfZoom=it},valueRange=1f..3f,modifier=Modifier.weight(1f));Text("${(pdfZoom*100).toInt()}%")}
                    if(model.pdfLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFCCD2CD)).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())){
                        model.pdfBitmap?.let { bitmap -> Image(bitmap.asImageBitmap(),contentDescription="Page ${model.pdfPage+1} of ${model.name}",modifier=Modifier.width(maxWidth*pdfZoom).padding(12.dp)) }
                    }
                } else {
                    Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value=search,onValueChange={search=it},label={Text("Find content")},singleLine=true,modifier=Modifier.weight(1f))
                        FilterChip(selected=editMode,onClick={editMode=!editMode},label={Text(if(editMode)"Editing" else "Read")})
                    }
                    val blocks=model.session?.blocks.orEmpty().filter { search.isBlank() || (model.edits[it.id]?:it.original).contains(search,true) || it.label.contains(search,true) || it.section.contains(search,true) }
                    Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("${blocks.size} content blocks",style=MaterialTheme.typography.labelMedium);if(model.edits.isNotEmpty())TextButton(onClick={exitDialog=true},contentPadding=PaddingValues(0.dp)){Text("Discard edits")}}
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(blocks.isEmpty())item{Text("No matching content",Modifier.padding(20.dp))}
                        items(blocks,key={it.id}) { block ->
                            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),shape=RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text("${block.section}  /  ${block.label}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
                                    if(editMode&&block.editable) OutlinedTextField(value=model.edits[block.id]?:block.original,onValueChange={model.edit(block.id,it)},modifier=Modifier.fillMaxWidth(),minLines=if(model.session?.blocks?.size==1)10 else 1,label={Text("Content")})
                                    else androidx.compose.foundation.text.selection.SelectionContainer { Text(model.edits[block.id]?:block.original,style=MaterialTheme.typography.bodyLarge) }
                                    if(!block.editable)Text("Read-only: calculated or unsupported value",style=MaterialTheme.typography.labelSmall)
                                    if(model.edits.containsKey(block.id))Text("Edited in this session",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(pendingOpen)AlertDialog(onDismissRequest={pendingOpen=false},title={Text("Leave these edits?")},text={Text("Opening another document discards unsaved edits after it successfully opens. Export a copy first if you want to keep them.")},confirmButton={TextButton(onClick={pendingOpen=false;open.launch(arrayOf("*/*"))}){Text("Choose document")}},dismissButton={TextButton(onClick={pendingOpen=false}){Text("Keep editing")}})
    if(exitDialog)AlertDialog(onDismissRequest={exitDialog=false},title={Text("Discard unsaved edits?")},text={Text("Your original file is unchanged. Unsaved changes in this session will be lost.")},confirmButton={TextButton(onClick={exitDialog=false;model.discardEdits()}){Text("Discard edits")}},dismissButton={TextButton(onClick={exitDialog=false}){Text("Keep editing")}})
    if(showExport)AlertDialog(onDismissRequest={showExport=false},title={Text("Export a separate copy")},text={Text(if(model.edits.isEmpty())"An unchanged export preserves the original file bytes. Choose a new filename; provider writes may not be atomic." else "Only the displayed text or cells are changed. This is not a full-layout office editor. Fonts, pagination, formulas and charts are not recalculated. Review the exported copy in a full office app. Choose a new filename; never select your original.")},confirmButton={TextButton(onClick={showExport=false;val name=model.name;val i=name.lastIndexOf('.');save.launch(if(i>0)name.substring(0,i)+"-edited"+name.substring(i) else "$name-edited")}){Text("Choose destination")}},dismissButton={TextButton(onClick={showExport=false}){Text("Cancel")}})
    if(showCapabilities)AlertDialog(onDismissRequest={showCapabilities=false},title={Text("Clear format boundaries")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("PDF: page rendering, zoom and copy export. No text editing or annotations yet.");Text("DOCX / PPTX: text-run editing; no page or slide-layout preview.");Text("XLSX: existing cells; edits become text. Formulas are read-only, cached values are not recalculated.");Text("ODT / ODP: existing paragraph text. ODS: string-cell text only; numeric and formula cells are read-only.");Text("TXT / MD / CSV / TSV / JSON / XML / YAML: plain-text source editing, not a spreadsheet or rendered-markup view.");Text("DOC / XLS / PPT / RTF and encrypted documents: unsupported, original never changed.");Text("Limits: 32 MiB input, 64 MiB expanded archive, 8 MiB per archive member, 12,000 content blocks, 2 MiB editable plain text.");Text("Draft edits are saved in app-private crash recovery. Export creates a separate document you can keep outside alldoc. Force termination during a write may lose the latest keystrokes.")}},confirmButton={TextButton(onClick={showCapabilities=false}){Text("Got it")}})
}
@Composable private fun Notice(message: String, error: Boolean, dismiss: () -> Unit) {
    Surface(color=if(error)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer){Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Text(message,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick=dismiss){Text("Dismiss")}}}
}
