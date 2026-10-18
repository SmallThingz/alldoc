package app.smallthingz.alldoc

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.smallthingz.alldoc.ui.ReaderModel
import app.smallthingz.alldoc.ui.ReaderAccessModel
import app.smallthingz.alldoc.sources.LibraryAccess
import app.smallthingz.alldoc.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    private lateinit var reader: ReaderModel
    private lateinit var access: ReaderAccessModel
    private var externalDocument by mutableStateOf(false)
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { externalDocument = false; reader.open(uri, persistPermission = true) }
    }
    private val saveText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { reader.export(it) }
    private val saveJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { reader.export(it) }
    private val saveImage = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { reader.export(it) }
    private val storageFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) reader.useStorageFolder(uri)
    }

    private val libraryFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) reader.addLibraryFolder(uri)
    }
    private val libraryAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshLibraryAccess(true) }
    private val legacyLibraryAccess = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshLibraryAccess(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalDocument = savedInstanceState?.getBoolean("externalDocument")
            ?: (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND)
        reader = ViewModelProvider(this)[ReaderModel::class.java]
        access = ViewModelProvider(this)[ReaderAccessModel::class.java]
        setContent {
            ReaderScreen(reader, onOpen = {
                try { openDocument.launch(app.smallthingz.alldoc.core.DocumentFormats.pickerMimeTypes) }
                catch (_: android.content.ActivityNotFoundException) { reader.showError("No document picker is available.") }
            }, onExport = { kind, identity ->
                reader.prepareExport(kind, identity)?.let { name ->
                    try {
                        when (kind) {
                            ReaderModel.ExportKind.TEXT -> saveText.launch(name)
                            ReaderModel.ExportKind.JSON -> saveJson.launch(name)
                            ReaderModel.ExportKind.IMAGE -> saveImage.launch(name)
                        }
                    } catch (_: android.content.ActivityNotFoundException) {
                        reader.discardPendingExport()
                        reader.showError("No file picker is available.")
                    }
                }
            }, onChooseStorage = {
                try { storageFolder.launch(reader.copySettings.treeUri?.let(Uri::parse)) }
                catch (_: android.content.ActivityNotFoundException) { reader.showError("No folder picker is available.") }
            }, libraryAccess = access, onLibraryAccess = ::requestLibraryAccess, onAddLibraryFolder = {
                try { libraryFolder.launch(null) }
                catch (_: ActivityNotFoundException) { reader.showError("No folder picker is available.") }
            }, externalDocument = externalDocument, onInternalOpen = { externalDocument = false },
                onFullscreen = ::setFullscreen,
                onPrint = { identity ->
                    reader.createPrintAdapter(identity)?.let { adapter ->
                        try {
                            val manager = getSystemService(android.print.PrintManager::class.java)
                            if (manager == null) { adapter.abort(); reader.showError("Printing is unavailable.") }
                            else manager.print(reader.currentTitle, adapter, null)
                        } catch (_: Exception) { adapter.abort(); reader.showError("Couldn’t start printing.") }
                    }
                })
        }
        if (reader.source == null && reader.info == null && !reader.loading) {
            val restored = savedInstanceState?.getString("document")?.let(Uri::parse)
            val hash = savedInstanceState?.getString("documentHash")
            val mime = savedInstanceState?.getString("documentMime")
            if (savedInstanceState?.getBoolean("openingCancelled") == true) reader.restoreCancelledOpen(restored)
            else if (savedInstanceState != null && !savedInstanceState.getBoolean("readerVisible", true)) Unit
            else if (hash != null) reader.reopenDocument(hash, restored, mimeHint = mime)
            else if (restored != null) reader.open(restored, mimeHint = mime)
            else if (savedInstanceState?.getBoolean("openingDocument") == true) {
                reader.showError("Opening interrupted.")
            } else if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) openIntent(intent)
            else reader.restoreLastDocument()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        reader.flushDocumentState()
        reader.source?.toString()?.let { outState.putString("document", it) }
        reader.documentHash?.let { outState.putString("documentHash", it) }
        reader.sourceMimeHint?.let { outState.putString("documentMime", it) }
        outState.putBoolean("externalDocument", externalDocument)
        outState.putBoolean("openingCancelled", reader.openingCancelled)
        outState.putBoolean("readerVisible", reader.readerVisible)
        outState.putBoolean("openingDocument", reader.loading)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        reader.setAppVisible(true)
    }

    override fun onResume() {
        super.onResume()
        reader.refreshRecentDocuments()
        refreshLibraryAccess()
    }

    override fun onStop() {
        reader.setAppVisible(false)
        reader.flushDocumentState()
        super.onStop()
    }

    private fun refreshLibraryAccess(returned: Boolean = false) {
        if (if (returned) access.returned() else access.refresh()) reader.refreshLibrary()
    }

    private fun requestLibraryAccess() {
        refreshLibraryAccess()
        val legacy = Build.VERSION.SDK_INT < 30
        val previouslyRequested = access.legacyRequested
        if (!access.beginRequest(legacy)) return
        if (!legacy || previouslyRequested && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            if (!LibraryAccess.openSettings(packageName, !legacy) { libraryAccess.launch(it) }) access.launchFailed()
        } else try { legacyLibraryAccess.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
            catch (_: ActivityNotFoundException) { access.launchFailed() }
            catch (_: SecurityException) { access.launchFailed() }
    }

    private fun setFullscreen(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = when {
                !enabled -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun openIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return
        externalDocument = true
        documentUri(intent)?.let { uri ->
            if (uri.scheme != "content" && uri.scheme != "file") reader.rejectOpen("Only local files are supported.")
            else reader.open(uri, persistPermission = (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0, mimeHint = intent.type)
            return
        }
        reader.rejectOpen(if (intent.action == Intent.ACTION_SEND) "No readable document was shared." else "No document provided.")
    }

    @Suppress("DEPRECATION")
    private fun documentUri(intent: Intent): Uri? {
        val uri = runCatching { when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } }.getOrNull()
        return uri
    }
}
