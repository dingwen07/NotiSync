package net.extrawdw.apps.notisync.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNfcProtocolTest {
    @Test
    fun oneKilobytePayloadsAreExchangedBidirectionallyAndCommittedTogether() {
        val hcePayload = payload(1_087, 'A')
        val readerPayload = payload(1_213, 'a')
        var receivedByHce: String? = null
        val exchange = PairingPayloadExchange(
            outgoingPayload = { hcePayload },
            onIncomingPayload = { receivedByHce = it },
        )

        val selection = PairingNfcProtocol.parseSelectResponse(exchange.select())
        val downloaded = ByteArray(selection.payloadSize)
        selection.initialPayload.copyInto(downloaded)
        val readerBytes = PairingNfcProtocol.payloadBytes(readerPayload)
        var readerOffset = 0
        var hceOffset = selection.initialPayload.size
        var exchangeCount = 0

        while (readerOffset < readerBytes.size || hceOffset < downloaded.size) {
            val readerLength = minOf(selection.maxChunkSize, readerBytes.size - readerOffset)
            val hceLength = minOf(selection.maxChunkSize, downloaded.size - hceOffset)
            val command = PairingNfcProtocol.exchangePayloadCommand(
                readerPayload = readerBytes,
                readerOffset = readerOffset,
                readerLength = readerLength,
                requestedPeerOffset = hceOffset,
                requestedPeerLength = hceLength,
            )
            assertTrue(command.size <= 260) // standard short command APDU maximum
            val hceChunk = PairingNfcProtocol.requireSuccessfulResponse(exchange.process(command))
            assertEquals(hceLength, hceChunk.size)
            hceChunk.copyInto(downloaded, destinationOffset = hceOffset)
            readerOffset += readerLength
            hceOffset += hceLength
            exchangeCount += 1
        }

        assertTrue(exchangeCount <= 5)
        assertArrayEquals(
            PairingNfcProtocol.statusOk,
            exchange.process(PairingNfcProtocol.commitExchangeCommand),
        )
        assertEquals(hcePayload, PairingNfcProtocol.payloadString(downloaded))
        assertEquals(readerPayload, receivedByHce)
    }

    @Test
    fun commitRejectsAnIncompleteReaderPayload() {
        val exchange = PairingPayloadExchange(
            outgoingPayload = { payload(300, 'B') },
            onIncomingPayload = { error("must not publish a partial payload") },
        )
        val selection = PairingNfcProtocol.parseSelectResponse(exchange.select())
        val reader = PairingNfcProtocol.payloadBytes(payload(300, 'C'))
        val remainingHce = selection.payloadSize - selection.initialPayload.size

        val partial = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = reader,
            readerOffset = 0,
            readerLength = 20,
            requestedPeerOffset = selection.initialPayload.size,
            requestedPeerLength = remainingHce,
        )
        PairingNfcProtocol.requireSuccessfulResponse(exchange.process(partial))

        assertArrayEquals(
            PairingNfcProtocol.statusConditionsNotSatisfied,
            exchange.process(PairingNfcProtocol.commitExchangeCommand),
        )
    }

    @Test
    fun commitRejectsUntilTheReaderHasDownloadedTheWholePeerPayload() {
        var published = false
        val exchange = PairingPayloadExchange(
            outgoingPayload = { payload(600, 'F') },
            onIncomingPayload = { published = true },
        )
        val selection = PairingNfcProtocol.parseSelectResponse(exchange.select())
        val reader = PairingNfcProtocol.payloadBytes(payload(20, 'G'))

        val uploadsReaderButLeavesPeerUnread = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = reader,
            readerOffset = 0,
            readerLength = reader.size,
            requestedPeerOffset = selection.initialPayload.size,
            requestedPeerLength = 0,
        )
        PairingNfcProtocol.requireSuccessfulResponse(exchange.process(uploadsReaderButLeavesPeerUnread))

        assertArrayEquals(
            PairingNfcProtocol.statusConditionsNotSatisfied,
            exchange.process(PairingNfcProtocol.commitExchangeCommand),
        )
        assertTrue(!published)
    }

    @Test
    fun outOfOrderChunkIsRejectedWithoutAdvancingEitherDirection() {
        val exchange = PairingPayloadExchange(
            outgoingPayload = { payload(400, 'D') },
            onIncomingPayload = {},
        )
        val selection = PairingNfcProtocol.parseSelectResponse(exchange.select())
        val reader = PairingNfcProtocol.payloadBytes(payload(400, 'E'))
        val invalid = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = reader,
            readerOffset = 1,
            readerLength = 10,
            requestedPeerOffset = selection.initialPayload.size,
            requestedPeerLength = 10,
        )
        assertArrayEquals(PairingNfcProtocol.statusWrongOffset, exchange.process(invalid))

        val valid = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = reader,
            readerOffset = 0,
            readerLength = 10,
            requestedPeerOffset = selection.initialPayload.size,
            requestedPeerLength = 10,
        )
        assertEquals(10, PairingNfcProtocol.requireSuccessfulResponse(exchange.process(valid)).size)
    }

    @Test
    fun payloadSizeIsBoundedBeforeAllocation() {
        val oversized = "A".repeat(PairingNfcProtocol.MAX_PAIRING_PAYLOAD_BYTES + 1)
        assertTrue(runCatching { PairingNfcProtocol.payloadBytes(oversized) }.isFailure)
    }

    private fun payload(size: Int, seed: Char): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val start = alphabet.indexOf(seed).coerceAtLeast(0)
        return buildString(size) {
            repeat(size) { append(alphabet[(start + it) % alphabet.length]) }
        }
    }
}
