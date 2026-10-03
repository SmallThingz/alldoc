package org.smallthingz.alldoc

import android.os.Build
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/** A supported existing PDF form field, never an arbitrary piece of page text. */
data class PdfFormField(
    val page: Int,
    val index: Int,
    val label: String,
    val value: String,
    val maxLength: Int,
    val multiline: Boolean,
) {
    val id: String
        get() = PdfTools.fieldId(page, index)
}

data class PdfFormPage(val fields: List<PdfFormField>, val message: String? = null)

internal enum class PdfFormRestriction(val message: String) {
    ENCRYPTION(PdfTools.ENCRYPTED_FORM_MESSAGE),
    SIGNATURE(PdfTools.SIGNATURE_FORM_MESSAGE),
}

/** Small, version-independent IPC/recovery format. Native PDF objects stay in the isolated UID. */
object PdfTools {
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_FORM_PAGES = 500
    const val MAX_FIELDS_PER_PAGE = 64
    const val MAX_EDITS = 256
    const val MAX_FIELD_CHARACTERS = 4096
    const val MAX_EDIT_CHARACTERS = 65536
    const val MAX_PAYLOAD_CHARACTERS = 131072
    const val MIN_FORM_SDK = 35
    const val FORM_LIMITATION =
        "Fill existing PDF text fields on Android 15 or later. Page text, checkboxes, signatures, and XFA forms are not editable."

    const val ENCRYPTED_FORM_MESSAGE =
        "This PDF contains an encryption marker. Filling is disabled to protect document permissions; viewing and unchanged copies are still available."

    const val SIGNATURE_FORM_MESSAGE =
        "This PDF contains signature data or a signature-like marker. Filling is disabled to protect signatures; viewing and unchanged copies are still available."

    /**
     * Conservative refusal filter, NOT a PDF parser or proof of security/signature validity. A
     * standards-conforming /Encrypt key is an unencrypted PDF name in a trailer/xref dictionary.
     * Signature dictionaries use /ByteRange, /Sig, or /DocTimeStamp, including invisible
     * signatures. Decode #hh name escapes and scan every byte, including old incremental revisions.
     * Names in comments, strings, and binary streams deliberately cause harmless false positives
     * too. Never use this method to remove protection, derive permissions, or rewrite PDF
     * structure.
     */
    internal fun formRestrictionMarker(input: InputStream): PdfFormRestriction? {
        val names = arrayOf("Encrypt", "ByteRange", "Sig", "DocTimeStamp")
        var inName = false
        var matched = 0
        var possibleNames = 15
        var hexDigits = 0
        var escaped = 0
        var total = 0L
        val buffer = ByteArray(8192)
        fun restrictionAtNameEnd(): PdfFormRestriction? {
            if (!inName || hexDigits != 0) return null
            names.forEachIndexed { index, name ->
                if (possibleNames and (1 shl index) != 0 && matched == name.length) {
                    return if (index == 0) PdfFormRestriction.ENCRYPTION
                    else PdfFormRestriction.SIGNATURE
                }
            }
            return null
        }
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_FILE_BYTES) { "PDF exceeds the 32 MiB safety limit" }
            for (offset in 0 until count) {
                val value = buffer[offset].toInt() and 255
                val delimiter =
                    value == 0 ||
                        value == 9 ||
                        value == 10 ||
                        value == 12 ||
                        value == 13 ||
                        value == 32 ||
                        value == 40 ||
                        value == 41 ||
                        value == 60 ||
                        value == 62 ||
                        value == 91 ||
                        value == 93 ||
                        value == 123 ||
                        value == 125 ||
                        value == 47 ||
                        value == 37
                if (!inName || delimiter) {
                    restrictionAtNameEnd()?.let {
                        return it
                    }
                    inName = value == 47
                    matched = 0
                    possibleNames = 15
                    hexDigits = 0
                    escaped = 0
                    continue
                }
                if (possibleNames == 0) continue
                val decoded =
                    if (hexDigits > 0) {
                        val digit =
                            when (value) {
                                in 48..57 -> value - 48
                                in 65..70 -> value - 65 + 10
                                in 97..102 -> value - 97 + 10
                                else -> -1
                            }
                        if (digit < 0) {
                            possibleNames = 0
                            continue
                        }
                        escaped = (escaped shl 4) or digit
                        hexDigits--
                        if (hexDigits > 0) continue
                        escaped
                    } else if (value == 35) {
                        hexDigits = 2
                        escaped = 0
                        continue
                    } else value
                names.forEachIndexed { index, name ->
                    if (matched >= name.length || decoded != name[matched].code) {
                        possibleNames = possibleNames and (1 shl index).inv()
                    }
                }
                matched++
            }
        }
        return restrictionAtNameEnd()
    }

    fun supportsFormEditing(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= MIN_FORM_SDK

    fun fieldId(page: Int, index: Int): String {
        require(page in 0 until MAX_FORM_PAGES && index >= 0) { "Invalid PDF form field" }
        return "pdf:$page:$index"
    }

    internal fun address(id: String): Pair<Int, Int> {
        require(id.length <= 30) { "Invalid PDF form field" }
        val parts = id.split(':')
        require(parts.size == 3 && parts[0] == "pdf") { "Invalid PDF form field" }
        val page = parts[1].toIntOrNull() ?: error("Invalid PDF form page")
        val index = parts[2].toIntOrNull() ?: error("Invalid PDF form index")
        require(fieldId(page, index) == id) { "Invalid PDF form field" }
        return page to index
    }

    fun validateValue(field: PdfFormField, value: String) {
        require(value.length <= MAX_FIELD_CHARACTERS && value.length <= field.maxLength) {
            "This PDF field allows at most ${field.maxLength.coerceAtMost(MAX_FIELD_CHARACTERS)} characters"
        }
        require('\u0000' !in value) { "PDF form text cannot contain a NUL character" }
        require(field.multiline || ('\n' !in value && '\r' !in value)) {
            "This PDF field allows a single line of text"
        }
    }

    fun encodeEdits(edits: Map<String, String>): String {
        validateEdits(edits)
        val records = JSONArray()
        edits.toSortedMap().forEach { (id, value) ->
            records.put(JSONObject().put("id", id).put("value", value))
        }
        return records.toString().also { requirePayload(it) }
    }

    internal fun decodeEdits(payload: String): Map<String, String> {
        requirePayload(payload)
        val records = JSONArray(payload)
        require(records.length() <= MAX_EDITS) { "Too many PDF form edits" }
        val edits = linkedMapOf<String, String>()
        repeat(records.length()) { position ->
            val record = records.getJSONObject(position)
            val id = record.get("id")
            val value = record.get("value")
            require(id is String && value is String) { "Invalid PDF form edit" }
            require(!edits.containsKey(id)) { "Duplicate PDF form edit" }
            edits[id] = value
        }
        validateEdits(edits)
        return edits
    }

    private fun validateEdits(edits: Map<String, String>) {
        require(edits.size <= MAX_EDITS) { "Too many PDF form edits" }
        var characters = 0L
        edits.forEach { (id, value) ->
            address(id)
            require(value.length <= MAX_FIELD_CHARACTERS && '\u0000' !in value) {
                "PDF form text exceeds the safe field limit or contains a NUL character"
            }
            characters += value.length
        }
        require(characters <= MAX_EDIT_CHARACTERS) {
            "PDF form edits exceed the safe draft size limit"
        }
    }

    internal fun encodeFields(page: PdfFormPage): String {
        require(page.fields.size <= MAX_FIELDS_PER_PAGE) { "Too many PDF form fields on this page" }
        val fields = JSONArray()
        page.fields.forEach {
            fields.put(
                JSONObject()
                    .put("page", it.page)
                    .put("index", it.index)
                    .put("label", it.label)
                    .put("value", it.value)
                    .put("maxLength", it.maxLength)
                    .put("multiline", it.multiline)
            )
        }
        return JSONObject().put("fields", fields).put("message", page.message).toString().also {
            requirePayload(it)
        }
    }

    fun decodeFields(payload: String): PdfFormPage {
        requirePayload(payload)
        val result = JSONObject(payload)
        val records = result.getJSONArray("fields")
        require(records.length() <= MAX_FIELDS_PER_PAGE) { "Too many PDF form fields on this page" }
        val fields = ArrayList<PdfFormField>(records.length())
        val ids = hashSetOf<String>()
        repeat(records.length()) { position ->
            val record = records.getJSONObject(position)
            val field =
                PdfFormField(
                    record.getInt("page"),
                    record.getInt("index"),
                    record.getString("label"),
                    record.getString("value"),
                    record.getInt("maxLength"),
                    record.getBoolean("multiline"),
                )
            require(ids.add(field.id)) { "Duplicate PDF form field" }
            require(field.label.length <= 200 && field.maxLength in 0..MAX_FIELD_CHARACTERS) {
                "Invalid PDF form field metadata"
            }
            require(field.value.length <= MAX_FIELD_CHARACTERS) { "PDF form value is too large" }
            fields += field
        }
        val message = if (result.isNull("message")) null else result.getString("message")
        require(message == null || message.length <= 1024) { "Invalid PDF form message" }
        return PdfFormPage(fields, message)
    }

    private fun requirePayload(payload: String) {
        require(payload.length <= MAX_PAYLOAD_CHARACTERS) {
            "PDF form data exceeds the safe size limit"
        }
    }
}
