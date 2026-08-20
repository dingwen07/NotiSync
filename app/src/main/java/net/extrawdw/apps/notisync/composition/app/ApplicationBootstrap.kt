package net.extrawdw.apps.notisync.composition.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.extrawdw.apps.notisync.AppGraph
import net.extrawdw.apps.notisync.data.activity.ActivityRepository
import net.extrawdw.apps.notisync.work.RelayWorkerRuntime

internal enum class ApplicationBootstrapFailureKind {
    RETRYABLE,
    USER_RECOVERABLE,
    SECURITY_BLOCKING,
}

/** Value-free process bootstrap failure. Private source values and exception messages never cross this boundary. */
internal data class ApplicationBootstrapFailure(
    val kind: ApplicationBootstrapFailureKind,
    val code: String,
) {
    init {
        require(code.length in 1..MAX_CODE_CHARS) { "application bootstrap failure code has an invalid length" }
        require(code.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
            "application bootstrap failure code contains unsupported characters"
        }
    }

    private companion object {
        const val MAX_CODE_CHARS = 128
    }
}

/** The only object publication that can make application features and relay work live. */
internal data class ReadyServices(
    val graph: AppGraph,
    val activityRepository: ActivityRepository,
    val relayWorkerRuntime: RelayWorkerRuntime,
    val fcmRouteRegistration: FcmRouteRegistration,
)

/** Narrow route callback; FCM never receives the graph, broker, Core repository, or Room handles. */
internal fun interface FcmRouteRegistration {
    fun onRegistered(installationId: String)
}

internal sealed interface ApplicationBootstrapState<out T : Any> {
    data object Loading : ApplicationBootstrapState<Nothing>
    data class Ready<T : Any>(val services: T) : ApplicationBootstrapState<T>
    data class Unavailable(val failure: ApplicationBootstrapFailure) : ApplicationBootstrapState<Nothing>
}

internal sealed interface ApplicationBootstrapOutcome<out T : Any> {
    data class Ready<T : Any>(val services: T) : ApplicationBootstrapOutcome<T>
    data class Unavailable(val failure: ApplicationBootstrapFailure) : ApplicationBootstrapOutcome<Nothing>
}

/**
 * One user-open initializer and one atomic ready publication. A failed migration stays unavailable until the next
 * user launch; background callbacks never run or retry the migrator.
 */
internal class ApplicationBootstrapCoordinator<T : Any>(
    private val scope: CoroutineScope,
    private val initialize: suspend () -> ApplicationBootstrapOutcome<T>,
) {
    private val startLock = Any()
    private var startedJob: Job? = null
    private var existingProbeJob: Job? = null
    private val mutableState = MutableStateFlow<ApplicationBootstrapState<T>>(ApplicationBootstrapState.Loading)

    val state: StateFlow<ApplicationBootstrapState<T>> = mutableState.asStateFlow()

    /** Background-safe probe: it may publish an existing authority but cannot invoke the migrator. */
    fun probeExisting(
        probe: suspend () -> ApplicationBootstrapOutcome<T>?,
    ): Job = synchronized(startLock) {
        existingProbeJob ?: scope.launch {
            val outcome = try {
                probe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ApplicationBootstrapOutcome.Unavailable(
                    ApplicationBootstrapFailure(
                        ApplicationBootstrapFailureKind.SECURITY_BLOCKING,
                        UNEXPECTED_FAILURE_CODE,
                    ),
                )
            }
            if (outcome != null && mutableState.value is ApplicationBootstrapState.Loading) {
                mutableState.value = outcome.toState()
            }
        }.also { existingProbeJob = it }
    }

    fun start(): Job = synchronized(startLock) {
        startedJob ?: scope.launch {
            existingProbeJob?.join()
            if (mutableState.value !is ApplicationBootstrapState.Loading) return@launch
            val outcome = try {
                initialize()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ApplicationBootstrapOutcome.Unavailable(
                    ApplicationBootstrapFailure(
                        ApplicationBootstrapFailureKind.SECURITY_BLOCKING,
                        UNEXPECTED_FAILURE_CODE,
                    ),
                )
            }
            mutableState.value = outcome.toState()
        }.also { startedJob = it }
    }

    private fun ApplicationBootstrapOutcome<T>.toState(): ApplicationBootstrapState<T> = when (this) {
        is ApplicationBootstrapOutcome.Ready -> ApplicationBootstrapState.Ready(services)
        is ApplicationBootstrapOutcome.Unavailable -> ApplicationBootstrapState.Unavailable(failure)
    }

    private companion object {
        const val UNEXPECTED_FAILURE_CODE = "application_bootstrap_unexpected_failure"
    }
}
