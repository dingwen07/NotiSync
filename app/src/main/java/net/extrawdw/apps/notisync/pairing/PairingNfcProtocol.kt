package net.extrawdw.apps.notisync.pairing

import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * Versioned ISO-DEP transport for an opaque, reciprocal pairing payload exchange.
 *
 * Both sides already carry about 1 KiB. Every EXCHANGE_PAYLOAD command therefore uses a short APDU whose
 * request uploads the reader's next chunk while its response downloads the HCE peer's next chunk. ISO-DEP
 * handles RF-frame fragmentation; this application chunking avoids requiring extended-length APDUs.
 */
internal object PairingNfcProtocol {
    private const val EXCHANGE_HEADER_BYTES = 7

    const val APPLICATION_AID_HEX = "F04E6F746953796E63"
    const val MAX_PAIRING_PAYLOAD_BYTES = 16 * 1024

    /** Seven bytes of exchange metadata leave 248 bytes in a standard 255-byte APDU data field. */
    const val MAX_EXCHANGE_CHUNK_BYTES = 0xFF - EXCHANGE_HEADER_BYTES
    const val EXCHANGE_COMMAND_OVERHEAD = 5 + EXCHANGE_HEADER_BYTES

    private const val VERSION = 1
    private const val INS_SELECT = 0xA4
    private const val NOTISYNC_CLA = 0x80
    private const val INS_EXCHANGE_PAYLOAD = 0xE0
    private const val EXCHANGE_TRANSFER = 0x00
    private const val EXCHANGE_COMMIT = 0x01
    private const val SELECT_METADATA_BYTES = 6

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
        0x00,
    )

    val statusOk = byteArrayOf(0x90.toByte(), 0x00)
    val statusFileNotFound = byteArrayOf(0x6A, 0x82.toByte())
    val statusWrongData = byteArrayOf(0x6A, 0x80.toByte())
    val statusConditionsNotSatisfied = byteArrayOf(0x69, 0x85.toByte())
    val statusInstructionNotSupported = byteArrayOf(0x6D, 0x00)
    val statusWrongOffset = byteArrayOf(0x6B, 0x00)
    val statusMemoryFailure = byteArrayOf(0x65, 0x81.toByte())

    data class Selection(
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

    fun selectResponse(payload: ByteArray): ByteArray {
        require(payload.size in 1..MAX_PAIRING_PAYLOAD_BYTES)
        val initial = payload.copyOf(min(payload.size, MAX_EXCHANGE_CHUNK_BYTES))
        return responseMarker + byteArrayOf(
            VERSION.toByte(),
            (payload.size shr 8).toByte(),
            payload.size.toByte(),
            MAX_EXCHANGE_CHUNK_BYTES.toByte(),
        ) + initial + statusOk
    }

    fun parseSelectResponse(response: ByteArray): Selection {
        val data = requireSuccessfulResponse(response)
        require(data.size >= SELECT_METADATA_BYTES &&
            data[0] == responseMarker[0] && data[1] == responseMarker[1]
        ) { "unsupported NotiSync NFC response" }
        require(data.u(2) == VERSION) { "unsupported NotiSync NFC protocol version" }
        val payloadSize = (data.u(3) shl 8) or data.u(4)
        require(payloadSize in 1..MAX_PAIRING_PAYLOAD_BYTES) { "invalid pairing payload length" }
        val maxChunkSize = data.u(5)
        require(maxChunkSize in 1..MAX_EXCHANGE_CHUNK_BYTES) { "invalid NFC chunk size" }
        val initialPayload = data.copyOfRange(SELECT_METADATA_BYTES, data.size)
        require(initialPayload.size <= payloadSize && initialPayload.size <= maxChunkSize) {
            "invalid initial NFC payload chunk"
        }
        return Selection(payloadSize, maxChunkSize, initialPayload)
    }

    fun exchangePayloadCommand(
        readerPayload: ByteArray,
        readerOffset: Int,
        readerLength: Int,
        requestedPeerOffset: Int,
        requestedPeerLength: Int,
    ): ByteArray {
        require(readerPayload.size in 1..MAX_PAIRING_PAYLOAD_BYTES)
        require(readerOffset in 0..readerPayload.size)
        require(readerLength in 0..MAX_EXCHANGE_CHUNK_BYTES)
        require(readerOffset + readerLength <= readerPayload.size)
        require(requestedPeerOffset in 0..MAX_PAIRING_PAYLOAD_BYTES)
        require(requestedPeerLength in 0..MAX_EXCHANGE_CHUNK_BYTES)
        val dataLength = EXCHANGE_HEADER_BYTES + readerLength
        return byteArrayOf(
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
    }

    fun parseTransfer(command: ByteArray): Transfer? {
        if (command.size < EXCHANGE_COMMAND_OVERHEAD) return null
        if (command.u(0) != NOTISYNC_CLA || command.u(1) != INS_EXCHANGE_PAYLOAD ||
            command.u(2) != EXCHANGE_TRANSFER || command.u(3) != 0x00
        ) return null
        val dataLength = command.u(4)
        if (dataLength !in EXCHANGE_HEADER_BYTES..0xFF || command.size != 5 + dataLength) return null
        val readerPayloadSize = (command.u(5) shl 8) or command.u(6)
        val readerOffset = (command.u(7) shl 8) or command.u(8)
        val requestedPeerOffset = (command.u(9) shl 8) or command.u(10)
        val requestedPeerLength = command.u(11)
        val readerChunk = command.copyOfRange(EXCHANGE_COMMAND_OVERHEAD, command.size)
        if (readerPayloadSize !in 1..MAX_PAIRING_PAYLOAD_BYTES ||
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
        command.size == 5 &&
            command.u(0) == NOTISYNC_CLA && command.u(1) == INS_EXCHANGE_PAYLOAD &&
            command.u(2) == EXCHANGE_COMMIT && command.u(3) == 0x00 && command.u(4) == 0x00

    fun requireSuccessfulResponse(response: ByteArray): ByteArray {
        require(response.size >= 2 &&
            response[response.lastIndex - 1] == statusOk[0] && response.last() == statusOk[1]
        ) { "NFC peer rejected the pairing exchange" }
        return response.copyOf(response.size - statusOk.size)
    }

    fun payloadBytes(payload: String): ByteArray {
        val bytes = payload.toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size in 1..MAX_PAIRING_PAYLOAD_BYTES) { "pairing payload is too large for NFC" }
        require(bytes.all(::isBase64UrlByte)) { "pairing payload is not base64url" }
        return bytes
    }

    fun payloadString(payload: ByteArray): String {
        require(payload.size in 1..MAX_PAIRING_PAYLOAD_BYTES)
        require(payload.all(::isBase64UrlByte)) { "pairing payload is not base64url" }
        return String(payload, StandardCharsets.US_ASCII)
    }

    private fun isBase64UrlByte(value: Byte): Boolean {
        val char = value.toInt().toChar()
        return char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_'
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF
}

/** Stateful HCE side of one bidirectional payload exchange. */
internal class PairingPayloadExchange(
    private val outgoingPayload: () -> String?,
    private val onIncomingPayload: (String) -> Unit,
) {
    private var selectedOutgoing: ByteArray? = null
    private var outgoingRead = 0
    private var incoming: ByteArray? = null
    private var incomingWritten = 0
    private var committed = false

    fun select(): ByteArray {
        reset()
        val payload = outgoingPayload()?.let {
            runCatching { PairingNfcProtocol.payloadBytes(it) }.getOrNull()
        } ?: return PairingNfcProtocol.statusFileNotFound
        selectedOutgoing = payload
        outgoingRead = min(payload.size, PairingNfcProtocol.MAX_EXCHANGE_CHUNK_BYTES)
        return PairingNfcProtocol.selectResponse(payload)
    }

    fun process(command: ByteArray): ByteArray = when {
        PairingNfcProtocol.isCommitExchange(command) -> commit()
        else -> PairingNfcProtocol.parseTransfer(command)?.let(::transfer)
            ?: PairingNfcProtocol.statusInstructionNotSupported
    }

    fun reset() {
        selectedOutgoing = null
        outgoingRead = 0
        incoming = null
        incomingWritten = 0
        committed = false
    }

    private fun transfer(transfer: PairingNfcProtocol.Transfer): ByteArray {
        val outgoing = selectedOutgoing ?: return PairingNfcProtocol.statusConditionsNotSatisfied
        if (committed) return PairingNfcProtocol.statusConditionsNotSatisfied

        val destination = incoming ?: ByteArray(transfer.readerPayloadSize).also { incoming = it }
        if (destination.size != transfer.readerPayloadSize ||
            transfer.readerOffset != incomingWritten ||
            transfer.readerOffset + transfer.readerChunk.size > destination.size
        ) return PairingNfcProtocol.statusWrongOffset

        if (transfer.requestedPeerOffset != outgoingRead ||
            transfer.requestedPeerOffset + transfer.requestedPeerLength > outgoing.size
        ) return PairingNfcProtocol.statusWrongOffset

        transfer.readerChunk.copyInto(destination, destinationOffset = transfer.readerOffset)
        incomingWritten += transfer.readerChunk.size

        val responseEnd = transfer.requestedPeerOffset + transfer.requestedPeerLength
        val response = outgoing.copyOfRange(transfer.requestedPeerOffset, responseEnd)
        outgoingRead = responseEnd
        return response + PairingNfcProtocol.statusOk
    }

    private fun commit(): ByteArray {
        if (committed) return PairingNfcProtocol.statusOk
        val outgoing = selectedOutgoing ?: return PairingNfcProtocol.statusConditionsNotSatisfied
        val complete = incoming?.takeIf { incomingWritten == it.size }
            ?: return PairingNfcProtocol.statusConditionsNotSatisfied
        if (outgoingRead != outgoing.size) return PairingNfcProtocol.statusConditionsNotSatisfied
        val payload = runCatching { PairingNfcProtocol.payloadString(complete) }.getOrNull()
            ?: return PairingNfcProtocol.statusWrongData
        return runCatching { onIncomingPayload(payload) }.fold(
            onSuccess = {
                committed = true
                incoming = null
                PairingNfcProtocol.statusOk
            },
            onFailure = { PairingNfcProtocol.statusMemoryFailure },
        )
    }
}
