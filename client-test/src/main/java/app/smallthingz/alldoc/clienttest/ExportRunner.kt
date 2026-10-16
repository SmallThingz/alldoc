package app.smallthingz.alldoc.clienttest

import android.app.Instrumentation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import app.smallthingz.alldoc.api.AllDocClient
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Development CLI bridge: reads only its own staged file, never a caller-selected device path. */
class ExportRunner : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle) { super.onCreate(arguments); args = arguments; start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val token = requireNotNull(args.getString("token")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
            val name = String(Base64.decode(args.getString("name64"), Base64.NO_WRAP), Charsets.UTF_8)
            require(name.length <= 512)
            val directory = File(targetContext.filesDir, "agent-$token")
            val source = File(directory, "input")
            require(source.isFile && source.length() <= 128L * 1024 * 1024)
            val connected = CountDownLatch(1)
            val ready = CountDownLatch(1)
            var state = Bundle()
            AllDocClient.connect(targetContext, Executor { it.run() }, onConnected = { connected.countDown() }).use { client ->
                check(connected.await(10, TimeUnit.SECONDS)) { "AllDoc did not connect" }
                client.open(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY), name,
                    onState = { _, value -> state = value; if (value.getString("state") != "opening") ready.countDown() }).use { document ->
                    check(ready.await(65, TimeUnit.SECONDS)) { "AllDoc did not open the document" }
                    check(state.getString("state") == "ready") { state.getString("error") ?: "Document failed" }
                    document.read(args.getString("format", "text"), args.getString("page", "0").toInt(),
                        args.getString("width", "1200").toInt(), args.getString("theme", "original")).use { output ->
                        output.inputStream().use { input -> File(directory, "output").outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            result.putString("result", "ok")
            finish(0, result)
        } catch (error: Exception) {
            result.putString("error", error.message ?: error.javaClass.simpleName)
            finish(1, result)
        }
    }
}
