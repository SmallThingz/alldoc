package app.smallthingz.alldoc.clienttest

import android.app.Activity
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Tests literal protocol messages from a separate app, not the implementation's SDK constants. */
@RunWith(AndroidJUnit4::class)
class OacpIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val application = "app.smallthingz.alldoc"
    private val extra = "org.oacp.extra."
    private val actions = linkedMapOf("read_document" to "READ_DOCUMENT", "document_structure" to "DOCUMENT_STRUCTURE", "render_page" to "RENDER_PAGE")

    @Suppress("DEPRECATION")
    @Test fun discoveryIsReadOnlyAndAdvertisesResolvableCapabilities() {
        val manifest = Uri.parse("content://$application.oacp/manifest")
        val notes = Uri.parse("content://$application.oacp/context")
        val provider = requireNotNull(context.packageManager.resolveContentProvider(manifest.authority!!, 0))
        assertTrue(provider.exported)
        assertEquals(application, provider.packageName)
        assertNotEquals(context.applicationInfo.uid, provider.applicationInfo.uid)
        assertEquals("application/json", resolver.getType(manifest))
        assertEquals("text/markdown", resolver.getType(notes))
        val source = String(read(manifest), Charsets.UTF_8)
        assertFalse(source.contains("__APPLICATION_ID__"))
        val declared = JSONObject(source)
        assertEquals("0.3", declared.getString("oacpVersion"))
        assertEquals(application, declared.getString("appId"))
        val capabilities = declared.getJSONArray("capabilities")
        assertEquals(actions.keys, (0 until capabilities.length()).map { capabilities.getJSONObject(it).getString("id") }.toSet())
        for (index in 0 until capabilities.length()) {
            val capability = capabilities.getJSONObject(index)
            val invoke = capability.getJSONObject("invoke").getJSONObject("android")
            assertEquals("activity", invoke.getString("type"))
            assertEquals("$application.oacp.ACTION_${actions.getValue(capability.getString("id"))}", invoke.getString("action"))
            val intent = Intent(invoke.getString("action")).setPackage(application)
            assertTrue(context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any {
                it.activityInfo.packageName == application && it.activityInfo.exported && it.activityInfo.enabled
            })
        }
        assertTrue(String(read(notes), Charsets.UTF_8).contains("AllDoc"))
        for (uri in listOf(manifest, notes)) denied { resolver.openFileDescriptor(uri, "w")?.use { } }
    }

    @Test fun textStructureAndPageReturnTargetedMetadataAndReadableOutputs() = fixture { uri, text ->
        for ((capability, action) in actions) {
            val request = request(action, uri).putExtra("width", 320)
                // Authenticated callingPackage must win over this untrusted recipient hint.
                .putExtra("recipient_package", application)
            val result = invoke(request)
            assertEquals(Activity.RESULT_OK, result.reply.code)
            assertEquals("completed", result.data.getStringExtra(extra + "STATUS"))
            assertEquals(capability, result.broadcast.getStringExtra(extra + "CAPABILITY_ID"))
            val metadata = JSONObject(result.broadcast.getStringExtra(extra + "RESULT")!!)
            val output = requireNotNull(result.data.data)
            assertEquals(output.toString(), metadata.getString("uri"))
            assertEquals("$application.oacp.results", output.authority)
            assertTrue(result.data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(output, result.data.clipData!!.getItemAt(0).uri)
            assertEquals(result.data.type, metadata.getString("mimeType"))
            val bytes = read(output)
            assertEquals(bytes.size.toLong(), metadata.getLong("bytes"))
            assertTrue("Markdown output retains continuous-document metadata", metadata.getBoolean("continuous"))
            assertFalse("Document text belongs in the granted stream", metadata.toString().contains(text))
            when (capability) {
                "read_document" -> { assertEquals("text", metadata.getString("format")); assertEquals(text, String(bytes, Charsets.UTF_8)) }
                "document_structure" -> {
                    assertEquals("json", metadata.getString("format"))
                    val document = JSONObject(String(bytes, Charsets.UTF_8))
                    assertTrue(document.getJSONArray("sections").length() > 0)
                    assertTrue(document.toString().contains("OACP fixture"))
                }
                "render_page" -> {
                    assertEquals("png", metadata.getString("format"))
                    assertEquals(0, metadata.getInt("page"))
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try {
                        assertEquals(320, bitmap.width)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        assertTrue("Rendered page must contain fixture text", pixels.any { it != pixels[0] })
                    } finally { bitmap.recycle() }
                }
            }
            val provider = requireNotNull(context.packageManager.resolveContentProvider(output.authority!!, 0))
            assertFalse("Results are private without an explicit grant", provider.exported)
            denied { resolver.openFileDescriptor(output, "w")?.use { } }
        }
    }

    @Test fun numericBoundsAndTypesAreRejectedBeforeReading() = fixture { uri, _ ->
        val invalid = listOf(
            request("RENDER_PAGE", uri).putExtra("width", 63),
            request("RENDER_PAGE", uri).putExtra("width", 2049),
            request("RENDER_PAGE", uri).putExtra("width", 4_294_968_496L),
            request("RENDER_PAGE", uri).putExtra("width", 1200.5),
            request("RENDER_PAGE", uri).putExtra("page", -1),
            request("RENDER_PAGE", uri).putExtra("page", Long.MAX_VALUE),
            request("READ_DOCUMENT", uri).putExtra("password", "x".repeat(257)),
            request("READ_DOCUMENT", uri).putExtra("password", true),
            request("READ_DOCUMENT", uri).putExtra("uri", 123)
        )
        for (request in invalid) {
            val result = invoke(request)
            assertEquals(Activity.RESULT_CANCELED, result.reply.code)
            assertEquals("failed", result.data.getStringExtra(extra + "STATUS"))
            assertEquals("invalid_parameters", result.data.getStringExtra(extra + "ERROR_CODE"))
            assertEquals("invalid_parameters", result.broadcast.getStringExtra(extra + "ERROR_CODE"))
            assertNull(result.data.data)
            assertFalse(result.broadcast.hasExtra(extra + "RESULT"))
        }
        // Exact-name precedence must match the official SDK, regardless of conflicting aliases.
        val good = request("RENDER_PAGE", uri).putExtra("EXTRA_WIDTH", 2049).putExtra("width", 64)
        val result = invoke(good)
        assertEquals(Activity.RESULT_OK, result.reply.code)
        val bytes = read(requireNotNull(result.data.data))
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try { assertEquals(64, bitmap.width) } finally { bitmap.recycle() }
    }

    @Test fun callerAndReaderBothNeedAccessToTheSuppliedSource() = fixture { uri, _ ->
        // The client owns this provider, but deliberately gives AllDoc no read grant.
        val noGrant = request("READ_DOCUMENT", uri).apply { flags = 0; clipData = null }
        val foreign = request("READ_DOCUMENT", Uri.parse("content://$application.oacp.results/results/unowned.txt")).apply {
            flags = 0; clipData = null
        }
        for (request in listOf(noGrant, foreign)) {
            val result = invoke(request)
            assertEquals(Activity.RESULT_CANCELED, result.reply.code)
            assertEquals("failed", result.data.getStringExtra(extra + "STATUS"))
            assertEquals("not_authorized", result.data.getStringExtra(extra + "ERROR_CODE"))
            assertEquals("failed", result.broadcast.getStringExtra(extra + "STATUS"))
            assertEquals("not_authorized", result.broadcast.getStringExtra(extra + "ERROR_CODE"))
            assertNull(result.data.data)
            assertFalse(result.broadcast.hasExtra(extra + "RESULT"))
        }
    }

    @Test fun cancellingAStalledSourceReturnsBeforeTheProviderFinishes() = fixture { uri, text ->
        val stalled = OacpFixtureProvider.Stalled()
        val name = requireNotNull(uri.lastPathSegment)
        check(OacpFixtureProvider.stalls.putIfAbsent(name, stalled) == null)
        try {
            val result = invoke(request("READ_DOCUMENT", uri)) {
                assertTrue("Adapter did not request the fixture stream", stalled.opened.await(10, TimeUnit.SECONDS))
                awaitCancelControl()
                assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
                    .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            }
            // The writer is still open: cancellation cannot rely on the provider reaching EOF.
            assertTrue(stalled.writers.isNotEmpty())
            assertEquals(Activity.RESULT_CANCELED, result.reply.code)
            assertEquals("cancelled", result.data.getStringExtra(extra + "STATUS"))
            assertEquals("cancelled", result.broadcast.getStringExtra(extra + "STATUS"))
            assertNull(result.data.data)
        } finally {
            OacpFixtureProvider.stalls.remove(name, stalled)
            stalled.close()
        }
        val next = invoke(request("READ_DOCUMENT", uri))
        assertEquals(Activity.RESULT_OK, next.reply.code)
        assertEquals(text, String(read(requireNotNull(next.data.data)), Charsets.UTF_8))
    }

    @Suppress("DEPRECATION")
    private fun awaitCancelControl() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        var observed = "No active accessibility window"
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null) try {
                observed = "package=${root.packageName}"
                if (root.packageName?.toString() == application) {
                    var visited = 0
                    val labels = ArrayList<String>()
                    // Compose exposes virtual children but does not implement the provider's text-search method.
                    fun visibleCancel(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): Boolean {
                        if (++visited > 128 || depth > 32) return false
                        val text = node.text?.toString()
                        if (labels.size < 24 && (text != null || node.contentDescription != null)) {
                            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                            labels += "${node.className}: text=${text?.take(80)}, description=${node.contentDescription?.toString()?.take(80)}, visible=${node.isVisibleToUser}, bounds=$bounds"
                        }
                        if (node.isVisibleToUser && text == "Cancel") return true
                        for (index in 0 until node.childCount) {
                            if (visited >= 128) break
                            val child = node.getChild(index) ?: continue
                            try { if (visibleCancel(child, depth + 1)) return true }
                            finally { child.recycle() }
                        }
                        return false
                    }
                    if (visibleCancel(root, 0)) return
                    observed += ", visited=$visited, nodes=${labels.joinToString("; ")}"
                }
            } finally { root.recycle() }
            SystemClock.sleep(25)
        }
        fail("OACP cancellation control did not become visible: $observed")
    }

    private fun request(action: String, uri: Uri) = Intent("$application.oacp.ACTION_$action")
        .setPackage(application).putExtra("mime_type", "text/markdown")
        .putExtra(extra + "REQUEST_ID", UUID.randomUUID().toString())
        .putExtra("uri", uri.toString()).apply {
            clipData = ClipData.newRawUri("Test document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private data class Outcome(val reply: OacpCallerActivity.Reply, val data: Intent, val broadcast: Intent)
    private fun invoke(request: Intent, whileRunning: (() -> Unit)? = null): Outcome {
        val id = requireNotNull(request.getStringExtra(extra + "REQUEST_ID"))
        val events = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(extra + "REQUEST_ID") == id &&
                    intent.getStringExtra(extra + "STATUS") in setOf("completed", "failed", "cancelled")) events.offer(Intent(intent))
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter("org.oacp.ACTION_RESULT"), Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, IntentFilter("org.oacp.ACTION_RESULT"))
        val future = CompletableFuture<OacpCallerActivity.Reply>()
        check(OacpCallerActivity.pending.putIfAbsent(id, future) == null)
        try {
            val launch = Intent(context, OacpCallerActivity::class.java).putExtra("invocation", id)
            return ActivityScenario.launch<OacpCallerActivity>(launch).use { scenario ->
                // Reach RESUMED before launching the child; a stalled child must not
                // block ActivityScenario.launch before the cancellation action runs.
                scenario.onActivity { it.startRequest(request) }
                whileRunning?.invoke()
                val reply = future.get(25, TimeUnit.SECONDS)
                val data = requireNotNull(reply.data) { "Capability returned no protocol result" }
                assertEquals(id, data.getStringExtra(extra + "REQUEST_ID"))
                val event = requireNotNull(events.poll(10, TimeUnit.SECONDS)) { "No correlated terminal OACP result broadcast" }
                assertEquals(context.packageName, event.`package`)
                assertEquals(application, event.getStringExtra(extra + "SOURCE_PACKAGE"))
                assertEquals(data.getStringExtra(extra + "STATUS"), event.getStringExtra(extra + "STATUS"))
                Outcome(reply, data, event)
            }
        } finally {
            OacpCallerActivity.pending.remove(id)
            context.unregisterReceiver(receiver)
        }
    }

    private fun read(uri: Uri): ByteArray = requireNotNull(resolver.openInputStream(uri)).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            assertTrue("Fixture output exceeded its test bound", output.size() + count <= 2 * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    private fun denied(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected access denial, got $failure", failure is SecurityException || failure is IOException)
    }
    private fun fixture(test: (Uri, String) -> Unit) {
        val directory = File(context.cacheDir, "oacp-fixtures").apply { check(mkdirs() || isDirectory) }
        val file = File(directory, UUID.randomUUID().toString() + ".md")
        val text = "OACP fixture ${UUID.randomUUID()}\nहिन्दी · café · 🇦🇺"
        file.writeText(text.replace("\n", "  \n"))
        val uri = Uri.parse("content://${context.packageName}.oacp-fixtures/${file.name}")
        try { test(uri, text) } finally {
            context.revokeUriPermission(application, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete()
        }
    }
}
