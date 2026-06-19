package net.extrawdw.apps.notisync.notif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import java.io.ByteArrayOutputStream

/**
 * Rasterizes notification [Icon]s to compact WEBP bytes for transfer using only platform APIs
 * (Icon.loadDrawable + Canvas + Bitmap.compress) — no image library. Every output is clamped to a
 * max dimension and a byte budget, so a huge or hostile graphic can't blow up memory or the upload;
 * anything that can't be rasterized within budget returns null and the graphic is simply omitted.
 */
class GraphicsExtractor(private val context: Context) {

    /** The large icon (often a contact photo), or null if absent / unrasterizable / over budget. */
    fun largeIcon(sbn: StatusBarNotification): ByteArray? {
        val icon = sbn.notification.getLargeIcon() ?: return null
        return rasterize(icon, MAX_ICON_DIM)
    }

    private fun rasterize(icon: Icon, maxDim: Int): ByteArray? = runCatching {
        val drawable = icon.loadDrawable(context) ?: return null
        encode(drawable.toClampedBitmap(maxDim))
    }.getOrNull()

    private fun Drawable.toClampedBitmap(maxDim: Int): Bitmap {
        if (this is BitmapDrawable) {
            val b = bitmap
            if (b != null && maxOf(b.width, b.height) <= maxDim) return b
        }
        val w = intrinsicWidth.takeIf { it > 0 } ?: maxDim
        val h = intrinsicHeight.takeIf { it > 0 } ?: maxDim
        val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
        val bw = maxOf(1, (w * scale).toInt())
        val bh = maxOf(1, (h * scale).toInt())
        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        setBounds(0, 0, bw, bh)
        draw(canvas)
        return out
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        for (quality in intArrayOf(80, 50, 30)) {
            val bytes = ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, it); it.toByteArray()
            }
            if (bytes.size <= MAX_BYTES) return bytes
        }
        return null // still over budget at lowest quality -> omit the graphic
    }

    private companion object {
        const val MAX_ICON_DIM = 256
        const val MAX_BYTES = 256 * 1024
    }
}
