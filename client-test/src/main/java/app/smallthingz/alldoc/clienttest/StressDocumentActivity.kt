package app.smallthingz.alldoc.clienttest

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Shell-addressable development launcher; it can grant only an existing owned stress fixture. */
class StressDocumentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val token = requireNotNull(intent.getStringExtra("token"))
            val fixture = StressFixtureProvider.fixture(this, token)
            val uri = Uri.parse("content://${StressFixtureProvider.AUTHORITY}/$token")
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, fixture.mime)
                .setPackage("app.smallthingz.alldoc").apply {
                    clipData = ClipData.newRawUri(fixture.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
        } finally { finish() }
    }
}
