package net.extrawdw.apps.notisync.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import kotlin.math.min
import net.extrawdw.apps.notisync.pairing.PairingCardStore
import net.extrawdw.apps.notisync.pairing.PairingNfcInbox

/** Always-on HCE dispatcher for the generic NotiSync AID and foreground Type 4 NDEF compatibility. */
class NotiSyncHostApduService : HostApduService() {
    private enum class SelectedApplication { NOTISYNC, NDEF }

    private var selectedApplication: SelectedApplication? = null
    private var selectedNdefFile: Int? = null
    private val pairingExchange by lazy {
        BidirectionalPayloadExchange(
            outgoingPayload = PairingCardStore::currentWirePayload,
            onIncomingPayload = { PairingNfcInbox.offer(applicationContext, it) },
        )
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (NotiSyncNfcProtocol.isSelectApplication(commandApdu)) {
            selectedApplication = SelectedApplication.NOTISYNC
            selectedNdefFile = null
            pairingExchange.reset()
            return NotiSyncNfcProtocol.applicationSelectionResponse()
        }
        if (commandApdu.isSelectNdefApplication()) {
            selectedApplication = SelectedApplication.NDEF
            selectedNdefFile = null
            pairingExchange.reset()
            return if (ForegroundNdefSession.isActive) {
                NotiSyncNfcProtocol.statusOk
            } else {
                NotiSyncNfcProtocol.statusFileNotFound
            }
        }

        return when (selectedApplication) {
            SelectedApplication.NOTISYNC -> processNotiSyncCommand(commandApdu)
            SelectedApplication.NDEF -> processNdefCommand(commandApdu)
            null -> NotiSyncNfcProtocol.statusFileNotFound
        }
    }

    override fun onDeactivated(reason: Int) {
        selectedApplication = null
        selectedNdefFile = null
        pairingExchange.reset()
    }

    private fun processNotiSyncCommand(commandApdu: ByteArray): ByteArray {
        val negotiation = NotiSyncNfcProtocol.parseOperationNegotiation(commandApdu)
        if (negotiation != null) {
            pairingExchange.reset()
            val operation = negotiation.operation
                ?: return NotiSyncNfcProtocol.statusFunctionNotSupported
            if (!operation.supports(negotiation.version)) return NotiSyncNfcProtocol.statusWrongData
            return when (operation) {
                NotiSyncNfcProtocol.Operation.PAIRING ->
                    pairingExchange.start(operation, negotiation.version)
            }
        }
        if (NotiSyncNfcProtocol.isOperationNegotiationCommand(commandApdu)) {
            pairingExchange.reset()
            return NotiSyncNfcProtocol.statusWrongData
        }
        return pairingExchange.process(commandApdu)
    }

    private fun processNdefCommand(commandApdu: ByteArray): ByteArray = when {
        !ForegroundNdefSession.isActive -> NotiSyncNfcProtocol.statusFileNotFound

        commandApdu.isSelectFile(CAPABILITY_CONTAINER_FILE_ID) -> {
            selectedNdefFile = CAPABILITY_CONTAINER_FILE_ID
            NotiSyncNfcProtocol.statusOk
        }

        commandApdu.isSelectFile(NDEF_FILE_ID) -> {
            selectedNdefFile = NDEF_FILE_ID
            NotiSyncNfcProtocol.statusOk
        }

        commandApdu.isReadBinary() -> {
            val file = selectedNdefFile?.let(ForegroundNdefSession::file)
                ?: return NotiSyncNfcProtocol.statusConditionsNotSatisfied
            commandApdu.readBinary(file)
        }

        else -> NotiSyncNfcProtocol.statusInstructionNotSupported
    }

    private fun ByteArray.isSelectNdefApplication(): Boolean =
        size >= SELECT_NDEF_APPLICATION_PREFIX.size &&
            SELECT_NDEF_APPLICATION_PREFIX.indices.all { this[it] == SELECT_NDEF_APPLICATION_PREFIX[it] }

    private fun ByteArray.isSelectFile(fileId: Int): Boolean =
        size >= 7 &&
            u(0) == 0x00 && u(1) == 0xA4 && u(2) == 0x00 &&
            (u(3) == 0x0C || u(3) == 0x00) && u(4) == 0x02 &&
            u(5) == (fileId shr 8) && u(6) == (fileId and 0xFF)

    private fun ByteArray.isReadBinary(): Boolean =
        size == 5 && u(0) == 0x00 && u(1) == 0xB0

    private fun ByteArray.readBinary(file: ByteArray): ByteArray {
        val offset = (u(2) shl 8) or u(3)
        if (offset > file.size) return NotiSyncNfcProtocol.statusWrongOffset
        val requested = u(4).let { if (it == 0) 256 else it }
        val end = min(file.size, offset + requested)
        return file.copyOfRange(offset, end) + NotiSyncNfcProtocol.statusOk
    }

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    private companion object {
        private const val CAPABILITY_CONTAINER_FILE_ID = 0xE103
        private const val NDEF_FILE_ID = 0xE104

        private val SELECT_NDEF_APPLICATION_PREFIX = byteArrayOf(
            0x00,
            0xA4.toByte(),
            0x04,
            0x00,
            0x07,
            0xD2.toByte(),
            0x76,
            0x00,
            0x00,
            0x85.toByte(),
            0x01,
            0x01,
        )
    }
}

/** Process-local Type 4 files; intentionally unavailable to a cold-started/background service. */
internal object ForegroundNdefSession {
    @Volatile
    private var files: Files? = null

    val isActive: Boolean get() = files != null

    fun setUrl(url: String, packageName: String): Boolean = runCatching {
        val ndefBytes = NdefMessage(
            arrayOf(
                NdefRecord.createUri(url),
                NdefRecord.createApplicationRecord(packageName),
            )
        ).toByteArray()
        require(ndefBytes.size <= MAX_NDEF_PAYLOAD_BYTES)
        val ndefFile = ByteArray(ndefBytes.size + NLEN_SIZE)
        ndefFile[0] = (ndefBytes.size shr 8).toByte()
        ndefFile[1] = ndefBytes.size.toByte()
        ndefBytes.copyInto(ndefFile, destinationOffset = NLEN_SIZE)
        files = Files(
            capabilityContainer = capabilityContainer(ndefFile.size),
            ndef = ndefFile,
        )
        true
    }.getOrDefault(false)

    fun clear() {
        files = null
    }

    fun file(fileId: Int): ByteArray? = when (fileId) {
        CAPABILITY_CONTAINER_FILE_ID -> files?.capabilityContainer
        NDEF_FILE_ID -> files?.ndef
        else -> null
    }

    private fun capabilityContainer(ndefFileSize: Int): ByteArray {
        val advertisedSize = ndefFileSize.coerceAtLeast(0x00FF)
        return byteArrayOf(
            0x00,
            0x0F,
            0x20,
            0x00,
            0xFF.toByte(),
            0x00,
            0xFF.toByte(),
            0x04,
            0x06,
            (NDEF_FILE_ID shr 8).toByte(),
            NDEF_FILE_ID.toByte(),
            (advertisedSize shr 8).toByte(),
            advertisedSize.toByte(),
            0x00,
            0xFF.toByte(),
        )
    }

    private data class Files(val capabilityContainer: ByteArray, val ndef: ByteArray)

    private const val CAPABILITY_CONTAINER_FILE_ID = 0xE103
    private const val NDEF_FILE_ID = 0xE104
    private const val NLEN_SIZE = 2
    private const val MAX_NDEF_PAYLOAD_BYTES = 0xFFFF - NLEN_SIZE
}
