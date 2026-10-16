package org.oacp.android

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * Represents a result to send back to the calling OACP assistant.
 *
 * Use the companion factory methods to create results:
 * ```kotlin
 * // Simple success
 * OacpResult.success("Flashlight turned on")
 *
 * // Success with structured data
 * OacpResult.success("Currently 22°C", mapOf("temperature" to 22, "unit" to "celsius"))
 *
 * // Error
 * OacpResult.error("not_found", "No matching article found", retryable = true)
 *
 * // Progress update
 * OacpResult.accepted("Processing your request...")
 * ```
 *
 * Results are broadcast as `org.oacp.ACTION_RESULT` intents with standard
 * OACP extras for the assistant to consume.
 */
public class OacpResult private constructor(
    private val status: String,
    private val message: String?,
    private val data: Map<String, Any>?,
    private val errorCode: String?,
    private val errorRetryable: Boolean
) {

    /**
     * Sends this result back to the calling assistant via broadcast.
     *
     * @param context        Android context for sending the broadcast.
     * @param requestId      The request ID from the original OACP action intent.
     * @param capabilityId   The capability ID that produced this result.
     * @param targetPackage  Optional package name for explicit broadcast targeting.
     *                       When null, sends an implicit broadcast. On Android 8+,
     *                       the receiving assistant must register a runtime receiver
     *                       for implicit broadcasts to work.
     */
    @JvmOverloads
    public fun send(
        context: Context,
        requestId: String,
        capabilityId: String,
        targetPackage: String? = null
    ) {
        // Capture fields as locals — inside Intent.apply {}, unqualified `data`
        // resolves to Intent.getData() (the URI), shadowing this.data.
        val resultMessage = message
        val resultData = data
        val resultErrorCode = errorCode

        val intent = Intent(Oacp.ACTION_RESULT).apply {
            putExtra(Oacp.EXTRA_REQUEST_ID, requestId)
            putExtra(Oacp.EXTRA_STATUS, status)
            putExtra(Oacp.EXTRA_CAPABILITY_ID, capabilityId)
            putExtra(Oacp.EXTRA_SOURCE_PACKAGE, context.packageName)

            if (targetPackage != null) {
                setPackage(targetPackage)
            }

            if (resultMessage != null) {
                putExtra(Oacp.EXTRA_MESSAGE, resultMessage)
            }

            if (resultData != null) {
                try {
                    putExtra(Oacp.EXTRA_RESULT, JSONObject(resultData as Map<*, *>).toString())
                } catch (e: Exception) {
                    Log.w("OacpResult", "Failed to serialize result data", e)
                }
            }

            if (resultErrorCode != null) {
                putExtra(Oacp.EXTRA_ERROR_CODE, resultErrorCode)
                putExtra(Oacp.EXTRA_ERROR_MESSAGE, resultMessage ?: "")
                putExtra(Oacp.EXTRA_ERROR_RETRYABLE, errorRetryable.toString())
            }
        }

        context.sendBroadcast(intent)
    }

    public companion object {
        /**
         * The action completed successfully.
         *
         * @param message  Human-readable message the assistant may speak aloud.
         * @param data     Optional structured result data (serialized as JSON).
         */
        @JvmStatic
        @JvmOverloads
        public fun success(message: String, data: Map<String, Any>? = null): OacpResult = OacpResult(
            status = Oacp.STATUS_COMPLETED,
            message = message,
            data = data,
            errorCode = null,
            errorRetryable = false
        )

        /**
         * The action was accepted and is being processed.
         * Send this for long-running operations, followed by [success] or [error].
         *
         * @param message  Optional progress message.
         */
        @JvmStatic
        @JvmOverloads
        public fun accepted(message: String? = null): OacpResult = OacpResult(
            status = Oacp.STATUS_ACCEPTED,
            message = message,
            data = null,
            errorCode = null,
            errorRetryable = false
        )

        /**
         * The action started processing.
         *
         * @param message  Optional progress message.
         */
        @JvmStatic
        @JvmOverloads
        public fun started(message: String? = null): OacpResult = OacpResult(
            status = Oacp.STATUS_STARTED,
            message = message,
            data = null,
            errorCode = null,
            errorRetryable = false
        )

        /**
         * The action failed.
         *
         * @param code       Machine-readable error code (e.g. "not_found", "not_authenticated").
         * @param message    Human-readable error description.
         * @param retryable  Whether the assistant should suggest retrying.
         */
        @JvmStatic
        @JvmOverloads
        public fun error(code: String, message: String, retryable: Boolean = false): OacpResult = OacpResult(
            status = Oacp.STATUS_FAILED,
            message = message,
            data = null,
            errorCode = code,
            errorRetryable = retryable
        )

        /**
         * The action was cancelled.
         *
         * @param message  Optional cancellation message.
         */
        @JvmStatic
        @JvmOverloads
        public fun cancelled(message: String? = null): OacpResult = OacpResult(
            status = Oacp.STATUS_CANCELLED,
            message = message,
            data = null,
            errorCode = null,
            errorRetryable = false
        )
    }
}
