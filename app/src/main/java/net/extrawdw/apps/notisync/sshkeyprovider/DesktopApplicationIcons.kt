package net.extrawdw.apps.notisync.sshkeyprovider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.extrawdw.apps.notisync.appicon.MAX_ICON_SOURCE_BYTES
import net.extrawdw.apps.notisync.ui.icons.material.outlined.computer as ComputerIcon

/** The same ID-based image source and fallback serve registry rows, drafts, and approval cards. */
@Composable
internal fun DesktopApplicationIcon(iconData: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, iconData) {
        value = null
        value = withContext(Dispatchers.Default) {
            iconData?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Box(contentAlignment = Alignment.Center) {
                Icon(ComputerIcon, contentDescription = null, modifier = Modifier.fillMaxSize().padding(12.dp))
            }
        }
    }
}

/** Drafts stay out of saved-instance-state Bundles; only their random file token is saveable. */
internal object DesktopApplicationIconDrafts {
    private const val MAX_EDGE = 512

    suspend fun import(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val source = requireNotNull(context.contentResolver.openInputStream(uri)).use {
            it.readNBytes(MAX_ICON_SOURCE_BYTES + 1).also { bytes ->
                require(bytes.isNotEmpty() && bytes.size <= MAX_ICON_SOURCE_BYTES)
            }
        }
        import(context, source)
    }

    suspend fun import(context: Context, source: ByteArray): String = withContext(Dispatchers.IO) {
        require(source.isNotEmpty() && source.size <= MAX_ICON_SOURCE_BYTES)
        val icon = normalize(source)
        val directory = directory(context)
        check(directory.isDirectory || directory.mkdirs())
        // Discard abandoned drafts without tying cleanup to composition disposal (e.g. rotation).
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val token = UUID.randomUUID().toString()
        val file = file(context, token)
        try {
            file.writeBytes(icon)
            token
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    internal fun normalize(source: ByteArray): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val size = info.size
            val ratio = minOf(1.0, MAX_EDGE.toDouble() / maxOf(size.width, size.height))
            decoder.setTargetSize(maxOf(1, (size.width * ratio).toInt()), maxOf(1, (size.height * ratio).toInt()))
        }
        return try {
            ByteArrayOutputStream().use { output ->
                // For lossless WebP, quality controls compression effort, not pixel fidelity.
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 75, output))
                output.toByteArray().also { require(it.size <= MAX_DESKTOP_APPLICATION_ICON_BYTES) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun read(context: Context, token: String): ByteArray = withContext(Dispatchers.IO) {
        file(context, token).inputStream().use {
            it.readNBytes(MAX_DESKTOP_APPLICATION_ICON_BYTES + 1).also { bytes ->
                require(bytes.isNotEmpty() && bytes.size <= MAX_DESKTOP_APPLICATION_ICON_BYTES)
            }
        }
    }

    fun delete(context: Context, token: String?) {
        if (token != null) file(context, token).delete()
    }

    private fun directory(context: Context) = File(context.cacheDir, "desktop-application-icon-drafts")
    private fun file(context: Context, token: String): File {
        require(UUID.fromString(token).toString() == token)
        return File(directory(context), "$token.webp")
    }
}
