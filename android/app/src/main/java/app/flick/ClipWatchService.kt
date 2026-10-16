package app.flick

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Optional helper for automatic phone → computer clipboard.
 *
 * Android doesn't tell apps when the clipboard changes, and blocks reading it in the
 * background. This accessibility service only watches for the moment you copy
 * something (a tap on "Copy", a "Copied" message, Android's clipboard preview),
 * then takes focus for an instant with an invisible 1-pixel window, reads the
 * clipboard and sends it to your connected computers. It doesn't read, keep or
 * send anything else that's on screen.
 *
 * Note: because Flick declares an accessibility service, Google Play Protect blocks
 * installing it from a file in some countries (India included) until "Scan apps with
 * Play Protect" is turned off for the install. See README.
 */
class ClipWatchService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastGrab = 0L
    private val grabSoon = Runnable {
        try {
            grab()
        } catch (t: Throwable) {
            Log.w(TAG, "grab", t)
        }
    }

    private val words: List<String> by lazy {
        val sys = Resources.getSystem()
        (listOf(android.R.string.copy, android.R.string.copyUrl).mapNotNull { runCatching { sys.getString(it) }.getOrNull() } +
            listOf("copy", "copied", "clipboard"))
            .map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
    }

    private fun matches(vararg texts: CharSequence?): Boolean = texts.any { t ->
        val s = t?.toString()?.lowercase() ?: return@any false
        words.any { s.contains(it) }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // Never let anything here take Flick down: Android then marks the helper as "malfunctioning".
        try {
            onEvent(e ?: return)
        } catch (t: Throwable) {
            Log.w(TAG, "event", t)
        }
    }

    private fun onEvent(e: AccessibilityEvent) {
        val app = FlickApp.get(this)
        if (!app.store.sendClipboard.value || ClipShare.suppressed()) return
        if (e.packageName == packageName) return // Flick's own "Copied to …" messages
        val hit = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                matches(e.contentDescription, *e.text.toTypedArray()) || sourceMatches(e)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> // toasts like "Copied to clipboard"
                e.parcelableData !is Notification && matches(*e.text.toTypedArray())
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> // Android 13+ clipboard preview
                e.packageName == "com.android.systemui" && (sourceMatches(e) || matches(e.contentDescription, *e.text.toTypedArray()))
            else -> false
        }
        if (hit) {
            handler.removeCallbacks(grabSoon)
            handler.postDelayed(grabSoon, 300)
        }
    }

    private fun sourceMatches(e: AccessibilityEvent): Boolean {
        val n = runCatching { e.source }.getOrNull() ?: return false
        return try {
            matches(n.text, n.contentDescription, n.viewIdResourceName?.substringAfterLast('/'))
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) runCatching { n.recycle() }
        }
    }

    /** Take focus for an instant with an invisible window, read the clipboard, send it. */
    private fun grab() {
        val now = SystemClock.uptimeMillis()
        if (now - lastGrab < 600) return
        lastGrab = now
        // Only read when there's someone to send to (and it isn't Flick pasting the computer's
        // clipboard), so Android doesn't show "Flick pasted from your clipboard" for nothing.
        if (ClipShare.suppressed() || LinkHub.states.value.values.none { it == LinkState.CONNECTED }) return
        val wm = getSystemService(WindowManager::class.java)
        val view = object : View(this) {
            var done = false
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus || done) return
                done = true
                val text = runCatching { ClipShare.read(context) }.getOrNull()
                // Remove the window only after Android has finished handling the focus change:
                // removing it from inside this callback crashes the app (Android's window code
                // carries on using the window after this returns).
                handler.post {
                    runCatching { wm.removeView(this) }
                    runCatching { ClipShare.send(applicationContext, text, manual = false) }
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (runCatching { wm.addView(view, lp) }.isFailure) {
            readWithActivity()
            return
        }
        handler.postDelayed({
            if (!view.done) { // the overlay didn't get focus on this phone: use the invisible screen instead
                view.done = true
                runCatching { wm.removeView(view) }
                readWithActivity()
            }
        }, 700)
    }

    private fun readWithActivity() {
        runCatching {
            startActivity(Intent(this, ClipReadActivity::class.java)
                .putExtra(ClipReadActivity.EXTRA_MANUAL, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }
    }

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "FlickClip"

        /** Switched on in Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.startsWith("${ctx.packageName}/") }
        }

        fun openAccessibilitySettings(ctx: Context) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        /** Flick's App info page, where "Allow restricted settings" is (⋮ menu). */
        fun openAppInfo(ctx: Context) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
        }
    }
}

/** Quick Settings tile: one tap sends what's on the phone's clipboard to your computer. */
class ClipTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val app = FlickApp.get(this)
        val states = LinkHub.states.value
        val connected = app.store.paired().filter { states[it.id] == LinkState.CONNECTED }
        qsTile?.apply {
            state = if (connected.isNotEmpty()) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            label = "Send clipboard"
            if (Build.VERSION.SDK_INT >= 29) subtitle = connected.firstOrNull()?.name ?: "Not connected"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, ClipReadActivity::class.java)
            .putExtra(ClipReadActivity.EXTRA_MANUAL, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
