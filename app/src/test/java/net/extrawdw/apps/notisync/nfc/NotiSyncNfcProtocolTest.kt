package net.extrawdw.apps.notisync.nfc

import net.extrawdw.apps.notisync.pairing.PairingNfcPayloadCodec
import net.extrawdw.apps.notisync.pairing.PairingNfcPolling
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotiSyncNfcProtocolTest {
    @Test
    fun pollingLoopAnnotationMatchesItsExactFirmwareFilter() {
        val annotation = PairingNfcPolling.annotationBytes()

        assertEquals(PairingNfcPolling.FILTER_HEX, annotation.toHex())
        assertTrue(annotation.size in 1..16)
        annotation[0] = 0
        assertEquals(PairingNfcPolling.FILTER_HEX, PairingNfcPolling.annotationBytes().toHex())
    }

    @Test
    fun applicationSelectionAdvertisesPairingWithoutExposingItsPayload() {
        val response = NotiSyncNfcProtocol.applicationSelectionResponse()
        val discovery = NotiSyncNfcProtocol.parseApplicationSelectionResponse(response)

        assertEquals("4E5301010101019000", response.toHex())
        assertEquals(
            listOf(NotiSyncNfcProtocol.OperationSupport(wireId = 1, minVersion = 1, maxVersion = 1)),
            discovery.operations,
        )
        assertEquals(
            1,
            NotiSyncNfcProtocol.negotiateVersion(discovery, NotiSyncNfcProtocol.Operation.PAIRING),
        )
    }

    @Test
    fun pairingNegotiationUsesCase2AndRequiresItsCanonicalLe() {
        val command = NotiSyncNfcProtocol.negotiateOperationCommand(
            NotiSyncNfcProtocol.Operation.PAIRING,
            version = 1,
        )

        assertEquals("80E1010100", command.toHex())
        val negotiation = requireNotNull(NotiSyncNfcProtocol.parseOperationNegotiation(command))
        assertEquals(NotiSyncNfcProtocol.Operation.PAIRING, negotiation.operation)
        assertEquals(1, negotiation.version)
        assertEquals(null, NotiSyncNfcProtocol.parseOperationNegotiation(command.copyOf(4)))
        assertEquals(null, NotiSyncNfcProtocol.parseOperationNegotiation(command.copyOf().apply {
            this[lastIndex] = 1
        }))
    }

    @Test
    fun negotiationRejectsUnknownOperationsAndIncompatibleVersions() {
        val operation = NotiSyncNfcProtocol.Operation.PAIRING
        val unknown = NotiSyncNfcProtocol.Discovery(
            operations = listOf(NotiSyncNfcProtocol.OperationSupport(0x7F, 1, 1)),
        )
        val incompatible = NotiSyncNfcProtocol.Discovery(
            operations = listOf(NotiSyncNfcProtocol.OperationSupport(operation.wireId, 2, 3)),
        )

        assertTrue(runCatching { NotiSyncNfcProtocol.negotiateVersion(unknown, operation) }.isFailure)
        assertTrue(runCatching { NotiSyncNfcProtocol.negotiateVersion(incompatible, operation) }.isFailure)
        val unknownCommand = byteArrayOf(0x80.toByte(), 0xE1.toByte(), 0x7F, 0x01, 0x00)
        val parsed = requireNotNull(NotiSyncNfcProtocol.parseOperationNegotiation(unknownCommand))
        assertEquals(null, parsed.operation)
        assertEquals(0x7F, parsed.wireId)
    }

    @Test
    fun payloadTransferAndCommitRequireAnOperationNegotiationFirst() {
        val exchange = BidirectionalPayloadExchange(
            outgoingPayload = { byteArrayOf(0x01) },
            onIncomingPayload = { error("must not publish before negotiation") },
        )
        val transfer = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = byteArrayOf(0x02),
            readerOffset = 0,
            readerLength = 1,
            requestedPeerOffset = 0,
            requestedPeerLength = 1,
        )

        assertArrayEquals(NotiSyncNfcProtocol.statusConditionsNotSatisfied, exchange.process(transfer))
        assertArrayEquals(
            NotiSyncNfcProtocol.statusConditionsNotSatisfied,
            exchange.process(NotiSyncNfcProtocol.commitExchangeCommand),
        )
    }

    @Test
    fun oneKilobytePayloadsAreExchangedBidirectionallyAndCommittedTogether() {
        val hcePayload = binaryPayload(1_087, 0xA5)
        val readerPayload = binaryPayload(1_213, 0x3C)
        val result = exchangeBidirectionally(hcePayload, readerPayload)

        assertTrue(result.exchangeCount <= 5)
        assertTrue(result.sawUploadOnlyCommand)
        assertArrayEquals(hcePayload, result.downloadedByReader)
        assertArrayEquals(readerPayload, result.receivedByHce)
    }

    @Test
    fun readerCanFinishUploadingThenContinueDownloadingAlone() {
        val hcePayload = binaryPayload(1_200, 0x6D)
        val readerPayload = binaryPayload(1, 0xD6)
        val result = exchangeBidirectionally(hcePayload, readerPayload)

        assertTrue(result.sawDownloadOnlyCommand)
        assertArrayEquals(hcePayload, result.downloadedByReader)
        assertArrayEquals(readerPayload, result.receivedByHce)
    }

    @Test
    fun commitRejectsAnIncompleteReaderPayload() {
        val exchange = BidirectionalPayloadExchange(
            outgoingPayload = { binaryPayload(300, 0xB2) },
            onIncomingPayload = { error("must not publish a partial payload") },
        )
        val selection = startPairing(exchange)
        val reader = binaryPayload(300, 0xC3)
        val remainingHce = selection.payloadSize - selection.initialPayload.size

        val partial = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = reader,
            readerOffset = 0,
            readerLength = 20,
            requestedPeerOffset = selection.initialPayload.size,
            requestedPeerLength = remainingHce,
        )
        NotiSyncNfcProtocol.requireSuccessfulResponse(exchange.process(partial))

        assertArrayEquals(
            NotiSyncNfcProtocol.statusConditionsNotSatisfied,
            exchange.process(NotiSyncNfcProtocol.commitExchangeCommand),
        )
    }

    @Test
    fun commitRejectsUntilTheReaderHasDownloadedTheWholePeerPayload() {
        var published = false
        val exchange = BidirectionalPayloadExchange(
            outgoingPayload = { binaryPayload(600, 0xF6) },
            onIncomingPayload = { published = true },
        )
        val selection = startPairing(exchange)
        val reader = binaryPayload(20, 0x17)

        val uploadsReaderButLeavesPeerUnread = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = reader,
            readerOffset = 0,
            readerLength = reader.size,
            requestedPeerOffset = selection.initialPayload.size,
            requestedPeerLength = 0,
        )
        NotiSyncNfcProtocol.requireSuccessfulResponse(exchange.process(uploadsReaderButLeavesPeerUnread))

        assertArrayEquals(
            NotiSyncNfcProtocol.statusConditionsNotSatisfied,
            exchange.process(NotiSyncNfcProtocol.commitExchangeCommand),
        )
        assertTrue(!published)
    }

    @Test
    fun outOfOrderChunkIsRejectedWithoutAdvancingEitherDirection() {
        val exchange = BidirectionalPayloadExchange(
            outgoingPayload = { binaryPayload(400, 0xD4) },
            onIncomingPayload = {},
        )
        val selection = startPairing(exchange)
        val reader = binaryPayload(400, 0xE5)
        val invalid = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = reader,
            readerOffset = 1,
            readerLength = 10,
            requestedPeerOffset = selection.initialPayload.size,
            requestedPeerLength = 10,
        )
        assertArrayEquals(NotiSyncNfcProtocol.statusWrongOffset, exchange.process(invalid))

        val valid = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = reader,
            readerOffset = 0,
            readerLength = 10,
            requestedPeerOffset = selection.initialPayload.size,
            requestedPeerLength = 10,
        )
        assertEquals(10, NotiSyncNfcProtocol.requireSuccessfulResponse(exchange.process(valid)).size)
    }

    @Test
    fun zeroLengthPeerReadUsesCase3WithoutLe() {
        val command = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = "ABC".toByteArray(),
            readerOffset = 1,
            readerLength = 2,
            requestedPeerOffset = 0x0123,
            requestedPeerLength = 0,
        )

        assertEquals("80E0000009000300010123004243", command.toHex())
        assertArrayEquals("BC".toByteArray(), NotiSyncNfcProtocol.parseTransfer(command)?.readerChunk)
    }

    @Test
    fun positivePeerReadUsesCase4WithMatchingLeAfterTheLcData() {
        val command = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = "ABC".toByteArray(),
            readerOffset = 1,
            readerLength = 2,
            requestedPeerOffset = 0x0123,
            requestedPeerLength = 2,
        )

        assertEquals("80E000000900030001012302424302", command.toHex())
        val transfer = NotiSyncNfcProtocol.parseTransfer(command)
        assertEquals(2, transfer?.requestedPeerLength)
        assertArrayEquals("BC".toByteArray(), transfer?.readerChunk)
    }

    @Test
    fun positivePeerReadRejectsMissingOrMismatchedLe() {
        val command = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = "ABC".toByteArray(),
            readerOffset = 0,
            readerLength = 3,
            requestedPeerOffset = 0,
            requestedPeerLength = 2,
        )

        assertEquals(null, NotiSyncNfcProtocol.parseTransfer(command.copyOf(command.size - 1)))
        val mismatched = command.copyOf().apply { this[lastIndex] = 3 }
        assertEquals(null, NotiSyncNfcProtocol.parseTransfer(mismatched))
    }

    @Test
    fun zeroLengthPeerReadRejectsAnUnexpectedLe() {
        val case3 = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = "ABC".toByteArray(),
            readerOffset = 0,
            readerLength = 3,
            requestedPeerOffset = 0,
            requestedPeerLength = 0,
        )

        assertEquals(null, NotiSyncNfcProtocol.parseTransfer(case3 + byteArrayOf(0)))
    }

    @Test
    fun commitUsesCase1AndRejectsTheOldFiveByteEncoding() {
        assertEquals("80E00100", NotiSyncNfcProtocol.commitExchangeCommand.toHex())
        assertTrue(NotiSyncNfcProtocol.isCommitExchange(NotiSyncNfcProtocol.commitExchangeCommand))
        assertTrue(!NotiSyncNfcProtocol.isCommitExchange(byteArrayOf(
            0x80.toByte(), 0xE0.toByte(), 0x01, 0x00, 0x00,
        )))
    }

    @Test
    fun maximumChunksFitShortCase3AndCase4Commands() {
        val readerPayload = ByteArray(NotiSyncNfcProtocol.MAX_EXCHANGE_CHUNK_BYTES) { 'A'.code.toByte() }
        val case3 = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = readerPayload,
            readerOffset = 0,
            readerLength = readerPayload.size,
            requestedPeerOffset = 0,
            requestedPeerLength = 0,
        )
        val case4 = NotiSyncNfcProtocol.exchangePayloadCommand(
            readerPayload = readerPayload,
            readerOffset = 0,
            readerLength = readerPayload.size,
            requestedPeerOffset = 0,
            requestedPeerLength = NotiSyncNfcProtocol.MAX_EXCHANGE_CHUNK_BYTES,
        )

        assertEquals(260, case3.size)
        assertEquals(261, case4.size)
        assertEquals(NotiSyncNfcProtocol.MAX_EXCHANGE_CHUNK_BYTES, case4.last().toInt() and 0xFF)
    }

    @Test
    fun readerChunkCalculationNeverExceedsTheAdapterLimit() {
        assertEquals(248, NotiSyncNfcProtocol.readerChunkSize(261, 248))
        assertEquals(247, NotiSyncNfcProtocol.readerChunkSize(260, 248))
        assertEquals(100, NotiSyncNfcProtocol.readerChunkSize(261, 100))
        assertEquals(1, NotiSyncNfcProtocol.readerChunkSize(14, 248))
        assertTrue(runCatching { NotiSyncNfcProtocol.readerChunkSize(13, 248) }.isFailure)
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

        val encoded = PairingNfcPayloadCodec.encode(wirePayload)
        val operation = NotiSyncNfcProtocol.Operation.PAIRING
        val selection = NotiSyncNfcProtocol.parseOperationSelectionResponse(
            response = NotiSyncNfcProtocol.operationSelectionResponse(
                operation,
                version = 1,
                payload = PairingNfcPayloadCodec.decode(encoded),
            ),
            expectedOperation = operation,
            expectedVersion = 1,
        )

        assertArrayEquals(wirePayload, selection.initialPayload)
        assertTrue('=' !in encoded && '+' !in encoded && '/' !in encoded)
    }

    @Test
    fun textBoundaryAcceptsOnlyCanonicalUnpaddedBase64Url() {
        assertArrayEquals(byteArrayOf(0x01), PairingNfcPayloadCodec.decode("AQ"))
        assertTrue(runCatching { PairingNfcPayloadCodec.decode("AQ==") }.isFailure)
        assertTrue(runCatching { PairingNfcPayloadCodec.decode("") }.isFailure)
    }

    @Test
    fun wirePayloadSizeIsBoundedBeforeExchangeAllocation() {
        val oversized = ByteArray(NotiSyncNfcProtocol.MAX_PAYLOAD_BYTES + 1)

        assertTrue(runCatching {
            NotiSyncNfcProtocol.operationSelectionResponse(
                NotiSyncNfcProtocol.Operation.PAIRING,
                version = 1,
                payload = oversized,
            )
        }.isFailure)
        assertTrue(runCatching {
            NotiSyncNfcProtocol.exchangePayloadCommand(
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

    private fun startPairing(exchange: BidirectionalPayloadExchange): NotiSyncNfcProtocol.OperationSelection {
        val operation = NotiSyncNfcProtocol.Operation.PAIRING
        val discovery = NotiSyncNfcProtocol.parseApplicationSelectionResponse(
            NotiSyncNfcProtocol.applicationSelectionResponse()
        )
        val version = NotiSyncNfcProtocol.negotiateVersion(discovery, operation)
        val negotiation = requireNotNull(NotiSyncNfcProtocol.parseOperationNegotiation(
            NotiSyncNfcProtocol.negotiateOperationCommand(operation, version)
        ))
        return NotiSyncNfcProtocol.parseOperationSelectionResponse(
            response = exchange.start(requireNotNull(negotiation.operation), negotiation.version),
            expectedOperation = operation,
            expectedVersion = version,
        )
    }

    private fun exchangeBidirectionally(
        hcePayload: ByteArray,
        readerPayload: ByteArray,
    ): ExchangeResult {
        var receivedByHce: ByteArray? = null
        val exchange = BidirectionalPayloadExchange(
            outgoingPayload = { hcePayload },
            onIncomingPayload = { receivedByHce = it },
        )
        val selection = startPairing(exchange)
        val downloaded = ByteArray(selection.payloadSize)
        selection.initialPayload.copyInto(downloaded)
        var readerOffset = 0
        var hceOffset = selection.initialPayload.size
        var exchangeCount = 0
        var sawUploadOnlyCommand = false
        var sawDownloadOnlyCommand = false

        while (readerOffset < readerPayload.size || hceOffset < downloaded.size) {
            val readerLength = minOf(selection.maxChunkSize, readerPayload.size - readerOffset)
            val hceLength = minOf(selection.maxChunkSize, downloaded.size - hceOffset)
            sawUploadOnlyCommand = sawUploadOnlyCommand || readerLength > 0 && hceLength == 0
            sawDownloadOnlyCommand = sawDownloadOnlyCommand || readerLength == 0 && hceLength > 0
            val command = NotiSyncNfcProtocol.exchangePayloadCommand(
                readerPayload = readerPayload,
                readerOffset = readerOffset,
                readerLength = readerLength,
                requestedPeerOffset = hceOffset,
                requestedPeerLength = hceLength,
            )
            assertTrue(command.size <= 261)
            val hceChunk = NotiSyncNfcProtocol.requireSuccessfulResponse(exchange.process(command))
            assertEquals(hceLength, hceChunk.size)
            hceChunk.copyInto(downloaded, destinationOffset = hceOffset)
            readerOffset += readerLength
            hceOffset += hceLength
            exchangeCount += 1
        }

        assertArrayEquals(
            NotiSyncNfcProtocol.statusOk,
            exchange.process(NotiSyncNfcProtocol.commitExchangeCommand),
        )
        return ExchangeResult(
            downloadedByReader = downloaded,
            receivedByHce = requireNotNull(receivedByHce),
            exchangeCount = exchangeCount,
            sawUploadOnlyCommand = sawUploadOnlyCommand,
            sawDownloadOnlyCommand = sawDownloadOnlyCommand,
        )
    }

    private data class ExchangeResult(
        val downloadedByReader: ByteArray,
        val receivedByHce: ByteArray,
        val exchangeCount: Int,
        val sawUploadOnlyCommand: Boolean,
        val sawDownloadOnlyCommand: Boolean,
    )

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02X".format(it) }
}
