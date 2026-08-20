package net.extrawdw.apps.notisync.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Small source-level architecture checker used by [ArchitectureBoundaryTest].
 *
 * This intentionally does not parse Kotlin into an AST.  The boundaries being checked are package
 * and import boundaries, so a lexical view is sufficient and keeps this guard independent of a
 * compiler plugin or a second static-analysis tool.  Comments and Kotlin string/character literals
 * are masked before matching, which prevents documentation and diagnostics text from becoming
 * accidental dependencies.
 */
internal object SourceArchitectureScanner {
    private const val APP_PACKAGE = "net.extrawdw.apps.notisync"
    private const val CORE_STORAGE_PACKAGE = "$APP_PACKAGE.data.storage.core"
    private const val OPERATIONAL_STORAGE_PACKAGE = "$APP_PACKAGE.data.storage.operational"
    private const val LEGACY_IMPORT_PACKAGE = "$APP_PACKAGE.data.storage.importer.legacy"
    private const val IMPORT_COORDINATOR_PACKAGE = "$APP_PACKAGE.data.storage.importer.coordinator"
    private const val IMPORT_TARGET_PACKAGE = "$APP_PACKAGE.data.storage.importer.target"
    private const val ROOM_PACKAGE = "androidx.room3"

    /**
     * These are the existing v51-to-v52 graph bridges.  They are deliberately named files rather
     * than a package-wide exception: every new Android entry point must use a narrow factory or
     * coordinator instead of receiving the all-purpose graph.  Delete entries as the composition
     * root split lands.  This is the only temporary exception for whole [AppGraph] references.
     */
    val transitionalAppGraphBridgePaths: Set<String> = setOf(
        "ios/IosBridgeService.kt",
        "notification/capture/NotificationCapture.kt",
        "pairing/Pairing.kt",
        "sshagent/SshKeySendActivity.kt",
        "ui/DevicesScreen.kt",
        "ui/DiagnosticsCard.kt",
        "ui/DurableTrustAction.kt",
        "ui/UiCommon.kt",
        "work/RelayWorkers.kt",
    )

    private val packagePattern = Regex("(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)")
    private val importPattern = Regex("(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_.]*)")
    private val roomNamespacePattern = Regex("\\bandroidx\\.room3(?:\\.|\\b)")
    private val roomDatabaseTypePattern = Regex("\\bRoomDatabase\\b")
    private val roomBuilderPattern = Regex("\\bRoom\\s*\\.\\s*databaseBuilder\\b")
    private val roomAnnotationPattern = Regex("@\\s*(?:Dao|Database|Entity)\\b")
    private val appGraphPattern = Regex("\\bAppGraph\\b")

    /** Finds the repository root from any Gradle test worker working directory. */
    fun repositoryRoot(start: Path = Path.of(System.getProperty("user.dir"))): Path {
        var candidate: Path? = start.toAbsolutePath().normalize()
        while (candidate != null) {
            val settings = candidate.resolve("settings.gradle.kts")
            val appSources = candidate.resolve("app/src/main/java")
            if (settings.isRegularFile() && appSources.isDirectory()) return candidate
            candidate = candidate.parent
        }
        error("Unable to locate repository root from ${start.toAbsolutePath().normalize()}")
    }

    fun discover(root: Path = repositoryRoot()): List<ParsedKotlinSource> {
        val sourceRoot = root.resolve("app/src/main/java")
        require(sourceRoot.isDirectory()) { "Missing Android source root: $sourceRoot" }
        return Files.walk(sourceRoot).use { paths ->
            paths
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(".kt") }
                .map { parse(root, sourceRoot, it) }
                .toList()
                .sortedBy { it.relativePath }
        }
    }

    fun parse(
        root: Path,
        sourceRoot: Path = root.resolve("app/src/main/java"),
        path: Path,
    ): ParsedKotlinSource {
        val raw = Files.readString(path, Charsets.UTF_8)
        val code = KotlinLexicalMask.mask(raw)
        val packageName = packagePattern.find(code)?.groupValues?.get(1)
        val imports = importPattern.findAll(code).map { it.groupValues[1] }.toSet()
        val relativePath = root.relativize(path).toString().replace(path.fileSystem.separator, "/")
        return ParsedKotlinSource(
            relativePath = relativePath,
            sourceRootRelativePath = sourceRoot.relativize(path).toString().replace(path.fileSystem.separator, "/"),
            packageName = packageName,
            imports = imports,
            code = code,
        )
    }

    fun classify(source: ParsedKotlinSource): ArchitectureLayer {
        val path = source.sourceRootRelativePath
        val fileName = path.substringAfterLast('/')
        return when {
            path == "net/extrawdw/apps/notisync/AppGraph.kt" ||
                path.startsWith("net/extrawdw/apps/notisync/composition/") ->
                ArchitectureLayer.COMPOSITION_ROOT
            path.startsWith("net/extrawdw/apps/notisync/data/storage/core/") -> ArchitectureLayer.CORE_STORAGE
            path.startsWith("net/extrawdw/apps/notisync/data/storage/operational/") ->
                ArchitectureLayer.OPERATIONAL_STORAGE
            path.startsWith("net/extrawdw/apps/notisync/data/storage/importer/coordinator/") ->
                ArchitectureLayer.IMPORT_COORDINATOR
            path.startsWith("net/extrawdw/apps/notisync/data/storage/importer/target/") ->
                ArchitectureLayer.IMPORT_TARGET
            path.startsWith("net/extrawdw/apps/notisync/data/storage/importer/legacy/") ->
                ArchitectureLayer.LEGACY_IMPORTER
            path.startsWith("net/extrawdw/apps/notisync/ui/") -> ArchitectureLayer.UI
            path.startsWith("net/extrawdw/apps/notisync/work/") || fileName.endsWith("Worker.kt") ->
                ArchitectureLayer.WORKER
            path.startsWith("net/extrawdw/apps/notisync/domain/") ||
                path.startsWith("net/extrawdw/apps/notisync/foundation/") ||
                fileName.endsWith("Engine.kt") || fileName.endsWith("Coordinator.kt") ->
                ArchitectureLayer.ENGINE_OR_DOMAIN
            fileName.endsWith("Activity.kt") ||
                fileName.endsWith("Service.kt") ||
                fileName.endsWith("Receiver.kt") ||
                fileName.endsWith("HostApduService.kt") ||
                path == "net/extrawdw/apps/notisync/MainActivity.kt" ->
                ArchitectureLayer.ANDROID_ENTRYPOINT
            else -> ArchitectureLayer.OTHER
        }
    }

    fun roomOrLegacyReferences(source: ParsedKotlinSource): List<ArchitectureViolation> {
        val references = buildList {
            if (source.imports.any { it == ROOM_PACKAGE || it.startsWith("$ROOM_PACKAGE.") }) {
                add(ReferenceMatch("Room 3 namespace import", source.code.indexOf(ROOM_PACKAGE)))
            }
            if (source.imports.any { it == CORE_STORAGE_PACKAGE || it.startsWith("$CORE_STORAGE_PACKAGE.") }) {
                add(ReferenceMatch("Core Room storage import", source.code.indexOf(CORE_STORAGE_PACKAGE)))
            }
            if (source.imports.any {
                    it == OPERATIONAL_STORAGE_PACKAGE || it.startsWith("$OPERATIONAL_STORAGE_PACKAGE.")
                }
            ) {
                add(ReferenceMatch("Operational Room storage import", source.code.indexOf(OPERATIONAL_STORAGE_PACKAGE)))
            }
            if (source.imports.any { it == LEGACY_IMPORT_PACKAGE || it.startsWith("$LEGACY_IMPORT_PACKAGE.") }) {
                add(ReferenceMatch("legacy importer import", source.code.indexOf(LEGACY_IMPORT_PACKAGE)))
            }
            if (source.imports.any {
                    it == IMPORT_COORDINATOR_PACKAGE || it.startsWith("$IMPORT_COORDINATOR_PACKAGE.")
                }
            ) {
                add(ReferenceMatch("import coordinator import", source.code.indexOf(IMPORT_COORDINATOR_PACKAGE)))
            }
            if (source.imports.any { it == IMPORT_TARGET_PACKAGE || it.startsWith("$IMPORT_TARGET_PACKAGE.") }) {
                add(ReferenceMatch("import target import", source.code.indexOf(IMPORT_TARGET_PACKAGE)))
            }
            roomNamespacePattern.find(source.code)?.let {
                add(ReferenceMatch("Room 3 namespace mention", it.range.first))
            }
            roomDatabaseTypePattern.find(source.code)?.let {
                add(ReferenceMatch("RoomDatabase mention", it.range.first))
            }
            roomBuilderPattern.find(source.code)?.let {
                add(ReferenceMatch("Room databaseBuilder mention", it.range.first))
            }
            roomAnnotationPattern.find(source.code)?.let {
                add(ReferenceMatch("Room entity/DAO annotation", it.range.first))
            }
            // A fully-qualified use in code does not appear in imports.  Keep this check exact so
            // a prose mention of "legacy" or an unrelated data package cannot trigger the guard.
            listOf(
                CORE_STORAGE_PACKAGE to "Core Room storage package mention",
                OPERATIONAL_STORAGE_PACKAGE to "Operational Room storage package mention",
                LEGACY_IMPORT_PACKAGE to "legacy importer package mention",
                IMPORT_COORDINATOR_PACKAGE to "import coordinator package mention",
                IMPORT_TARGET_PACKAGE to "import target package mention",
            ).forEach { (packageName, description) ->
                val index = source.code.indexOf(packageName)
                if (index >= 0) add(ReferenceMatch(description, index))
            }
        }
        return references.distinctBy { it.description to it.offset }.map { match ->
            ArchitectureViolation(
                relativePath = source.relativePath,
                line = source.lineOf(match.offset),
                rule = "new-storage-boundary",
                detail = match.description,
            )
        }
    }

    fun appGraphReferences(source: ParsedKotlinSource): List<ArchitectureViolation> {
        val match = appGraphPattern.find(source.code) ?: return emptyList()
        return listOf(
            ArchitectureViolation(
                relativePath = source.relativePath,
                line = source.lineOf(match.range.first),
                rule = "narrow-composition-entrypoint",
                detail = "whole AppGraph reference",
            ),
        )
    }

    fun isTransitionalAppGraphBridge(source: ParsedKotlinSource): Boolean =
        source.sourceRootRelativePath.removePrefix("net/extrawdw/apps/notisync/") in
            transitionalAppGraphBridgePaths

    private data class ReferenceMatch(val description: String, val offset: Int)

    private fun ParsedKotlinSource.lineOf(offset: Int): Int =
        if (offset < 0) 1 else code.take(offset.coerceAtMost(code.length)).count { it == '\n' } + 1
}

internal enum class ArchitectureLayer {
    UI,
    WORKER,
    ANDROID_ENTRYPOINT,
    ENGINE_OR_DOMAIN,
    COMPOSITION_ROOT,
    CORE_STORAGE,
    OPERATIONAL_STORAGE,
    LEGACY_IMPORTER,
    IMPORT_COORDINATOR,
    IMPORT_TARGET,
    OTHER,
}

internal data class ParsedKotlinSource(
    val relativePath: String,
    val sourceRootRelativePath: String,
    val packageName: String?,
    val imports: Set<String>,
    val code: String,
)

internal data class ArchitectureViolation(
    val relativePath: String,
    val line: Int,
    val rule: String,
    val detail: String,
) {
    override fun toString(): String = "$relativePath:$line [$rule] $detail"
}

/** Masks non-code while retaining offsets and line endings for useful diagnostics. */
internal object KotlinLexicalMask {
    fun mask(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var blockCommentDepth = 0
        while (index < source.length) {
            if (blockCommentDepth > 0) {
                when {
                    source.startsWith("/*", index) -> {
                        out.append("  ")
                        blockCommentDepth++
                        index += 2
                    }
                    source.startsWith("*/", index) -> {
                        out.append("  ")
                        blockCommentDepth--
                        index += 2
                    }
                    else -> appendMaskedChar(source[index], out).also { index++ }
                }
                continue
            }
            when {
                source.startsWith("//", index) -> {
                    out.append("  ")
                    index += 2
                    while (index < source.length && source[index] != '\n') {
                        appendMaskedChar(source[index], out)
                        index++
                    }
                }
                source.startsWith("/*", index) -> {
                    out.append("  ")
                    blockCommentDepth = 1
                    index += 2
                }
                source.startsWith("\"\"\"", index) -> {
                    out.append("   ")
                    index += 3
                    while (index < source.length) {
                        if (source.startsWith("\"\"\"", index)) {
                            out.append("   ")
                            index += 3
                            break
                        }
                        appendMaskedChar(source[index], out)
                        index++
                    }
                }
                source[index] == '\"' -> {
                    out.append(' ')
                    index++
                    var escaped = false
                    while (index < source.length) {
                        val character = source[index]
                        appendMaskedChar(character, out)
                        index++
                        if (escaped) {
                            escaped = false
                        } else if (character == '\\') {
                            escaped = true
                        } else if (character == '\"') {
                            break
                        }
                    }
                }
                source[index] == '\'' -> {
                    out.append(' ')
                    index++
                    var escaped = false
                    while (index < source.length) {
                        val character = source[index]
                        appendMaskedChar(character, out)
                        index++
                        if (escaped) {
                            escaped = false
                        } else if (character == '\\') {
                            escaped = true
                        } else if (character == '\'') {
                            break
                        }
                    }
                }
                else -> {
                    out.append(source[index])
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun appendMaskedChar(character: Char, out: StringBuilder) {
        out.append(if (character == '\n' || character == '\r') character else ' ')
    }
}
