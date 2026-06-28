package net.extrawdw.apps.notisync.pairing

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import kotlin.math.min

object PairingNfcController {
    private const val NDEF_TAG_APPLICATION_AID = "D2760000850101"

    fun enable(context: Context, pairingUrl: String) {
        val cardEmulation = cardEmulation(context) ?: return PairingNfcSession.clear()
        val component =
            ComponentName(context.applicationContext, PairingHostApduService::class.java)
        if (!PairingNfcSession.setPairingUrl(pairingUrl)) return PairingNfcSession.clear()
        val registered = runCatching {
            cardEmulation.registerAidsForService(
                component,
                CardEmulation.CATEGORY_OTHER,
                listOf(NDEF_TAG_APPLICATION_AID),
            )
        }.getOrDefault(false)
        if (!registered) {
            PairingNfcSession.clear()
            return
        }
        context.findActivity()?.let { activity ->
            runCatching { cardEmulation.setPreferredService(activity, component) }
        }
    }

    fun disable(context: Context) {
        PairingNfcSession.clear()
        val cardEmulation = cardEmulation(context) ?: return
        context.findActivity()?.let { activity ->
            runCatching { cardEmulation.unsetPreferredService(activity) }
        }
        runCatching {
            cardEmulation.removeAidsForService(
                ComponentName(context.applicationContext, PairingHostApduService::class.java),
                CardEmulation.CATEGORY_OTHER,
            )
        }
    }

    private fun cardEmulation(context: Context): CardEmulation? {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return null
        val adapter = NfcAdapter.getDefaultAdapter(appContext) ?: return null
        return runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

class PairingHostApduService : HostApduService() {
    private var selectedFile: Int? = null

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.isSelectNdefApplication()) {
            selectedFile = null
            return if (PairingNfcSession.isActive) STATUS_OK else STATUS_FILE_NOT_FOUND
        }
        if (!PairingNfcSession.isActive) return STATUS_FILE_NOT_FOUND

        return when {
            commandApdu.isSelectFile(CAPABILITY_CONTAINER_FILE_ID) -> {
                selectedFile = CAPABILITY_CONTAINER_FILE_ID
                STATUS_OK
            }

            commandApdu.isSelectFile(NDEF_FILE_ID) -> {
                selectedFile = NDEF_FILE_ID
                STATUS_OK
            }

            commandApdu.isReadBinary() -> {
                val file = selectedFile?.let(PairingNfcSession::file)
                    ?: return STATUS_CONDITIONS_NOT_SATISFIED
                commandApdu.readBinary(file)
            }

            else -> STATUS_INS_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        selectedFile = null
    }

    private fun ByteArray.isSelectNdefApplication(): Boolean =
        size >= SELECT_NDEF_APPLICATION_PREFIX.size &&
                SELECT_NDEF_APPLICATION_PREFIX.indices.all { this[it] == SELECT_NDEF_APPLICATION_PREFIX[it] }

    private fun ByteArray.isSelectFile(fileId: Int): Boolean =
        size >= 7 &&
                u(0) == 0x00 &&
                u(1) == 0xA4 &&
                u(2) == 0x00 &&
                (u(3) == 0x0C || u(3) == 0x00) &&
                u(4) == 0x02 &&
                u(5) == (fileId shr 8) &&
                u(6) == (fileId and 0xFF)

    private fun ByteArray.isReadBinary(): Boolean =
        size >= 5 && u(0) == 0x00 && u(1) == 0xB0

    private fun ByteArray.readBinary(file: ByteArray): ByteArray {
        if (size < 5) return STATUS_WRONG_LENGTH
        val offset = (u(2) shl 8) or u(3)
        if (offset > file.size) return STATUS_WRONG_P1P2
        val requested = u(4).let { if (it == 0) 256 else it }
        val end = min(file.size, offset + requested)
        return file.copyOfRange(offset, end) + STATUS_OK
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

        private val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val STATUS_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val STATUS_CONDITIONS_NOT_SATISFIED = byteArrayOf(0x69, 0x85.toByte())
        private val STATUS_INS_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
        private val STATUS_WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        private val STATUS_WRONG_P1P2 = byteArrayOf(0x6B, 0x00)
    }
}

private object PairingNfcSession {
    @Volatile
    private var files: Files? = null

    val isActive: Boolean get() = files != null

    fun setPairingUrl(pairingUrl: String): Boolean = runCatching {
        val ndefBytes = NdefMessage(arrayOf(NdefRecord.createUri(pairingUrl))).toByteArray()
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
