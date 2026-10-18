package app.smallthingz.alldoc.ui

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.smallthingz.alldoc.oacp.DocumentCapabilityModel
import app.smallthingz.alldoc.state.AppAppearanceStore
import app.smallthingz.alldoc.state.AppTheme
import app.smallthingz.alldoc.state.DocumentViewMode
import app.smallthingz.alldoc.state.LibraryCatalog
import app.smallthingz.alldoc.state.LibraryCategory
import app.smallthingz.alldoc.state.TextPosition
import app.smallthingz.alldoc.theme.ReadingMode
import app.smallthingz.alldoc.theme.ThemeSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderRefinementStateTest {
    @Test fun explicitSystemWinsWhileStartupReadsAreBlockedAndLaterChoicesStayOrdered() = ReaderPersistenceTest().Fixture().use { fixture ->
        val appearance = AppAppearanceStore(fixture.context)
        appearance.write(AppTheme.DARK)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blocker: Future<*>? = null
        val worker = ReaderModel::class.java.getDeclaredField("StateWorker").let {
            it.isAccessible = true; it.get(null) as ExecutorService
        }
        try {
            fixture.launch(beforeCreate = {
                blocker = worker.submit { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
            }) { reader ->
                assertEquals(AppTheme.SYSTEM, reader.appTheme)
                reader.updateAppTheme(AppTheme.SYSTEM)
            }
        } finally { release.countDown() }
        blocker?.get(15, TimeUnit.SECONDS)
        fixture.stateBarrier()
        fixture.act { assertEquals("Hydration must not replace an explicit same-value choice", AppTheme.SYSTEM, it.appTheme) }
        assertEquals(AppTheme.SYSTEM, appearance.read())
        fixture.act { it.updateAppTheme(AppTheme.LIGHT); it.updateAppTheme(AppTheme.DARK); it.updateAppTheme(AppTheme.AMOLED) }
        fixture.launch()
        fixture.await { it.appTheme == AppTheme.AMOLED }
        assertEquals("Ordered writes must survive a new model", AppTheme.AMOLED, appearance.read())
    }

    @Test fun capabilityThemeHydrationDrainsNewerAcceptedAppearanceWrites() = ReaderPersistenceTest().Fixture().use { fixture ->
        fixture.launch(width = 0)
        fixture.stateBarrier()
        val worker = ReaderModel::class.java.getDeclaredField("StateWorker").let {
            it.isAccessible = true; it.get(null) as ExecutorService
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = worker.submit { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        val owner = ViewModelStore()
        lateinit var capability: DocumentCapabilityModel
        var job: Job? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.act { reader ->
                capability = DocumentCapabilityModel(fixture.application)
                owner.put("capability", capability)
                job = capability.viewModelScope.coroutineContext[Job]
                // Hydration is queued first; the read barrier must still drain this newer choice.
                reader.updateAppTheme(AppTheme.AMOLED)
                assertEquals(AppTheme.SYSTEM, capability.appTheme)
            }
            release.countDown()
            blocker.get(15, TimeUnit.SECONDS)
            fixture.stateBarrier()
            fixture.await { capability.appTheme == AppTheme.AMOLED }
            assertEquals(AppTheme.AMOLED, AppAppearanceStore(fixture.context).read())
        } finally {
            release.countDown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
            runBlocking { withTimeout(15_000) { job?.join() } }
            blocker.get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun acceptedLibrarySettingsSurviveImmediateOwnerReplacement() = ReaderPersistenceTest().Fixture().use { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = ReaderModel::class.java.getDeclaredField("StateWorker").let {
            it.isAccessible = true; it.get(null) as ExecutorService
        }
        val blocker = worker.submit { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        val owners = ArrayList<ViewModelStore>()
        val jobs = ArrayList<Job>()
        var replacement: ReaderModel? = null
        val expected = LibraryCategory.available.take(2).toSet()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                fun model(): ReaderModel = ReaderModel(fixture.application, fixture.store, fixture.sources).also { reader ->
                    owners += ViewModelStore().also { it.put("reader", reader) }
                    reader.viewModelScope.coroutineContext[Job]?.let(jobs::add)
                }
                val first = model()
                first.setLibraryHiddenTypes(setOf(LibraryCategory.available.last()))
                first.setLibraryHiddenTypes(expected)
                owners.first().clear()
                replacement = model()
            }
            release.countDown()
            blocker.get(15, TimeUnit.SECONDS)
            fixture.stateBarrier()
            val deadline = SystemClock.uptimeMillis() + 10_000
            var restored = false
            while (!restored && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { restored = replacement?.libraryHiddenTypes == expected }
                if (!restored) SystemClock.sleep(25)
            }
            assertTrue("A fresh owner must hydrate the latest accepted settings", restored)
            LibraryCatalog(fixture.context).use { assertEquals(expected, it.hiddenTypes()) }
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { owners.forEach(ViewModelStore::clear) }
            runBlocking { withTimeout(15_000) { jobs.forEach { it.join() } } }
            blocker.get(15, TimeUnit.SECONDS)
        }
    }

    @Test fun openedDocumentsUseResolvedFormatsInTheLibraryWithoutScanning() = ReaderPersistenceTest().Fixture().use { fixture ->
        val html = File(fixture.context.root, "Guide").apply {
            writeText("<!doctype html><html><body><h1>AllDoc guide</h1><p>Document preview.</p></body></html>")
        }
        val pdf = File(fixture.context.root, "Source.kt").also { writeReaderPdf(it) }
        fixture.launch(width = 0)
        for ((file, mime, category) in listOf(
            Triple(html, "text/html", LibraryCategory.DOCUMENTS),
            Triple(pdf, "application/pdf", LibraryCategory.DOCUMENTS),
        )) {
            val uri = Uri.fromFile(file)
            fixture.act { it.open(uri) }
            fixture.await { it.currentTitle == file.name && it.info != null && !it.loading }
            var hash = ""
            fixture.act {
                assertEquals(mime, it.info!!.mimeType)
                assertFalse("Opening must not depend on a Library scan", it.libraryScanning)
                hash = requireNotNull(it.documentHash)
            }
            LibraryCatalog(fixture.context).use { catalog ->
                val document = requireNotNull(catalog.location(uri.toString()))
                assertEquals(file.name, document.title)
                assertEquals(mime, document.mimeType)
                assertEquals(category, document.category)
                assertEquals(hash, document.hash)
                assertTrue(catalog.pageSnapshot(0, 20, app.smallthingz.alldoc.state.LibraryQuery(category = category))
                    .rows.any { it.id == document.id })
            }
        }
    }

    @Test fun visibleCoversCorrectProvisionalTypesAndAliasesWithoutOpeningOrScanning() = ReaderPersistenceTest().Fixture().use { fixture ->
        val first = File(fixture.context.root, "A.pdf")
        fixture.context.assets.open("examples/office/office-guide.epub").use { input ->
            first.outputStream().use { input.copyTo(it) }
        }
        val alias = File(fixture.context.root, "B.pdf").also { first.copyTo(it) }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(first.readBytes()).toHexString()
        val folder = File(fixture.context.cacheDir, "library-previews-v7").apply { check(mkdirs()) }
        // Existing releases cached by hash alone, without confirming a location's advertised type.
        val obsolete = android.graphics.Bitmap.createBitmap(8, 4, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            obsolete.eraseColor(android.graphics.Color.BLUE)
            File(folder, "$hash.png").outputStream().use {
                assertTrue(obsolete.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { obsolete.recycle() }
        LibraryCatalog(fixture.context).use { catalog ->
            fun discover(file: File) = requireNotNull(catalog.discover(app.smallthingz.alldoc.state.LibraryFile(
                Uri.fromFile(file).toString(), file.name, "application/pdf", file.length(), 0)))
            val canonical = requireNotNull(catalog.resolve(discover(first), hash))
            val candidate = discover(alias)
            catalog.setHiddenTypes(setOf(LibraryCategory.BOOKS))
            fixture.launch(width = 0)
            fixture.await { it.libraryHiddenTypes == setOf(LibraryCategory.BOOKS) }
            lateinit var library: ReaderLibrary
            val query = app.smallthingz.alldoc.state.LibraryQuery(excluded = setOf(LibraryCategory.BOOKS))
            lateinit var pageSource: LibraryPaging
            fixture.act { reader ->
                library = ReaderModel::class.java.getDeclaredField("library").apply { isAccessible = true }.get(reader) as ReaderLibrary
                // Isolate cover publication from device scanning; the real loader and callback stay intact.
                ReaderLibrary::class.java.getDeclaredField("dirty").apply { isAccessible = true }.setBoolean(library, false)
                pageSource = LibraryPaging(catalog, query)
                ReaderLibrary::class.java.getDeclaredField("pageSource").apply { isAccessible = true }.set(library, pageSource)
                reader.setLibraryActive(true)
                reader.setLibraryVisible(listOf(candidate))
            }
            fun corrected(uri: String): app.smallthingz.alldoc.state.LibraryDocument {
                val deadline = SystemClock.uptimeMillis() + 15_000
                while (SystemClock.uptimeMillis() < deadline) {
                    val row = requireNotNull(catalog.location(uri))
                    if (row.mimeType == "application/epub+zip") return row
                    SystemClock.sleep(25)
                }
                error("The real visible-cover pipeline did not publish the parsed MIME")
            }
            val merged = corrected(candidate.uri)
            assertEquals(canonical.id, merged.id)
            fixture.await { pageSource.invalid }
            val oldCanonical = requireNotNull(catalog.location(canonical.uri))
            fixture.act {
                assertTrue(it.libraryPreviews.isEmpty())
                pageSource = LibraryPaging(catalog, query)
                ReaderLibrary::class.java.getDeclaredField("pageSource").apply { isAccessible = true }.set(library, pageSource)
                it.setLibraryVisible(listOf(oldCanonical))
            }
            val actual = corrected(canonical.uri)
            assertEquals(hash, actual.hash)
            assertEquals(LibraryCategory.BOOKS, actual.category)
            assertNotEquals(canonical.revision, actual.revision)
            fixture.await { pageSource.invalid }
            assertTrue(catalog.pageSnapshot(0, 20, query).rows.isEmpty())
            assertTrue(catalog.pageSnapshot(0, 20, app.smallthingz.alldoc.state.LibraryQuery(category = LibraryCategory.DOCUMENTS)).rows.isEmpty())
            assertEquals(listOf(actual.id), catalog.pageSnapshot(0, 20,
                app.smallthingz.alldoc.state.LibraryQuery(category = LibraryCategory.BOOKS)).rows.map { it.id })
            fixture.act { reader ->
                assertTrue("A stale category must not receive a cover", reader.libraryPreviews.isEmpty())
                reader.setLibraryVisible(listOf(actual))
            }
            fixture.await { it.libraryPreviews.containsKey(actual.id) }
            fixture.act {
                assertFalse(it.libraryScanning)
                assertNull(it.info)
                assertTrue(it.recentDocuments.isEmpty())
                it.setLibraryActive(false)
            }
            assertEquals("Aliases reuse the MIME-qualified original beside the obsolete bounded cache entry",
                2, folder.listFiles().orEmpty().count { it.extension == "png" })
            assertEquals("Covers do not retain imported copies", 0, fixture.sources.status(fixture.store.copySettings()).count)
        }
    }

    @Test fun displayedPageDoesNotOverwriteDurableScrollAnchorOrPerHashRoughness() = ReaderPersistenceTest().Fixture().use { fixture ->
        val file = File(fixture.context.root, "anchor.pdf").also { writeReaderPdf(it, pages = 4) }
        val alias = File(fixture.context.root, "renamed.pdf").also { file.copyTo(it) }
        fixture.launch(width = 0)
        fixture.act { it.open(Uri.fromFile(file)) }
        fixture.await { it.info != null && !it.loading }
        var hash = ""
        val appearance = ThemeSettings(ReadingMode.SEPIA, paperRoughness = 67)
        fixture.act {
            hash = requireNotNull(it.documentHash)
            it.updatePagePosition(1, .875f)
            it.updateCurrentPage(2)
            it.setAppearance(appearance)
            it.flushDocumentState()
            assertEquals(2, it.page)
            assertEquals(1, it.anchorPage)
        }
        fixture.stateBarrier()
        val saved = requireNotNull(fixture.store.get(hash)).state
        assertEquals(1, saved.page)
        assertEquals(.875f, saved.pageFraction, 0f)
        assertEquals(appearance, saved.appearance)
        fixture.launch(width = 0)
        fixture.act { it.open(Uri.fromFile(alias)) }
        fixture.await { it.documentHash == hash && !it.loading }
        fixture.act {
            assertEquals(1, it.anchorPage)
            assertEquals(.875f, it.pageFraction, 0f)
            assertEquals(appearance, it.settings)
        }
    }

    @Test fun temporarySearchDoesNotReplaceTheExplicitReadingView() = ReaderPersistenceTest().Fixture().use { fixture ->
        val file = File(fixture.context.root, "choice.html").apply {
            writeText("<!doctype html><html><body>" + "<p>AllDoc reading choice</p>\n".repeat(30) + "</body></html>")
        }
        fixture.launch(width = 0)
        fixture.act { it.open(Uri.fromFile(file)) }
        fixture.await { it.info != null && !it.loading }
        var hash = ""
        fixture.act {
            hash = requireNotNull(it.documentHash)
            it.choosePageView(true)
            it.setTextMode(true) // Search temporarily extracts text while Present remains preferred.
            it.updateTextPosition(TextPosition(8, .25f))
            it.flushDocumentState()
        }
        fixture.stateBarrier()
        assertEquals(DocumentViewMode.PAGES, fixture.store.get(hash)!!.state.mode)
        fixture.launch(width = 0)
        fixture.act { it.open(Uri.fromFile(file)) }
        fixture.await { it.documentHash == hash && !it.loading }
        fixture.act {
            assertFalse(it.textMode)
            assertTrue(it.horizontalPages)
            assertEquals(TextPosition(8, .25f), it.textPosition)
            it.chooseTextView()
        }
        fixture.launch(width = 0)
        fixture.act { it.open(Uri.fromFile(file)) }
        fixture.await { it.documentHash == hash && !it.loading && it.text != null && !it.textLoading }
        fixture.act {
            assertTrue("Explicit Text is restored", it.textMode)
            it.setTextMode(false); it.setTextMode(true) // A temporary transition cannot erase Text preference.
            it.flushDocumentState()
        }
        fixture.stateBarrier()
        assertEquals(DocumentViewMode.TEXT, fixture.store.get(hash)!!.state.mode)
        fixture.act { it.choosePageView(false) }
        fixture.stateBarrier()
        assertEquals(DocumentViewMode.PAGES, fixture.store.get(hash)!!.state.mode)
        assertFalse(fixture.store.get(hash)!!.state.horizontalPages)
    }
}
