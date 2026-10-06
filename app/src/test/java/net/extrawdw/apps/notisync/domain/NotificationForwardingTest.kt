package net.extrawdw.apps.notisync.domain

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import net.extrawdw.apps.notisync.channel.InboundMessage
import net.extrawdw.apps.notisync.data.ActivityLog
import net.extrawdw.apps.notisync.data.NotificationFilterStore
import net.extrawdw.apps.notisync.data.NotificationForwardingStore
import net.extrawdw.apps.notisync.testsupport.CapturingTransport
import net.extrawdw.apps.notisync.testsupport.FakeTrustState
import net.extrawdw.apps.notisync.testsupport.InMemoryOperationalApplicationState
import net.extrawdw.apps.notisync.testsupport.TestActivityText
import net.extrawdw.apps.notisync.testsupport.newHpke
import net.extrawdw.apps.notisync.testsupport.newSigner
import net.extrawdw.apps.notisync.testsupport.peerOf
import net.extrawdw.apps.notisync.testsupport.testChannel
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationFilterRule
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.OriginPlatform
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NotificationForwardingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val me = newSigner()
    private val local = OriginPlatform.ANDROID_LOCAL
    private val iphone = OriginPlatform.IOS_ANCS
    private val transport = CapturingTransport()
    private val trust = FakeTrustState()
    private val filters = NotificationFilterStore(scope, InMemoryOperationalApplicationState())
    private val renderer = object : MirrorRenderer {
        var renders = 0
        override fun render(notif: CapturedNotification, silent: Boolean, phase: RenderPhase) {
            renders++
        }
        override fun clear(sourceClientId: ClientId, sourceKey: String) = Unit
    }

    @After fun close() = runBlocking { job.cancelAndJoin() }

    private fun forwardingStore() = NotificationForwardingStore(
        PreferenceDataStoreFactory.create(scope = scope) {
            File(temporaryFolder.root, "forwarding.preferences_pb")
        },
    )

    private fun peer(): ClientId {
        val identity = newSigner()
        trust.peers.value += peerOf(identity, newHpke().publicKeyset, ownDevice = true, platform = "android")
        return identity.clientId
    }

    private fun engine(forwarding: NotificationForwardingStore) = MirrorEngine(
        channel = testChannel(me, newHpke().privateKeyset, trust, transport),
        renderer = renderer,
        activityLog = ActivityLog(),
        activityText = TestActivityText,
        scope = scope,
        notificationFilters = filters,
        notificationForwarding = forwarding,
    )

    private fun notification(origin: OriginPlatform) = CapturedNotification(
        sourceClientId = me.clientId,
        sourceKey = "notification",
        packageName = "com.example",
        appLabel = "Example",
        title = "Title",
        text = "Text",
        style = NotificationStyle.DEFAULT,
        category = MirrorCategory.MESSAGE,
        importance = MirrorImportance.DEFAULT,
        postTime = 1L,
        originPlatform = origin,
    )

    @Test fun everyNotificationPathHonorsEachOriginAndDestinationIndependently() = runBlocking {
        val defaultOn = peer()
        val localOff = peer()
        val iphoneOff = peer()
        val bothOff = peer()
        val forwarding = forwardingStore()
        forwarding.setEnabled(localOff, local, false)
        forwarding.setEnabled(iphoneOff, iphone, false)
        OriginPlatform.entries.forEach { forwarding.setEnabled(bothOff, it, false) }
        val mirror = engine(forwarding)
        val paths: List<suspend (CapturedNotification) -> Int> = listOf(
            { mirror.captureLocal(it) },
            { mirror.sendNotificationQuiet(it) },
            { mirror.sendOngoingUpdatePrompt(it, allowIos = true) },
        )
        for (send in paths) {
            for (origin in OriginPlatform.entries) {
                transport.sent.clear()
                assertEquals(2, send(notification(origin)))
                assertEquals(
                    setOf(defaultOn, if (origin == local) iphoneOff else localOff),
                    transport.envelopes.flatMap { it.recipientIds() }.toSet(),
                )
            }
        }
    }

    @Test fun filtersContinueUpdatingWhileOffAndStillApplyAfterReenabling() = runBlocking {
        val recipient = peer()
        val forwarding = forwardingStore()
        val mirror = engine(forwarding)
        val message = InboundMessage(recipient, true, MessageType.DATA_SYNC, ByteArray(0))
        fun receive(filter: FilterSync) = mirror.onFilterSync(
            message, DataSync(DataSyncKind.FILTER, filter = filter),
        )
        for (origin in OriginPlatform.entries) forwarding.setEnabled(recipient, origin, false)
        receive(FilterSync(listOf(NotificationFilterRule(local)), updatedAt = 1L))
        val latest = FilterSync(listOf(NotificationFilterRule(iphone)), updatedAt = 2L)
        receive(latest)
        assertEquals(latest, filters.filterFor(recipient))
        for (origin in OriginPlatform.entries) assertEquals(0, mirror.captureLocal(notification(origin)))
        assertTrue(transport.envelopes.isEmpty())

        for (origin in OriginPlatform.entries) forwarding.setEnabled(recipient, origin, true)
        assertEquals(1, mirror.captureLocal(notification(local)))
        assertEquals(0, mirror.captureLocal(notification(iphone)))

        forwarding.setEnabled(recipient, iphone, false)
        receive(FilterSync(emptyList(), updatedAt = 3L))
        assertTrue(filters.filterFor(recipient)!!.rules.isEmpty())
        assertEquals(0, mirror.captureLocal(notification(iphone)))
        assertEquals(0, mirror.sendNotificationQuiet(notification(iphone)))
        assertEquals(0, mirror.sendOngoingUpdatePrompt(notification(iphone), allowIos = true))
        forwarding.setEnabled(recipient, iphone, true)
        assertEquals(1, mirror.captureLocal(notification(iphone)))
    }

    @Test fun disabledForwardingPreservesDismissalsActionsAndIncomingNotifications() = runBlocking {
        val recipient = peer()
        val forwarding = forwardingStore()
        for (origin in OriginPlatform.entries) forwarding.setEnabled(recipient, origin, false)
        val mirror = engine(forwarding)

        mirror.dismissLocal(me.clientId, "notification")
        assertTrue(mirror.tapRemote(recipient, "remote-notification"))
        assertEquals(listOf(MessageType.DISMISSAL, MessageType.ACTION), transport.envelopes.map { it.typ })
        transport.envelopes.forEach { assertEquals(listOf(recipient), it.recipientIds()) }
        mirror.onQuietNotification(
            InboundMessage(recipient, true, MessageType.DATA_SYNC, ByteArray(0)),
            DataSync(DataSyncKind.NOTIFICATION, notification = notification(local).copy(sourceClientId = recipient)),
        )
        assertEquals(1, renderer.renders)
    }
}
