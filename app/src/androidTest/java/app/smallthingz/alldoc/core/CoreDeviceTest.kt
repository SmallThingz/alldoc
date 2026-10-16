package app.smallthingz.alldoc.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreDeviceTest {
    @Test fun nativeAndXmlReadersWorkOnAndroid() {
        assertTrue("Native document library missing", NativeDocuments.isAvailable())
        val document = NativeDocuments.preview("{\\rtf1 Hello \\u233?}".toByteArray(), "example.rtf", null)
        assertTrue(document.toPlainText().contains("Hello é"))
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("fb2-", ".fb2", cache)
        try {
            file.writeText("<FictionBook><body><section><title><p>Title</p></title><p>Text</p></section></body></FictionBook>")
            val book = DocumentParser.parse(file)
            assertEquals("heading", book.sections.single().blocks.first().kind)
            assertTrue(book.toPlainText().contains("Text"))
            file.writeText("<!DOCTYPE FictionBook><FictionBook/>")
            try { DocumentParser.parse(file); fail("DTD accepted") } catch (_: java.io.IOException) { }
        } finally { file.delete() }
    }
}
