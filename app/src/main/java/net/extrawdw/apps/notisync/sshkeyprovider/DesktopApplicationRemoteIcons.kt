package net.extrawdw.apps.notisync.sshkeyprovider

import io.ktor.client.HttpClient
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.withTimeout
import net.extrawdw.apps.notisync.appicon.AppStoreIconClient
import net.extrawdw.apps.notisync.appicon.IconFetchResult
import net.extrawdw.apps.notisync.appicon.downloadIconBytes

internal class DesktopApplicationIconNotFoundException : IOException()

/** Explicit imports only. A saved icon is a local copy, with no dependency on its source URL. */
internal class DesktopApplicationRemoteIcons(
    private val client: HttpClient = AppStoreIconClient.defaultClient(),
) : Closeable {
    private val appStore = AppStoreIconClient(client)

    suspend fun load(source: DesktopApplicationIconSource): ByteArray = withTimeout(30_000) {
        val bytes = when (source) {
            is DesktopApplicationIconSource.AppStore -> appStore.fetchAppId(source.appId, 512, source.country).iconBytes()
            is DesktopApplicationIconSource.BundleId -> appStore.fetch(source.bundleId, 512).iconBytes()
            is DesktopApplicationIconSource.ImageUrl -> client.downloadIconBytes(source.url)
        }
        bytes
    }

    override fun close() { client.close() }

    private fun IconFetchResult.iconBytes(): ByteArray = when (this) {
        is IconFetchResult.Found -> bytes
        IconFetchResult.NotFound -> throw DesktopApplicationIconNotFoundException()
        IconFetchResult.TransientError -> throw IOException("App Store icon lookup failed")
    }
}
