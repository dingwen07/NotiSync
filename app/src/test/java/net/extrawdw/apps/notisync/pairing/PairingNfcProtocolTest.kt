package net.extrawdw.apps.notisync.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNfcProtocolTest {
    @Test
    fun pollingLoopAnnotationMatchesItsExactFirmwareFilter() {
        val annotation = PairingNfcPolling.annotationBytes()

        assertEquals(PairingNfcPolling.FILTER_HEX, annotation.toHex())
        assertTrue(annotation.size in 1..16)
        annotation[0] = 0
        assertEquals(PairingNfcPolling.FILTER_HEX, PairingNfcPolling.annotationBytes().toHex())
    }

    @Test
    fun oneKilobytePayloadsAreExchangedBidirectionallyAndCommittedTogether() {
        val hcePayload = binaryPayload(1_087, 0xA5)
        val readerPayload = binaryPayload(1_213, 0x3C)
        var receivedByHce: ByteArray? = null
        val exchange = PairingPayloadExchange(
            outgoingPayload = { hcePayload },
            onIncomingPayload = { receivedByHce = it },
        )

        val selection = PairingNfcProtocol.parseSelectResponse(exchange.select())
        val downloaded = ByteArray(selection.payloadSize)
        selection.initialPayload.copyInto(downloaded)
        var readerOffset = 0
        var hceOffset = selection.initialPayload.size
        var exchangeCount = 0

        while (readerOffset < readerPayload.size || hceOffset < downloaded.size) {
            val readerLength = minOf(selection.maxChunkSize, readerPayload.size - readerOffset)
            val hceLength = minOf(selection.maxChunkSize, downloaded.size - hceOffset)
            val command = PairingNfcProtocol.exchangePayloadCommand(
                readerPayload = readerPayload,
                readerOffset = readerOffset,
                readerLength = readerLength,
                requestedPeerOffset = hceOffset,
                requestedPeerLength = hceLength,
            )
            assertTrue(command.size <= 261) // standard short Case 4 command APDU maximum
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
        assertArrayEquals(hcePayload, downloaded)
        assertArrayEquals(readerPayload, requireNotNull(receivedByHce))
    }

    @Test
    fun commitRejectsAnIncompleteReaderPayload() {
        val exchange = PairingPayloadExchange(
            outgoingPayload = { binaryPayload(300, 0xB2) },
            onIncomingPayload = { error("must not publish a partial payload") },
        )
        val selection = PairingNfcProtocol.parseSelectResponse(exchange.select())
        val reader = binaryPayload(300, 0xC3)
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
            outgoingPayload = { binaryPayload(600, 0xF6) },
            onIncomingPayload = { published = true },
        )
        val selection = PairingNfcProtocol.parseSelectResponse(exchange.select())
        val reader = binaryPayload(20, 0x17)

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
            outgoingPayload = { binaryPayload(400, 0xD4) },
            onIncomingPayload = {},
        )
        val selection = PairingNfcProtocol.parseSelectResponse(exchange.select())
        val reader = binaryPayload(400, 0xE5)
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
    fun zeroLengthPeerReadUsesCase3WithoutLe() {
        val command = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = "ABC".toByteArray(),
            readerOffset = 1,
            readerLength = 2,
            requestedPeerOffset = 0x0123,
            requestedPeerLength = 0,
        )

        assertEquals("80E0000009000300010123004243", command.toHex())
        assertArrayEquals("BC".toByteArray(), PairingNfcProtocol.parseTransfer(command)?.readerChunk)
    }

    @Test
    fun positivePeerReadUsesCase4WithMatchingLeAfterTheLcData() {
        val command = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = "ABC".toByteArray(),
            readerOffset = 1,
            readerLength = 2,
            requestedPeerOffset = 0x0123,
            requestedPeerLength = 2,
        )

        assertEquals("80E000000900030001012302424302", command.toHex())
        val transfer = PairingNfcProtocol.parseTransfer(command)
        assertEquals(2, transfer?.requestedPeerLength)
        assertArrayEquals("BC".toByteArray(), transfer?.readerChunk)
    }

    @Test
    fun positivePeerReadRejectsMissingOrMismatchedLe() {
        val command = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = "ABC".toByteArray(),
            readerOffset = 0,
            readerLength = 3,
            requestedPeerOffset = 0,
            requestedPeerLength = 2,
        )

        assertEquals(null, PairingNfcProtocol.parseTransfer(command.copyOf(command.size - 1)))
        val mismatched = command.copyOf().apply { this[lastIndex] = 3 }
        assertEquals(null, PairingNfcProtocol.parseTransfer(mismatched))
    }

    @Test
    fun zeroLengthPeerReadRejectsAnUnexpectedLe() {
        val case3 = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = "ABC".toByteArray(),
            readerOffset = 0,
            readerLength = 3,
            requestedPeerOffset = 0,
            requestedPeerLength = 0,
        )

        assertEquals(null, PairingNfcProtocol.parseTransfer(case3 + byteArrayOf(0)))
    }

    @Test
    fun commitUsesCase1AndRejectsTheOldFiveByteEncoding() {
        assertEquals("80E00100", PairingNfcProtocol.commitExchangeCommand.toHex())
        assertTrue(PairingNfcProtocol.isCommitExchange(PairingNfcProtocol.commitExchangeCommand))
        assertTrue(!PairingNfcProtocol.isCommitExchange(byteArrayOf(
            0x80.toByte(), 0xE0.toByte(), 0x01, 0x00, 0x00,
        )))
    }

    @Test
    fun maximumChunksFitShortCase3AndCase4Commands() {
        val readerPayload = ByteArray(PairingNfcProtocol.MAX_EXCHANGE_CHUNK_BYTES) { 'A'.code.toByte() }
        val case3 = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = readerPayload,
            readerOffset = 0,
            readerLength = readerPayload.size,
            requestedPeerOffset = 0,
            requestedPeerLength = 0,
        )
        val case4 = PairingNfcProtocol.exchangePayloadCommand(
            readerPayload = readerPayload,
            readerOffset = 0,
            readerLength = readerPayload.size,
            requestedPeerOffset = 0,
            requestedPeerLength = PairingNfcProtocol.MAX_EXCHANGE_CHUNK_BYTES,
        )

        assertEquals(260, case3.size)
        assertEquals(261, case4.size)
        assertEquals(PairingNfcProtocol.MAX_EXCHANGE_CHUNK_BYTES, case4.last().toInt() and 0xFF)
    }

    @Test
    fun readerChunkCalculationNeverExceedsTheAdapterLimit() {
        assertEquals(248, PairingNfcProtocol.readerChunkSize(261, 248))
        assertEquals(247, PairingNfcProtocol.readerChunkSize(260, 248))
        assertEquals(100, PairingNfcProtocol.readerChunkSize(261, 100))
        assertEquals(1, PairingNfcProtocol.readerChunkSize(14, 248))
        assertTrue(runCatching { PairingNfcProtocol.readerChunkSize(13, 248) }.isFailure)
    }

    @Test
    fun customApduCarriesDecodedBinaryRatherThanBase64Text() {
        val wirePayload = byteArrayOf(
            0x00,
            0x01,
            0x7F,
            0x80.toByte(),
            0xFB.toByte(),
            0xFF.toByte(),
        )

        val encoded = PairingNfcProtocol.encodePayload(wirePayload)
        val selection = PairingNfcProtocol.parseSelectResponse(
            PairingNfcProtocol.selectResponse(PairingNfcProtocol.decodePayload(encoded))
        )

        assertArrayEquals(wirePayload, selection.initialPayload)
        assertTrue('=' !in encoded && '+' !in encoded && '/' !in encoded)
    }

    @Test
    fun textBoundaryAcceptsOnlyCanonicalUnpaddedBase64Url() {
        assertArrayEquals(byteArrayOf(0x01), PairingNfcProtocol.decodePayload("AQ"))
        assertTrue(runCatching { PairingNfcProtocol.decodePayload("AQ==") }.isFailure)
        assertTrue(runCatching { PairingNfcProtocol.decodePayload("") }.isFailure)
    }

    @Test
    fun wirePayloadSizeIsBoundedBeforeExchangeAllocation() {
        val oversized = ByteArray(PairingNfcProtocol.MAX_PAIRING_WIRE_BYTES + 1)

        assertTrue(runCatching { PairingNfcProtocol.selectResponse(oversized) }.isFailure)
        assertTrue(runCatching {
            PairingNfcProtocol.exchangePayloadCommand(
                readerPayload = oversized,
                readerOffset = 0,
                readerLength = 0,
                requestedPeerOffset = 0,
                requestedPeerLength = 0,
            )
        }.isFailure)
    }

    private fun binaryPayload(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> (seed + index * 37).toByte() }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it) }
}
