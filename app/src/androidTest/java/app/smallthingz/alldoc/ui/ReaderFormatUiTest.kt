package app.smallthingz.alldoc.ui

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderFormatUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var scrollDiagnostics = "No page scroll probe"

    @Test fun representativeFormatsReachTheirActualReadingSurface() {
        val directory = File(instrumentation.targetContext.cacheDir, "format-ui-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val pdf = File(directory, "sample.pdf").also { writeReaderPdf(it, pages = 8) }
            val office = listOf("sample.docx", "sample.xlsx", "sample.pptx").map { name ->
                File(directory, name).also { file -> instrumentation.context.assets.open("office/$name").use { input ->
                    ZipInputStream(input).use { source -> ZipOutputStream(file.outputStream()).use { output ->
                        output.setComment(UUID.randomUUID().toString())
                        while (true) {
                            val entry = source.nextEntry ?: break
                            output.putNextEntry(ZipEntry(entry.name))
                            source.copyTo(output); output.closeEntry(); source.closeEntry()
                        }
                    } }
                } }
            }
            val odt = File(directory, "sample.odt").also { zip(it, mapOf(
                "mimetype" to "application/vnd.oasis.opendocument.text",
                "content.xml" to """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:text><text:p>AllDoc ODF reading fixture</text:p></office:text></office:body></office:document-content>""")) }
            val epub = File(directory, "sample.epub").also { zip(it, mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                "book.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>AllDoc EPUB</dc:title><dc:identifier id="id">alldoc-ui</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
                "chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body><p>AllDoc EPUB reading fixture</p></body></html>""")) }
            ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)).use { scenario ->
                for (file in listOf(pdf) + office + listOf(odt, epub)) {
                    scenario.onActivity { ViewModelProvider(it)[ReaderModel::class.java].open(Uri.fromFile(file)) }
                    await(file.name) {
                        var ready = false
                        scenario.onActivity {
                            val reader = ViewModelProvider(it)[ReaderModel::class.java]
                            assertNull("${file.name}: ${reader.error}", reader.error)
                            ready = reader.currentTitle == file.name && reader.info != null && !reader.loading &&
                                reader.bitmap != null && !reader.pageLoading
                        }
                        ready
                    }
                    scenario.onActivity {
                        val reader = ViewModelProvider(it)[ReaderModel::class.java]
                        assertEquals(file.name, reader.currentTitle)
                        assertFalse(reader.textMode)
                        assertFalse("${file.name}: pages must default to vertical scrolling", reader.horizontalPages)
                        val bitmap = requireNotNull(reader.bitmap)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        assertTrue("${file.name}: blank rendered content", pixels.any { it != pixels[0] })
                    }
                    await("${file.name}: visible reading surface") {
                        visible("Document pages")
                    }
                    run {
                        assertFalse("Page-by-page arrows must not replace continuous reading", visible("Next page") || visible("Previous page"))
                        // Multiple short slides may fit at once. Exercise overflow with the long PDF.
                        if (file == pdf) {
                            var advanced = false
                            repeat(4) {
                                if (!advanced) {
                                    await("${file.name}: pages must expose vertical scrolling", diagnostics = { scrollDiagnostics }) {
                                        scrollPages()
                                    }
                                    instrumentation.waitForIdleSync()
                                    scenario.onActivity { advanced = ViewModelProvider(it)[ReaderModel::class.java].page > 0 }
                                }
                            }
                            await("${file.name}: later page must render after scrolling") {
                                var ready = false
                                scenario.onActivity { val reader = ViewModelProvider(it)[ReaderModel::class.java]
                                    assertNull(reader.error)
                                    ready = reader.page > 0 && reader.bitmap != null && !reader.pageLoading }
                                ready
                            }
                        }
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Suppress("DEPRECATION")
    private fun scrollPages(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: run { scrollDiagnostics = "No active window"; return false }
        val candidates = ArrayList<String>()
        fun find(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 24 || !node.refresh()) return false
            val pages = node.contentDescription?.toString() == "Document pages"
            if (candidates.size < 12 && (pages || node.isScrollable)) candidates.add(
                "${node.className}: description=${node.contentDescription}, visible=${node.isVisibleToUser}, " +
                    "scrollable=${node.isScrollable}, actions=${node.actionList.map { it.id }}")
            if (node.isVisibleToUser && pages && node.isScrollable &&
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (index in 0 until minOf(node.childCount, 64)) {
                val child = node.getChild(index) ?: continue
                try { if (find(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { find(root, 0) } finally {
            scrollDiagnostics = candidates.joinToString("; ").ifEmpty { "No page or scrollable node" }
            root.recycle()
        }
    }

    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { output ->
            output.setComment(UUID.randomUUID().toString())
            entries.forEach { (name, text) ->
            val bytes = text.toByteArray()
            val entry = ZipEntry(name).apply {
                if (name == "mimetype") {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                }
            }
            output.putNextEntry(entry); output.write(bytes); output.closeEntry()
        } }
    }
    private fun await(message: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        fail(listOf(message, diagnostics()).filter(String::isNotEmpty).joinToString("; "))
    }
    @Suppress("DEPRECATION")
    private fun visible(label: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val seen = intArrayOf(0)
        fun find(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 24 || seen[0]++ >= 512) return false
            if (node.isVisibleToUser && node.packageName?.toString() == "app.smallthingz.alldoc" &&
                (node.contentDescription?.toString() == label || node.text?.contains(label) == true)) return true
            for (index in 0 until minOf(node.childCount, 64)) {
                val child = node.getChild(index) ?: continue
                try { if (find(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return try { find(root, 0) } finally { root.recycle() }
    }
}
