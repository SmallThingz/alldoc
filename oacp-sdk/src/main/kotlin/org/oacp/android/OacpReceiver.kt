package org.oacp.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base BroadcastReceiver for handling OACP action intents.
 *
 * Extend this class and implement [onAction] to handle voice commands.
 * The receiver automatically extracts parameters, the request ID,
 * and sends async results back to the calling assistant.
 *
 * This receiver uses [goAsync] internally, so [onAction] may safely
 * perform blocking I/O (database queries, network calls) without
 * risking an ANR. Work runs on a background thread.
 *
 * Register in AndroidManifest.xml with intent filters for each capability:
 * ```xml
 * <receiver android:name=".MyOacpReceiver" android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.example.myapp.ACTION_DO_THING" />
 *     </intent-filter>
 * </receiver>
 * ```
 *
 * Example implementation:
 * ```kotlin
 * class MyOacpReceiver : OacpReceiver() {
 *     override fun onAction(
 *         context: Context,
 *         action: String,
 *         params: OacpParams,
 *         requestId: String?
 *     ): OacpResult? {
 *         return when {
 *             action.endsWith(".ACTION_PLAY") -> {
 *                 startPlayback()
 *                 OacpResult.success("Playing music")
 *             }
 *             action.endsWith(".ACTION_PAUSE") -> {
 *                 pausePlayback()
 *                 null // fire-and-forget, no result
 *             }
 *             else -> null
 *         }
 *     }
 * }
 * ```
 *
 * **Build variant safety:** Match actions with `endsWith()` so the same
 * receiver works for `com.example.app.ACTION_X` and `com.example.app.debug.ACTION_X`.
 */
public abstract class OacpReceiver : BroadcastReceiver() {

    /**
     * Called when an OACP action intent is received.
     *
     * This method runs on a background thread (via [goAsync]), so it is
     * safe to perform blocking I/O such as database queries or short network calls.
     * Work must complete within ~10 seconds (the system's `goAsync` timeout).
     * For long-running operations, return [OacpResult.accepted] immediately and
     * complete the work via a `Service` or `WorkManager`.
     *
     * @param context  The receiver context.
     * @param action   The full intent action string (e.g. "com.example.app.ACTION_PLAY").
     *                 Use `action.endsWith(".ACTION_PLAY")` for variant-safe matching.
     * @param params   Type-safe accessor for intent extras (parameters from the assistant).
     * @param requestId  The OACP request ID for correlating async results, or null if
     *                   the assistant did not attach one.
     * @return An [OacpResult] to send back to the assistant, or null for fire-and-forget.
     */
    public abstract fun onAction(
        context: Context,
        action: String,
        params: OacpParams,
        requestId: String?
    ): OacpResult?

    final override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val requestId = intent.getStringExtra(Oacp.EXTRA_REQUEST_ID)
        val params = OacpParams(intent)

        Log.d(TAG, "OACP action received: $action (requestId=$requestId)")

        val pendingResult = goAsync()

        executor.execute {
            try {
                val result = try {
                    onAction(context, action, params, requestId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling OACP action: $action", e)
                    OacpResult.error(
                        code = Oacp.ERROR_INTERNAL,
                        message = e.message ?: "Unexpected error"
                    )
                }

                if (result != null && requestId != null) {
                    result.send(context, requestId, guessCapabilityId(action))
                } else if (result != null) {
                    Log.d(TAG, "Result dropped: no requestId for action $action")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Extracts a capability ID from an intent action string.
     * E.g. "com.example.app.ACTION_PLAY_MUSIC" → "play_music"
     */
    private fun guessCapabilityId(action: String): String {
        val suffix = action.substringAfterLast(".ACTION_", "")
        return if (suffix.isNotEmpty()) {
            suffix.lowercase()
        } else {
            action.substringAfterLast(".")
        }
    }

    public companion object {
        private const val TAG: String = "OacpReceiver"
        private val threadCount = AtomicInteger(0)
        private val executor = ThreadPoolExecutor(
            0, 4, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()
        ) { runnable ->
            Thread(runnable, "oacp-receiver-${threadCount.getAndIncrement()}")
                .apply { isDaemon = true }
        }
    }
}
