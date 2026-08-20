package net.extrawdw.apps.notisync.messaging.core

import net.extrawdw.apps.notisync.data.relay.RelayHandledDisposition
import net.extrawdw.apps.notisync.data.relay.RelayStableCode

internal sealed interface CoreCommandProcessingResult {
    val messageId: String

    /** Exact handled evidence and any required Activity projection committed before this result is created. */
    data class AcknowledgementReady(
        override val messageId: String,
        val disposition: RelayHandledDisposition,
    ) : CoreCommandProcessingResult {
        init {
            require(
                disposition == RelayHandledDisposition.APPLIED ||
                    disposition == RelayHandledDisposition.SUPERSEDED,
            ) { "Only marker-authoritative Core outcomes are acknowledgement-ready" }
        }
    }

    /** No local retry lifecycle is created; the broker-retained delivery or its bounded wake locator retries. */
    data class RetryRequired(
        override val messageId: String,
        val errorCode: RelayStableCode,
    ) : CoreCommandProcessingResult

    /** Fail closed and do not ACK. The exact authenticated delivery remains broker-retained for investigation. */
    data class SecurityBlocked(
        override val messageId: String,
        val errorCode: RelayStableCode,
    ) : CoreCommandProcessingResult
}
