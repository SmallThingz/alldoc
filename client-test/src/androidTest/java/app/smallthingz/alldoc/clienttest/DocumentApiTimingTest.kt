package app.smallthingz.alldoc.clienttest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.api.AllDocClient
import app.smallthingz.alldoc.api.IAllDocCallback
import app.smallthingz.alldoc.api.IAllDocService
import app.smallthingz.alldoc.api.IAllDocSession
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Opt in with -e profile true, after correctness checks and a quiet-device/host window. */
@RunWith(AndroidJUnit4::class)
class DocumentApiTimingTest {
    @Test fun profileBinderAndDocumentWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("profile") == "true")
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "ipc-timing.md")
        source.writeText("AllDoc document API timing fixture.\n".repeat(128))
        var service: IAllDocService? = null
        var document: IAllDocSession? = null
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IAllDocService.Stub.asInterface(binder); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        var bound = false
        try {
            val bindStarted = SystemClock.elapsedRealtimeNanos()
            bound = context.bindService(Intent().setComponent(ComponentName(AllDocClient.PACKAGE, AllDocClient.SERVICE)),
                connection, Context.BIND_AUTO_CREATE)
            check(bound && connected.await(10, TimeUnit.SECONDS))
            val bindMs = (SystemClock.elapsedRealtimeNanos() - bindStarted) / 1e6
            val ready = CountDownLatch(1)
            var state = Bundle()
            val callback = object : IAllDocCallback.Stub() {
                override fun onState(value: Bundle) { state = value; ready.countDown() }
            }
            val openStarted = SystemClock.elapsedRealtimeNanos()
            document = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                checkNotNull(service).open(it, "timing.md", "text/markdown", "", callback)
            }
            check(ready.await(20, TimeUnit.SECONDS) && state.getString("state") == "ready")
            val readyMs = (SystemClock.elapsedRealtimeNanos() - openStarted) / 1e6
            val session = checkNotNull(document)
            repeat(10) { check(session.status().getString("state") == "ready") }
            val statuses = samples(100) { check(session.status().getString("state") == "ready") }
            var id = 0L
            val expectedBytes = source.length().toInt() - 1
            fun text() {
                val descriptor = session.read(++id, "text", 0, 480, "original", true)
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                check(bytes.size == expectedBytes)
            }
            repeat(3) { text() }
            val texts = samples(31) { text() }
            val report = JSONObject()
                .put("device", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
                .put("sourceBytes", source.length())
                .put("coldProcessRequested", args.getString("coldStart") == "true")
                .put("bindCount", 1).put("bindMs", bindMs)
                .put("firstDocumentReadyCount", 1).put("firstDocumentReadyMs", readyMs)
                .put("warmStatusBinder", stats(statuses)).put("warmTextStream", stats(texts))
                .put("notes", "Ready includes snapshot, text parsing and layout. Status is a Binder round trip; text includes export and pipe transfer. No page rendering is measured.")
            Log.i("AllDocTiming", report.toString())
            instrumentation.sendStatus(0, Bundle().apply { putString("alldoc_timing", report.toString()) })
        } finally {
            document?.close()
            if (bound) context.unbindService(connection)
            source.delete()
        }
    }
    private fun samples(count: Int, operation: () -> Unit) = LongArray(count) {
        val start = SystemClock.elapsedRealtimeNanos()
        operation()
        SystemClock.elapsedRealtimeNanos() - start
    }
    private fun stats(values: LongArray): JSONObject {
        values.sort()
        return JSONObject().put("count", values.size)
            .put("medianMs", values[values.size / 2] / 1e6)
            .put("p95Ms", values[ceil(values.size * 0.95).toInt() - 1] / 1e6)
    }
}
