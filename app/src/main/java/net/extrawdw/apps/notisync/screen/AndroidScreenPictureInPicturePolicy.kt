package net.extrawdw.apps.notisync.screen

import android.util.Rational
import net.extrawdw.notisync.protocol.ClientId

/**
 * Platform-independent inputs which decide whether the Android screen viewer may auto-enter PiP.
 *
 * [aspectRatioParts] is kept as integers so the policy remains covered by ordinary JVM tests. The
 * framework [Rational] is created only when the Activity applies its picture-in-picture params.
 */
internal data class AndroidScreenPictureInPicturePolicy(
    val supported: Boolean,
    val eligible: Boolean,
    val aspectRatioParts: AndroidScreenPictureInPictureAspectRatio?,
) {
    fun rationalAspectRatio(): Rational? = aspectRatioParts?.let {
        Rational(it.numerator, it.denominator)
    }
}

internal data class AndroidScreenPictureInPictureAspectRatio(
    val numerator: Int,
    val denominator: Int,
) {
    init {
        require(numerator > 0 && denominator > 0) { "PiP aspect-ratio terms must be positive" }
    }
}

/**
 * Returns a PiP policy for the latest service/viewer state.
 *
 * Android accepts PiP ratios from 1:2.39 through 2.39:1 inclusive. Keeping the exact reduced
 * source ratio inside that interval avoids unnecessary scaling; unusually tall or wide displays
 * are clamped to the nearest framework limit.
 */
internal fun androidScreenPictureInPicturePolicy(
    supported: Boolean,
    connected: Boolean,
    sourceWidth: Int?,
    sourceHeight: Int?,
): AndroidScreenPictureInPicturePolicy {
    val ratio = positivePictureInPictureAspectRatio(sourceWidth, sourceHeight)
    return AndroidScreenPictureInPicturePolicy(
        supported = supported,
        eligible = supported && connected && ratio != null,
        aspectRatioParts = ratio,
    )
}

/**
 * A terminal host snapshot may close a background/PiP renderer only after that Activity observed
 * the same live attempt. This prevents a newly opened viewer from consuming a stale terminal state
 * left by an older attempt for the same source.
 */
internal fun shouldFinishTerminatedScreenViewer(
    viewerSourceId: ClientId,
    observedAttemptId: String?,
    hostState: AndroidScreenHostState,
    inPictureInPicture: Boolean,
    renderingAllowed: Boolean,
): Boolean =
    observedAttemptId != null &&
        hostState.attemptId == observedAttemptId &&
        hostState.sourceId == viewerSourceId &&
        hostState.phase in TERMINAL_SCREEN_HOST_PHASES &&
        (inPictureInPicture || !renderingAllowed)

private fun positivePictureInPictureAspectRatio(
    sourceWidth: Int?,
    sourceHeight: Int?,
): AndroidScreenPictureInPictureAspectRatio? {
    val width = sourceWidth?.takeIf { it > 0 } ?: return null
    val height = sourceHeight?.takeIf { it > 0 } ?: return null

    // Compare using Long products so even hostile Int-sized dimensions cannot overflow.
    if (width.toLong() * PIP_LIMIT_DENOMINATOR > height.toLong() * PIP_LIMIT_NUMERATOR) {
        return AndroidScreenPictureInPictureAspectRatio(
            PIP_LIMIT_NUMERATOR,
            PIP_LIMIT_DENOMINATOR,
        )
    }
    if (height.toLong() * PIP_LIMIT_DENOMINATOR > width.toLong() * PIP_LIMIT_NUMERATOR) {
        return AndroidScreenPictureInPictureAspectRatio(
            PIP_LIMIT_DENOMINATOR,
            PIP_LIMIT_NUMERATOR,
        )
    }

    val divisor = greatestCommonDivisor(width, height)
    return AndroidScreenPictureInPictureAspectRatio(width / divisor, height / divisor)
}

private tailrec fun greatestCommonDivisor(left: Int, right: Int): Int =
    if (right == 0) left else greatestCommonDivisor(right, left % right)

private const val PIP_LIMIT_NUMERATOR = 239
private const val PIP_LIMIT_DENOMINATOR = 100

private val TERMINAL_SCREEN_HOST_PHASES = setOf(
    AndroidScreenHostPhase.ENDED,
    AndroidScreenHostPhase.ERROR,
)
