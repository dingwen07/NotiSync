package net.extrawdw.apps.notisync.data.incomingfilter

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Stable storage-level origin codes. They are intentionally independent of protocol enum names/ordinals. */
internal enum class CanonicalIncomingFilterOrigin(val code: Byte) {
    ANDROID_LOCAL(1),
    IOS_ANCS(2),
}

internal data class IncomingFilterRuleValue(
    val origin: CanonicalIncomingFilterOrigin,
    val appId: String?,
    val channelId: String?,
)

internal class CanonicalIncomingFilterRule internal constructor(
    val position: Int,
    val value: IncomingFilterRuleValue,
    digest: ByteArray,
) {
    private val digestValue = digest.copyOf()

    init {
        require(position >= 0 && digestValue.size == DIGEST_BYTES) { "invalid canonical filter rule" }
    }

    fun digestCopy(): ByteArray = digestValue.copyOf()

    private companion object {
        const val DIGEST_BYTES = 32
    }
}

internal class CanonicalIncomingFilterSet internal constructor(
    rules: List<CanonicalIncomingFilterRule>,
    digest: ByteArray,
) {
    val rules: List<CanonicalIncomingFilterRule> = rules.toList()
    private val digestValue = digest.copyOf()

    init {
        require(this.rules.indices.all { this.rules[it].position == it }) { "filter positions are not canonical" }
        require(digestValue.size == IncomingFilterCanonicalizer.DIGEST_BYTES) { "invalid canonical filter-set digest" }
    }

    fun digestCopy(): ByteArray = digestValue.copyOf()
}

/**
 * Versioned canonical identity shared by import and post-cutover repositories.
 *
 * Strings are used byte-for-byte: there is no trimming, case folding, or Unicode normalization. Every
 * nullable UTF-8 value is framed with an explicit presence byte and big-endian byte length. Rules are
 * deduplicated only when their complete canonical tuple bytes match, then sorted by unsigned SHA-256.
 */
internal object IncomingFilterCanonicalizer {
    const val VERSION = 1
    const val DIGEST_BYTES = 32

    fun canonicalize(input: List<IncomingFilterRuleValue>): CanonicalIncomingFilterSet {
        require(input.size <= MAX_RULES) { "incoming filter has too many rules" }
        val unique = linkedMapOf<ByteArrayKey, EncodedRule>()
        input.forEach { value ->
            value.requireValid()
            val canonical = encodeRule(value)
            unique.putIfAbsent(ByteArrayKey(canonical), EncodedRule(value, canonical, sha256(canonical)))
        }
        val sorted = unique.values.sortedWith { left, right ->
            val digestOrder = compareUnsigned(left.digest, right.digest)
            if (digestOrder != 0) {
                digestOrder
            } else {
                check(left.canonical.contentEquals(right.canonical)) {
                    "incoming filter rule SHA-256 collision"
                }
                0
            }
        }
        val rules = sorted.mapIndexed { position, encoded ->
            CanonicalIncomingFilterRule(position, encoded.value, encoded.digest)
        }
        return CanonicalIncomingFilterSet(rules, encodeSetDigest(sorted))
    }

    private fun IncomingFilterRuleValue.requireValid() {
        appId?.requireCanonicalString("filter app id", MAX_APP_ID_CHARS, MAX_APP_ID_BYTES)
        channelId?.requireCanonicalString("filter channel id", MAX_CHANNEL_ID_CHARS, MAX_CHANNEL_ID_BYTES)
        require(appId != null || channelId == null) { "channel-scoped filter requires an app id" }
        require(origin != CanonicalIncomingFilterOrigin.IOS_ANCS || channelId == null) {
            "iOS filter cannot contain a channel id"
        }
    }

    private fun String.requireCanonicalString(name: String, maximumChars: Int, maximumUtf8Bytes: Int) {
        require(isNotEmpty()) { "$name must not be empty" }
        require(hasOnlyPairedSurrogatesAndNoControls()) { "$name contains unsupported characters" }
        require(length <= maximumChars) { "$name is too long" }
        require(encodeToByteArray().size <= maximumUtf8Bytes) { "$name is too long" }
    }

    private fun String.hasOnlyPairedSurrogatesAndNoControls(): Boolean {
        var index = 0
        while (index < length) {
            val first = this[index]
            val codePoint = when {
                Character.isHighSurrogate(first) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                    Character.toCodePoint(first, this[index + 1]).also { index++ }
                }
                Character.isLowSurrogate(first) -> return false
                else -> first.code
            }
            if (Character.isISOControl(codePoint)) return false
            index++
        }
        return true
    }

    private fun encodeRule(value: IncomingFilterRuleValue): ByteArray = framedBytes { output ->
        output.write(RULE_DOMAIN)
        output.writeInt(VERSION)
        output.writeByte(value.origin.code.toInt())
        output.writeNullableUtf8(value.appId)
        output.writeNullableUtf8(value.channelId)
    }

    private fun encodeSetDigest(rules: List<EncodedRule>): ByteArray = sha256(framedBytes { output ->
        output.write(SET_DOMAIN)
        output.writeInt(VERSION)
        output.writeInt(rules.size)
        rules.forEach { output.write(it.digest) }
    })

    private fun DataOutputStream.writeNullableUtf8(value: String?) {
        if (value == null) {
            writeByte(0)
            return
        }
        val bytes = value.encodeToByteArray()
        writeByte(1)
        writeInt(bytes.size)
        write(bytes)
    }

    private inline fun framedBytes(write: (DataOutputStream) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return bytes.toByteArray()
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        for (index in 0 until minOf(left.size, right.size)) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private class ByteArrayKey(bytes: ByteArray) {
        private val value = bytes.copyOf()
        override fun equals(other: Any?): Boolean = other is ByteArrayKey && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }

    private data class EncodedRule(
        val value: IncomingFilterRuleValue,
        val canonical: ByteArray,
        val digest: ByteArray,
    )

    private val RULE_DOMAIN = "NotiSync/incoming-filter/rule/v1\u0000".encodeToByteArray()
    private val SET_DOMAIN = "NotiSync/incoming-filter/set/v1\u0000".encodeToByteArray()
    private const val MAX_RULES = 512
    private const val MAX_APP_ID_CHARS = 512
    private const val MAX_APP_ID_BYTES = 2_048
    private const val MAX_CHANNEL_ID_CHARS = 256
    private const val MAX_CHANNEL_ID_BYTES = 1_024
}
