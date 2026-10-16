package app.smallthingz.alldoc.sources

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import app.smallthingz.alldoc.core.DocumentProbe
import app.smallthingz.alldoc.engine.DocumentEngine
import app.smallthingz.alldoc.state.LibraryStamp
import android.system.StructStat
import java.nio.file.Files
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Descriptor-only hashing shares admission limits; it never creates a retained payload. */
internal object LibraryInput {
    /** Only private-cache ENOENT proves absence; inaccessible/provider locations stay indexed. */
    fun isMissingPrivateFile(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        return try {
            val cache = context.cacheDir.canonicalFile
            val file = File(uri.path ?: return false).canonicalFile
            if (file == cache || !file.toPath().startsWith(cache.toPath()) ||
                !Os.access(cache.path, OsConstants.R_OK or OsConstants.X_OK)) return false
            try { Os.stat(file.path); false }
            catch (failure: ErrnoException) { failure.errno == OsConstants.ENOENT }
        } catch (_: IOException) { false }
        catch (_: SecurityException) { false }
        catch (_: ErrnoException) { false }
    }

    fun open(context: Context, uri: Uri, signal: CancellationSignal): ParcelFileDescriptor {
        signal.throwIfCanceled()
        return when (uri.scheme) {
            "file" -> try {
                val descriptor = Os.open(requireNotNull(uri.path), OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or
                    OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
                try {
                    if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) throw IOException("Choose a regular document file.")
                    signal.throwIfCanceled()
                    ParcelFileDescriptor.dup(descriptor)
                } finally { try { Os.close(descriptor) } catch (_: ErrnoException) { } }
            } catch (failure: ErrnoException) { throw IOException("The document is unavailable.", failure) }
            "content" -> context.contentResolver.openFileDescriptor(uri, "r", signal)
                ?: throw IOException("The source is unavailable.")
            else -> throw IOException("Choose a local document.")
        }
    }

    /** At most 8 KiB, or 64 KiB for ZIP metadata; a stalled pipe cannot hold discovery indefinitely. */
    fun mime(context: Context, uri: Uri, signal: CancellationSignal): DocumentProbe.Identification = open(context, uri, signal).use { fd ->
        val seekable = try { Os.pread(fd.fileDescriptor, ByteArray(1), 0, 1, 0); true } catch (_: ErrnoException) { false }
        val flags = if (seekable) null else Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0)
        if (flags != null) Os.fcntlInt(fd.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        val deadline = SystemClock.elapsedRealtime() + 2_000
        val poll = arrayOf(StructPollfd().apply { this.fd = fd.fileDescriptor; events = OsConstants.POLLIN.toShort() })
        var position = 0L
        fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            while (true) {
                signal.throwIfCanceled()
                if (SystemClock.elapsedRealtime() > deadline) throw IOException("Document identification timed out.")
                val size = try {
                    if (seekable) Os.pread(fd.fileDescriptor, bytes, offset, count, position)
                    else Os.read(fd.fileDescriptor, bytes, offset, count)
                } catch (failure: ErrnoException) {
                    if (failure.errno == OsConstants.EINTR) continue
                    if (seekable || failure.errno != OsConstants.EAGAIN) throw IOException("Cannot identify this document.", failure)
                    try {
                        Os.poll(poll, 100)
                        if (poll[0].revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0)
                            throw IOException("Cannot read this document.")
                    } catch (interrupted: ErrnoException) {
                        if (interrupted.errno != OsConstants.EINTR) throw IOException("Cannot wait for this document.", interrupted)
                    }
                    continue
                }
                if (size == 0) fd.checkError()
                position += size
                return size
            }
        }
        try { DocumentProbe.read(::read) { signal.isCanceled } }
        finally {
            if (flags != null) {
                val current = Os.fcntlInt(fd.fileDescriptor, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd.fileDescriptor, OsConstants.F_SETFL, (current and OsConstants.O_NONBLOCK.inv()) or (flags and OsConstants.O_NONBLOCK))
            }
        }
    }

    /** Only filesystems with change timestamps support safe unchanged-content reuse. */
    fun stamp(uri: Uri): LibraryStamp? {
        if (uri.scheme != "file") return null
        return try {
            val file = File(uri.path ?: return null)
            val stat = Os.lstat(file.path)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_mtim.tv_nsec == 0L && stat.st_ctim.tv_nsec == 0L) return null
            val trusted = synchronized(fileSystems) {
                fileSystems[stat.st_dev] ?: (Files.getFileStore(file.toPath()).type() in
                    setOf("ext4", "f2fs", "xfs", "btrfs", "tmpfs")).also {
                    if (fileSystems.size >= 64) fileSystems.remove(fileSystems.keys.first())
                    fileSystems[stat.st_dev] = it
                }
            }
            if (trusted) stat.stamp() else null
        } catch (_: Exception) { null }
    }
    private val fileSystems = LinkedHashMap<Long, Boolean>()
    private fun StructStat.stamp() = LibraryStamp(st_dev, st_ino, st_size,
        st_mtim.tv_sec, st_mtim.tv_nsec, st_ctim.tv_sec, st_ctim.tv_nsec)

    data class Fingerprint(val hash: String, val detected: DocumentProbe.Identification?)

    fun hash(context: Context, uri: Uri, signal: CancellationSignal, expectedSize: Long? = null,
        expectedStamp: LibraryStamp? = null): String = readHash(context, uri, signal, expectedSize, expectedStamp, null).hash

    /** Final provider classification uses the same bytes as its digest, with no retained copy. */
    fun fingerprint(context: Context, uri: Uri, signal: CancellationSignal, expectedSize: Long?): Fingerprint =
        readHash(context, uri, signal, expectedSize, null, ByteArray(64 * 1024))

    private fun readHash(context: Context, uri: Uri, signal: CancellationSignal, expectedSize: Long?,
        expectedStamp: LibraryStamp?, prefix: ByteArray?): Fingerprint = open(context, uri, signal).use { fd ->
        val size = fd.statSize
        if (expectedSize != null && size >= 0 && size != expectedSize) throw IOException("The document has changed.")
        val before = if (uri.scheme == "file") Os.fstat(fd.fileDescriptor).stamp() else null
        if (expectedStamp != null && before != expectedStamp) throw IOException("The document has changed.")
        val seekable = try { Os.pread(fd.fileDescriptor, ByteArray(1), 0, 1, 0); true } catch (_: ErrnoException) { false }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = DocumentEngine.copyInput(fd, null, DocumentEngine.PDF_INPUT_LIMIT, seekable, signal, digest, prefix = prefix)
        if (expectedSize != null && bytes != expectedSize || before != null &&
            (before != Os.fstat(fd.fileDescriptor).stamp() || before != Os.lstat(requireNotNull(uri.path)).stamp()))
            throw IOException("The document has changed.")
        val detected = prefix?.let {
            DocumentProbe.identify(if (bytes < it.size) it.copyOf(bytes.toInt()) else it,
                truncated = bytes > it.size, cancel = { signal.isCanceled })
        }
        Fingerprint(digest.digest().hex(), detected)
    }
}
