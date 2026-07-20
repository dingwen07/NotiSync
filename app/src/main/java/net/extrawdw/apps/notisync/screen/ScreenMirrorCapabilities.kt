package net.extrawdw.apps.notisync.screen

import android.media.MediaCodecList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import net.extrawdw.apps.notisync.data.SettingsRepository
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

internal data class EncoderDescriptor(
    val encoder: Boolean,
    val hardwareAccelerated: Boolean,
    val supportedTypes: Set<String>,
)

/** Probes only hardware-backed encoders; screen sharing never silently falls back to a software encoder. */
object HardwareScreenEncoderProbe {
    fun probe(): Set<ScreenMirrorCodec> = runCatching {
        codecsFrom(
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.map { info ->
                EncoderDescriptor(
                    encoder = info.isEncoder,
                    hardwareAccelerated = info.isHardwareAccelerated,
                    supportedTypes = info.supportedTypes.map(String::lowercase).toSet(),
                )
            },
        )
    }.getOrDefault(emptySet())

    internal fun codecsFrom(descriptors: Iterable<EncoderDescriptor>): Set<ScreenMirrorCodec> = buildSet {
        descriptors.asSequence()
            .filter { it.encoder && it.hardwareAccelerated }
            .flatMap { it.supportedTypes.asSequence() }
            .forEach { mime ->
                when (mime.lowercase()) {
                    "video/avc" -> add(ScreenMirrorCodec.H264)
                    "video/hevc" -> add(ScreenMirrorCodec.H265)
                    "video/av01" -> add(ScreenMirrorCodec.AV1)
                }
            }
    }
}

internal fun screenMirrorCapabilitiesFor(
    enabled: Boolean,
    hardwareCodecs: Set<ScreenMirrorCodec>,
): List<Capability> {
    if (!enabled || hardwareCodecs.isEmpty()) return emptyList()

    return buildList {
        add(Capability.SCREEN_MIRROR_SOURCE_V1)
        add(Capability.SCREEN_MIRROR_CONTROL_V1)
        add(Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1)
        add(Capability.SCREEN_MIRROR_VIDEO_VISIBILITY_V1)
        hardwareCodecs.sortedBy { it.ordinal }
            .forEach { add(it.requiredEncoderCapability()) }
    }
}

/**
 * Sticky capability declaration used by both ClientCard and ProfileUpdate construction.
 *
 * The persisted master opt-in and the normal-process hardware codec registry describe whether this
 * installation supports screen sharing. Shizuku permission, its binder, privileged capture/input probes,
 * and the active network describe whether a particular request can start *right now*, so they must not make
 * an otherwise capable source disappear from peers. The privileged backend rechecks the selected encoder
 * and returns a request-time status if it cannot start.
 *
 * Hardware codecs are deliberately reprobed on every app-process start instead of persisting a last-known
 * positive result. This keeps an OTA or codec-service change from leaving stale routing authority behind,
 * while the immutable set below keeps the declaration sticky for the lifetime of the process.
 */
class ScreenMirrorCapabilityProvider(
    settings: SettingsRepository,
    authorizations: ScreenMirrorAuthorizationStore,
    scope: CoroutineScope,
    private val hardwareCodecs: Set<ScreenMirrorCodec> = HardwareScreenEncoderProbe.probe(),
) {
    private val _advertisedCapabilities = MutableStateFlow(emptyList<Capability>())
    val advertisedCapabilities: StateFlow<List<Capability>> = _advertisedCapabilities.asStateFlow()

    init {
        combine(
            settings.screenMirroringEnabled,
            authorizations.replayStateHealth,
            authorizations.authorizationStateHealth,
        ) { enabled, replayHealth, authorizationHealth ->
            screenMirrorCapabilitiesFor(
                enabled = enabled &&
                    replayHealth == ScreenReplayStateHealth.HEALTHY &&
                    authorizationHealth == ScreenAuthorizationStateHealth.HEALTHY,
                hardwareCodecs = hardwareCodecs,
            )
        }.onEach { _advertisedCapabilities.value = it }.launchIn(scope)
    }

    fun supports(codec: ScreenMirrorCodec): Boolean =
        codec in hardwareCodecs && codec.requiredEncoderCapability() in advertisedCapabilities.value

    fun probedCodecs(): Set<ScreenMirrorCodec> = hardwareCodecs
}
