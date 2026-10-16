package app.smallthingz.alldoc.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import app.smallthingz.alldoc.core.DocumentFormats
import app.smallthingz.alldoc.core.DocumentParser
import app.smallthingz.alldoc.core.DocumentProbe
import app.smallthingz.alldoc.core.takeScalarSafe
import app.smallthingz.alldoc.pdf.PdfDocument
import app.smallthingz.alldoc.render.DocRenderCore
import app.smallthingz.alldoc.theme.BitmapTheme
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Local, worker-only document admission. Every descriptor and snapshot has one owner. */
object DocumentEngine {
    internal const val PDF_INPUT_LIMIT = 128L * 1024 * 1024
    private val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)
    fun open(context: Context, uri: Uri, signal: CancellationSignal, password: String? = null): DocumentSession {
        require(uri.scheme == "content") { "Open this document through Android's file picker or a content provider." }
        signal.throwIfCanceled()
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeScalarSafe(256) ?: "Document"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
            if (it.moveToFirst() && !it.isNull(0)) name = it.getString(0).takeScalarSafe(256)
        }
        val mime = resolver.getType(uri)
        val fd = resolver.openFileDescriptor(uri, "r", signal) ?: throw IOException("The provider did not return a document.")
        return open(context, fd, name, mime, signal, password)
    }

    /** Adopts fd immediately, including cancellation and failed opens. */
    fun open(context: Context, fd: ParcelFileDescriptor, displayName: String, mimeType: String?, signal: CancellationSignal, password: String? = null): DocumentSession {
        fd.use {
            signal.throwIfCanceled()
            PdfDocument.configure(context)
            val name = displayName.takeScalarSafe(256).ifBlank { "Document" }
            val mime = mimeType?.substringBefore(';')?.take(128) ?: "application/octet-stream"
            val prefix = ByteArray(8)
            val seekable = try { Os.pread(fd.fileDescriptor, prefix, 0, prefix.size, 0); true } catch (_: android.system.ErrnoException) { false }
            val isPdf = prefix.copyOf(5).contentEquals(PDF_HEADER)
            if (isPdf && seekable) {
                // PdfRenderer seeks during construction; dup would move the caller's shared offset.
                val independent = try {
                    ParcelFileDescriptor.open(File("/proc/self/fd/${fd.fd}"), ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (_: java.io.FileNotFoundException) { null } catch (_: SecurityException) { null }
                if (independent != null) return openPdf(independent, name, password, signal, emptyList())
                // A granted descriptor may be readable even when reopening its inode is denied.
            }
            val directory = storage(context)
            val snapshot = File.createTempFile("document-", ".bin", directory)
            try { copyInput(fd, snapshot, if (isPdf) PDF_INPUT_LIMIT else DocumentParser.INPUT_LIMIT.toLong(), seekable, signal,
                pdfHeaderLimit = PDF_INPUT_LIMIT) }
            catch (failure: Throwable) { snapshot.delete(); throw failure }
            return openSnapshot(context, snapshot, name, mime, signal, password)
        }
    }

    /** Adopts an exclusively owned temporary file, including failed/cancelled opens. */
    internal fun openSnapshot(context: Context, snapshot: File, displayName: String, mimeType: String?,
        signal: CancellationSignal, password: String? = null): DocumentSession {
        var result: DocumentSession? = null
        try {
            val name = displayName.takeScalarSafe(256).ifBlank { "Document" }
            val identification = DocumentProbe.file(snapshot) { signal.isCanceled }
            val detected = identification.mime
            val suppliedMime = mimeType?.substringBefore(';')?.take(128)
            var renderName = DocumentProbe.openingName(name, suppliedMime, identification)
                ?: throw IOException("This file type is not supported.")
            var mime = DocumentFormats.mimeType(renderName) ?: detected ?: suppliedMime ?: "application/octet-stream"
            signal.throwIfCanceled()
            PdfDocument.configure(context)
            if (detected == "application/pdf") {
                result = openPdf(ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY), name, password, signal, emptyList())
            } else {
                val document = DocumentParser.parseDocument(snapshot, renderName) { signal.isCanceled }
                if (identification.inconclusive && (renderName == "document.zip" || DocumentFormats.isNative(renderName))) {
                    renderName = when (document.format) {
                        "DOCX", "XLSX", "PPTX", "ODT", "ODS", "ODP", "ODG", "EPUB" -> "document.${document.format.lowercase()}"
                        else -> throw IOException("This archive document type is not supported.")
                    }
                    mime = requireNotNull(DocumentFormats.mimeType(renderName))
                }
                val ext = DocumentFormats.extension(renderName)
                if (detected == "application/x-ole-storage") mime = when (document.format) {
                    "DOC" -> "application/msword"
                    "XLS" -> "application/vnd.ms-excel"
                    "PPT" -> "application/vnd.ms-powerpoint"
                    else -> throw IOException("This compound document type is not supported.")
                }
                val preview = if (ext in setOf("pages", "numbers", "key"))
                    DocumentParser.embeddedPreview(snapshot) { signal.isCanceled } else null
                if (preview != null) {
                    val file = File.createTempFile("preview-", ".bin", storage(context))
                    var inner: DocumentSession? = null
                    try {
                        file.writeBytes(preview.bytes)
                        inner = if (preview.mime == "application/pdf")
                            openPdf(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), name, null, signal, document.warnings)
                        else ImageSession(file, name, preview.mime, signal, document.warnings)
                        result = OwnedSession(PreviewSession(inner, ext, mime, preview.name, document.warnings), file)
                        inner = null
                    } catch (failure: Throwable) {
                        try { inner?.close() } finally { file.delete() }
                        throw failure
                    }
                } else if (document.markdown != null) {
                    result = MarkdownSession(name, mime, document, signal)
                } else if (DocRenderCore.supportsName(renderName)) {
                    DocRenderCore.configure(context)
                    result = try { OfficeSession(snapshot, name, mime, document, signal, renderName) }
                    catch (error: IOException) {
                        signal.throwIfCanceled()
                        if (document.sections.isEmpty()) throw error
                        TextSession(name, mime, document.copy(warnings = document.warnings +
                            "Original-layout view unavailable: ${error.message.orEmpty().takeScalarSafe(180)}"), signal)
                    }
                } else result = TextSession(name, mime, document, signal)
            }
            signal.throwIfCanceled()
            return OwnedSession(withDocumentNotes(requireNotNull(result), snapshot), snapshot)
        } catch (failure: Throwable) {
            try { result?.close() } finally { snapshot.delete() }
            throw failure
        }
    }

    private fun openPdf(fd: ParcelFileDescriptor, name: String, password: String?, signal: CancellationSignal,
        warnings: List<String>): DocumentSession {
        val pdf = PdfDocument.open(fd, name, password, signal)
        try { return PdfSession(pdf, name, warnings) }
        catch (failure: Throwable) {
            try { pdf.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    internal fun newSnapshot(context: Context): File = File.createTempFile("document-", ".bin", storage(context))

    private var storage: File? = null
    @Synchronized private fun storage(context: Context): File {
        storage?.let {
            check(it.isDirectory || it.mkdirs()) { "Cannot prepare private document storage" }
            return it
        }
        val directory = File(context.cacheDir, "documents-${android.os.Process.myPid()}")
        context.cacheDir.listFiles()?.filter { it.name.matches(Regex("documents-[0-9]+")) }?.forEach {
            if (it == directory || !File("/proc/${it.name.substringAfter('-')}").exists()) it.deleteRecursively()
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare private document storage" }
        storage = directory
        return directory
    }

    internal fun copyInput(fd: ParcelFileDescriptor, file: File?, limit: Long, seekable: Boolean, signal: CancellationSignal,
        digest: MessageDigest? = null, pdfHeaderLimit: Long = limit, prefix: ByteArray? = null): Long {
        var inputLimit = limit
        val header = if (pdfHeaderLimit > limit) ByteArray(5) else null
        var headerSize = 0
        val size = fd.statSize
        val ceiling = maxOf(limit, pdfHeaderLimit)
        if (size > ceiling) throw IOException("This document exceeds the ${ceiling / (1024 * 1024)} MiB input limit.")
        val deadline = SystemClock.elapsedRealtime() + 30_000
        val poll = arrayOf(StructPollfd().apply { this.fd = fd.fileDescriptor; events = OsConstants.POLLIN.toShort() })
        val flags = if (seekable) null else Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0)
        if (flags != null) Os.fcntlInt(fd.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        var total = 0L
        try { file?.let(::FileOutputStream).use { output ->
            val bytes = ByteArray(32 * 1024)
            while (true) {
                signal.throwIfCanceled()
                if (SystemClock.elapsedRealtime() > deadline) throw IOException("The document provider took too long to read.")
                val count = try {
                    if (seekable) Os.pread(fd.fileDescriptor, bytes, 0, bytes.size, total)
                    else Os.read(fd.fileDescriptor, bytes, 0, bytes.size)
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EINTR) continue
                    if (seekable || error.errno != OsConstants.EAGAIN) throw IOException("The document descriptor is not readable.", error)
                    try {
                        Os.poll(poll, 100)
                        if (poll[0].revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0)
                            throw IOException("The document descriptor is not readable.")
                    } catch (interrupted: ErrnoException) {
                        if (interrupted.errno != OsConstants.EINTR) throw IOException("Could not wait for the document provider.", interrupted)
                    }
                    continue
                }
                if (count == 0) { fd.checkError(); break }
                if (header != null && headerSize < header.size) {
                    val copied = minOf(count, header.size - headerSize)
                    bytes.copyInto(header, headerSize, 0, copied)
                    headerSize += copied
                    if (headerSize == header.size && header.contentEquals(PDF_HEADER)) inputLimit = pdfHeaderLimit
                }
                if (prefix != null && total < prefix.size) {
                    val offset = total.toInt()
                    bytes.copyInto(prefix, offset, 0, minOf(count, prefix.size - offset))
                }
                total += count
                if (total > inputLimit || (header == null || headerSize == header.size) && size > inputLimit)
                    throw IOException("This document exceeds the ${inputLimit / (1024 * 1024)} MiB input limit.")
                digest?.update(bytes, 0, count)
                output?.write(bytes, 0, count)
            }
        } } finally {
            if (flags != null) {
                val current = Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd.fileDescriptor, OsConstants.F_SETFL,
                    (current and OsConstants.O_NONBLOCK.inv()) or (flags and OsConstants.O_NONBLOCK))
            }
        }
        signal.throwIfCanceled()
        return total
    }
}

internal class OwnedSession(private val delegate: DocumentSession, private val snapshot: File) : DocumentSession by delegate {
    override fun close() { try { delegate.close() } finally { snapshot.delete() } }
}

internal class PreviewSession(private val delegate: DocumentSession, format: String, mime: String, private val part: String, warnings: List<String>) : DocumentSession by delegate {
    override val info = delegate.info.copy(format = format, mimeType = mime, fidelity = "EMBEDDED_PREVIEW_ONLY", warnings = (delegate.info.warnings + warnings + "Saved embedded preview; it may be stale or incomplete.").distinct())
    override fun text(signal: CancellationSignal): String = "[Saved embedded preview; it may be stale or incomplete.]\n\n" + delegate.text(signal)
    override fun json(signal: CancellationSignal): String = org.json.JSONObject(delegate.json(signal)).apply {
        put("contentSource", "embedded-preview")
        put("previewPart", part)
        put("previewFormat", optString("format"))
        put("format", info.format)
        put("fidelity", info.fidelity)
        val existing = optJSONArray("warnings")
        val messages = buildList {
            if (existing != null) for (i in 0 until existing.length()) add(existing.getString(i))
            addAll(info.warnings)
        }.distinct()
        put("warnings", org.json.JSONArray(messages))
    }.toString()
}

internal class PdfSession(private val pdf: PdfDocument, name: String, warnings: List<String>) : DocumentSession {
    override val info = DocumentInfo(name, "application/pdf", "pdf", pdf.pageCount, pdf.textAvailable, "ORIGINAL_LAYOUT", warnings, supportsMargins = true, supportsOutline = true, supportsNotes = true)
    override fun outline(signal: CancellationSignal) = pdf.outline(signal)
    override fun notes(signal: CancellationSignal) = pdf.notes(signal)
    override fun bodyText(signal: CancellationSignal) = pdf.text(signal)
    override fun pageAspect(index: Int, signal: CancellationSignal) = pdf.pageAspect(index, signal)
    override fun renderPrintPage(index: Int, width: Int, signal: CancellationSignal) = pdf.renderPrintPage(index, width, signal)
    override fun text(signal: CancellationSignal): String {
        val text = pdf.text(signal)
        return notesForExport(signal).appendToText(text)
    }
    override fun json(signal: CancellationSignal): String {
        val json = pdf.json(signal)
        return notesForExport(signal).appendTo(json)
    }
    override fun renderPage(index: Int, width: Int, settings: ThemeSettings, signal: CancellationSignal): Bitmap = themed(pdf.renderPage(index, width, signal), settings, signal)
    override fun close() = pdf.close()
}

internal fun themed(source: Bitmap, settings: ThemeSettings, signal: CancellationSignal): Bitmap {
    try {
        val result = BitmapTheme.apply(source, settings, signal)
        if (result !== source) source.recycle()
        return result
    } catch (failure: Throwable) { source.recycle(); throw failure }
}
