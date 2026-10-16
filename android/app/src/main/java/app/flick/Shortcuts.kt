package app.flick

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.flick.core.Receiver

/**
 * Direct Share: after you send to a computer once, it shows up as its own
 * target at the top of the Android share sheet — one tap, like AirDrop.
 */
object Shortcuts {
    const val CATEGORY = "app.flick.category.COMPUTER"
    private const val PREFIX = "pc_"

    fun receiverIdFrom(shortcutId: String?): String? =
        shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun publish(ctx: Context, r: Receiver) {
        runCatching {
            val icon = IconCompat.createWithAdaptiveBitmap(iconBitmap(ctx, r))
            val info = ShortcutInfoCompat.Builder(ctx, PREFIX + r.id)
                .setShortLabel(r.name.take(24))
                .setLongLabel("Send to ${r.name}")
                .setIcon(icon)
                .setIntent(Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_DEFAULT))
                .setLongLived(true)
                .setCategories(setOf(CATEGORY))
                .setPerson(Person.Builder().setName(r.name).setKey(r.id).setBot(true).build())
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(ctx, info)
        }
    }

    fun remove(ctx: Context, id: String) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(ctx, listOf(PREFIX + id)) }
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(ctx, listOf(PREFIX + id)) }
    }

    /** 108×108dp adaptive bitmap: brand gradient with a laptop/desktop glyph in the safe zone. */
    private fun iconBitmap(ctx: Context, r: Receiver): Bitmap {
        val px = (108 * ctx.resources.displayMetrics.density).toInt().coerceIn(108, 432)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, px.toFloat(), px.toFloat(), 0f, 0xFF2F6BFF.toInt(), 0xFF12C2E9.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, px.toFloat(), px.toFloat(), p)
        val glyph = ContextCompat.getDrawable(ctx, if (r.isMac) R.drawable.ic_laptop else R.drawable.ic_desktop)!!.mutate()
        glyph.setTint(0xFFFFFFFF.toInt())
        val g = (px * 0.40f).toInt()
        val o = (px - g) / 2
        glyph.setBounds(o, o, o + g, o + g)
        glyph.draw(c)
        return bmp
    }
}
