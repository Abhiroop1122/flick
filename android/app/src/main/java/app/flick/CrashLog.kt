package app.flick

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why Flick last stopped unexpectedly, so the details can be shown the
 * next time Flick is opened (and copied, to report the problem). Stays on the phone.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(app, thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Problems Flick caught and survived: logged only. */
    fun caught(where: String, error: Throwable) = Log.w("Flick", where, error)

    fun pending(ctx: Context): String? = runCatching {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }

    private fun report(ctx: Context, thread: String, error: Throwable): String {
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "Flick $version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Time: $time  Thread: $thread\n\n" +
            error.stackTraceToString().take(12_000)
    }
}
