package app.smallthingz.alldoc.oacp

import android.content.Intent
import android.net.Uri
import org.oacp.android.Oacp
import org.oacp.android.OacpParams
import app.smallthingz.alldoc.theme.ImageTreatment
import app.smallthingz.alldoc.theme.ReadingMode
import java.util.Locale

internal class CapabilityFailure(val code: String, message: String) : IllegalArgumentException(message)

internal data class CapabilityRequest(val requestId: String, val capability: String, val format: String,
    val uri: Uri?, val page: Int, val width: Int, val password: String, val name: String?, val mime: String?,
    val sheet: CapabilitySheetRequest? = null) {
    companion object {
        fun header(intent: Intent, packageName: String): Pair<String, String> {
            val capability = when (intent.action) {
                "$packageName.oacp.ACTION_READ_DOCUMENT" -> "read_document"
                "$packageName.oacp.ACTION_DOCUMENT_STRUCTURE" -> "document_structure"
                "$packageName.oacp.ACTION_RENDER_PAGE" -> "render_page"
                "$packageName.oacp.ACTION_RENDER_SHEET" -> "render_sheet"
                else -> throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "Unknown document action.")
            }
            val id = intent.getStringExtra(Oacp.EXTRA_REQUEST_ID)
            if (id.isNullOrBlank() || id.length > 128) throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "A request ID is required.")
            return id to capability
        }
        fun parse(intent: Intent, packageName: String): CapabilityRequest {
            val (id, capability) = header(intent, packageName)
            val params = OacpParams(intent)
            fun string(name: String, maximum: Int): String? {
                if (!params.has(name)) return null
                val value = params.getRaw(name) as? String
                    ?: throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "Invalid $name.")
                if (value.length > maximum) throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "$name is too long.")
                return value
            }
            // OacpParams narrows Long/Double; inspect the matched raw value before narrowing.
            fun number(name: String, default: Int, minimum: Int, maximum: Int): Int {
                if (!params.has(name)) return default
                val raw = params.getRaw(name)
                val value = when (raw) {
                    is Byte -> raw.toLong(); is Short -> raw.toLong(); is Int -> raw.toLong(); is Long -> raw
                    is String -> raw.toLongOrNull()
                    else -> null
                }
                if (value == null || value !in minimum.toLong()..maximum.toLong())
                    throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "Invalid $name.")
                return value.toInt()
            }
            fun decimal(name: String, default: Double, minimum: Double, maximum: Double): Double {
                if (!params.has(name)) return default
                val value = when (val raw = params.getRaw(name)) {
                    is Number -> raw.toDouble()
                    is String -> raw.toDoubleOrNull()
                    else -> null
                }
                if (value == null || !value.isFinite() || value !in minimum..maximum)
                    throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "Invalid $name.")
                return value
            }
            fun <T : Enum<T>> option(name: String, default: T, values: List<T>): T {
                val value = string(name, 20) ?: return default
                return values.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "Invalid $name.")
            }
            val sheet = if (capability == "render_sheet") CapabilitySheetRequest(
                if (params.has("sheet")) number("sheet", 0, 0, 1023) else null,
                decimal("x", 0.0, 0.0, 1e9), decimal("y", 0.0, 0.0, 1e9), decimal("scale", 1.0, .0625, 32.0),
                number("height", 800, 64, 2048), option("theme", ReadingMode.ORIGINAL, ReadingMode.entries),
                when (string("imageTreatment", 8)?.lowercase(Locale.ROOT) ?: "auto") {
                    "auto" -> ImageTreatment.AUTO
                    "keep" -> ImageTreatment.KEEP
                    "all" -> ImageTreatment.RECOLOR
                    else -> throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "Invalid imageTreatment.")
                }) else null
            val uri = string("uri", 8192)?.let(Uri::parse) ?: intent.data
            if (uri != null && (uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.toString().length > 8192))
                throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "A content URI is required.")
            return CapabilityRequest(id, capability, when (capability) { "render_page", "render_sheet" -> "png"; "document_structure" -> "json"; else -> "text" }, uri,
                number("page", 0, 0, 99999), number("width", 1200, 64, 2048), string("password", 256).orEmpty(),
                string("display_name", 256), string("mime_type", 128) ?: intent.type?.take(128), sheet)
        }
    }
}
