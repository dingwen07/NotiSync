package net.extrawdw.apps.notisync.ios

/** A fresh LE link belongs to the setup screen that saw it arrive, even while a system dialog covers it. */
internal class IphonePairingCandidates {
    data class Offer(val session: Long, val eligibleAt: Long)

    private data class Link(val session: Long, val bondedAtArrival: Boolean, var eligibleAt: Long?, var repairing: Boolean = false)

    var foregroundSession: Long? = null
    private val links = mutableMapOf<String, Link>()
    private val serverLinks = mutableSetOf<String>()

    fun leConnected(address: String, bonded: Boolean, now: Long) {
        val session = foregroundSession ?: return
        links.putIfAbsent(address, Link(session, bonded, if (bonded) null else now))
    }

    fun serverConnectionChanged(address: String, connected: Boolean) {
        if (connected) serverLinks.add(address) else serverLinks.remove(address)
    }

    fun repairRequested(address: String) {
        val link = links[address] ?: return
        if (link.eligibleAt == null) link.repairing = true
    }

    fun leEncryptionChanged(address: String, success: Boolean, now: Long): Boolean {
        val link = links[address] ?: return false
        if (!link.repairing) return false
        link.repairing = false
        if (success) link.eligibleAt = now
        return success
    }

    fun offer(address: String, hasLeBond: () -> Boolean): Offer? {
        if (address !in serverLinks) return null
        val link = links[address] ?: return null
        val eligibleAt = link.eligibleAt ?: return null
        // During key replacement Android can report NONE → BONDING → BONDED *after* encryption.
        if (link.bondedAtArrival && !hasLeBond()) return null
        return Offer(link.session, eligibleAt)
    }

    fun leDisconnected(address: String) {
        links.remove(address)
        serverLinks.remove(address)
    }

    fun clearLinks() {
        links.clear()
        serverLinks.clear()
    }
}
