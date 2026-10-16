package app.smallthingz.alldoc.clienttest

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.smallthingz.alldoc.api.AllDocClient
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentIntentTest {
    @Suppress("DEPRECATION")
    @Test fun supportedDocumentMimeTypesResolveFromAnotherUid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        assertNotEquals(context.applicationInfo.uid,
            manager.getApplicationInfo(AllDocClient.PACKAGE, 0).uid)
        for (scheme in listOf("content", "file")) for (mime in listOf("application/pdf", "text/markdown", "text/html", "application/epub+zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            // Package-scoped, not component-explicit: Android must still match the VIEW filter.
            // Resolution does not open this synthetic URI or require a fixture provider.
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("$scheme://app.smallthingz.alldoc.clienttest/fixture"), mime)
                .setPackage(AllDocClient.PACKAGE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val activities = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("AllDoc must be available in Open With for $scheme / $mime", activities.any {
                it.activityInfo.packageName == AllDocClient.PACKAGE &&
                    it.activityInfo.name == "${AllDocClient.PACKAGE}.MainActivity" &&
                    it.activityInfo.exported && it.activityInfo.enabled
            })
        }
    }
    @Suppress("DEPRECATION")
    @Test fun removedStandaloneFormatsAreNotAdvertisedAsDocuments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (mime in listOf("text/plain", "image/png", "image/svg+xml", "image/vnd.adobe.photoshop",
            "message/rfc822", "text/calendar", "text/csv", "application/json")) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://app.smallthingz.alldoc.clienttest/fixture"), mime)
                .setPackage(AllDocClient.PACKAGE)
            assertTrue("Removed format is still advertised: $mime", context.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).none { it.activityInfo.packageName == AllDocClient.PACKAGE })
        }
    }

}
