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
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import kotlin.math.min

/** Foreground-only compatibility routing for NFC Forum Type 4 NDEF. */
internal object PairingNfcController {
    private const val NDEF_TAG_APPLICATION_AID = "D2760000850101"

    fun enableForegroundNdef(context: Context, pairingUrl: String) {
        val cardEmulation = cardEmulation(context) ?: return ForegroundNdefSession.clear()
        val component = serviceComponent(context)
        if (!ForegroundNdefSession.setPairingUrl(pairingUrl, context.applicationContext.packageName)) {
            return disableForegroundNdef(context)
        }
        // Dynamic registration replaces the static category group. Include the proprietary AID so custom
        // pairing remains routable while NDEF compatibility is temporarily added.
        val registered = runCatching {
            cardEmulation.registerAidsForService(
                component,
                CardEmulation.CATEGORY_OTHER,
                listOf(PairingNfcProtocol.APPLICATION_AID_HEX, NDEF_TAG_APPLICATION_AID),
            )
        }.getOrDefault(false)
        if (!registered) return disableForegroundNdef(context)
        context.findActivity()?.let { activity ->
            runCatching { cardEmulation.setPreferredService(activity, component) }
        }
    }

    /** Keep proprietary pairing HCE preferred while withholding the foreground-only NDEF application. */
    fun enableForegroundCustomAidOnly(context: Context) {
        ForegroundNdefSession.clear()
        val cardEmulation = cardEmulation(context) ?: return
        val component = serviceComponent(context)
        val registered = runCatching {
            cardEmulation.registerAidsForService(
                component,
                CardEmulation.CATEGORY_OTHER,
                listOf(PairingNfcProtocol.APPLICATION_AID_HEX),
            )
        }.getOrDefault(false)
        if (!registered) return disableForegroundNdef(context)
        context.findActivity()?.let { activity ->
            runCatching { cardEmulation.setPreferredService(activity, component) }
        }
    }

    /** Remove the dynamic group so Android restores the manifest's always-on proprietary AID. */
    fun disableForegroundNdef(context: Context) {
        ForegroundNdefSession.clear()
        val cardEmulation = cardEmulation(context) ?: return
        context.findActivity()?.let { activity ->
            runCatching { cardEmulation.unsetPreferredService(activity) }
        }
        runCatching {
            cardEmulation.removeAidsForService(serviceComponent(context), CardEmulation.CATEGORY_OTHER)
        }
    }

    /** Repairs foreground-only routing and installs the Observe Mode rendezvous filter. */
    fun restoreBackgroundRouting(context: Context) {
        val appContext = context.applicationContext
        disableForegroundNdef(appContext)
        registerPollingLoopFilter(appContext)
    }

    private fun registerPollingLoopFilter(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return

        // Android only auto-transacts when this service is preferred or no other service owns the same
        // filter. Installing NotiSync in both the primary user and a work/private profile would otherwise
        // make the shared marker ambiguous. The proprietary AID remains registered in every profile; only
        // the primary profile owns the automatic background rendezvous.
        val isProfile = runCatching {
            context.getSystemService(UserManager::class.java).isProfile
        }.getOrDefault(false)
        if (isProfile) return

        val cardEmulation = cardEmulation(context) ?: return
        val registered = runCatching {
            cardEmulation.registerPollingLoopFilterForService(
                serviceComponent(context),
                PairingNfcPolling.FILTER_HEX,
                true,
            )
        }.getOrDefault(false)
        if (!registered) Log.w(TAG, "Could not register the NFC pairing polling-loop filter")
    }

    private fun serviceComponent(context: Context) =
        ComponentName(context.applicationContext, PairingHostApduService::class.java)

    private fun cardEmulation(context: Context): CardEmulation? {
        val appContext = context.applicationContext
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            return null
        }
        val adapter = NfcAdapter.getDefaultAdapter(appContext) ?: return null
        return runCatching { CardEmulation.getInstance(adapter) }.getOrNull()
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private const val TAG = "PairingNfcController"
}

class PairingHostApduService : HostApduService() {
    private enum class SelectedApplication { PAIRING, NDEF }

    private var selectedApplication: SelectedApplication? = null
    private var selectedNdefFile: Int? = null
    private val pairingExchange by lazy {
        PairingPayloadExchange(
            outgoingPayload = PairingCardStore::current,
            onIncomingPayload = { PairingNfcInbox.offer(applicationContext, it) },
        )
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (PairingNfcProtocol.isSelectApplication(commandApdu)) {
            selectedApplication = SelectedApplication.PAIRING
            selectedNdefFile = null
            return pairingExchange.select()
        }
        if (commandApdu.isSelectNdefApplication()) {
            selectedApplication = SelectedApplication.NDEF
            selectedNdefFile = null
            pairingExchange.reset()
            return if (ForegroundNdefSession.isActive) {
                PairingNfcProtocol.statusOk
            } else {
                PairingNfcProtocol.statusFileNotFound
            }
        }

        return when (selectedApplication) {
            SelectedApplication.PAIRING -> pairingExchange.process(commandApdu)
            SelectedApplication.NDEF -> processNdefCommand(commandApdu)
            null -> PairingNfcProtocol.statusFileNotFound
        }
    }

    override fun onDeactivated(reason: Int) {
        selectedApplication = null
        selectedNdefFile = null
        pairingExchange.reset()
    }

    private fun processNdefCommand(commandApdu: ByteArray): ByteArray = when {
        !ForegroundNdefSession.isActive -> PairingNfcProtocol.statusFileNotFound

        commandApdu.isSelectFile(CAPABILITY_CONTAINER_FILE_ID) -> {
            selectedNdefFile = CAPABILITY_CONTAINER_FILE_ID
            PairingNfcProtocol.statusOk
        }

        commandApdu.isSelectFile(NDEF_FILE_ID) -> {
            selectedNdefFile = NDEF_FILE_ID
            PairingNfcProtocol.statusOk
        }

        commandApdu.isReadBinary() -> {
            val file = selectedNdefFile?.let(ForegroundNdefSession::file)
                ?: return PairingNfcProtocol.statusConditionsNotSatisfied
            commandApdu.readBinary(file)
        }

        else -> PairingNfcProtocol.statusInstructionNotSupported
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
        if (offset > file.size) return PairingNfcProtocol.statusWrongOffset
        val requested = u(4).let { if (it == 0) 256 else it }
        val end = min(file.size, offset + requested)
        return file.copyOfRange(offset, end) + PairingNfcProtocol.statusOk
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
private object ForegroundNdefSession {
    @Volatile
    private var files: Files? = null

    val isActive: Boolean get() = files != null

    fun setPairingUrl(pairingUrl: String, packageName: String): Boolean = runCatching {
        val ndefBytes = NdefMessage(
            arrayOf(
                NdefRecord.createUri(pairingUrl),
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
