package app.smallthingz.alldoc.oacp

import android.content.Intent
import android.net.Uri
import org.oacp.android.Oacp
import org.oacp.android.OacpParams

internal class CapabilityFailure(val code: String, message: String) : IllegalArgumentException(message)

internal data class CapabilityRequest(val requestId: String, val capability: String, val format: String,
    val uri: Uri?, val page: Int, val width: Int, val password: String, val name: String?, val mime: String?) {
    companion object {
        fun header(intent: Intent, packageName: String): Pair<String, String> {
            val capability = when (intent.action) {
                "$packageName.oacp.ACTION_READ_DOCUMENT" -> "read_document"
                "$packageName.oacp.ACTION_DOCUMENT_STRUCTURE" -> "document_structure"
                "$packageName.oacp.ACTION_RENDER_PAGE" -> "render_page"
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
            val uri = string("uri", 8192)?.let(Uri::parse) ?: intent.data
            if (uri != null && (uri.scheme != "content" || uri.authority.isNullOrBlank() || uri.toString().length > 8192))
                throw CapabilityFailure(Oacp.ERROR_INVALID_PARAMETERS, "A content URI is required.")
            return CapabilityRequest(id, capability, when (capability) { "render_page" -> "png"; "document_structure" -> "json"; else -> "text" }, uri,
                number("page", 0, 0, 99999), number("width", 1200, 64, 2048), string("password", 256).orEmpty(),
                string("display_name", 256), string("mime_type", 128) ?: intent.type?.take(128))
        }
    }
}
