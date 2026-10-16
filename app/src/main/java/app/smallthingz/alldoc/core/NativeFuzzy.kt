package app.smallthingz.alldoc.core

import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Nucleo matching in bounded batches; Kotlin never retains a second full library index. */
internal object NativeFuzzy {
    fun matches(query: String, names: List<String>, checkCancelled: () -> Unit): BooleanArray {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(query.length <= 128 && names.size <= 128)
        checkCancelled()
        check(NativeDocuments.isAvailable()) { "Library search is unavailable." }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { wire ->
            wire.writeShort(names.size)
            for (name in names) {
                require(name.length <= 256)
                val encoded = name.toByteArray(Charsets.UTF_8)
                wire.writeShort(encoded.size); wire.write(encoded)
            }
        }
        val result = matchesNative(bytes.toByteArray(), query, NativeDocuments.Cancellation {
            checkCancelled(); Thread.currentThread().isInterrupted
        })
        checkCancelled()
        check(result != null && result.size == names.size && result.all { it == 0.toByte() || it == 1.toByte() }) {
            "Library search failed."
        }
        return BooleanArray(names.size) { result[it] != 0.toByte() }
    }
    @JvmStatic private external fun matchesNative(input: ByteArray, query: String,
        cancellation: NativeDocuments.Cancellation): ByteArray?
}
