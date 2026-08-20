package net.extrawdw.apps.notisync.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static guards for the persistence/composition boundaries described in the durable-state design.
 *
 * This is deliberately a JVM test: it checks source ownership without opening an Android runtime
 * or a database. The legacy readers remain reachable only from the one-time importer.
 */
class ArchitectureBoundaryTest {
    @Test
    fun uiWorkersEntryPointsAndEnginesDoNotBypassNewStorage() {
        val guardedLayers = setOf(
            ArchitectureLayer.UI,
            ArchitectureLayer.WORKER,
            ArchitectureLayer.ANDROID_ENTRYPOINT,
            ArchitectureLayer.ENGINE_OR_DOMAIN,
        )
        val violations = sources()
            .filter { SourceArchitectureScanner.classify(it) in guardedLayers }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)

        assertNoViolations(
            "UI/worker/entry-point/engine code must use repositories and coordinators, not Room or the legacy importer",
            violations,
        )
    }

    @Test
    fun coreAndOperationalStorageDoNotDependOnTheLegacyImporter() {
        val violations = sources()
            .filter {
                SourceArchitectureScanner.classify(it) in setOf(
                    ArchitectureLayer.CORE_STORAGE,
                    ArchitectureLayer.OPERATIONAL_STORAGE,
                )
            }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filter { it.detail.contains("legacy importer", ignoreCase = true) }

        assertNoViolations(
            "clean Room storage must not depend on one-time legacy import adapters",
            violations,
        )
    }

    @Test
    fun legacyImporterDoesNotDependOnRoomOrCleanStorageTypes() {
        val violations = sources()
            .filter { SourceArchitectureScanner.classify(it) == ArchitectureLayer.LEGACY_IMPORTER }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filterNot { it.detail.contains("legacy importer", ignoreCase = true) }

        assertNoViolations(
            "legacy readers and source DTOs may share legacy contracts but cannot depend on clean storage or adapters",
            violations,
        )
    }

    @Test
    fun importCoordinatorDependsOnlyOnLegacySourcesAndCleanTargetPorts() {
        val violations = sources()
            .filter { SourceArchitectureScanner.classify(it) == ArchitectureLayer.IMPORT_COORDINATOR }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filterNot { violation ->
                violation.detail.contains("legacy importer", ignoreCase = true) ||
                    violation.detail.contains("import target", ignoreCase = true) ||
                    violation.detail.contains("import coordinator package", ignoreCase = true)
            }

        assertNoViolations(
            "import coordination cannot depend directly on Room or clean Core/Operational storage",
            violations,
        )
    }

    @Test
    fun importTargetsUseOnlyTheirAssignedSourceAndAggregateBoundaries() {
        val violations = sources()
            .filter { SourceArchitectureScanner.classify(it) == ArchitectureLayer.IMPORT_TARGET }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filterNot { violation ->
                val coreMapping = "/importer/target/core/mapping/" in violation.relativePath
                val coreRoom = "/importer/target/core/room/" in violation.relativePath
                val preferencesMapper = violation.relativePath.endsWith(
                    "/importer/target/preferences/LegacyOperationalPreferencesMapper.kt",
                )
                val preferencesRoom = violation.relativePath.endsWith(
                    "/importer/target/preferences/RoomOperationalPreferencesImportTarget.kt",
                )
                val preferencesModels = violation.relativePath.endsWith(
                    "/importer/target/preferences/OperationalPreferencesImportModels.kt",
                )
                when {
                    coreMapping ->
                        violation.detail.contains("legacy importer", ignoreCase = true) ||
                            violation.detail.contains("import target", ignoreCase = true)
                    coreRoom ->
                        violation.detail.contains("Core", ignoreCase = true) ||
                            violation.detail.contains("Room 3", ignoreCase = true) ||
                            violation.detail.contains("RoomDatabase", ignoreCase = true) ||
                            violation.detail.contains("databaseBuilder", ignoreCase = true) ||
                            violation.detail.contains("Room entity", ignoreCase = true) ||
                            violation.detail.contains("import target", ignoreCase = true)
                    preferencesMapper ->
                        violation.detail.contains("legacy importer", ignoreCase = true) ||
                            violation.detail.contains("import target", ignoreCase = true)
                    preferencesRoom ->
                        violation.detail.contains("Operational", ignoreCase = true) ||
                            violation.detail.contains("Room 3", ignoreCase = true) ||
                            violation.detail.contains("RoomDatabase", ignoreCase = true) ||
                            violation.detail.contains("databaseBuilder", ignoreCase = true) ||
                            violation.detail.contains("Room entity", ignoreCase = true) ||
                            violation.detail.contains("import target", ignoreCase = true)
                    preferencesModels -> violation.detail.contains("import target", ignoreCase = true)
                    else ->
                        violation.detail.contains("Operational", ignoreCase = true) ||
                            violation.detail.contains("Room 3", ignoreCase = true) ||
                            violation.detail.contains("RoomDatabase", ignoreCase = true) ||
                            violation.detail.contains("databaseBuilder", ignoreCase = true) ||
                            violation.detail.contains("Room entity", ignoreCase = true) ||
                            violation.detail.contains("import target", ignoreCase = true)
                }
            }

        assertNoViolations(
            "target models stay clean; legacy-aware mappers and Room adapters may use only their assigned boundary",
            violations,
        )
    }

    @Test
    fun operationalPreferencesCutoverKeepsSourceModelsCommandsAndRoomPersistenceDisjoint() {
        val preferenceTargets = sources().filter { "/importer/target/preferences/" in it.relativePath }
        val modelViolations = preferenceTargets
            .filter { it.relativePath.endsWith("OperationalPreferencesImportModels.kt") }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filterNot { it.detail.contains("import target package", ignoreCase = true) }
        val mappingViolations = preferenceTargets
            .filter { it.relativePath.endsWith("LegacyOperationalPreferencesMapper.kt") }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filter { violation ->
                violation.detail.contains("Operational Room", ignoreCase = true) ||
                    violation.detail.contains("Room 3", ignoreCase = true) ||
                    violation.detail.contains("import coordinator", ignoreCase = true)
            }
        val roomViolations = preferenceTargets
            .filter { it.relativePath.endsWith("RoomOperationalPreferencesImportTarget.kt") }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filter { violation ->
                violation.detail.contains("legacy importer", ignoreCase = true) ||
                    violation.detail.contains("import coordinator", ignoreCase = true)
            }

        assertNoViolations(
            "Operational Preferences legacy DTOs, clean commands, and Room persistence must remain separate",
            modelViolations + mappingViolations + roomViolations,
        )
    }

    @Test
    fun coreV51MappingAndRoomAdapterRemainPhysicallyDisjoint() {
        val coreTargets = sources().filter { "/importer/target/core/" in it.relativePath }
        val mappingViolations = coreTargets
            .filter { "/mapping/" in it.relativePath }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filter { violation ->
                violation.detail.contains("Core Room", ignoreCase = true) ||
                    violation.detail.contains("Room 3", ignoreCase = true) ||
                    violation.detail.contains("import coordinator", ignoreCase = true)
            }
        val roomViolations = coreTargets
            .filter { "/room/" in it.relativePath }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filter { violation ->
                violation.detail.contains("legacy importer", ignoreCase = true) ||
                    violation.detail.contains("import coordinator", ignoreCase = true)
            }
        val modelViolations = coreTargets
            .filter { "/model/" in it.relativePath }
            .flatMap(SourceArchitectureScanner::roomOrLegacyReferences)
            .filterNot { it.detail.contains("import target package", ignoreCase = true) }

        assertNoViolations(
            "Core v51 legacy mapping, clean commands, and Room persistence must remain separate packages",
            mappingViolations + roomViolations + modelViolations,
        )
    }

    @Test
    fun wholeAppGraphIsLimitedToTheCompositionRootAndNamedTransitionBridges() {
        val violations = sources()
            .filter { SourceArchitectureScanner.classify(it) != ArchitectureLayer.COMPOSITION_ROOT }
            .filterNot(SourceArchitectureScanner::isTransitionalAppGraphBridge)
            .flatMap(SourceArchitectureScanner::appGraphReferences)

        assertNoViolations(
            "new Android entry points and feature code must resolve a narrow factory/coordinator, not AppGraph",
            violations,
        )
    }

    private fun sources(): List<ParsedKotlinSource> = SourceArchitectureScanner.discover()

    private fun assertNoViolations(message: String, violations: List<ArchitectureViolation>) {
        assertTrue(
            "$message\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
