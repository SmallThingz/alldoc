package app.smallthingz.alldoc.clienttest

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.api.AllDocClient
import app.smallthingz.alldoc.api.IAllDocCallback
import app.smallthingz.alldoc.api.IAllDocService
import app.smallthingz.alldoc.api.IAllDocSession
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocumentApiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connections = ArrayList<ServiceConnection>()
    private val sessions = ArrayList<IAllDocSession>()
    private lateinit var service: IAllDocService

    private fun bind(component: ComponentName): IBinder {
        val ready = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, value: IBinder) { binder = value; ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        check(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
        connections += connection
        check(ready.await(10, TimeUnit.SECONDS))
        return checkNotNull(binder)
    }
    @Before fun connect() {
        service = IAllDocService.Stub.asInterface(bind(ComponentName(AllDocClient.PACKAGE, AllDocClient.SERVICE)))
        assertEquals(AllDocClient.VERSION, service.version())
        assertNotEquals(context.applicationInfo.uid, context.packageManager.getApplicationInfo(AllDocClient.PACKAGE, 0).uid)
    }
    @After fun cleanup() {
        sessions.forEach { runCatching { it.close() } }
        connections.forEach { context.unbindService(it) }
        File(context.cacheDir, "api-fixture.html").delete()
        File(context.cacheDir, "api-fixture.md").delete()
    }
    private fun open(text: String = "Hello AllDoc\nA second line\n", offset: Long = 0, markdown: Boolean = false): IAllDocSession {
        val source = File(context.cacheDir, if (markdown) "api-fixture.md" else "api-fixture.html").apply {
            writeText(if (markdown) text else "<html><body><pre>" + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre></body></html>")
        }
        val ready = CountDownLatch(1)
        var state = Bundle()
        val callback = object : IAllDocCallback.Stub() {
            override fun onState(value: Bundle) { state = value; ready.countDown() }
        }
        val session = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
            if (offset != 0L) Os.lseek(it.fileDescriptor, offset, OsConstants.SEEK_SET)
            service.open(it, source.name, if (markdown) "text/markdown" else "text/html", "", callback)
        }
        sessions += session
        assertTrue("Document did not open", ready.await(20, TimeUnit.SECONDS))
        assertEquals(state.getString("error"), "ready", state.getString("state"))
        return session
    }
    private fun read(session: IAllDocSession, id: Long, format: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(session.read(id, format, 0, 480, "original", true)).use { it.readBytes() }

    @Test fun textHierarchyAndImageAcrossUid() {
        val content = "a".repeat(8191) + "😀".repeat(5000) + "b" + "😀".repeat(5000) + " café, क्षि, 🇦🇺"
        val document = open(content)
        assertArrayEquals(content.toByteArray(Charsets.UTF_8), read(document, 1, "text"))
        val json = String(read(document, 2, "json"), Charsets.UTF_8)
        assertTrue("JSON must split a supplementary scalar at a writer boundary",
            (8192 until json.length step 8192).any { json[it - 1].isHighSurrogate() && json[it].isLowSurrogate() })
        assertEquals(content, JSONObject(json).getJSONArray("sections").getJSONObject(0)
            .getJSONArray("blocks").getJSONObject(0).getString("text"))
        val image = read(document, 3, "png")
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size)
        assertNotNull(bitmap)
        assertEquals(480, bitmap.width)
        bitmap.recycle()
    }

    @Test fun markdownHasOneContinuousLogicalPageAcrossUid() {
        val document = open("# Document title\n\n**Readable** Markdown body.", markdown = true)
        assertEquals(1, document.status().getInt("pageCount"))
        assertTrue(document.status().getBoolean("continuous"))
        assertTrue(String(read(document, 1, "text"), Charsets.UTF_8).contains("Readable Markdown body."))
        val image = read(document, 2, "png")
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(image, 0, image.size))
        try { assertEquals(480, bitmap.width); assertTrue(bitmap.height > 0) } finally { bitmap.recycle() }
        val invalid = document.read(3, "png", 1, 480, "original", true)
        assertThrows(IOException::class.java) { ParcelFileDescriptor.AutoCloseInputStream(invalid).use { it.readBytes() } }
        assertEquals(1, document.status().getInt("pageCount"))
    }

    @Suppress("DEPRECATION")
    @Test fun previewSdkRendersSparseWorksheetsWithoutPrintPaginationAcrossUid() {
        val source = File.createTempFile("api-sparse-", ".xlsx", context.cacheDir)
        writeSparseWorkbook(source)
        val connected = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val state = AtomicReference<Bundle>()
        try {
            AllDocClient.connect(context, Executor { it.run() }, onConnected = { connected.countDown() }).use { client ->
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                client.openForPreview(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), source.name,
                    onState = { _, value -> state.set(value); ready.countDown() }).use { document ->
                    assertTrue("Sparse workbook did not settle", ready.await(30, TimeUnit.SECONDS))
                    val status = checkNotNull(state.get())
                    assertEquals(status.getString("error"), "ready", status.getString("state"))
                    assertEquals("worksheet", status.getString("view"))
                    assertFalse(status.getBoolean("paginationKnown"))
                    assertFalse("An unpaginated worksheet must not claim zero pages", status.containsKey("pageCount"))
                    val sheets = checkNotNull(status.getParcelableArrayList<Bundle>("sheets"))
                    val active = status.getInt("activeSheet")
                    val sheet = sheets.single { it.getInt("index") == active }
                    assertEquals(12000, sheet.getInt("usedRows"))
                    assertEquals(52, sheet.getInt("usedColumns"))
                    val images = ArrayList<android.graphics.Bitmap>()
                    try {
                        for (theme in listOf("original", "adaptive")) {
                            val bytes = document.readSheet(active, width = 192, height = 128, theme = theme).use {
                                it.inputStream().use { input -> input.readBytes() }
                            }
                            images += checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        }
                        assertTrue(images.all { it.width == 192 && it.height == 128 })
                        assertFalse("Adaptive worksheet rendering must apply its theme", images[0].sameAs(images[1]))
                    } finally { images.forEach { it.recycle() } }
                    val text = document.read("text").use { it.inputStream().use { input -> input.readBytes().toString(Charsets.UTF_8) } }
                    val json = document.read("json").use { it.inputStream().use { input -> input.readBytes().toString(Charsets.UTF_8) } }
                    assertTrue(text.contains("Origin")); assertTrue(text.contains("Far cell"))
                    assertTrue(json.contains("Far cell"))
                    assertFalse(document.status().containsKey("pageCount"))
                    for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
                        assertThrows(IllegalArgumentException::class.java) { document.readSheet(active, x = x).close() }
                    }
                    assertThrows(IllegalArgumentException::class.java) { document.readSheet(active, width = 2049).close() }
                    assertThrows(IllegalArgumentException::class.java) { document.readSheet(active, scale = 0.0).close() }
                    assertThrows(IllegalArgumentException::class.java) { document.readSheet(active, imageTreatment = "unknown").close() }
                    assertThrows(IOException::class.java) {
                        document.readSheet(1023, width = 64, height = 64).use { it.inputStream().use { input -> input.readBytes() } }
                    }
                    assertTrue(document.read("text").use { it.inputStream().use { input -> input.readBytes().isNotEmpty() } })
                }
            }
        } finally { source.delete() }
    }

    @Test fun previewSdkRejectsLegacyServerWithoutLeakingItsInput() {
        val source = File.createTempFile("api-legacy-", ".md", context.cacheDir).apply { writeText("Old server") }
        val legacy = object : IAllDocService.Stub() {
            override fun version() = 1
            override fun open(input: ParcelFileDescriptor?, name: String?, mime: String?, password: String?,
                              callback: IAllDocCallback?): IAllDocSession = error("Preview must negotiate before opening")
            override fun openForPreview(input: ParcelFileDescriptor?, name: String?, mime: String?, password: String?,
                                        callback: IAllDocCallback?): IAllDocSession = error("Version-1 transaction must not be invoked")
        }
        val fake = object : ContextWrapper(context) {
            lateinit var connection: ServiceConnection
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                this.connection = connection; return true
            }
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        try {
            AllDocClient.connect(fake, Executor { it.run() }, onConnected = {}).use { client ->
                fake.connection.onServiceConnected(ComponentName(AllDocClient.PACKAGE, AllDocClient.SERVICE), legacy)
                val input = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                val fd = input.fileDescriptor
                assertThrows(IllegalStateException::class.java) { client.openForPreview(input, source.name, onState = { _, _ -> }) }
                assertFalse("Rejected preview must consume its source descriptor", fd.valid())
            }
        } finally { source.delete() }
    }

    private fun writeSparseWorkbook(file: File) {
        val parts = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sparse" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:AZ12000"/><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Origin</t></is></c></row><row r="12000"><c r="AZ12000" t="inlineStr"><is><t>Far cell</t></is></c></row></sheetData></worksheet>"""
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, xml) in parts) { zip.putNextEntry(ZipEntry(name)); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        }
    }

    @Test fun nonemptyWhitespacePasswordIsPassedLiterally() {
        val source = File.createTempFile("api-password-", ".pdf", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("space-password.pdf").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            for (password in listOf("", "incorrect", "   ")) {
                val ready = CountDownLatch(1)
                val state = AtomicReference<Bundle>()
                val callback = object : IAllDocCallback.Stub() {
                    override fun onState(value: Bundle) { state.set(value); ready.countDown() }
                }
                val document = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    service.open(it, "space-password.pdf", "application/pdf", password, callback)
                }
                sessions += document
                try {
                    assertTrue("Encrypted document did not settle", ready.await(20, TimeUnit.SECONDS))
                    val status = checkNotNull(state.get())
                    if (Build.VERSION.SDK_INT >= 35 && password == "   ") {
                        assertEquals(status.getString("error"), "ready", status.getString("state"))
                        assertEquals(1, status.getInt("pageCount"))
                        val png = read(document, 1, "png")
                        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "Expected a rendered PNG" }
                        try { assertEquals(480, bitmap.width); assertEquals(320, bitmap.height) }
                        finally { bitmap.recycle() }
                    } else {
                        assertEquals("error", status.getString("state"))
                        val expected = if (Build.VERSION.SDK_INT < 35 && password.isNotEmpty()) "Android 15" else "password"
                        assertTrue(status.getString("error").orEmpty().contains(expected, ignoreCase = true))
                    }
                } finally { document.close() }
            }
        } finally { source.delete() }
    }

    @Test fun descriptorOffsetDoesNotSkipDocumentBeginning() {
        val document = open(offset = 6)
        assertEquals("Hello AllDoc\nA second line", String(read(document, 1, "text")).trim())
    }

    @Test fun sdkCallbackCarriesItsUsableDocument() {
        val source = File(context.cacheDir, "api-fixture.html").apply { writeText("<html><body><p>Callback owner</p></body></html>") }
        val connected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val observed = AtomicReference<AllDocClient.Document>()
        val failure = AtomicReference<Throwable>()
        AllDocClient.connect(context, Executor { it.run() }, onConnected = { connected.countDown() }).use { client ->
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            client.open(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), "fixture.html",
                onState = { owner, state ->
                    try {
                        check(state.getString("state") == "ready")
                        observed.set(owner)
                        owner.read("text").use { result ->
                            result.inputStream().use { check(String(it.readBytes()) == "Callback owner") }
                        }
                    } catch (error: Throwable) { failure.set(error) }
                    finally { completed.countDown() }
                }).use { document ->
                assertTrue(completed.await(20, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertSame(document, observed.get())
            }
        }
    }

    @Test fun sdkDiscardsStaleConnectionCallbacks() {
        val queued = java.util.ArrayDeque<Runnable>()
        val events = ArrayList<String>()
        var unbound = 0
        val fake = object : ContextWrapper(context) {
            lateinit var connection: ServiceConnection
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                this.connection = connection
                return true
            }
            override fun unbindService(connection: ServiceConnection) { unbound++ }
        }
        val component = ComponentName(AllDocClient.PACKAGE, AllDocClient.SERVICE)
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        fun connectClient() = AllDocClient.connect(fake, Executor { queued.add(it) },
            onDisconnected = { events += "disconnected" }, onConnected = { events += "connected" })
        connectClient().use { client ->
            fake.connection.onServiceConnected(component, Binder())
            fake.connection.onServiceDisconnected(component)
            fake.connection.onServiceConnected(component, Binder())
            drain()
            assertEquals(listOf("connected"), events)
            fake.connection.onServiceDisconnected(component)
            client.close()
            drain()
            assertEquals(listOf("connected"), events)
        }
        connectClient().use {
            fake.connection.onBindingDied(component)
            drain()
            assertEquals(listOf("connected", "disconnected"), events)
        }
        connectClient().use { client ->
            fake.connection.onNullBinding(component)
            client.close()
            drain()
            assertEquals(listOf("connected", "disconnected"), events)
        }
        assertEquals(3, unbound)
    }

    @Test fun forwardedBinderDoesNotTransferOwnership() {
        val document = open()
        val probe = ISessionProbe.Stub.asInterface(bind(ComponentName(context, IsolatedProbeService::class.java)))
        assertTrue(probe.rejects(document.asBinder()))
    }

    @Test fun boundedSessionsAndClosedHandle() {
        val first = open()
        open()
        val source = File(context.cacheDir, "api-fixture.html")
        val callback = object : IAllDocCallback.Stub() { override fun onState(status: Bundle) {} }
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                service.open(it, "fixture.html", "text/html", "", callback)
            }
            fail("Third session must be rejected")
        } catch (_: IllegalStateException) {}
        first.close()
        assertEquals("closed", first.status().getString("state"))
        try { first.read(1, "text", 0, 480, "original", true); fail("Closed session read") }
        catch (_: IllegalStateException) {}
    }

    @Test fun stalledInputCanBeCancelled() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val callback = object : IAllDocCallback.Stub() { override fun onState(status: Bundle) {} }
            val document = service.open(pipe[0], "fixture.html", "text/html", "", callback)
            sessions += document
            document.close()
            assertEquals("closed", document.status().getString("state"))
        } finally { pipe.forEach { it.close() } }
        val document = open()
        assertTrue(String(read(document, 1, "text")).contains("Hello AllDoc"))
    }

    @Test fun outputBackpressureDoesNotBlockOtherReadsOrCancellation() {
        val text = "AllDoc output backpressure.\n".repeat(16000).trimEnd()
        val document = open(text)
        val blocked = document.read(1, "text", 0, 480, "original", true)
        val reader = Executors.newSingleThreadExecutor()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(blocked).use { input ->
                // Linux F_GETPIPE_SZ: prove this payload exceeds the actual output pipe.
                val capacity = Os.fcntlInt(blocked.fileDescriptor, 1032, 0)
                assertTrue("Fixture must exceed pipe capacity ($capacity)", capacity > 0 && text.length - 1 > capacity)
                assertEquals('A'.code, reader.submit<Int> { input.read() }.get(10, TimeUnit.SECONDS))
                // A is producing but remains unread; B must finish before A is cancelled.
                val image = reader.submit<ByteArray> { read(document, 2, "png") }.get(10, TimeUnit.SECONDS)
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(image, 0, image.size))
                try { assertEquals(480, bitmap.width) } finally { bitmap.recycle() }
                document.cancel(1)
                try {
                    input.readBytes()
                    fail("Cancelled export should end with an error")
                } catch (_: IOException) {}
            }
            assertEquals(text, String(read(document, 3, "text"), Charsets.UTF_8))
        } finally {
            runCatching { document.cancel(1) }
            runCatching { document.cancel(2) }
            runCatching { blocked.close() }
            reader.shutdownNow()
            assertTrue("Output reader must stop after cleanup", reader.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun completedRequestIdCanBeReusedAfterSuccessAndError() {
        val document = open("Reusable output")
        val id = 23L
        val pageCount = document.status().getInt("pageCount")
        assertEquals("Reusable output", String(read(document, id, "text")))
        val failed = document.read(id, "png", pageCount, 480, "original", true)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(failed).use { it.readBytes() }
            fail("Out-of-range page must end with an error")
        } catch (_: IOException) {}
        assertEquals("Reusable output", String(read(document, id, "text")))
        assertTrue(JSONObject(String(read(document, id, "json"))).getJSONArray("sections").length() > 0)
    }

    @Test fun unknownOutputAndInvalidDimensionsAreRejected() {
        val document = open()
        for ((format, width) in listOf("unknown" to 480, "png" to Int.MAX_VALUE)) {
            try { document.read(1, format, 0, width, "original", true); fail("Invalid request accepted") }
            catch (_: IllegalArgumentException) {}
        }
        assertTrue(read(document, 2, "text").isNotEmpty())
    }
}

