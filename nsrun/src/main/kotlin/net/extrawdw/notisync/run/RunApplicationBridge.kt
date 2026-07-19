package net.extrawdw.notisync.run

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonPrimitive
import net.extrawdw.notisync.desktop.api.DaemonLocalApi
import net.extrawdw.notisync.desktop.api.ReceiveStream
import net.extrawdw.notisync.localapi.ApplicationRegistrationRequest
import net.extrawdw.notisync.localapi.MessageFilter
import net.extrawdw.notisync.localapi.QueuePolicy
import net.extrawdw.notisync.localapi.ReceiveRecord
import net.extrawdw.notisync.localapi.ReceiveRecordType
import net.extrawdw.notisync.localapi.ReceiveRequest
import net.extrawdw.notisync.localapi.SendRequest
import net.extrawdw.notisync.peer.channel.Recipients
import net.extrawdw.notisync.peer.channel.SignerSelection
import net.extrawdw.notisync.protocol.ActionEvent
import net.extrawdw.notisync.protocol.ActionKind
import net.extrawdw.notisync.protocol.Capability
import net.extrawdw.notisync.protocol.CapturedNotification
import net.extrawdw.notisync.protocol.ClientId
import net.extrawdw.notisync.protocol.DataSync
import net.extrawdw.notisync.protocol.DataSyncKind
import net.extrawdw.notisync.protocol.MessageType
import net.extrawdw.notisync.protocol.MirrorCategory
import net.extrawdw.notisync.protocol.MirrorImportance
import net.extrawdw.notisync.protocol.NotificationAction
import net.extrawdw.notisync.protocol.NotificationLiveUpdate
import net.extrawdw.notisync.protocol.NotificationProgress
import net.extrawdw.notisync.protocol.NotificationStyle
import net.extrawdw.notisync.protocol.ProtocolCodec
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlResult
import net.extrawdw.notisync.protocol.RunPhase
import net.extrawdw.notisync.protocol.RunPromptKind
import net.extrawdw.notisync.protocol.RunState
import net.extrawdw.notisync.protocol.RunSync
import net.extrawdw.notisync.protocol.RunSyncKind
import net.extrawdw.notisync.protocol.RunUpdateReason
import net.extrawdw.notisync.protocol.Urgency

/**
 * The complete local-API/SecureChannel boundary for one Run. The daemon sees only generic
 * application registration, opaque CBOR messages, selectors, and application-level acknowledgments.
 */
internal class RunApplicationBridge private constructor(
    private val api: DaemonLocalApi,
    val hostClientId: ClientId,
    val runId: String,
    val sourceKey: String,
    private val random: SecureRandom,
) : AutoCloseable {
    private val sessionActions = LinkedHashMap<String, ActionBinding>()
    private var contextualGeneration: Long? = null
    private var contextualActions = emptyMap<String, ActionBinding>()

    val controlInterest: ReceiveRequest = ReceiveRequest(
        applicationId = APPLICATION_ID,
        messageTypes = listOf(MessageType.DATA_SYNC),
        filters = listOf(
            MessageFilter(MessageType.DATA_SYNC, "/kind", listOf(JsonPrimitive(DataSyncKind.RUN.name))),
            MessageFilter(MessageType.DATA_SYNC, "/run/kind", listOf(JsonPrimitive(RunSyncKind.CONTROL.name))),
            MessageFilter(
                MessageType.DATA_SYNC,
                "/run/control/hostClientId",
                listOf(JsonPrimitive(hostClientId.value)),
            ),
            MessageFilter(MessageType.DATA_SYNC, "/run/control/runId", listOf(JsonPrimitive(runId))),
        ),
    )
    val actionInterest: ReceiveRequest = ReceiveRequest(
        applicationId = APPLICATION_ID,
        messageTypes = listOf(MessageType.ACTION),
        filters = listOf(
            MessageFilter(MessageType.ACTION, "/sourceKey", listOf(JsonPrimitive(sourceKey))),
        ),
    )

    /** Both interests are attached before the initial state is accepted. */
    fun openInitialStreams(): RunReceiveStreams {
        val control = api.openReceive(controlInterest)
        val action = try {
            api.openReceive(actionInterest)
        } catch (error: Throwable) {
            // The first stream has no owner if the second request fails.
            runCatching { control.close() }
            runCatching { api.unregisterReceive(controlInterest) }
            throw error
        }
        return RunReceiveStreams(control, action)
    }

    fun reopenControl(): ReceiveStream = api.openReceive(controlInterest)

    fun reopenAction(): ReceiveStream = api.openReceive(actionInterest)

    /** Native state is first; when present, the iOS compatibility projection is second. */
    @Synchronized
    fun publish(state: RunState): Boolean {
        val sends = buildList {
            add(nativeStateSend(state))
            iosNotificationSend(state)?.let(::add)
        }
        if (sends.size == 1) api.send(sends.single()) else api.sendAll(sends)
        return true
    }

    fun nativeStateSend(state: RunState): SendRequest {
        require(state.hostClientId == hostClientId && state.runId == runId) { "Run state belongs to another host" }
        return SendRequest(
            applicationId = APPLICATION_ID,
            messageType = MessageType.DATA_SYNC,
            body = encodeBody(DataSync(DataSyncKind.RUN, run = RunSync(RunSyncKind.STATE, state = state))),
            scope = Recipients.OwnMeshFiltered(
                requiredCapabilities = NATIVE_RUN_CAPABILITIES,
                requireCapabilityRoutingV1 = true,
            ),
            urgency = runUrgency(state.updateReason),
            signWith = SignerSelection.OPERATIONAL,
            submissionId = "$sourceKey/native/${state.revision}",
            queuePolicy = QueuePolicy(
                streamKey = "$sourceKey/native",
                sequence = state.revision,
                coalesceKey = "$sourceKey/native",
            ),
        )
    }

    @Synchronized
    fun iosNotificationSend(state: RunState): SendRequest? {
        val projection = projectIosNotification(state) ?: return null
        return SendRequest(
            applicationId = APPLICATION_ID,
            messageType = MessageType.NOTIFICATION,
            body = encodeBody(projection),
            scope = Recipients.OwnMeshFiltered(
                requiredCapabilities = IOS_FALLBACK_CAPABILITIES,
                forbiddenCapabilities = setOf(Capability.PUSH_FILTERING),
            ),
            urgency = Urgency.HIGH,
            signWith = SignerSelection.OPERATIONAL,
            submissionId = "$sourceKey/ios/${state.revision}",
            queuePolicy = QueuePolicy(
                streamKey = "$sourceKey/ios",
                sequence = state.revision,
                coalesceKey = "$sourceKey/ios",
            ),
        )
    }

    fun controlResultSend(sender: ClientId, result: RunControlResult): SendRequest = SendRequest(
        applicationId = APPLICATION_ID,
        messageType = MessageType.DATA_SYNC,
        body = encodeBody(
            DataSync(DataSyncKind.RUN, run = RunSync(RunSyncKind.CONTROL_RESULT, controlResult = result)),
        ),
        scope = Recipients.Only(sender),
        urgency = Urgency.NORMAL,
        signWith = SignerSelection.OPERATIONAL,
        submissionId = "$sourceKey/control/${result.requestId}/result",
    )

    fun complete(envelopeId: String, sends: List<SendRequest>) =
        api.complete(APPLICATION_ID, envelopeId, sends)

    fun ack(envelopeId: String) = api.ack(APPLICATION_ID, envelopeId)

    fun decode(record: ReceiveRecord): RunInbound {
        if (record.recordType == ReceiveRecordType.HEARTBEAT) return RunInbound.Heartbeat
        val envelopeId = record.envelopeId ?: return RunInbound.Invalid(null)
        if (record.applicationId != APPLICATION_ID || record.body == null || record.messageType == null) {
            return RunInbound.Invalid(envelopeId)
        }
        val body = runCatching { Base64.getDecoder().decode(record.body) }
            .getOrElse { return RunInbound.Invalid(envelopeId) }
        return when (record.messageType) {
            MessageType.DATA_SYNC -> {
                val sync = runCatching { ProtocolCodec.decodeFromCbor<DataSync>(body) }
                    .getOrElse { return RunInbound.Invalid(envelopeId) }
                val control = sync.takeIf { it.kind == DataSyncKind.RUN }
                    ?.run?.takeIf { it.kind == RunSyncKind.CONTROL }?.control
                    ?: return RunInbound.Invalid(envelopeId)
                RunInbound.Control(envelopeId, record.senderClientId?.let(::ClientId), record.senderOwnDevice == true, control)
            }
            MessageType.ACTION -> {
                val action = runCatching { ProtocolCodec.decodeFromCbor<ActionEvent>(body) }
                    .getOrElse { return RunInbound.Invalid(envelopeId) }
                RunInbound.Action(envelopeId, record.senderClientId?.let(::ClientId), record.senderOwnDevice == true, action)
            }
            else -> RunInbound.Invalid(envelopeId)
        }
    }

    /** Returns null for a forged, stale, mismatched, or structurally invalid compatibility action. */
    @Synchronized
    fun validateAction(inbound: RunInbound.Action, currentGeneration: Long): RunActionCommand? {
        val event = inbound.event
        if (!inbound.senderOwnDevice || inbound.sender == null) return null
        if (event.sourceClientId != hostClientId || event.sourceKey != sourceKey || event.kind != ActionKind.PERFORM) {
            return null
        }
        if (event.remoteInputText?.encodeToByteArray()?.size ?: 0 > MAX_REMOTE_INPUT_BYTES) return null
        val binding = sessionActions.values.firstOrNull { it.index == event.actionIndex }
            ?: contextualActions.values.firstOrNull { it.index == event.actionIndex }
            ?: return null
        if (event.actionTitle != binding.title || event.actionGeneration != binding.generation) return null
        val suppliedToken = event.actionToken ?: return null
        if (!MessageDigest.isEqual(binding.token.encodeToByteArray(), suppliedToken.encodeToByteArray())) return null
        if (!binding.remoteInput && event.remoteInputText != null) return null
        if (!binding.sessionLifetime && binding.generation != currentGeneration) return null
        if (binding.remoteInput && event.remoteInputText == null) return null
        return RunActionCommand(binding.id, event.remoteInputText)
    }

    @Synchronized
    internal fun projectIosNotification(state: RunState): CapturedNotification? {
        require(state.hostClientId == hostClientId && state.runId == runId) { "Run state belongs to another host" }
        if (state.updateReason == RunUpdateReason.PERIODIC || state.updateReason == RunUpdateReason.REFRESH) return null

        val terminal = state.phase == RunPhase.COMPLETED || state.phase == RunPhase.FAILED_TO_START
        val freshSummary = state.llmSummary.takeIf { state.updateReason == RunUpdateReason.LLM_SUMMARY }
        val deterministicText = when (state.phase) {
            RunPhase.RUNNING -> if (state.updateReason == RunUpdateReason.RESUMED) "Running again" else {
                state.progress?.let { progress ->
                    if (progress.indeterminate) "Still running"
                    else "${((progress.current ?: 0).coerceIn(0, progress.total ?: 1) * 100) / (progress.total ?: 1)}% complete"
                } ?: "Still running"
            }
            RunPhase.BLOCKED -> if (state.blockedReason == net.extrawdw.notisync.protocol.RunBlockedReason.TERMINAL_INPUT) {
                "Waiting for input"
            } else {
                "May need your attention"
            }
            RunPhase.COMPLETED -> if (state.exitCode == 0) "Completed successfully" else "Exited with code ${state.exitCode}"
            RunPhase.FAILED_TO_START -> "Could not start"
        }
        val command = state.argv.firstOrNull()?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "Command"
        val title = state.llmSummary?.title ?: command
        val expanded = (freshSummary?.expandedText?.takeIf(String::isNotBlank)
            ?: state.failureMessage
            ?: state.terminal.text).take(MAX_EXPANDED_TEXT_CHARS).ifBlank { null }
        val quiet = state.updateReason in setOf(
            RunUpdateReason.INITIAL,
            RunUpdateReason.RESUMED,
            RunUpdateReason.LLM_SUMMARY,
        )
        val currentProgress = state.progress
        return CapturedNotification(
            sourceClientId = hostClientId,
            sourceKey = sourceKey,
            packageName = "net.extrawdw.notisync.run",
            appLabel = DISPLAY_NAME,
            title = title.take(MAX_TITLE_CHARS),
            text = (freshSummary?.text ?: deterministicText).take(MAX_TEXT_CHARS),
            bigText = expanded,
            style = if (expanded != null) NotificationStyle.BIG_TEXT else NotificationStyle.DEFAULT,
            category = MirrorCategory.PROGRESS,
            importance = MirrorImportance.HIGH,
            postTime = state.updatedAt,
            isOngoing = !terminal,
            isClearable = terminal,
            onlyAlertOnce = quiet,
            silentUpdate = quiet,
            actions = iosActions(state),
            liveUpdate = NotificationLiveUpdate(
                requestPromotedOngoing = !terminal,
                progress = currentProgress?.takeUnless { terminal }?.let {
                    NotificationProgress(it.current, it.total, it.indeterminate)
                },
                shortCriticalText = when {
                    state.phase == RunPhase.BLOCKED && state.prompt != null -> "Input"
                    state.phase == RunPhase.BLOCKED -> "Check"
                    state.phase == RunPhase.COMPLETED -> "Done"
                    state.phase == RunPhase.FAILED_TO_START -> "Failed"
                    currentProgress != null -> {
                        val total = currentProgress.total ?: 1
                        "${(((currentProgress.current ?: 0).coerceIn(0, total) * 100) / total).coerceIn(0, 100)}%"
                    }
                    else -> "Run"
                },
            ),
        )
    }

    @Synchronized
    private fun iosActions(state: RunState): List<NotificationAction> {
        if (state.phase == RunPhase.COMPLETED || state.phase == RunPhase.FAILED_TO_START) return emptyList()
        val interrupt = sessionAction("signal-int", "Interrupt", state.interactionGeneration)
        val terminate = sessionAction("signal-term", "Terminate", state.interactionGeneration)
        if (state.phase != RunPhase.BLOCKED) return listOf(interrupt, terminate).map(ActionBinding::toWire)

        if (contextualGeneration != state.interactionGeneration) {
            contextualGeneration = state.interactionGeneration
            contextualActions = when (state.prompt) {
                RunPromptKind.YES_NO -> listOf(
                    contextualAction("yes", "Yes", state.interactionGeneration),
                    contextualAction("no", "No", state.interactionGeneration),
                ).associateBy(ActionBinding::id)
                RunPromptKind.TEXT -> listOf(
                    contextualAction("input", "Input", state.interactionGeneration, remoteInput = true),
                ).associateBy(ActionBinding::id)
                null -> emptyMap()
            }
        }
        return when (state.prompt) {
            RunPromptKind.YES_NO -> listOf(
                contextualActions.getValue("yes"),
                contextualActions.getValue("no"),
                interrupt,
            )
            RunPromptKind.TEXT -> listOf(contextualActions.getValue("input"), interrupt, terminate)
            null -> listOf(interrupt, terminate)
        }.map(ActionBinding::toWire)
    }

    private fun sessionAction(id: String, title: String, generation: Long): ActionBinding =
        sessionActions.getOrPut(id) {
            ActionBinding(id, allocateActionIndex("session\u0000$id"), title, generation, randomToken(), false, true)
        }

    private fun contextualAction(id: String, title: String, generation: Long, remoteInput: Boolean = false) =
        ActionBinding(
            id,
            allocateActionIndex("generation\u0000$generation\u0000$id"),
            title,
            generation,
            randomToken(),
            remoteInput,
            false,
        )

    private fun allocateActionIndex(material: String): Int {
        val used = sessionActions.values.mapTo(mutableSetOf(), ActionBinding::index).apply {
            addAll(contextualActions.values.map(ActionBinding::index))
        }
        var index = java.nio.ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray()))
            .int and Int.MAX_VALUE
        while (!used.add(index)) index = (index + 1) and Int.MAX_VALUE
        return index
    }

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    override fun close() {
        runCatching { api.unregisterReceive(controlInterest) }
        runCatching { api.unregisterReceive(actionInterest) }
    }

    private data class ActionBinding(
        val id: String,
        val index: Int,
        val title: String,
        val generation: Long,
        val token: String,
        val remoteInput: Boolean,
        val sessionLifetime: Boolean,
    ) {
        fun toWire() = NotificationAction(
            index = index,
            title = title,
            remoteInput = remoteInput,
            remoteInputLabel = "Send to command".takeIf { remoteInput },
            actionGeneration = generation,
            actionToken = token,
        )
    }

    companion object {
        fun connect(
            api: DaemonLocalApi,
            runId: String,
            random: SecureRandom = SecureRandom(),
        ): RunApplicationBridge {
            api.putApplication(
                APPLICATION_ID,
                ApplicationRegistrationRequest(
                    displayName = DISPLAY_NAME,
                    capabilities = setOf(Capability.CAPTURE, Capability.PUBLISH_RUNS),
                ),
            )
            val hostClientId = api.status().clientId?.takeIf(String::isNotBlank)?.let(::ClientId)
                ?: error("notisyncd has no active client identity")
            val sourceKey = ByteArray(24).also(random::nextBytes).let {
                "run:" + Base64.getUrlEncoder().withoutPadding().encodeToString(it)
            }
            return RunApplicationBridge(api, hostClientId, runId, sourceKey, random)
        }

        private const val APPLICATION_ID = "nsrun"
        private const val DISPLAY_NAME = "NotiSync Run"
        private const val MAX_REMOTE_INPUT_BYTES = 64 * 1024
        private const val MAX_TITLE_CHARS = 160
        private const val MAX_TEXT_CHARS = 512
        private const val MAX_EXPANDED_TEXT_CHARS = 2_048
        private val NATIVE_RUN_CAPABILITIES = setOf(
            Capability.RECEIVE_RUNS,
            Capability.DISPLAY,
            Capability.BACKGROUND_WAKE,
            Capability.PUSH_FILTERING,
        )
        private val IOS_FALLBACK_CAPABILITIES = setOf(Capability.DISPLAY, Capability.BACKGROUND_WAKE)
    }
}

internal data class RunReceiveStreams(
    val control: ReceiveStream,
    val action: ReceiveStream,
) : AutoCloseable {
    override fun close() {
        runCatching { control.close() }
        runCatching { action.close() }
    }
}

internal sealed interface RunInbound {
    data object Heartbeat : RunInbound
    data class Invalid(val envelopeId: String?) : RunInbound
    data class Control(
        val envelopeId: String,
        val sender: ClientId?,
        val senderOwnDevice: Boolean,
        val control: RunControl,
    ) : RunInbound
    data class Action(
        val envelopeId: String,
        val sender: ClientId?,
        val senderOwnDevice: Boolean,
        val event: ActionEvent,
    ) : RunInbound
}

internal data class RunActionCommand(val id: String, val inputText: String?)

internal fun runUrgency(reason: RunUpdateReason): Urgency = when (reason) {
    RunUpdateReason.INITIAL,
    RunUpdateReason.BLOCKED,
    RunUpdateReason.RESUMED,
    RunUpdateReason.COMPLETED,
    RunUpdateReason.FAILED,
    -> Urgency.HIGH
    RunUpdateReason.PERIODIC,
    RunUpdateReason.LLM_SUMMARY,
    RunUpdateReason.REFRESH,
    -> Urgency.NORMAL
}

private inline fun <reified T> encodeBody(value: T): String =
    Base64.getEncoder().encodeToString(ProtocolCodec.encodeToCbor(value))
