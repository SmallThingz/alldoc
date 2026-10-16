package app.smallthingz.alldoc.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/** Bounded semantic Office preview. All methods except supportsName belong on a worker. */
object NativeDocuments {
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    private const val MAX_WIRE_BYTES = 2 * 1024 * 1024
    fun interface Cancellation { fun isCancelled(): Boolean }

    class PreviewException(@JvmField val code: String, message: String?) : IOException(message)

    private object Native {
        val AVAILABLE = try {
            System.loadLibrary("alldoc")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
    @JvmStatic fun isAvailable(): Boolean = Native.AVAILABLE

    /** The name is only a routing hint; the parser validates the content independently. */
    @JvmStatic fun supportsName(name: String?): Boolean = name != null && DocumentFormats.isNative(name)

    @JvmStatic @Throws(IOException::class)
    fun preview(input: ByteArray, nameHint: String?, cancellation: Cancellation?): Document {
        val hint = nameHint ?: ""
        val combined = Cancellation {
            Thread.currentThread().isInterrupted || cancellation?.isCancelled() == true
        }
        check(combined)
        if (input.size > MAX_INPUT_BYTES || hint.length > 512)
            throw PreviewException("LIMIT", "Document input exceeds preview limits")
        if (!isAvailable()) throw PreviewException("UNAVAILABLE", "Native document preview is unavailable")
        val wire = previewNative(input, hint, combined)
        check(combined)
        if (wire == null || wire.size > MAX_WIRE_BYTES)
            throw PreviewException("MALFORMED", "Invalid document preview result")
        try {
            DataInputStream(ByteArrayInputStream(wire)).use { stream ->
                if (stream.readInt() != 0x5a464431) throw IOException("Invalid preview protocol")
                if (stream.readUnsignedByte() != 0) {
                    val code = string(stream)
                    val message = string(stream)
                    if (code == "CANCELLED") throw CancellationException(message)
                    throw PreviewException(code, message)
                }
                val format = string(stream)
                val truncated = stream.readBoolean()
                val warnings = ArrayList<String>()
                repeat(count(stream, 32)) { warnings.add(string(stream)) }
                val sections = ArrayList<Section>()
                val ns = count(stream, 64)
                var blocks = 0
                var textBytes = 0
                repeat(ns) {
                    check(combined)
                    val title = string(stream)
                    val kind = string(stream)
                    if (kind != "sheet" && kind != "slide" && kind != "document")
                        throw IOException("Unknown preview section")
                    val values = ArrayList<Block>()
                    val nb = count(stream, 4096)
                    blocks += nb
                    if (blocks > 4096) throw IOException("Too many preview blocks")
                    repeat(nb) {
                        val k = string(stream)
                        val t = string(stream)
                        val row = stream.readInt()
                        val column = stream.readInt()
                        val level = stream.readInt()
                        textBytes += t.toByteArray(StandardCharsets.UTF_8).size
                        if (textBytes > 512 * 1024 || level < 0 || level > 6 ||
                            (k != "paragraph" && k != "heading" && k != "cell") ||
                            (if (k == "cell") row < 0 || column < 0 else row != -1 || column != -1))
                            throw IOException("Invalid preview block")
                        values.add(Block(k, t, row, column, level))
                    }
                    sections.add(Section(title, kind, values.toList()))
                }
                if (stream.available() != 0) throw IOException("Trailing preview data")
                check(combined)
                return Document(format, "TEXT_STRUCTURE", truncated, warnings.toList(), sections.toList())
            }
        } catch (expected: PreviewException) {
            throw expected
        } catch (invalid: IOException) {
            throw PreviewException("MALFORMED", invalid.message)
        }
    }

    private fun count(stream: DataInputStream, max: Int): Int {
        val n = stream.readInt()
        if (n < 0 || n > max) throw IOException("Preview count exceeds bounds")
        return n
    }
    private fun string(stream: DataInputStream): String {
        val n = count(stream, 512 * 1024)
        if (n > stream.available()) throw IOException("Truncated preview string")
        val data = ByteArray(n)
        stream.readFully(data)
        return try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        } catch (malformed: CharacterCodingException) {
            throw IOException("Invalid preview Unicode", malformed)
        }
    }
    private fun check(cancellation: Cancellation) {
        if (cancellation.isCancelled()) throw CancellationException("Document preview cancelled")
    }
    @JvmStatic private external fun previewNative(
        input: ByteArray, nameHint: String, cancellation: Cancellation
    ): ByteArray?
}
