package org.oacp.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Base Activity for handling foreground OACP actions (camera, QR scanner, etc.).
 *
 * Extend this class and implement [onOacpAction] to handle voice commands
 * that require a visible UI. The activity extracts the request ID and
 * parameters from the launching intent and provides [sendResult] to
 * send results back to the assistant.
 *
 * Register in AndroidManifest.xml:
 * ```xml
 * <activity android:name=".MyCameraActivity" android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.example.app.ACTION_TAKE_PHOTO" />
 *         <category android:name="android.intent.category.DEFAULT" />
 *     </intent-filter>
 * </activity>
 * ```
 *
 * Example implementation:
 * ```kotlin
 * class MyCameraActivity : OacpActivity() {
 *     override fun onOacpAction(action: String, params: OacpParams) {
 *         setContentView(R.layout.camera)
 *         // ... set up camera UI
 *     }
 *
 *     private fun onPhotoTaken(path: String) {
 *         sendResult(OacpResult.success("Photo saved", mapOf("path" to path)))
 *         finish()
 *     }
 * }
 * ```
 */
public abstract class OacpActivity : Activity() {

    /** The OACP request ID from the launching intent, or null. */
    protected var requestId: String? = null
        private set

    /** The resolved capability ID derived from the intent action. */
    protected var capabilityId: String? = null
        private set

    /** Type-safe parameters from the launching intent. */
    protected var params: OacpParams = OacpParams(Intent())
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action ?: return

        requestId = intent.getStringExtra(Oacp.EXTRA_REQUEST_ID)
        capabilityId = guessCapabilityId(action)
        params = OacpParams(intent)

        onOacpAction(action, params)
    }

    /**
     * Called when the activity receives an OACP action intent.
     *
     * Set up your UI here. When work is complete, call [sendResult]
     * and [finish].
     *
     * @param action  The full intent action string.
     * @param params  Type-safe accessor for intent extras.
     */
    protected abstract fun onOacpAction(action: String, params: OacpParams)

    /**
     * Sends a result back to the calling assistant.
     *
     * Does nothing if the launching intent had no request ID.
     *
     * @param result         The result to send.
     * @param targetPackage  Optional explicit target package for the broadcast.
     */
    @JvmOverloads
    protected fun sendResult(result: OacpResult, targetPackage: String? = null) {
        val rid = requestId ?: return
        val cid = capabilityId ?: return
        result.send(this, rid, cid, targetPackage)
    }

    private fun guessCapabilityId(action: String): String {
        val suffix = action.substringAfterLast(".ACTION_", "")
        return if (suffix.isNotEmpty()) {
            suffix.lowercase()
        } else {
            action.substringAfterLast(".")
        }
    }
}
