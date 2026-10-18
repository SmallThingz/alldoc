package app.smallthingz.alldoc.oacp

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.ViewModelProvider
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.ui.ReaderTheme
import org.oacp.android.Oacp
import org.oacp.android.OacpParams
import org.oacp.android.OacpResult

/** OACP foreground adapter. Private results always have an explicit recipient. */
class DocumentCapabilityActivity : ComponentActivity() {
    private lateinit var model: DocumentCapabilityModel
    private var request: CapabilityRequest? = null
    private var header: Pair<String, String>? = null
    private var recipient: String? = null
    private var recipientName = ""
    private var failure by mutableStateOf<String?>(null)
    private var completed = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) cancel() else request?.let { model.start(it.copy(uri = uri)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[DocumentCapabilityModel::class.java]
        try {
            header = CapabilityRequest.header(intent, packageName)
            val params = OacpParams(intent)
            val initialCaller = if (Build.VERSION.SDK_INT >= 35) getInitialCaller() else null
            val caller = callingPackage ?: initialCaller?.getPackage()?.takeIf { initialCaller.getUid() >= 0 }
            recipient = caller ?: params.getString("recipient_package")?.takeIf { it.length in 1..256 }
            val target = recipient ?: throw CapabilityFailure(Oacp.ERROR_MISSING_PARAMETERS, "A reply app is required.")
            val application = packageManager.getApplicationInfo(target, 0)
            if (callingPackage == null && caller != null && initialCaller?.getUid() != application.uid)
                throw CapabilityFailure(Oacp.ERROR_NOT_AUTHORIZED, "The calling app could not be verified.")
            recipientName = packageManager.getApplicationLabel(application).toString().takeScalarSafe(120)
            request = CapabilityRequest.parse(intent, packageName)
            val source = request!!.uri
            if (caller != null && source != null) {
                val owner = packageManager.resolveContentProvider(source.authority.orEmpty(), 0)?.applicationInfo?.uid
                val granted = checkUriPermission(source, -1, application.uid, Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                if (owner != application.uid && !granted)
                    throw CapabilityFailure(Oacp.ERROR_NOT_AUTHORIZED, "The calling app cannot read this document.")
                if (model.start(request!!)) OacpResult.accepted().send(this, request!!.requestId, request!!.capability, target)
            }
            // Without authenticated caller identity, only a fresh user picker selection is admitted.
        } catch (error: Exception) {
            val code = (error as? CapabilityFailure)?.code ?: Oacp.ERROR_INVALID_PARAMETERS
            failure = (error as? CapabilityFailure)?.message ?: "Could not open this document request."
            finishWith(OacpResult.error(code, failure!!), RESULT_CANCELED, code = code)
        }
        if (isFinishing) return
        setContent {
            ReaderTheme(appTheme = model.appTheme) {
                var backProgress by remember { mutableFloatStateOf(0f) }
                PredictiveBackHandler { events ->
                    try { events.collect { backProgress = it.progress }; cancel() }
                    finally { backProgress = 0f }
                }
                LaunchedEffect(model.result) {
                    val result = model.result
                    if (result != null && model.takeResult()) {
                        when (result) {
                            is CapabilityOutcome.Success -> {
                                val target = recipient ?: return@LaunchedEffect
                                try {
                                    val uri = result.uri
                                    grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    val data = mapOf("uri" to uri.toString(), "mimeType" to result.mime,
                                        "bytes" to result.bytes, "format" to result.format,
                                        "fidelity" to result.fidelity, "warnings" to result.warnings) +
                                        (result.worksheet ?: mapOf("page" to result.page, "continuous" to result.continuous))
                                    finishWith(OacpResult.success("Document ready.", data), RESULT_OK, uri, result.mime)
                                    model.releaseResult()
                                } catch (_: Exception) {
                                    model.rejectResult()
                                    finishWith(OacpResult.error(Oacp.ERROR_INTERNAL, "Could not share the result."), RESULT_CANCELED, code = Oacp.ERROR_INTERNAL)
                                }
                            }
                            is CapabilityOutcome.Failure -> finishWith(if (result.code == Oacp.ERROR_CANCELLED)
                                OacpResult.cancelled(result.message) else OacpResult.error(result.code, result.message), RESULT_CANCELED, code = result.code)
                        }
                    }
                }
                Column(Modifier.fillMaxSize().safeDrawingPadding().graphicsLayer {
                    translationX = size.width * backProgress * .12f
                    alpha = 1f - backProgress * .2f
                }.verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("AllDoc", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(24.dp))
                    if (failure != null) {
                        Text(failure!!)
                        TextButton(onClick = ::cancel) { Text("Close") }
                    } else if (model.running) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Reading document…")
                        TextButton(onClick = ::cancel) { Text("Cancel") }
                    } else {
                        Text("Send to $recipientName", style = MaterialTheme.typography.titleMedium)
                        Text(recipient.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = {
                            try { picker.launch(app.smallthingz.alldoc.core.DocumentFormats.pickerMimeTypes) }
                            catch (_: android.content.ActivityNotFoundException) { failure = "No document picker is installed." }
                        }) { Text("Choose document") }
                        TextButton(onClick = ::cancel) { Text("Cancel") }
                    }
                }
            }
        }
    }

    // A second launch must not replace the recipient of work already in progress.
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent) }

    private fun cancel() {
        model.cancel()
        finishWith(OacpResult.cancelled("Cancelled."), RESULT_CANCELED, code = Oacp.ERROR_CANCELLED)
    }

    private fun finishWith(result: OacpResult, status: Int, uri: Uri? = null, mime: String? = null, code: String? = null) {
        if (completed) return
        completed = true
        val current = header
        // A failed broadcast must not strand the Activity or suppress its result callback.
        if (current != null && recipient != null) runCatching { result.send(this, current.first, current.second, recipient) }
        setResult(status, Intent().apply {
            putExtra(Oacp.EXTRA_REQUEST_ID, current?.first)
            putExtra(Oacp.EXTRA_STATUS, if (status == RESULT_OK) Oacp.STATUS_COMPLETED else if (code == Oacp.ERROR_CANCELLED) Oacp.STATUS_CANCELLED else Oacp.STATUS_FAILED)
            code?.let { putExtra(Oacp.EXTRA_ERROR_CODE, it) }
            if (uri != null) {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri("Document result", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        })
        finish()
    }
}
