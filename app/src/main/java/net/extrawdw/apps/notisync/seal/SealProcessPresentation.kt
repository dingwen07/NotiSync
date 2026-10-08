package net.extrawdw.apps.notisync.seal

import net.extrawdw.apps.notisync.sshkeyprovider.BUILT_IN_DESKTOP_APPLICATIONS
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationAnchor
import net.extrawdw.apps.notisync.sshkeyprovider.DesktopApplicationAnchorSelector
import net.extrawdw.apps.notisync.sshkeyprovider.KnownDesktopApplicationRegistry
import net.extrawdw.apps.notisync.sshkeyprovider.mainCallerLabel
import net.extrawdw.notisync.protocol.DesktopProcessIdentity

internal fun StoredOpenPgpRequest.applicationAnchor(
    registry: KnownDesktopApplicationRegistry = BUILT_IN_DESKTOP_APPLICATIONS,
): DesktopApplicationAnchor? = request.processContext?.let {
    DesktopApplicationAnchorSelector.select(it, registry).recommended
}

internal fun StoredOpenPgpRequest.processLabel(
    registry: KnownDesktopApplicationRegistry = BUILT_IN_DESKTOP_APPLICATIONS,
): String? = request.processContext?.processLineage?.mainCallerLabel(registry)

internal fun StoredOpenPgpRequest.processLineageForDisplay(): List<DesktopProcessIdentity> =
    request.processContext?.processLineage?.asReversed().orEmpty()
