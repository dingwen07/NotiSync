package net.extrawdw.apps.notisync.notification

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class MessageData(val mimeType: String, val bytes: ByteArray)

/**
 * Rasterizes notification graphics ([Icon]s and big-picture bitmaps) to compact WEBP bytes using
 * only platform APIs (Icon.loadDrawable + Canvas + Bitmap.compress) — no image library. Every output
 * is clamped to a max dimension and byte budget, so a huge or hostile graphic can't blow up memory
 * or the upload; anything that can't be rasterized within budget returns null and is simply omitted.
 */
class GraphicsExtractor(private val context: Context) {

    /** The large icon (often a contact photo), or null if absent / unrasterizable / over budget. */
    fun largeIcon(sbn: StatusBarNotification): ByteArray? =
        sbn.notification.getLargeIcon()?.let { rasterize(it, MAX_ICON_DIM, MAX_ICON_BYTES) }

    /**
     * The source app's launcher icon as WEBP bytes, for delivery as an `APP_ICON` asset so a consumer that
     * doesn't have the app installed can still render the real icon. Null if the package can't be resolved
     * or the icon can't fit the budget. Content is stable across notifications, so the asset layer dedups it
     * to a single upload per app.
     */
    fun appIcon(packageName: String): ByteArray? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        encode(drawable.toBitmap(MAX_ICON_DIM), MAX_ICON_DIM, MAX_ICON_BYTES)
    }.getOrNull()

    /** The big picture (BigPictureStyle image), as a Bitmap (classic) or an Icon (newer). */
    fun bigPicture(sbn: StatusBarNotification): ByteArray? {
        val extras = sbn.notification.extras
        extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
            ?.let { return encode(it, MAX_PICTURE_DIM, MAX_PICTURE_BYTES) }
        extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
            ?.let { return rasterize(it, MAX_PICTURE_DIM, MAX_PICTURE_BYTES) }
        return null
    }

    /** Per-message avatar bytes, aligned to the MessagingStyle messages (null where a message has none). */
    fun messageAvatars(sbn: StatusBarNotification): List<ByteArray?> {
        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        }.getOrNull() ?: return emptyList()
        return messaging.messages.map { msg ->
            msg.person?.icon?.let {
                runCatching {
                    rasterize(
                        it.toIcon(context),
                        MAX_AVATAR_DIM,
                        MAX_ICON_BYTES
                    )
                }.getOrNull()
            }
        }
    }

    /** Per-message inline image bytes, aligned to the MessagingStyle messages. */
    fun messageData(sbn: StatusBarNotification): List<MessageData?> {
        val messaging = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)
        }.getOrNull() ?: return emptyList()
        return messaging.messages.map { msg ->
            val mimeType = msg.dataMimeType?.takeIf(::isSupportedInlineMime) ?: return@map null
            val uri = msg.dataUri ?: return@map null
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBounded(MAX_INLINE_IMAGE_BYTES)?.let { MessageData(mimeType, it) }
                }
            }.getOrNull()
        }
    }

    private fun rasterize(icon: Icon, maxDim: Int, maxBytes: Int): ByteArray? = runCatching {
        val drawable = icon.loadDrawable(context) ?: return null
        encode(drawable.toBitmap(maxDim), maxDim, maxBytes)
    }.getOrNull()

    private fun Drawable.toBitmap(maxDim: Int): Bitmap {
        if (this is BitmapDrawable) {
            val b = bitmap
            if (b != null && maxOf(b.width, b.height) <= maxDim) return b
        }
        val w = intrinsicWidth.takeIf { it > 0 } ?: maxDim
        val h = intrinsicHeight.takeIf { it > 0 } ?: maxDim
        val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
        val out = createBitmap(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        setBounds(0, 0, out.width, out.height)
        draw(Canvas(out))
        return out
    }

    private fun encode(bitmap: Bitmap, maxDim: Int, maxBytes: Int): ByteArray? {
        val scaled = if (maxOf(bitmap.width, bitmap.height) <= maxDim) bitmap else {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            bitmap.scale(
                maxOf(1, (bitmap.width * scale).toInt()),
                maxOf(1, (bitmap.height * scale).toInt())
            )
        }
        for (quality in intArrayOf(80, 50, 30)) {
            val bytes = ByteArrayOutputStream().use {
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, it); it.toByteArray()
            }
            if (bytes.size <= maxBytes) return bytes
        }
        return null // still over budget at lowest quality -> omit the graphic
    }

    private fun isSupportedInlineMime(mimeType: String): Boolean =
        mimeType.startsWith("image/", ignoreCase = true) && ImageDecoder.isMimeTypeSupported(mimeType)

    private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_ICON_DIM = 256
        const val MAX_AVATAR_DIM = 128
        const val MAX_PICTURE_DIM = 1024
        const val MAX_ICON_BYTES = 256 * 1024
        const val MAX_PICTURE_BYTES = 512 * 1024
        const val MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
