package app.flick

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * Phone → computer clipboard.
 *
 * Android (10+) only lets an app read the clipboard while one of its windows has
 * focus. So Flick reads it at moments it legitimately has focus: when you open
 * Flick, from the optional "Send clipboard" Quick Settings tile, or — with the
 * accessibility helper (ClipWatchService) switched on — right after you tap Copy anywhere.
 */
object ClipShare {
    @Volatile var lastFromComputer: String? = null
    @Volatile var lastSent: String? = null
    @Volatile private var suppressUntil = 0L

    /** Flick itself is putting a computer's text on the clipboard: don't send it back. */
    fun noteFromComputer(text: String) {
        lastFromComputer = text
        suppressUntil = SystemClock.uptimeMillis() + 2_500
    }

    fun suppressed() = SystemClock.uptimeMillis() < suppressUntil

    /** The clipboard's text, or null. Only works while a Flick window has focus. */
    fun read(ctx: Context): String? {
        val clip = runCatching { ctx.getSystemService(ClipboardManager::class.java).primaryClip }.getOrNull() ?: return null
        // Password managers mark secrets as sensitive: never share those.
        if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    /** Copies [text] to every connected computer. [manual] = the person asked, so give feedback. */
    fun send(ctx: Context, text: String?, manual: Boolean) {
        val app = FlickApp.get(ctx)
        val main = Handler(Looper.getMainLooper())
        fun toast(s: String) { if (manual) main.post { Toast.makeText(app, s, Toast.LENGTH_SHORT).show() } }
        if (text.isNullOrBlank()) { toast("Your clipboard is empty (or holds a password, which Flick never shares)."); return }
        if (text.length > 100_000) { toast("That's too long to share."); return }
        if (!manual && (text == lastFromComputer || text == lastSent)) return
        val states = LinkHub.states.value
        val targets = app.store.paired().filter { states[it.id] == LinkState.CONNECTED }
        if (targets.isEmpty()) {
            toast("Not connected to a computer. Make sure it's on the same Wi-Fi with Flick on.")
            return
        }
        lastSent = text
        app.scope.launch {
            val ok = mutableListOf<String>()
            var err: String? = null
            for (k in targets) {
                val auth = app.store.auth(k.id) ?: continue
                val e = app.client.sendClip(k.toReceiver(), auth, text)
                if (e == null) ok += k.name else err = "${k.name}: $e"
            }
            toast(if (ok.isNotEmpty()) "Copied to ${ok.joinToString()}" else "Couldn't copy — ${err ?: "not reachable"}")
        }
    }
}

/**
 * Invisible screen that exists only to have focus for a moment, read the clipboard
 * and send it. Opened by the "Send clipboard" Quick Settings tile, and by the
 * accessibility helper when its 1-pixel window can't get focus.
 */
class ClipReadActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({ if (!done) close() }, 2_000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        ClipShare.send(this, ClipShare.read(this), intent.getBooleanExtra(EXTRA_MANUAL, true))
        close()
    }

    private fun close() {
        done = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_MANUAL = "manual"
    }
}
