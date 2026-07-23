package net.extrawdw.apps.notisync.screen

import android.media.MediaCodecList
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.ScreenMirrorCodec

/** Decoder support on the Android device displaying the remote screen. */
internal data class AndroidScreenDecoderSupport(
    val decodableCodecs: Set<ScreenMirrorCodec>,
    val hardwareCodecs: Set<ScreenMirrorCodec>,
    val hardwareDecoderNames: Map<ScreenMirrorCodec, String> = emptyMap(),
) {
    init {
        require(decodableCodecs.containsAll(hardwareCodecs)) {
            "hardware screen decoders must also be decodable"
        }
        require(hardwareCodecs.containsAll(hardwareDecoderNames.keys)) {
            "named hardware screen decoders must be reported as hardware codecs"
        }
    }

    /**
     * AV1/H.265 are selected only when the viewer has hardware decoding. H.264 is the compatibility
     * fallback and may use any Android decoder (in practice Android devices normally expose hardware AVC).
     */
    fun isEligible(codec: ScreenMirrorCodec): Boolean = when (codec) {
        ScreenMirrorCodec.AV1, ScreenMirrorCodec.H265 -> codec in hardwareCodecs
        ScreenMirrorCodec.H264 -> codec in decodableCodecs
    }

    fun hardwareDecoderName(codec: ScreenMirrorCodec): String? = hardwareDecoderNames[codec]
}

internal object AndroidScreenDecoderCapabilities {
    fun detect(): AndroidScreenDecoderSupport = runCatching {
        supportFrom(
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
                runCatching {
                    DecoderDescriptor(
                        name = info.name,
                        encoder = info.isEncoder,
                        hardwareAccelerated = info.isHardwareAccelerated,
                        supportedTypes = info.supportedTypes.toSet(),
                    )
                }.getOrNull()
            },
        )
    }.getOrDefault(AndroidScreenDecoderSupport(emptySet(), emptySet()))

    internal fun supportFrom(
        descriptors: Iterable<DecoderDescriptor>,
    ): AndroidScreenDecoderSupport {
        val decodable = mutableSetOf<ScreenMirrorCodec>()
        val hardware = mutableSetOf<ScreenMirrorCodec>()
        val hardwareNames = mutableMapOf<ScreenMirrorCodec, String>()
        descriptors.asSequence()
            .filterNot(DecoderDescriptor::encoder)
            .forEach { descriptor ->
                descriptor.supportedTypes.forEach { mime ->
                    codecForMime(mime)?.let { codec ->
                        decodable += codec
                        if (descriptor.hardwareAccelerated) {
                            hardware += codec
                            descriptor.name?.takeIf(String::isNotBlank)?.let { name ->
                                hardwareNames.putIfAbsent(codec, name)
                            }
                        }
                    }
                }
            }
        return AndroidScreenDecoderSupport(decodable, hardware, hardwareNames)
    }

    internal data class DecoderDescriptor(
        val name: String? = null,
        val encoder: Boolean,
        val hardwareAccelerated: Boolean,
        val supportedTypes: Set<String>,
    )

    private fun codecForMime(mime: String): ScreenMirrorCodec? = when (mime.lowercase()) {
        "video/avc" -> ScreenMirrorCodec.H264
        "video/hevc" -> ScreenMirrorCodec.H265
        "video/av01" -> ScreenMirrorCodec.AV1
        else -> null
    }
}

/** Codecs that both the source can hardware-encode and this Android viewer may decode. */
internal fun availableAndroidScreenCodecs(
    sourceCapabilities: Set<Capability>,
    decoderSupport: AndroidScreenDecoderSupport,
): Set<ScreenMirrorCodec> {
    if (!sourceCapabilities.containsAll(SCREEN_MIRROR_SOURCE_BASE_CAPABILITIES)) return emptySet()
    return ScreenMirrorCodec.entries.filterTo(mutableSetOf()) { codec ->
        decoderSupport.isEligible(codec) && codec.requiredEncoderCapability() in sourceCapabilities
    }
}

/**
 * Honors an available per-source preference, otherwise chooses AV1, H.265, then H.264. A stale preference
 * is deliberately not fatal: capability or codec-service changes fall back to the best current match.
 */
internal fun selectAndroidScreenCodec(
    sourceCapabilities: Set<Capability>,
    decoderSupport: AndroidScreenDecoderSupport,
    preferredCodec: ScreenMirrorCodec? = null,
): ScreenMirrorCodec? {
    val available = availableAndroidScreenCodecs(sourceCapabilities, decoderSupport)
    preferredCodec?.takeIf(available::contains)?.let { return it }
    return ANDROID_SCREEN_CODEC_PRIORITY.firstOrNull(available::contains)
}

internal val ANDROID_SCREEN_CODEC_PRIORITY = listOf(
    ScreenMirrorCodec.AV1,
    ScreenMirrorCodec.H265,
    ScreenMirrorCodec.H264,
)

private val SCREEN_MIRROR_SOURCE_BASE_CAPABILITIES = setOf(
    Capability.CAPABILITY_ROUTING_V1,
    Capability.SCREEN_MIRROR_SOURCE_V1,
    Capability.SCREEN_MIRROR_CONTROL_V1,
    Capability.SCREEN_MIRROR_CLIPBOARD_TEXT_V1,
)
