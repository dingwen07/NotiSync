package net.extrawdw.apps.notisync.nfc

import kotlin.math.min

/**
 * Versioned ISO-DEP transport for negotiated NotiSync operations with reciprocal binary payloads.
 *
 * Text transports use canonical unpadded Base64URL, but this custom APDU protocol carries the decoded bytes
 * directly. Selecting the AID discovers operation/version ranges without exposing operation data. The reader
 * then negotiates one operation before EXCHANGE_PAYLOAD commands upload and download its opaque payload.
 * ISO-DEP handles RF-frame fragmentation; this application chunking avoids extended-length APDUs.
 */
internal object NotiSyncNfcProtocol {
    private const val EXCHANGE_HEADER_BYTES = 7
    private const val EXCHANGE_DATA_OFFSET = 5 + EXCHANGE_HEADER_BYTES
    private const val DISCOVERY_HEADER_BYTES = 4
    private const val OPERATION_DESCRIPTOR_BYTES = 3
    private const val OPERATION_RESPONSE_METADATA_BYTES = 7

    const val APPLICATION_AID_HEX = "F04E6F746953796E63"
    const val MAX_PAYLOAD_BYTES = 16 * 1024

    /** Seven bytes of exchange metadata leave 248 bytes in a standard 255-byte APDU data field. */
    const val MAX_EXCHANGE_CHUNK_BYTES = 0xFF - EXCHANGE_HEADER_BYTES

    /** Case 4 fixed bytes: four-byte header, Lc, seven metadata bytes, and Le. */
    const val EXCHANGE_COMMAND_OVERHEAD = 6 + EXCHANGE_HEADER_BYTES

    private const val DISCOVERY_FORMAT_VERSION = 1
    private const val INS_SELECT = 0xA4
    private const val NOTISYNC_CLA = 0x80
    private const val INS_EXCHANGE_PAYLOAD = 0xE0
    private const val INS_NEGOTIATE_OPERATION = 0xE1
    private const val EXCHANGE_TRANSFER = 0x00
    private const val EXCHANGE_COMMIT = 0x01

    private val applicationAid = APPLICATION_AID_HEX.hexToBytes()
    private val responseMarker = byteArrayOf('N'.code.toByte(), 'S'.code.toByte())

    val selectApplicationCommand: ByteArray =
        byteArrayOf(0x00, INS_SELECT.toByte(), 0x04, 0x00, applicationAid.size.toByte()) +
            applicationAid + byteArrayOf(0x00)

    val commitExchangeCommand: ByteArray = byteArrayOf(
        NOTISYNC_CLA.toByte(),
        INS_EXCHANGE_PAYLOAD.toByte(),
        EXCHANGE_COMMIT.toByte(),
        0x00,
    )

    val statusOk = byteArrayOf(0x90.toByte(), 0x00)
    val statusFileNotFound = byteArrayOf(0x6A, 0x82.toByte())
    val statusWrongData = byteArrayOf(0x6A, 0x80.toByte())
    val statusFunctionNotSupported = byteArrayOf(0x6A, 0x81.toByte())
    val statusConditionsNotSatisfied = byteArrayOf(0x69, 0x85.toByte())
    val statusInstructionNotSupported = byteArrayOf(0x6D, 0x00)
    val statusWrongOffset = byteArrayOf(0x6B, 0x00)
    val statusMemoryFailure = byteArrayOf(0x65, 0x81.toByte())

    enum class Operation(val wireId: Int, val minVersion: Int, val maxVersion: Int) {
        PAIRING(wireId = 0x01, minVersion = 1, maxVersion = 1);

        fun supports(version: Int): Boolean = version in minVersion..maxVersion

        companion object {
            fun fromWireId(wireId: Int): Operation? = entries.firstOrNull { it.wireId == wireId }
        }
    }

    data class OperationSupport(
        val wireId: Int,
        val minVersion: Int,
        val maxVersion: Int,
    )

    data class Discovery(val operations: List<OperationSupport>)

    data class OperationNegotiation(val operation: Operation?, val wireId: Int, val version: Int)

    data class OperationSelection(
        val operation: Operation,
        val version: Int,
        val payloadSize: Int,
        val maxChunkSize: Int,
        val initialPayload: ByteArray,
    )

    data class Transfer(
        val readerPayloadSize: Int,
        val readerOffset: Int,
        val requestedPeerOffset: Int,
        val requestedPeerLength: Int,
        val readerChunk: ByteArray,
    )

    fun isSelectApplication(command: ByteArray): Boolean {
        if (command.size < 5 + applicationAid.size) return false
        if (command.u(0) != 0x00 || command.u(1) != INS_SELECT || command.u(2) != 0x04) return false
        if (command.u(3) != 0x00 && command.u(3) != 0x0C) return false
        if (command.u(4) != applicationAid.size) return false
        return applicationAid.indices.all { command[5 + it] == applicationAid[it] }
    }

    /** Generic application discovery returned by SELECT AID. It intentionally contains no operation data. */
    fun applicationSelectionResponse(): ByteArray {
        val operations = Operation.entries
        val data = buildList {
            add(responseMarker[0])
            add(responseMarker[1])
            add(DISCOVERY_FORMAT_VERSION.toByte())
            add(operations.size.toByte())
            operations.forEach { operation ->
                add(operation.wireId.toByte())
                add(operation.minVersion.toByte())
                add(operation.maxVersion.toByte())
            }
        }.toByteArray()
        return data + statusOk
    }

    fun parseApplicationSelectionResponse(response: ByteArray): Discovery {
        val data = requireSuccessfulResponse(response)
        require(data.size >= DISCOVERY_HEADER_BYTES &&
            data[0] == responseMarker[0] && data[1] == responseMarker[1]
        ) { "unsupported NotiSync NFC response" }
        require(data.u(2) == DISCOVERY_FORMAT_VERSION) {
            "unsupported NotiSync NFC discovery format"
        }
        val operationCount = data.u(3)
        require(data.size == DISCOVERY_HEADER_BYTES + operationCount * OPERATION_DESCRIPTOR_BYTES) {
            "invalid NotiSync NFC operation list"
        }
        val operations = List(operationCount) { index ->
            val offset = DISCOVERY_HEADER_BYTES + index * OPERATION_DESCRIPTOR_BYTES
            val wireId = data.u(offset)
            val minVersion = data.u(offset + 1)
            val maxVersion = data.u(offset + 2)
            require(wireId != 0 && minVersion != 0 && minVersion <= maxVersion) {
                "invalid NotiSync NFC operation descriptor"
            }
            OperationSupport(wireId, minVersion, maxVersion)
        }
        require(operations.map(OperationSupport::wireId).distinct().size == operations.size) {
            "duplicate NotiSync NFC operation"
        }
        return Discovery(operations)
    }

    /** Pick the highest mutually supported version for an operation advertised by the HCE peer. */
    fun negotiateVersion(discovery: Discovery, operation: Operation): Int {
        val remote = discovery.operations.singleOrNull { it.wireId == operation.wireId }
            ?: error("NFC peer does not support ${operation.name.lowercase()}")
        val minimum = maxOf(operation.minVersion, remote.minVersion)
        val maximum = minOf(operation.maxVersion, remote.maxVersion)
        require(minimum <= maximum) { "NFC peer has no compatible ${operation.name.lowercase()} version" }
        return maximum
    }

    /** Case 2 command: operation and version are P1/P2; Le=00 requests up to 256 response bytes. */
    fun negotiateOperationCommand(operation: Operation, version: Int): ByteArray {
        require(operation.supports(version))
        return byteArrayOf(
            NOTISYNC_CLA.toByte(),
            INS_NEGOTIATE_OPERATION.toByte(),
            operation.wireId.toByte(),
            version.toByte(),
            0x00,
        )
    }

    fun parseOperationNegotiation(command: ByteArray): OperationNegotiation? {
        if (command.size != 5 || command.u(0) != NOTISYNC_CLA ||
            command.u(1) != INS_NEGOTIATE_OPERATION || command.u(4) != 0x00
        ) return null
        val wireId = command.u(2)
        val version = command.u(3)
        if (wireId == 0 || version == 0) return null
        return OperationNegotiation(Operation.fromWireId(wireId), wireId, version)
    }

    fun isOperationNegotiationCommand(command: ByteArray): Boolean =
        command.size >= 2 && command.u(0) == NOTISYNC_CLA && command.u(1) == INS_NEGOTIATE_OPERATION

    fun operationSelectionResponse(operation: Operation, version: Int, payload: ByteArray): ByteArray {
        require(operation.supports(version))
        requireWirePayload(payload)
        val initial = payload.copyOf(min(payload.size, MAX_EXCHANGE_CHUNK_BYTES))
        return responseMarker + byteArrayOf(
            operation.wireId.toByte(),
            version.toByte(),
            (payload.size shr 8).toByte(),
            payload.size.toByte(),
            MAX_EXCHANGE_CHUNK_BYTES.toByte(),
        ) + initial + statusOk
    }

    fun parseOperationSelectionResponse(
        response: ByteArray,
        expectedOperation: Operation,
        expectedVersion: Int,
    ): OperationSelection {
        val data = requireSuccessfulResponse(response)
        require(data.size >= OPERATION_RESPONSE_METADATA_BYTES &&
            data[0] == responseMarker[0] && data[1] == responseMarker[1]
        ) { "unsupported NotiSync NFC response" }
        require(data.u(2) == expectedOperation.wireId && data.u(3) == expectedVersion) {
            "NFC peer selected a different operation or version"
        }
        val payloadSize = (data.u(4) shl 8) or data.u(5)
        require(payloadSize in 1..MAX_PAYLOAD_BYTES) { "invalid operation payload length" }
        val maxChunkSize = data.u(6)
        require(maxChunkSize in 1..MAX_EXCHANGE_CHUNK_BYTES) { "invalid NFC chunk size" }
        val initialPayload = data.copyOfRange(OPERATION_RESPONSE_METADATA_BYTES, data.size)
        require(initialPayload.size <= payloadSize && initialPayload.size <= maxChunkSize) {
            "invalid initial NFC payload chunk"
        }
        return OperationSelection(
            operation = expectedOperation,
            version = expectedVersion,
            payloadSize = payloadSize,
            maxChunkSize = maxChunkSize,
            initialPayload = initialPayload,
        )
    }

    fun exchangePayloadCommand(
        readerPayload: ByteArray,
        readerOffset: Int,
        readerLength: Int,
        requestedPeerOffset: Int,
        requestedPeerLength: Int,
    ): ByteArray {
        requireWirePayload(readerPayload)
        require(readerOffset in 0..readerPayload.size)
        require(readerLength in 0..MAX_EXCHANGE_CHUNK_BYTES)
        require(readerOffset + readerLength <= readerPayload.size)
        require(requestedPeerOffset in 0..MAX_PAYLOAD_BYTES)
        require(requestedPeerLength in 0..MAX_EXCHANGE_CHUNK_BYTES)
        val dataLength = EXCHANGE_HEADER_BYTES + readerLength
        val case3Command = byteArrayOf(
            NOTISYNC_CLA.toByte(),
            INS_EXCHANGE_PAYLOAD.toByte(),
            EXCHANGE_TRANSFER.toByte(),
            0x00,
            dataLength.toByte(),
            (readerPayload.size shr 8).toByte(),
            readerPayload.size.toByte(),
            (readerOffset shr 8).toByte(),
            readerOffset.toByte(),
            (requestedPeerOffset shr 8).toByte(),
            requestedPeerOffset.toByte(),
            requestedPeerLength.toByte(),
        ) + readerPayload.copyOfRange(readerOffset, readerOffset + readerLength)
        return if (requestedPeerLength > 0) {
            case3Command + byteArrayOf(requestedPeerLength.toByte())
        } else {
            case3Command
        }
    }

    fun parseTransfer(command: ByteArray): Transfer? {
        if (command.size < EXCHANGE_DATA_OFFSET) return null
        if (command.u(0) != NOTISYNC_CLA || command.u(1) != INS_EXCHANGE_PAYLOAD ||
            command.u(2) != EXCHANGE_TRANSFER || command.u(3) != 0x00
        ) return null
        val dataLength = command.u(4)
        if (dataLength !in EXCHANGE_HEADER_BYTES..0xFF) return null
        val dataEnd = 5 + dataLength
        val readerPayloadSize = (command.u(5) shl 8) or command.u(6)
        val readerOffset = (command.u(7) shl 8) or command.u(8)
        val requestedPeerOffset = (command.u(9) shl 8) or command.u(10)
        val requestedPeerLength = command.u(11)
        val expectedCommandSize = dataEnd + if (requestedPeerLength > 0) 1 else 0
        if (command.size != expectedCommandSize) return null
        if (requestedPeerLength > 0 && command.u(dataEnd) != requestedPeerLength) return null
        val readerChunk = command.copyOfRange(EXCHANGE_DATA_OFFSET, dataEnd)
        if (readerPayloadSize !in 1..MAX_PAYLOAD_BYTES ||
            readerChunk.size > MAX_EXCHANGE_CHUNK_BYTES ||
            requestedPeerLength !in 0..MAX_EXCHANGE_CHUNK_BYTES
        ) return null
        return Transfer(
            readerPayloadSize = readerPayloadSize,
            readerOffset = readerOffset,
            requestedPeerOffset = requestedPeerOffset,
            requestedPeerLength = requestedPeerLength,
            readerChunk = readerChunk,
        )
    }

    fun isCommitExchange(command: ByteArray): Boolean =
        command.size == 4 &&
            command.u(0) == NOTISYNC_CLA && command.u(1) == INS_EXCHANGE_PAYLOAD &&
            command.u(2) == EXCHANGE_COMMIT && command.u(3) == 0x00

    fun readerChunkSize(maxTransceiveLength: Int, peerMaxChunkSize: Int): Int {
        require(peerMaxChunkSize in 1..MAX_EXCHANGE_CHUNK_BYTES)
        val adapterChunkSize = maxTransceiveLength - EXCHANGE_COMMAND_OVERHEAD
        require(adapterChunkSize >= 1) { "NFC adapter APDU limit is too small" }
        return minOf(peerMaxChunkSize, MAX_EXCHANGE_CHUNK_BYTES, adapterChunkSize)
    }

    fun requireSuccessfulResponse(response: ByteArray): ByteArray {
        require(response.size >= 2 &&
            response[response.lastIndex - 1] == statusOk[0] && response.last() == statusOk[1]
        ) { "NFC peer rejected the payload exchange" }
        return response.copyOf(response.size - statusOk.size)
    }

    private fun requireWirePayload(wirePayload: ByteArray) {
        require(wirePayload.size in 1..MAX_PAYLOAD_BYTES) {
            "operation payload is too large for NFC"
        }
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

}

/** Stateful HCE side of one bidirectional payload exchange. */
internal class BidirectionalPayloadExchange(
    private val outgoingPayload: () -> ByteArray?,
    private val onIncomingPayload: (ByteArray) -> Unit,
) {
    private var selectedOutgoing: ByteArray? = null
    private var outgoingRead = 0
    private var incoming: ByteArray? = null
    private var incomingWritten = 0
    private var committed = false

    fun start(operation: NotiSyncNfcProtocol.Operation, version: Int): ByteArray {
        reset()
        val payload = outgoingPayload()?.takeIf {
            it.size in 1..NotiSyncNfcProtocol.MAX_PAYLOAD_BYTES
        } ?: return NotiSyncNfcProtocol.statusFileNotFound
        selectedOutgoing = payload
        outgoingRead = min(payload.size, NotiSyncNfcProtocol.MAX_EXCHANGE_CHUNK_BYTES)
        return NotiSyncNfcProtocol.operationSelectionResponse(operation, version, payload)
    }

    fun process(command: ByteArray): ByteArray = when {
        NotiSyncNfcProtocol.isCommitExchange(command) -> commit()
        else -> NotiSyncNfcProtocol.parseTransfer(command)?.let(::transfer)
            ?: NotiSyncNfcProtocol.statusInstructionNotSupported
    }

    fun reset() {
        selectedOutgoing = null
        outgoingRead = 0
        incoming = null
        incomingWritten = 0
        committed = false
    }

    private fun transfer(transfer: NotiSyncNfcProtocol.Transfer): ByteArray {
        val outgoing = selectedOutgoing ?: return NotiSyncNfcProtocol.statusConditionsNotSatisfied
        if (committed) return NotiSyncNfcProtocol.statusConditionsNotSatisfied

        val destination = incoming ?: ByteArray(transfer.readerPayloadSize).also { incoming = it }
        if (destination.size != transfer.readerPayloadSize ||
            transfer.readerOffset != incomingWritten ||
            transfer.readerOffset + transfer.readerChunk.size > destination.size
        ) return NotiSyncNfcProtocol.statusWrongOffset

        if (transfer.requestedPeerOffset != outgoingRead ||
            transfer.requestedPeerOffset + transfer.requestedPeerLength > outgoing.size
        ) return NotiSyncNfcProtocol.statusWrongOffset

        transfer.readerChunk.copyInto(destination, destinationOffset = transfer.readerOffset)
        incomingWritten += transfer.readerChunk.size

        val responseEnd = transfer.requestedPeerOffset + transfer.requestedPeerLength
        val response = outgoing.copyOfRange(transfer.requestedPeerOffset, responseEnd)
        outgoingRead = responseEnd
        return response + NotiSyncNfcProtocol.statusOk
    }

    private fun commit(): ByteArray {
        if (committed) return NotiSyncNfcProtocol.statusOk
        val outgoing = selectedOutgoing ?: return NotiSyncNfcProtocol.statusConditionsNotSatisfied
        val complete = incoming?.takeIf { incomingWritten == it.size }
            ?: return NotiSyncNfcProtocol.statusConditionsNotSatisfied
        if (outgoingRead != outgoing.size) return NotiSyncNfcProtocol.statusConditionsNotSatisfied
        return runCatching { onIncomingPayload(complete) }.fold(
            onSuccess = {
                committed = true
                incoming = null
                NotiSyncNfcProtocol.statusOk
            },
            onFailure = { NotiSyncNfcProtocol.statusMemoryFailure },
        )
    }
}
