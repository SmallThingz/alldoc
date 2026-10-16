package app.smallthingz.alldoc.clienttest

import android.app.Activity
import android.content.Intent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Real Activity caller identity for the separate-UID OACP integration suite. */
class OacpCallerActivity : Activity() {
    @Suppress("DEPRECATION")
    fun startRequest(request: Intent) { startActivityForResult(request, 1) }

    @Deprecated("Activity result bridge for testing the public Android protocol")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) intent.getStringExtra("invocation")?.let { pending[it]?.complete(Reply(resultCode, data)) }
    }

    data class Reply(val code: Int, val data: Intent?)
    companion object {
        val pending = ConcurrentHashMap<String, CompletableFuture<Reply>>()
    }
}
