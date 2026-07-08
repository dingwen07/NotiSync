package net.extrawdw.apps.notisync.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.channel.SecureChannel
import net.extrawdw.apps.notisync.data.ActivityEvent
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.testsupport.testChannel
import net.extrawdw.apps.notisync.testsupport.CapturingTransport
import net.extrawdw.apps.notisync.transport.DeliveryMode
import net.extrawdw.apps.notisync.testsupport.FakeTrustState
import net.extrawdw.apps.notisync.testsupport.newHpke
import net.extrawdw.apps.notisync.testsupport.newSigner
import net.extrawdw.apps.notisync.testsupport.peerOf
import net.extrawdw.apps.notisync.testsupport.seal
import net.extrawdw.apps.notisync.testsupport.TestActivityText
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.AssetRole
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.ConversationMessage
import net.extrawdw.notisync.protocol.DismissEvent
import net.extrawdw.notisync.protocol.GroupAlertBehavior
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.Urgency
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotifStyle
import net.extrawdw.notisync.protocol.OriginPlatform
import net.extrawdw.notisync.protocol.PrivateAssetRef
import net.extrawdw.notisync.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification application over the channel: own-mesh capture + inbound render, ownDevice gated. */
class MirrorEngineTest {

    private class RecordingRenderer : MirrorRenderer {
        var renders = 0
        var silentRenders = 0
        val cleared = mutableListOf<Pair<ClientId, String>>()
        override fun render(notif: CapturedNotification, silent: Boolean, phase: RenderPhase) {
            renders++
            if (silent) silentRenders++
        }

        override fun clear(sourceClientId: ClientId, sourceKey: String) {
            cleared.add(sourceClientId to sourceKey)
        }
    }

    /** Reports every ref as still-missing so onNotification fires an ASSET_MISSING repair request. */
    private class MissingResolver(private val missing: List<PrivateAssetRef>) : AssetResolver {
        override suspend fun ensureLocal(refs: List<PrivateAssetRef>, trigger: AssetTrigger) =
            ResolveResult(newlyAvailable = false, stillMissing = missing)

        override suspend fun repair(assetHash: String, sourceClientId: ClientId): PrivateAssetRef? =
            null
    }

    private class RecordingMissingResolver : AssetResolver {
        var requested: List<PrivateAssetRef> = emptyList()
        override suspend fun ensureLocal(
            refs: List<PrivateAssetRef>,
            trigger: AssetTrigger,
        ): ResolveResult {
            requested = refs
            return ResolveResult(newlyAvailable = false, stillMissing = refs)
        }

        override suspend fun repair(assetHash: String, sourceClientId: ClientId): PrivateAssetRef? =
            null
    }

    /** Reports every ref as freshly available, so onNotification fires the silent enrichment re-render. */
    private class AvailableResolver : AssetResolver {
        override suspend fun ensureLocal(refs: List<PrivateAssetRef>, trigger: AssetTrigger) =
            ResolveResult(newlyAvailable = true, stillMissing = emptyList())

        override suspend fun repair(assetHash: String, sourceClientId: ClientId): PrivateAssetRef? =
            null
    }

    private fun ref(source: ClientId) = PrivateAssetRef(
        role = AssetRole.LARGE_ICON, assetHash = "h", mimeType = "image/png", sizeBytes = 1,
        sourceClientId = source, assetId = "a", assetKey = byteArrayOf(1),
    )

    private fun sampleNotif(source: ClientId) = CapturedNotification(
        sourceClientId = source, sourceKey = "0|com.x|1|t", packageName = "com.x", appLabel = "X",
        title = "t", text = "x", style = NotifStyle.DEFAULT, category = MirrorCategory.MESSAGE,
        importance = MirrorImportance.DEFAULT, postTime = 1L,
    )

    /** A notification bridged from an iPhone over ANCS: ANCS-shaped key, IOS_ANCS origin, iPhone name. */
    private fun iosNotif(source: ClientId) = CapturedNotification(
        sourceClientId = source, sourceKey = "ancs|ip|com.x|7", packageName = "com.x",
        appLabel = "WhatsApp", title = "t", text = "x", style = NotifStyle.DEFAULT,
        category = MirrorCategory.MESSAGE, importance = MirrorImportance.HIGH, postTime = 1L,
        originPlatform = OriginPlatform.IOS_ANCS, originDeviceName = "Dingwen's iPhone",
        originDeviceId = "ip",
    )

    private fun engine(
        me: net.extrawdw.notisync.protocol.crypto.IdentitySigner,
        myHpkePrivate: ByteArray,
        trust: FakeTrustState,
        renderer: MirrorRenderer,
        transport: CapturingTransport = CapturingTransport(),
        activityLog: ActivityLog = ActivityLog(),
    ): Pair<SecureChannel, MirrorEngine> {
        val channel = testChannel(me, myHpkePrivate, trust, transport)
        val mirror = MirrorEngine(
            channel = channel,
            renderer = renderer,
            activityLog = activityLog,
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
        )
        mirror.register()
        return channel to mirror
    }

    @Test
    fun notificationFromNotOwnDevice_isDropped() {
        val me = newSigner();
        val myHpke = newHpke()
        val other = newSigner();
        val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(other, otherHpke.publicKeyset, ownDevice = false))
        }
        val renderer = RecordingRenderer()
        val (channel, _) = engine(me, myHpke.privateKeyset, trust, renderer)

        channel.deliver(
            seal(
                other,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(sampleNotif(other.clientId)),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        assertEquals("a notification from a not-own device must be dropped", 0, renderer.renders)
    }

    @Test
    fun notificationFromOwnDevice_isRendered() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val renderer = RecordingRenderer()
        val (channel, _) = engine(me, myHpke.privateKeyset, trust, renderer)

        channel.deliver(
            seal(
                own,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(sampleNotif(own.clientId)),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        assertEquals(1, renderer.renders)
    }

    @Test
    fun notificationActivity_includesDeliveryMode() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true, name = "Desk"))
        }
        val renderer = RecordingRenderer()
        val activityLog = ActivityLog()
        val channel = testChannel(me, myHpke.privateKeyset, trust)
        val mirror = MirrorEngine(
            channel = channel,
            renderer = renderer,
            activityLog = activityLog,
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            peerNameResolver = { trust.displayName(it) ?: it.shortForm() },
        )
        mirror.register()

        channel.deliver(
            seal(
                own,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(sampleNotif(own.clientId)),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            ),
            DeliveryMode.FCM_RELAY_FETCH,
        )

        assertEquals(1, renderer.renders)
        val row = activityLog.events.value.single()
        assertEquals("from Desk", row.detail)
        assertEquals(DeliveryMode.FCM_RELAY_FETCH, row.deliveryMode)
    }

    @Test
    fun receivedGroupSummary_rendersButDoesNotLogActivityRow() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val renderer = RecordingRenderer()
        val activityLog = ActivityLog()
        val (channel, _) = engine(me, myHpke.privateKeyset, trust, renderer, activityLog = activityLog)

        channel.deliver(
            seal(
                own,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(
                    sampleNotif(own.clientId).copy(
                        sourceKey = "0|com.x|1|null|10316",
                        isGroupSummary = true,
                        groupKey = "messages",
                        groupAlertBehavior = GroupAlertBehavior.SUMMARY,
                    )
                ),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        assertEquals(1, renderer.renders)
        assertTrue(activityLog.events.value.isEmpty())
    }

    @Test
    fun dismissalFromOwnDevice_clearsAndCancelsOriginal() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val renderer = RecordingRenderer()
        val activityLog = ActivityLog()
        val (channel, mirror) = engine(
            me,
            myHpke.privateKeyset,
            trust,
            renderer,
            activityLog = activityLog
        )
        val canceled = mutableListOf<String>()
        mirror.originalCanceler = OriginalCanceler { canceled.add(it) }

        val event = DismissEvent(own.clientId, "0|com.x|1|t", 1L)
        channel.deliver(
            seal(
                own,
                MessageType.DISMISSAL,
                ProtocolCodec.encodeToCbor(event),
                me.clientId,
                myHpke.publicKeyset,
                "d1"
            ),
            DeliveryMode.WEBSOCKET,
        )

        assertEquals(listOf(own.clientId to "0|com.x|1|t"), renderer.cleared)
        assertEquals(listOf("0|com.x|1|t"), canceled)
        assertEquals(DeliveryMode.WEBSOCKET, activityLog.events.value.single().deliveryMode)
    }

    @Test
    fun localDismissal_propagatesToIosOrigin() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val (_, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer())
        val iosCleared = mutableListOf<String>()
        mirror.iosOriginCanceler = OriginalCanceler { iosCleared.add(it) }

        // Swiping an iOS mirror locally doesn't remove it from the iPhone, so the dismissal must be propagated.
        mirror.dismissLocal(me.clientId, "ancs|ip|com.x|7")

        assertEquals(listOf("ancs|ip|com.x|7"), iosCleared)
    }

    @Test
    fun remoteDismissal_propagatesToIosOrigin() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val (channel, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer())
        val iosCleared = mutableListOf<String>()
        mirror.iosOriginCanceler = OriginalCanceler { iosCleared.add(it) }

        // A peer's dismissal must clear the bridged iPhone notification too (like cancelling an Android original).
        val event = DismissEvent(own.clientId, "ancs|ip|com.x|7", 1L)
        channel.deliver(
            seal(
                own,
                MessageType.DISMISSAL,
                ProtocolCodec.encodeToCbor(event),
                me.clientId,
                myHpke.publicKeyset,
                "d1"
            )
        )

        assertEquals(listOf("ancs|ip|com.x|7"), iosCleared)
    }

    @Test
    fun receivedIosNotification_titleCarriesOriginDeviceName() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val activityLog = ActivityLog()
        val (channel, _) = engine(
            me, myHpke.privateKeyset, trust, RecordingRenderer(), activityLog = activityLog
        )

        channel.deliver(
            seal(
                own,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(iosNotif(own.clientId)),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        // A bridged iPhone notification's posted row reads "App (iPhone)", not just the app label.
        val row = activityLog.events.value.single()
        assertEquals(ActivityEvent.Kind.RECEIVED, row.kind)
        assertEquals("WhatsApp (Dingwen's iPhone)", row.title)
    }

    @Test
    fun remoteDismissalOfIosNotification_titleMatchesPostedRow() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val activityLog = ActivityLog()
        val (channel, _) = engine(
            me, myHpke.privateKeyset, trust, RecordingRenderer(), activityLog = activityLog
        )

        // Receive the iPhone notification (remembers its title) …
        channel.deliver(
            seal(
                own,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(iosNotif(own.clientId)),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )
        // … then a peer dismisses it. The DismissEvent carries only the opaque ANCS key (whose 2nd
        // segment is the iPhone id, not the app), yet the row must still read "App (iPhone)".
        channel.deliver(
            seal(
                own,
                MessageType.DISMISSAL,
                ProtocolCodec.encodeToCbor(DismissEvent(own.clientId, "ancs|ip|com.x|7", 2L)),
                me.clientId,
                myHpke.publicKeyset,
                "d1"
            )
        )

        val dismissed = activityLog.events.value.first()
        assertEquals(ActivityEvent.Kind.DISMISSED, dismissed.kind)
        assertEquals("WhatsApp (Dingwen's iPhone)", dismissed.title)
    }

    @Test
    fun dismissalOfIosNotification_coldCache_namesAppNotIphoneId() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val activityLog = ActivityLog()
        val (channel, _) = engine(
            me, myHpke.privateKeyset, trust, RecordingRenderer(), activityLog = activityLog
        )

        // No prior posted row (e.g. the process restarted while the mirror sat in the tray): the ANCS key's
        // app field is the bundle id, never the iPhone id — so the row names the app, not the opaque hash.
        channel.deliver(
            seal(
                own,
                MessageType.DISMISSAL,
                ProtocolCodec.encodeToCbor(
                    DismissEvent(own.clientId, "ancs|a3f9deadbeef|net.whatsapp.WhatsApp|7", 1L)
                ),
                me.clientId,
                myHpke.publicKeyset,
                "d1"
            )
        )

        val row = activityLog.events.value.single()
        assertEquals(ActivityEvent.Kind.DISMISSED, row.kind)
        assertEquals("net.whatsapp.WhatsApp", row.title)
    }

    @Test
    fun capturedIosNotification_titleCarriesDeviceName_andSurvivesLocalDismissal() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val activityLog = ActivityLog()
        val (_, mirror) = engine(
            me, myHpke.privateKeyset, trust, RecordingRenderer(), activityLog = activityLog
        )

        // Bridge side: capture from the iPhone (Sent row) then locally swipe the mirror (Dismissed row).
        mirror.captureLocal(iosNotif(me.clientId))
        mirror.dismissLocal(me.clientId, "ancs|ip|com.x|7")

        val rows = activityLog.events.value // newest first
        assertEquals(ActivityEvent.Kind.DISMISSED, rows[0].kind)
        assertEquals("WhatsApp (Dingwen's iPhone)", rows[0].title)
        assertEquals(ActivityEvent.Kind.SENT, rows[1].kind)
        assertEquals("WhatsApp (Dingwen's iPhone)", rows[1].title)
    }

    @Test
    fun actionFromOwnDevice_forOurCapture_reachesBothPerformers() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val (channel, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer())
        val performed = mutableListOf<ActionEvent>()
        val iosPerformed = mutableListOf<ActionEvent>()
        mirror.originalActionPerformer = OriginalActionPerformer { performed.add(it) }
        mirror.iosOriginActionPerformer = OriginalActionPerformer { iosPerformed.add(it) }

        val event = ActionEvent(
            sourceClientId = me.clientId, sourceKey = "0|com.x|1|t", kind = ActionKind.PERFORM,
            actionIndex = 2, actionTitle = "Reply", remoteInputText = "on my way", actedAt = 5L,
        )
        channel.deliver(
            seal(
                own,
                MessageType.ACTION,
                ProtocolCodec.encodeToCbor(event),
                me.clientId,
                myHpke.publicKeyset,
                "a1"
            )
        )

        // Both performer hooks see the event (each ignores keys that aren't theirs), fully decoded.
        assertEquals(1, performed.size)
        assertEquals(ActionKind.PERFORM, performed[0].kind)
        assertEquals(2, performed[0].actionIndex)
        assertEquals("Reply", performed[0].actionTitle)
        assertEquals("on my way", performed[0].remoteInputText)
        assertEquals(1, iosPerformed.size)
    }

    @Test
    fun actionAddressedToAnotherOrigin_isDropped() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val (channel, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer())
        val performed = mutableListOf<ActionEvent>()
        mirror.originalActionPerformer = OriginalActionPerformer { performed.add(it) }

        // The body names ANOTHER client's capture — only the origin may perform, so this is a
        // misrouted (or forged-body) event and must be ignored.
        val event = ActionEvent(own.clientId, "0|com.x|1|t", ActionKind.TAP, actedAt = 5L)
        channel.deliver(
            seal(
                own,
                MessageType.ACTION,
                ProtocolCodec.encodeToCbor(event),
                me.clientId,
                myHpke.publicKeyset,
                "a1"
            )
        )

        assertTrue(performed.isEmpty())
    }

    @Test
    fun actionFromNotOwnDevice_isDropped() {
        val me = newSigner();
        val myHpke = newHpke()
        val other = newSigner();
        val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(other, otherHpke.publicKeyset, ownDevice = false))
        }
        val (channel, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer())
        val performed = mutableListOf<ActionEvent>()
        mirror.originalActionPerformer = OriginalActionPerformer { performed.add(it) }

        val event = ActionEvent(me.clientId, "0|com.x|1|t", ActionKind.PERFORM, 0, "Open", null, 5L)
        channel.deliver(
            seal(
                other,
                MessageType.ACTION,
                ProtocolCodec.encodeToCbor(event),
                me.clientId,
                myHpke.publicKeyset,
                "a1"
            )
        )

        assertTrue("an action from a not-own device must be dropped", performed.isEmpty())
    }

    @Test
    fun performRemote_unicastsToOriginAtHighUrgency() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val origin = newSigner();
        val originHpke = newHpke()
        val bystander = newSigner();
        val bystanderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(origin, originHpke.publicKeyset, ownDevice = true),
                peerOf(bystander, bystanderHpke.publicKeyset, ownDevice = true),
            )
        }
        val transport = CapturingTransport()
        val (_, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer(), transport)

        val sent = mirror.performRemote(origin.clientId, "0|com.x|1|t", 1, "Mark as read")

        // Sealed ONLY to the origin (never fanned out to the rest of the mesh), at HIGH urgency —
        // the user is standing at this device waiting for the origin to act.
        assertTrue(sent)
        val (envelope, urgency) = transport.sent.single()
        assertEquals(MessageType.ACTION, envelope.typ)
        assertEquals(setOf(origin.clientId), envelope.recipientIds().toSet())
        assertEquals(Urgency.HIGH, urgency)
    }

    @Test
    fun tapRemote_unicastsToOrigin_andLogsActivity() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val origin = newSigner();
        val originHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(origin, originHpke.publicKeyset, ownDevice = true, name = "Pixel 9"))
        }
        val transport = CapturingTransport()
        val activityLog = ActivityLog()
        val channel = testChannel(me, myHpke.privateKeyset, trust, transport)
        val mirror = MirrorEngine(
            channel = channel,
            renderer = RecordingRenderer(),
            activityLog = activityLog,
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            peerNameResolver = { trust.displayName(it) ?: it.shortForm() },
        )
        mirror.register()

        assertTrue(mirror.tapRemote(origin.clientId, "0|com.x|1|t"))

        val (envelope, urgency) = transport.sent.single()
        assertEquals(MessageType.ACTION, envelope.typ)
        assertEquals(setOf(origin.clientId), envelope.recipientIds().toSet())
        assertEquals(Urgency.HIGH, urgency)
        assertEquals("opening on Pixel 9", activityLog.events.value.single().detail)
    }

    @Test
    fun captureLocal_sealsNotificationToOwnMesh() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val other = newSigner();
        val otherHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(own, ownHpke.publicKeyset, ownDevice = true),
                peerOf(other, otherHpke.publicKeyset, ownDevice = false)
            )
        }
        val transport = CapturingTransport()
        val (_, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer(), transport)

        mirror.captureLocal(sampleNotif(me.clientId))

        // Sealed to own devices only (never to an "other" contact device).
        assertEquals(setOf(own.clientId), transport.envelopes.single().recipientIds().toSet())
        assertEquals(MessageType.NOTIFICATION, transport.envelopes.single().typ)
    }

    @Test
    fun captureLocal_groupSummarySkipsIosOwnPeers() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val android = newSigner();
        val androidHpke = newHpke()
        val ios = newSigner();
        val iosHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(android, androidHpke.publicKeyset, ownDevice = true, platform = "android"),
                peerOf(ios, iosHpke.publicKeyset, ownDevice = true, platform = "ios"),
            )
        }
        val transport = CapturingTransport()
        val activityLog = ActivityLog()
        val (_, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer(), transport, activityLog)

        mirror.captureLocal(
            sampleNotif(me.clientId).copy(
                isGroupSummary = true,
                groupKey = "messages",
                groupAlertBehavior = GroupAlertBehavior.SUMMARY,
            )
        )

        assertEquals(setOf(android.clientId), transport.envelopes.single().recipientIds().toSet())
        assertTrue(activityLog.events.value.isEmpty())
    }

    @Test
    fun captureLocal_childNotificationStillGoesToIosOwnPeers() = runBlocking {
        val me = newSigner();
        val myHpke = newHpke()
        val android = newSigner();
        val androidHpke = newHpke()
        val ios = newSigner();
        val iosHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(android, androidHpke.publicKeyset, ownDevice = true, platform = "android"),
                peerOf(ios, iosHpke.publicKeyset, ownDevice = true, platform = "ios"),
            )
        }
        val transport = CapturingTransport()
        val (_, mirror) = engine(me, myHpke.privateKeyset, trust, RecordingRenderer(), transport)

        mirror.captureLocal(sampleNotif(me.clientId).copy(groupKey = "messages"))

        assertEquals(setOf(android.clientId, ios.clientId), transport.envelopes.single().recipientIds().toSet())
    }

    @Test
    fun assetRepairRequest_isSuppressedWhenSourceIsNonOwn() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()  // own device that delivered the notification
        val other = newSigner();
        val otherHpke = newHpke()    // a TRUSTED non-own contact device
        val trust = FakeTrustState().apply {
            peers.value = listOf(
                peerOf(sender, senderHpke.publicKeyset, ownDevice = true),
                peerOf(other, otherHpke.publicKeyset, ownDevice = false),
            )
        }
        val transport = CapturingTransport()
        val channel = testChannel(me, myHpke.privateKeyset, trust, transport)
        val mirror = MirrorEngine(
            channel = channel,
            renderer = RecordingRenderer(),
            activityLog = ActivityLog(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            assetResolver = MissingResolver(listOf(ref(other.clientId))),
        )
        mirror.register()

        // NOTIFICATION from an OWN device, but its body's sourceClientId names a NON-own peer (body-controlled).
        val notif = sampleNotif(other.clientId).copy(largeIcon = ref(other.clientId))
        channel.deliver(
            seal(
                sender,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(notif),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        assertTrue(
            "an asset-repair request must never be sealed to a non-own device",
            transport.sent.isEmpty()
        )
    }

    @Test
    fun assetRepairRequest_isSentWhenSourceIsOwn() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, ownDevice = true))
        }
        val transport = CapturingTransport()
        val channel = testChannel(me, myHpke.privateKeyset, trust, transport)
        val mirror = MirrorEngine(
            channel = channel,
            renderer = RecordingRenderer(),
            activityLog = ActivityLog(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            assetResolver = MissingResolver(listOf(ref(sender.clientId))),
        )
        mirror.register()

        val notif = sampleNotif(sender.clientId).copy(largeIcon = ref(sender.clientId))
        channel.deliver(
            seal(
                sender,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(notif),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        // sourceClientId is an own device → the ASSET_MISSING repair is delivered to it.
        assertEquals(1, transport.sent.size)
        assertEquals(MessageType.DATA_SYNC, transport.envelopes.single().typ)
        assertEquals(setOf(sender.clientId), transport.envelopes.single().recipientIds().toSet())
    }

    @Test
    fun assetEnrichmentReRender_isSilent_soItDoesNotReAlert() {
        val me = newSigner();
        val myHpke = newHpke()
        val own = newSigner();
        val ownHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(own, ownHpke.publicKeyset, ownDevice = true))
        }
        val renderer = RecordingRenderer()
        val channel = testChannel(me, myHpke.privateKeyset, trust)
        val mirror = MirrorEngine(
            channel = channel,
            renderer = renderer,
            activityLog = ActivityLog(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            assetResolver = AvailableResolver(),
        )
        mirror.register()

        // A notification carrying a private graphic is posted immediately (text), then re-rendered once the
        // asset resolves. The first render alerts per the source's own onlyAlertOnce flag; the asset-arrival
        // re-render must be silent so it refreshes the image in place without a second alert.
        val notif = sampleNotif(own.clientId).copy(largeIcon = ref(own.clientId))
        channel.deliver(
            seal(
                own,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(notif),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        assertEquals(2, renderer.renders)        // immediate + asset-arrival re-render
        assertEquals(1, renderer.silentRenders)  // only the re-render is silent
    }

    @Test
    fun messageDataAsset_isResolvedAndRepairable() {
        val me = newSigner();
        val myHpke = newHpke()
        val sender = newSigner();
        val senderHpke = newHpke()
        val trust = FakeTrustState().apply {
            peers.value = listOf(peerOf(sender, senderHpke.publicKeyset, ownDevice = true))
        }
        val resolver = RecordingMissingResolver()
        val transport = CapturingTransport()
        val channel = testChannel(me, myHpke.privateKeyset, trust, transport)
        val mirror = MirrorEngine(
            channel = channel,
            renderer = RecordingRenderer(),
            activityLog = ActivityLog(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            activityText = TestActivityText,
            assetResolver = resolver,
        )
        mirror.register()

        val inline = ref(sender.clientId).copy(
            role = AssetRole.INLINE_IMAGE,
            assetHash = "inline",
            mimeType = "image/png",
            assetId = "inline-asset",
        )
        val notif = sampleNotif(sender.clientId).copy(
            style = NotifStyle.MESSAGING,
            messages = listOf(
                ConversationMessage(
                    sender = "Alice",
                    text = "photo",
                    timestamp = 2L,
                    dataMimeType = "image/png",
                    data = inline,
                )
            )
        )

        channel.deliver(
            seal(
                sender,
                MessageType.NOTIFICATION,
                ProtocolCodec.encodeToCbor(notif),
                me.clientId,
                myHpke.publicKeyset,
                "n1"
            )
        )

        assertEquals(listOf(inline.assetHash), resolver.requested.map { it.assetHash })
        assertEquals(1, transport.sent.size)
    }
}
