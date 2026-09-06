package net.extrawdw.apps.notisync.sshkeyprovider

internal enum class DesktopApplicationDraftError { ID, DUPLICATE_ID, PRIORITY, NAMES, PATHS }

internal data class DesktopApplicationDraft(
    val id: String,
    val displayName: String,
    val priority: String,
    val traversal: DesktopApplicationLineageTraversal,
    val acceptedNames: String,
    val acceptedPaths: String,
) {
    fun validate(existingIds: Set<String>, previousId: String?): DesktopApplicationDraftError? = when {
        id.isBlank() -> DesktopApplicationDraftError.ID
        id.trim() != previousId && id.trim() in existingIds -> DesktopApplicationDraftError.DUPLICATE_ID
        priority.trim().toIntOrNull() == null -> DesktopApplicationDraftError.PRIORITY
        acceptedNames.entries().isEmpty() -> DesktopApplicationDraftError.NAMES
        acceptedPaths.entries().any { !it.isAbsoluteDesktopPath() } -> DesktopApplicationDraftError.PATHS
        else -> null
    }

    fun application(previousDisplayName: String? = null) = KnownDesktopApplication(
        id = id.trim(),
        displayName = displayName.trim().ifEmpty {
            previousDisplayName?.takeUnless(String::isBlank) ?: id.trim()
        },
        priority = priority.trim().toInt(),
        traversal = traversal,
        acceptedNames = acceptedNames.entries(),
        acceptedPaths = acceptedPaths.entries(),
    )

    private fun String.entries(): Set<String> = lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
}
