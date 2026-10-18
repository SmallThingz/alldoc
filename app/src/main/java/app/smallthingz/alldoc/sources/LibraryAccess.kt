package app.smallthingz.alldoc.sources

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException

internal object LibraryAccess {
    fun hasAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30)
        Environment.isExternalStorageManager()
    else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** The user grants access in Android Settings; a failed per-app route can use the system list. */
    fun openSettings(packageName: String, allFiles: Boolean, launch: (Intent) -> Unit): Boolean {
        val application = Uri.fromParts("package", packageName, null)
        val intents = if (allFiles) listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, application),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        else listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, application))
        for (intent in intents) try { launch(intent); return true }
            catch (_: ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        return false
    }

    /** Shared volumes only. Canonicalization and filesystem checks belong on the scan worker. */
    @WorkerThread
    fun roots(context: Context): List<File> {
        if (!hasAccess(context)) return emptyList()
        val candidates = if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
                .filter { mounted(it.state) }.mapNotNull { it.directory }
        } else {
            @Suppress("DEPRECATION")
            if (mounted(Environment.getExternalStorageState())) listOf(Environment.getExternalStorageDirectory()) else emptyList()
        }
        return readableRoots(candidates)
    }

    internal fun readableRoots(candidates: Iterable<File>): List<File> = candidates.mapNotNull { root ->
        try { root.canonicalFile.takeIf { it.isDirectory && it.canRead() } }
        catch (_: IOException) { null }
        catch (_: SecurityException) { null }
    }.distinctBy { it.path }

    private fun mounted(state: String) = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
}
