package net.extrawdw.notisync.screen.desktop

import net.extrawdw.notisync.protocol.ScreenMirrorCodec
import net.extrawdw.notisync.protocol.ScreenMirrorQualityLimits

internal sealed interface ScreenInvocation {
    data object Devices : ScreenInvocation
    data class Connect(val options: ConnectOptions) : ScreenInvocation
    data object Help : ScreenInvocation
}

internal data class ConnectOptions(
    val deviceId: String? = null,
    val codec: ScreenMirrorCodec? = null,
    val maxDimension: Int = 1_920,
    val maxFps: Int = 60,
    val bitrateBps: Int = 8_000_000,
    val control: Boolean = true,
    val clipboard: Boolean = true,
)

internal object NSScreenCli {
    fun parse(arguments: Array<String>): ScreenInvocation {
        if (arguments.isEmpty() || arguments[0] in setOf("help", "--help", "-h")) return ScreenInvocation.Help
        return when (arguments[0]) {
            "devices" -> {
                if (arguments.size != 1) throw ScreenCliException("devices takes no arguments")
                ScreenInvocation.Devices
            }
            "connect" -> ScreenInvocation.Connect(parseConnect(arguments.drop(1)))
            else -> throw ScreenCliException("unknown command: ${arguments[0]}")
        }
    }

    private fun parseConnect(arguments: List<String>): ConnectOptions {
        var deviceId: String? = null
        var codec: ScreenMirrorCodec? = null
        var maxDimension = 1_920
        var maxFps = 60
        var bitrate = 8_000_000
        var control = true
        var clipboard = true
        var index = 0
        while (index < arguments.size) {
            when (val argument = arguments[index]) {
                "--codec" -> {
                    codec = when (val value = arguments.valueAfter(index, argument).lowercase()) {
                        "h264" -> ScreenMirrorCodec.H264
                        "h265" -> ScreenMirrorCodec.H265
                        "av1" -> ScreenMirrorCodec.AV1
                        else -> throw ScreenCliException("unsupported codec: $value")
                    }
                    index += 2
                }
                "--max-dimension" -> {
                    maxDimension = arguments.positiveIntAfter(index, argument)
                    index += 2
                }
                "--max-fps" -> {
                    maxFps = arguments.positiveIntAfter(index, argument)
                    index += 2
                }
                "--bitrate" -> {
                    bitrate = arguments.positiveIntAfter(index, argument)
                    index += 2
                }
                "--no-control" -> {
                    control = false
                    index++
                }
                "--no-clipboard" -> {
                    clipboard = false
                    index++
                }
                else -> {
                    if (argument.startsWith('-')) throw ScreenCliException("unknown option: $argument")
                    if (deviceId != null) throw ScreenCliException("connect accepts at most one DEVICE_ID")
                    deviceId = argument.takeIf(String::isNotBlank)
                        ?: throw ScreenCliException("DEVICE_ID must not be blank")
                    index++
                }
            }
        }
        if (maxDimension !in ScreenMirrorQualityLimits.MIN_DIMENSION..ScreenMirrorQualityLimits.MAX_DIMENSION) {
            throw ScreenCliException(
                "--max-dimension must be ${ScreenMirrorQualityLimits.MIN_DIMENSION}..${ScreenMirrorQualityLimits.MAX_DIMENSION}",
            )
        }
        if (maxFps !in ScreenMirrorQualityLimits.MIN_FPS..ScreenMirrorQualityLimits.MAX_FPS) {
            throw ScreenCliException(
                "--max-fps must be ${ScreenMirrorQualityLimits.MIN_FPS}..${ScreenMirrorQualityLimits.MAX_FPS}",
            )
        }
        if (bitrate !in ScreenMirrorQualityLimits.MIN_BITRATE_BPS..ScreenMirrorQualityLimits.MAX_BITRATE_BPS) {
            throw ScreenCliException(
                "--bitrate must be ${ScreenMirrorQualityLimits.MIN_BITRATE_BPS}..${ScreenMirrorQualityLimits.MAX_BITRATE_BPS}",
            )
        }
        if (!control && clipboard) {
            throw ScreenCliException("clipboard requires the control channel; add --no-clipboard")
        }
        return ConnectOptions(deviceId, codec, maxDimension, maxFps, bitrate, control, clipboard)
    }

    fun usage(): String = """
        Usage:
          nsscreen devices
          nsscreen connect [DEVICE_ID] [options]

        Options:
          --codec h264|h265|av1  Select an advertised hardware encoder (default: h264)
          --max-dimension N      Maximum encoded width or height (default: 1920)
          --max-fps N            Maximum frame rate (default: 60)
          --bitrate BPS          Video bitrate in bits/second (default: 8000000)
          --no-control           View only
          --no-clipboard         Disable text clipboard synchronization
    """.trimIndent()

    private fun List<String>.valueAfter(index: Int, option: String): String =
        getOrNull(index + 1) ?: throw ScreenCliException("$option requires a value")

    private fun List<String>.positiveIntAfter(index: Int, option: String): Int =
        valueAfter(index, option).toIntOrNull()?.takeIf { it > 0 }
            ?: throw ScreenCliException("$option requires a positive integer")
}

internal class ScreenCliException(message: String) : IllegalArgumentException(message)
