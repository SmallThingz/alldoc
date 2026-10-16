package app.smallthingz.alldoc.ui

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.MainActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderIntentMimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun viewAndSendMimeSurviveRetryAndFreshModelRestoration() {
        val directory = File(context.cacheDir, "intent-mime-" + UUID.randomUUID()).apply { check(mkdirs()) }
        val preferences = context.getSharedPreferences("reader", 0)
        val previousActive = state { preferences.getString("activeDocument", null) }
        val ownedRows = ArrayList<Pair<ByteArray, String>>()
        val jobs = ArrayList<Job>()
        try {
            for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) {
                // A real original file has no provider MIME, no extension and no retained-copy side effect.
                val file = File(directory, UUID.randomUUID().toString())
                val uri = Uri.fromFile(file)
                val bytes = pdf()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                val hash = digest.toHexString()
                ownedRows += digest to uri.toString()
                val intent = Intent(context, MainActivity::class.java).setAction(action)
                if (action == Intent.ACTION_VIEW) intent.setDataAndType(uri, "application/pdf")
                else intent.setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                    model(scenario) { it.viewModelScope.coroutineContext[Job]?.let(jobs::add) }
                    await(scenario, "the missing-file error") {
                        it.source == uri && !it.loading && it.info == null && it.error != null
                    }
                    // The failed open must retain the type supplied by the original Intent.
                    file.writeBytes(bytes)
                    await("Retry accessibility action") { visit { node ->
                        (node.text?.toString() == "Retry" || node.contentDescription?.toString() == "Retry") &&
                            node.isVisibleToUser && click(node)
                    } }
                    awaitPage(scenario, hash)
                    var previous: ReaderModel? = null
                    scenario.onActivity { activity ->
                        previous = ViewModelProvider(activity)[ReaderModel::class.java]
                        // Existing restoration tests use this to exercise Activity saved state with
                        // a fresh owner, without terminating the instrumentation process.
                        activity.viewModelStore.clear()
                    }
                    scenario.recreate()
                    model(scenario) {
                        assertNotSame("Restoration must use a fresh model", previous, it)
                        it.viewModelScope.coroutineContext[Job]?.let(jobs::add)
                    }
                    awaitPage(scenario, hash)
                }
            }
        } finally {
            runBlocking { withTimeout(15_000) { jobs.forEach { it.join() } } }
            worker("CloseWorker").submit {}.get(15, TimeUnit.SECONDS)
            state {
                // The UUID is encoded in the PDF text, and both hash and URI must match.
                // Delete only this test's rows; leave existing documents/settings untouched.
                val database = context.getDatabasePath("documents.db")
                if (database.isFile) SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.compileStatement("DELETE FROM documents WHERE hash=? AND source_uri=?").use { delete ->
                        for ((hash, uri) in ownedRows) {
                            delete.bindBlob(1, hash); delete.bindString(2, uri)
                            delete.executeUpdateDelete(); delete.clearBindings()
                        }
                    }
                }
                check(preferences.edit().putString("activeDocument", previousActive).commit())
            }
            directory.deleteRecursively()
        }
    }

    private fun pdf(): ByteArray {
        val document = android.graphics.pdf.PdfDocument()
        return try {
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 400, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText(UUID.randomUUID().toString(), 10f, 40f,
                android.graphics.Paint().apply { color = Color.BLACK; textSize = 12f })
            document.finishPage(page)
            ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally { document.close() }
    }

    private fun awaitPage(scenario: ActivityScenario<MainActivity>, hash: String) {
        await(scenario, "the PDF page") {
            !it.loading && !it.pageLoading && it.documentHash == hash && it.bitmap != null
        }
        model(scenario) {
            assertNull(it.error)
            assertEquals("pdf", it.info?.format)
            assertEquals("application/pdf", it.info?.mimeType)
            assertEquals(1, it.info?.pageCount)
            assertEquals(4f / 3f, it.bitmap!!.height.toFloat() / it.bitmap!!.width, .01f)
        }
        await("visible rendered page") { visit { node ->
            node.isVisibleToUser && node.contentDescription?.toString() == "Document pages"
        } }
    }

    private fun model(scenario: ActivityScenario<MainActivity>, action: (ReaderModel) -> Unit) =
        scenario.onActivity { action(ViewModelProvider(it)[ReaderModel::class.java]) }

    private fun await(scenario: ActivityScenario<MainActivity>, description: String, test: (ReaderModel) -> Boolean) =
        await(description) { var done = false; model(scenario) { done = test(it) }; done }

    private fun await(description: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(25)
        }
        fail("Timed out waiting for $description")
    }

    @Suppress("DEPRECATION")
    private fun visit(test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { visit(root, 0, intArrayOf(0), test) } finally { root.recycle() }
    }

    @Suppress("DEPRECATION")
    private fun visit(node: AccessibilityNodeInfo, depth: Int, seen: IntArray, test: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 24 || seen[0]++ >= 512 || !node.refresh()) return false
        if (node.packageName?.toString() == context.packageName && test(node)) return true
        for (index in 0 until minOf(node.childCount, 64)) {
            val child = node.getChild(index) ?: continue
            try { if (visit(child, depth + 1, seen, test)) return true } finally { child.recycle() }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun click(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (!node.refresh()) return false
        if (node.isClickable) return node.isEnabled && node.isVisibleToUser &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (depth >= 16) return false
        val parent = node.parent ?: return false
        return try { click(parent, depth + 1) } finally { parent.recycle() }
    }

    private fun worker(name: String): ExecutorService =
        ReaderModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as ExecutorService

    private fun <T> state(action: () -> T): T = worker("StateWorker").submit<T> { action() }.get(15, TimeUnit.SECONDS)
}
