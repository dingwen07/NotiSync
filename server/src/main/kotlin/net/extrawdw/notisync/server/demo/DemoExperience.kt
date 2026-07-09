package net.extrawdw.notisync.server.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.Base32
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.CardDelivery
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.CipherSuite
import net.extrawdw.notisync.protocol.ClientCard
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ClientKeyEpoch
import net.extrawdw.notisync.protocol.ConversationMessage
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.PrivateAssetRef
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.Purpose
import net.extrawdw.notisync.protocol.SignedBlob
import net.extrawdw.notisync.protocol.SignedType
import net.extrawdw.notisync.protocol.crypto.AssetAead
import net.extrawdw.notisync.protocol.crypto.AssetHash
import net.extrawdw.notisync.protocol.crypto.EnvelopeCrypto
import net.extrawdw.notisync.protocol.crypto.Hpke
import net.extrawdw.notisync.protocol.crypto.IdentityVerifier
import net.extrawdw.notisync.protocol.crypto.KeyEpochs
import net.extrawdw.notisync.protocol.crypto.RecipientKey
import net.extrawdw.notisync.protocol.crypto.SoftwareIdentitySigner
import net.extrawdw.notisync.protocol.crypto.SoftwareOperationalSigner
import net.extrawdw.notisync.server.broker.Broker
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

@Serializable
data class DemoStartRequest(val pairingUrl: String)

@Serializable
data class DemoStartResponse(val pairingUrl: String)

@Serializable
data class DemoScenarioConfig(
    val displayName: String = "Experience Mode",
    val platform: String = "demo-server",
    val defaultIntervalMillis: Long = 3_500,
    val keyLifetimeMillis: Long = 30L * 60 * 1000,
    val assets: List<DemoAssetConfig> = emptyList(),
    val notifications: List<DemoNotificationConfig> = emptyList(),
)

@Serializable
data class DemoAssetConfig(
    val id: String,
    val role: AssetRole,
    val mimeType: String,
    val filename: String? = null,
    val path: String? = null,
)

@Serializable
data class DemoNotificationConfig(
    val packageName: String,
    val appLabel: String,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val style: NotificationStyle = NotificationStyle.DEFAULT,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    val messages: List<DemoConversationMessageConfig> = emptyList(),
    val category: MirrorCategory = MirrorCategory.NONE,
    val importance: MirrorImportance = MirrorImportance.HIGH,
    val groupKey: String? = "notisync.demo",
    val channelId: String? = "demo",
    val channelName: String? = "Experience Mode",
    val iosBundleId: String? = null,
    val originDeviceName: String? = null,
    val originDeviceId: String? = "demo-server",
    val appIconAsset: String? = null,
    val largeIconAsset: String? = null,
    val bigPictureAsset: String? = null,
    val intervalMillis: Long? = null,
)

@Serializable
data class DemoConversationMessageConfig(
    val sender: String? = null,
    val text: String,
    val avatarAsset: String? = null,
)

/**
 * Ephemeral "Experience Mode" peer. A click in the iOS pairing sheet POSTs the iOS pairing link here;
 * the server trusts that card immediately, returns its own normal pairing link, then sends a bounded burst
 * of real E2E notification envelopes through the existing broker fan-out path.
 *
 * Each request creates an isolated synthetic client and coroutine. There is no process-global active
 * recipient, so multiple iOS devices can run demos concurrently without sharing assets or sequence state.
 */
class DemoExperience(
    private val broker: Broker,
    private val scope: CoroutineScope,
    private val configPath: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun start(iosPairingUrl: String): DemoStartResponse {
        val scenario = DemoScenarioLoader.load(configPath)
        val recipient = decodeVerifiedRecipient(iosPairingUrl)
        val peer = DemoPeer.create(scenario.config)
        scope.launch {
            runCatching {
                publishSelf(peer)
                val assets = uploadAssets(peer, scenario)
                sendBurst(peer, recipient, scenario.config, assets)
            }.onFailure {
                log.warn(
                    "demo session failed server={} recipient={}: {}",
                    peer.clientId.shortForm(),
                    recipient.clientId.shortForm(),
                    it.message,
                )
            }
        }
        log.info("started demo experience server={} recipient={}", peer.clientId.shortForm(), recipient.clientId.shortForm())
        return DemoStartResponse(pairingUrl = peer.pairingUrl)
    }

    private suspend fun publishSelf(peer: DemoPeer) {
        check(broker.uploadKeyEpoch(peer.fullKeyEpochBlob)) { "demo key epoch rejected by broker" }
    }

    private suspend fun uploadAssets(peer: DemoPeer, scenario: LoadedDemoScenario): Map<String, PrivateAssetRef> {
        val refs = linkedMapOf<String, PrivateAssetRef>()
        for (asset in scenario.config.assets) {
            val plaintext = scenario.assetBytes(asset)
            val assetHash = AssetHash.of(plaintext)
            val filename = asset.filenameForAssetId()
            val ref = PrivateAssetRef(
                role = asset.role,
                assetHash = assetHash,
                mimeType = asset.mimeType,
                sizeBytes = plaintext.size,
                sourceClientId = peer.clientId,
                assetId = DemoAssetIds.fromFilename(filename),
                assetKey = DemoAssetIds.assetKey(),
            )
            val sealed = AssetAead.seal(ref, plaintext)
            when (broker.storeAsset(peer.clientId, ref.assetId, sealed)) {
                Broker.AssetStoreOutcome.STORED, Broker.AssetStoreOutcome.EXISTS -> refs[asset.id] = ref
                Broker.AssetStoreOutcome.TOO_LARGE -> error("demo asset '${asset.id}' exceeds broker asset limit")
                Broker.AssetStoreOutcome.BAD_ID -> error("demo asset '${asset.id}' generated an invalid asset id")
            }
        }
        return refs
    }

    private suspend fun sendBurst(
        peer: DemoPeer,
        recipient: DemoRecipient,
        config: DemoScenarioConfig,
        assets: Map<String, PrivateAssetRef>,
    ) {
        for ((index, sample) in config.notifications.withIndex()) {
            delay(sample.intervalMillis ?: config.defaultIntervalMillis)
            val now = System.currentTimeMillis()
            val notification = sample.toNotification(peer.clientId, now, index, assets, config)
            val envelope = EnvelopeCrypto.seal(
                signer = peer.operational,
                typ = MessageType.NOTIFICATION,
                bodyPlaintext = ProtocolCodec.encodeToCbor(notification),
                recipients = listOf(RecipientKey(recipient.clientId, recipient.hpkePublicKey, recipient.epoch)),
                messageId = "demo.${UUID.randomUUID()}",
                seq = now + index,
                createdAt = now,
            )
            broker.send(ProtocolCodec.encodeToCbor(envelope), envelope)
        }
    }

    private fun decodeVerifiedRecipient(scanned: String): DemoRecipient {
        val raw = urlDecoder.decode(extractPayload(scanned).trim())
        val delivery = runCatching { ProtocolCodec.decodeFromCbor<CardDelivery>(raw) }.getOrNull()
            ?: CardDelivery(clientId = ClientId("unknown"), card = ProtocolCodec.decodeFromCbor<SignedBlob>(raw))
        val cardBlob = requireNotNull(delivery.card) { "missing client card" }
        require(cardBlob.typ == SignedType.CLIENT_CARD) { "not a client card" }
        val card = cardBlob.decode<ClientCard>()
        require(card.clientId == cardBlob.signerId) { "card signer mismatch" }
        require(
            IdentityVerifier.verifyBound(cardBlob.signerId, card.identityPublicKey, cardBlob.payload, cardBlob.sig)
        ) { "card signature invalid" }

        val epochBlob = requireNotNull(delivery.epochBlob) { "missing key epoch" }
        val epoch = requireNotNull(KeyEpochs.verify(epochBlob, pinnedIdentitySpki = card.identityPublicKey)) {
            "key epoch signature invalid"
        }
        require(epoch.clientId == card.clientId) { "key epoch client mismatch" }
        require(Purpose.HPKE_SEAL in epoch.purposes) { "key epoch cannot receive envelopes" }
        return DemoRecipient(card.clientId, epoch.epoch, epoch.hpkePublicKey)
    }

    private fun extractPayload(scanned: String): String {
        val trimmed = scanned.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        val query = uri?.rawQuery
        if (!query.isNullOrBlank()) {
            for (part in query.split('&')) {
                val bits = part.split('=', limit = 2)
                if (bits.firstOrNull() == "payload" && bits.size == 2) {
                    return URLDecoder.decode(bits[1], Charsets.UTF_8)
                }
            }
        }
        return trimmed
    }

    private data class DemoRecipient(
        val clientId: ClientId,
        val epoch: Int,
        val hpkePublicKey: ByteArray,
    )

    private data class DemoPeer(
        val identity: SoftwareIdentitySigner,
        val operational: SoftwareOperationalSigner,
        val fullKeyEpochBlob: SignedBlob,
        val pairingUrl: String,
    ) {
        val clientId: ClientId = identity.clientId

        companion object {
            fun create(config: DemoScenarioConfig): DemoPeer {
                val identity = SoftwareIdentitySigner.generate()
                val operational = SoftwareOperationalSigner.generate(identity.clientId, signerEpoch = DEMO_EPOCH)
                val hpke = Hpke.generateKeyPair()
                val now = System.currentTimeMillis()
                val cardBlob = signedBlob(
                    identity,
                    SignedType.CLIENT_CARD,
                    ClientCard(
                        clientId = identity.clientId,
                        identityPublicKey = identity.publicKeySpki,
                        displayName = config.displayName,
                        platform = config.platform,
                        capabilities = listOf(Capability.CAPTURE, Capability.PROVIDE_ASSETS),
                        createdAt = now,
                    ),
                )
                val fullKeyEpochBlob = keyEpochBlob(
                    identity = identity,
                    operational = operational,
                    hpkePublicKey = Hpke.rawPublicKey(hpke.publicKeyset),
                    identityPublicKey = identity.publicKeySpki,
                    notAfter = now + config.keyLifetimeMillis,
                )
                val strippedKeyEpochBlob = keyEpochBlob(
                    identity = identity,
                    operational = operational,
                    hpkePublicKey = Hpke.rawPublicKey(hpke.publicKeyset),
                    identityPublicKey = ByteArray(0),
                    notAfter = now + config.keyLifetimeMillis,
                )
                val delivery = CardDelivery(identity.clientId, card = cardBlob, epochBlob = strippedKeyEpochBlob)
                val payload = urlEncoder.encodeToString(ProtocolCodec.encodeToCbor(delivery))
                return DemoPeer(
                    identity = identity,
                    operational = operational,
                    fullKeyEpochBlob = fullKeyEpochBlob,
                    pairingUrl = "https://notisync.apps.extrawdw.net/pair?payload=$payload",
                )
            }

            private fun keyEpochBlob(
                identity: SoftwareIdentitySigner,
                operational: SoftwareOperationalSigner,
                hpkePublicKey: ByteArray,
                identityPublicKey: ByteArray,
                notAfter: Long,
            ): SignedBlob =
                signedBlob(
                    identity,
                    SignedType.KEY_EPOCH,
                    ClientKeyEpoch(
                        suite = CipherSuite.NS2.id,
                        clientId = identity.clientId,
                        identityPublicKey = identityPublicKey,
                        epoch = DEMO_EPOCH,
                        operationalSigningKey = operational.operationalPublicKeySpki,
                        hpkePublicKey = hpkePublicKey,
                        purposes = listOf(Purpose.ENVELOPE_SIGN, Purpose.REQUEST_AUTH, Purpose.HPKE_SEAL),
                        notBefore = 0,
                        notAfter = notAfter,
                        minEpoch = DEMO_EPOCH,
                    ),
                )

            private inline fun <reified T> signedBlob(
                signer: SoftwareIdentitySigner,
                typ: String,
                value: T,
            ): SignedBlob {
                val payload = ProtocolCodec.encodeToCbor(value)
                return SignedBlob(typ = typ, signerId = signer.clientId, payload = payload, sig = signer.sign(payload))
            }
        }
    }

    private companion object {
        const val DEMO_EPOCH = 1

        val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}

private object DemoAssetIds {
    private val fixedAssetKey = sha256("notisync-demo-private-asset-key-v1".toByteArray(Charsets.UTF_8))

    fun assetKey(): ByteArray = fixedAssetKey.copyOf()

    fun fromFilename(filename: String): String =
        Base32.encode(sha256(filename.toByteArray(Charsets.UTF_8)).copyOf(24))

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

private fun DemoAssetConfig.filenameForAssetId(): String =
    filename?.takeIf { it.isNotBlank() }
        ?: path?.let { File(it).name }?.takeIf { it.isNotBlank() }
        ?: id

private data class LoadedDemoScenario(
    val config: DemoScenarioConfig,
    val baseDir: File?,
) {
    fun assetBytes(asset: DemoAssetConfig): ByteArray {
        asset.path?.let { path ->
            val file = File(path).let { if (it.isAbsolute) it else baseDir?.resolve(it.path) }
            if (file?.isFile == true) return file.readBytes()
            val resource = Thread.currentThread().contextClassLoader.getResourceAsStream(path)
            if (resource != null) return resource.use { it.readBytes() }
        }
        error("demo asset '${asset.id}' path could not be resolved")
    }
}

private object DemoScenarioLoader {
    private const val RESOURCE_NAME = "demo-experience.json"

    fun load(configPath: String): LoadedDemoScenario {
        if (configPath.isNotBlank()) {
            val file = File(configPath)
            require(file.isFile) { "demo config file does not exist: ${file.path}" }
            return LoadedDemoScenario(
                ProtocolCodec.decodeFromJson<DemoScenarioConfig>(file.readText()).normalized(),
                file.absoluteFile.parentFile,
            )
        }
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(RESOURCE_NAME)
        if (stream != null) {
            val text = stream.bufferedReader().use { it.readText() }
            return LoadedDemoScenario(ProtocolCodec.decodeFromJson<DemoScenarioConfig>(text).normalized(), null)
        }
        return LoadedDemoScenario(defaultConfig(), null)
    }

    private fun DemoScenarioConfig.normalized(): DemoScenarioConfig =
        if (notifications.isNotEmpty()) this else defaultConfig()

    private fun defaultConfig(): DemoScenarioConfig = DemoScenarioConfig(
        assets = listOf(
            DemoAssetConfig("messages-icon", AssetRole.APP_ICON, "image/png", filename = "messages-icon.png", path = "demo-assets/messages-icon.png"),
            DemoAssetConfig("calendar-icon", AssetRole.APP_ICON, "image/png", filename = "calendar-icon.png", path = "demo-assets/calendar-icon.png"),
            DemoAssetConfig("mail-icon", AssetRole.APP_ICON, "image/png", filename = "mail-icon.png", path = "demo-assets/mail-icon.png"),
            DemoAssetConfig("delivery-icon", AssetRole.APP_ICON, "image/png", filename = "delivery-icon.png", path = "demo-assets/delivery-icon.png"),
            DemoAssetConfig("delivery-photo", AssetRole.BIG_PICTURE, "image/jpeg", filename = "delivery-photo.jpg", path = "demo-assets/delivery-photo.jpg"),
            DemoAssetConfig("maya-avatar", AssetRole.AVATAR, "image/png", filename = "maya-avatar.png", path = "demo-assets/maya-avatar.png"),
            DemoAssetConfig("riley-avatar", AssetRole.AVATAR, "image/png", filename = "riley-avatar.png", path = "demo-assets/riley-avatar.png"),
        ),
        notifications = listOf(
            DemoNotificationConfig(
                packageName = "demo.messages",
                appLabel = "Messages",
                title = "Weekend plan",
                text = "Are we still on for coffee?",
                style = NotificationStyle.MESSAGING,
                conversationTitle = "Weekend plan",
                isGroupConversation = true,
                messages = listOf(
                    DemoConversationMessageConfig("Maya", "Are we still on for coffee?", "maya-avatar"),
                    DemoConversationMessageConfig(null, "Yes, 10 works."),
                    DemoConversationMessageConfig("Riley", "I'll grab us a table.", "riley-avatar"),
                    DemoConversationMessageConfig("Maya", "Perfect.", "maya-avatar"),
                ),
                category = MirrorCategory.MESSAGE,
                appIconAsset = "messages-icon",
            ),
            DemoNotificationConfig(
                packageName = "demo.calendar",
                appLabel = "Calendar",
                title = "Design review in 10 minutes",
                text = "NotiSync demo walkthrough",
                category = MirrorCategory.EVENT,
                appIconAsset = "calendar-icon",
            ),
            DemoNotificationConfig(
                packageName = "demo.mail",
                appLabel = "Mail",
                title = "Build is ready",
                text = "The latest test build finished successfully.",
                category = MirrorCategory.EMAIL,
                appIconAsset = "mail-icon",
            ),
            DemoNotificationConfig(
                packageName = "demo.delivery",
                appLabel = "Food Delivery",
                title = "Courier is nearby",
                text = "Your ramen bowl is 3 minutes away.",
                style = NotificationStyle.BIG_PICTURE,
                category = MirrorCategory.SERVICE,
                appIconAsset = "delivery-icon",
                bigPictureAsset = "delivery-photo",
                intervalMillis = 10_000,
            ),
        ),
    )
}

private fun DemoNotificationConfig.toNotification(
    sourceClientId: ClientId,
    now: Long,
    index: Int,
    assets: Map<String, PrivateAssetRef>,
    config: DemoScenarioConfig,
): CapturedNotification =
    CapturedNotification(
        sourceClientId = sourceClientId,
        sourceKey = "demo|$index|$now",
        packageName = packageName,
        appLabel = appLabel,
        largeIcon = largeIconAsset?.let { assets.requireAsset(it) },
        bigPicture = bigPictureAsset?.let { assets.requireAsset(it) },
        title = title,
        text = text,
        bigText = bigText,
        subText = subText,
        style = style,
        conversationTitle = conversationTitle,
        isGroupConversation = isGroupConversation,
        messages = messages.mapIndexed { i, message ->
            ConversationMessage(
                sender = message.sender,
                text = message.text,
                timestamp = now + i,
                avatar = message.avatarAsset?.let { assets.requireAsset(it) },
            )
        },
        category = category,
        importance = importance,
        postTime = now,
        groupKey = groupKey,
        channelId = channelId,
        channelName = channelName,
        appIcon = appIconAsset?.let { assets.requireAsset(it) },
        originPlatform = OriginPlatform.ANDROID_LOCAL,
        originDeviceName = originDeviceName ?: config.displayName,
        iosBundleId = iosBundleId,
        originDeviceId = originDeviceId,
    )

private fun Map<String, PrivateAssetRef>.requireAsset(id: String): PrivateAssetRef =
    requireNotNull(this[id]) { "demo asset '$id' was referenced but not configured" }
