package app.smallthingz.alldoc.ui

import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException

/** A local-only descriptor hook acknowledges a fully flushed, small payload without a scheduling race. */
internal class ReportedDestinationFailure(private val complete: (ByteArray) -> Boolean) : Closeable {
    private val pipe = ParcelFileDescriptor.createReliablePipe()
    @Volatile var checks = 0; private set
    @Volatile var completePayload = false; private set
    val output = object : ParcelFileDescriptor(pipe[1]) {
        override fun checkError() {
            check(Looper.myLooper() != Looper.getMainLooper())
            checks++
            // Both fixtures fit below pipe capacity. Nonblocking drain stops at the already-written
            // payload, not EOF: the producer still owns its write end while checking the peer.
            val fd = pipe[0].fileDescriptor
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = try { Os.read(fd, buffer, 0, buffer.size) }
                catch (error: ErrnoException) {
                    if (error.errno == OsConstants.EAGAIN) break
                    if (error.errno == OsConstants.EINTR) continue
                    throw IOException("Fixture output could not be consumed", error)
                }
                if (count <= 0) break
                bytes.write(buffer, 0, count)
                if (bytes.size() > 4096) throw IOException("Fixture output exceeds its small-pipe bound")
            }
            completePayload = complete(bytes.toByteArray())
            if (!completePayload) throw IOException("Destination status checked before the complete payload was flushed")
            pipe[0].closeWithError(MESSAGE)
            super.checkError()
        }
    }
    override fun close() { try { output.close() } finally { pipe[0].close() } }
    companion object { const val MESSAGE = "Synthetic destination storage failure" }
}
