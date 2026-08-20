package net.extrawdw.apps.notisync.messaging

import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.AssetSync
import net.extrawdw.notisync.protocol.AssetSyncKind
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.FilterSync
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.NotificationFilterRule
import net.extrawdw.notisync.protocol.OriginPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolMessageCatalogTest {
    @Test
    fun routingPolicyContainsOnlyEnforcedBehavior() {
        val notification = ProtocolLeaf.Notification.routingDescriptor()
        assertEquals(MessageType.NOTIFICATION, notification.messageType)
        assertEquals(SenderPolicy.TRUSTED_OWN_DEVICE, notification.senderPolicy)
        assertEquals(SignerPolicy.OPERATIONAL, notification.signerPolicy)
        assertEquals(InboundCommitBoundary.OPERATIONAL_OWNER, notification.commitBoundary)

        val profile = ProtocolLeaf.Profile.routingDescriptor()
        assertEquals(SenderPolicy.TRUSTED_PEER, profile.senderPolicy)
        assertEquals(SignerPolicy.OPERATIONAL, profile.signerPolicy)
        assertEquals(InboundCommitBoundary.CORE_THEN_OPERATIONAL_RECEIPT, profile.commitBoundary)

        val trust = ProtocolLeaf.Trust.routingDescriptor()
        assertEquals(SenderPolicy.TRUSTED_OWN_DEVICE, trust.senderPolicy)
        assertEquals(SignerPolicy.IDENTITY, trust.signerPolicy)
        assertEquals(InboundCommitBoundary.CORE_THEN_OPERATIONAL_RECEIPT, trust.commitBoundary)

        val card = ProtocolLeaf.Card.routingDescriptor()
        assertEquals(SignerPolicy.ANY_VERIFIED, card.signerPolicy)
        assertEquals(InboundCommitBoundary.CORE_THEN_OPERATIONAL_RECEIPT, card.commitBoundary)

        val filter = ProtocolLeaf.Filter.routingDescriptor()
        assertEquals(SenderPolicy.TRUSTED_PEER, filter.senderPolicy)
        assertEquals(InboundCommitBoundary.OPERATIONAL_OWNER, filter.commitBoundary)

        val action = ProtocolLeaf.Action(ActionKind.TAP).routingDescriptor()
        assertEquals(MessageType.ACTION, action.messageType)
        assertEquals(ProtocolLeaf.Action(ActionKind.TAP), action.leaf)
    }

    @Test
    fun dataSyncRequiresExactlyOneBodyMatchingItsKind() {
        assertEquals(
            "DATA_SYNC body is missing",
            DataSync(kind = DataSyncKind.FILTER).validateOneMatchingBody(),
        )
        assertEquals(
            "DATA_SYNC body does not match discriminator",
            DataSync(
                kind = DataSyncKind.ASSET,
                filter = FilterSync(emptyList(), updatedAt = 1L),
            ).validateOneMatchingBody(),
        )
        assertEquals(
            "DATA_SYNC contains multiple bodies",
            DataSync(
                kind = DataSyncKind.ASSET,
                asset = AssetSync(AssetSyncKind.ASSET_MISSING, emptyList()),
                filter = FilterSync(
                    rules = listOf(NotificationFilterRule(OriginPlatform.ANDROID_LOCAL)),
                    updatedAt = 1L,
                ),
            ).validateOneMatchingBody(),
        )
        assertNull(
            DataSync(
                kind = DataSyncKind.ASSET,
                asset = AssetSync(AssetSyncKind.ASSET_MISSING, emptyList()),
            ).validateOneMatchingBody(),
        )
    }
}
