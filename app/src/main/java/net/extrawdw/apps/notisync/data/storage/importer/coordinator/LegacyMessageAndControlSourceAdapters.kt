package net.extrawdw.apps.notisync.data.storage.importer.coordinator

import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyFailureKind
import net.extrawdw.apps.notisync.data.storage.importer.legacy.LegacyImportException
import net.extrawdw.apps.notisync.data.storage.importer.target.ImportFailureDisposition
import net.extrawdw.apps.notisync.data.storage.importer.target.OperationalImportFailure

internal inline fun <T> sourceRead(block: () -> T): T = try {
    block()
} catch (expected: OperationalImportFailure) {
    throw expected
} catch (failure: LegacyImportException) {
    val disposition = if (failure.kind == LegacyFailureKind.SOURCE_IO) {
        ImportFailureDisposition.RETRYABLE
    } else {
        ImportFailureDisposition.BLOCKED
    }
    throw OperationalImportFailure(disposition, failure.kind.toStableCode(), failure)
}

internal fun LegacyFailureKind.toStableCode(): String = when (this) {
    LegacyFailureKind.SOURCE_MISSING -> "source_missing"
    LegacyFailureKind.FILENAME_MISMATCH -> "source_filename_mismatch"
    LegacyFailureKind.UNSUPPORTED_VERSION -> "source_version_unsupported"
    LegacyFailureKind.SCHEMA_MISMATCH -> "source_schema_mismatch"
    LegacyFailureKind.QUICK_CHECK_FAILED -> "source_integrity_failed"
    LegacyFailureKind.MALFORMED_ROW -> "source_row_malformed"
    LegacyFailureKind.SOURCE_IO -> "source_io"
}
