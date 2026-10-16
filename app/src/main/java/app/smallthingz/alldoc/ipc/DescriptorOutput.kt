package app.smallthingz.alldoc.ipc

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.io.OutputStream

/** Cancellable pipe backpressure. The caller retains descriptor ownership through completion. */
internal class DescriptorOutput(
    descriptor: ParcelFileDescriptor,
    private val signal: CancellationSignal,
    private val limit: Long = 32L * 1024 * 1024,
    timeoutMillis: Long = 60_000
) : OutputStream() {
    private val fd = descriptor.fileDescriptor
    private val poll = StructPollfd().apply { this.fd = this@DescriptorOutput.fd; events = OsConstants.POLLOUT.toShort() }
    private val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    private var written = 0L
    init {
        require(limit > 0 && timeoutMillis in 1..(30 * 60_000L))
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }
    private fun check() {
        signal.throwIfCanceled()
        if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Export timed out")
    }
    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (written + length > limit) throw IOException("Export exceeds its byte limit")
        var position = offset
        while (position < offset + length) {
            check()
            try {
                val count = Os.write(fd, bytes, position, offset + length - position)
                if (count <= 0) throw IOException("Output descriptor is closed")
                position += count; written += count
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR)
                    throw IOException("Could not write document output", error)
                try { Os.poll(arrayOf(poll), 100) }
                catch (interrupted: ErrnoException) {
                    if (interrupted.errno != OsConstants.EINTR)
                        throw IOException("Could not wait for document output", interrupted)
                }
            }
        }
        check()
    }
    override fun close() { /* The owner reports reliable-pipe errors and closes the descriptor. */ }
}
