package app.smallthingz.alldoc.state

import android.content.Context
import android.os.Looper
import java.io.IOException

/** App chrome is independent of each document's saved color treatment. */
enum class AppTheme {
    SYSTEM, LIGHT, DARK, AMOLED;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK, AMOLED -> true
    }
}

/** Called by the state worker; constructing this object performs no disk access. */
class AppAppearanceStore(context: Context) {
    private val context = context.applicationContext

    private fun preferences(): android.content.SharedPreferences {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Appearance storage requires a worker" }
        return context.getSharedPreferences("app-appearance", Context.MODE_PRIVATE)
    }

    fun read(): AppTheme {
        val value = preferences().getString("theme", null)
        return AppTheme.entries.firstOrNull { it.name == value } ?: AppTheme.SYSTEM
    }

    fun write(theme: AppTheme) {
        if (!preferences().edit().putString("theme", theme.name).commit())
            throw IOException("Could not save app appearance")
    }
}
