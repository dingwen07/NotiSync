package net.extrawdw.notisync.daemon

import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.ByteBuffer
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import net.extrawdw.notisync.daemon.peer.storage.DaemonDatabaseRepository
import net.extrawdw.notisync.daemon.peer.storage.StoredLocalSession
import net.extrawdw.notisync.daemon.peer.storage.StoredWireAction
import net.extrawdw.notisync.localapi.CreateSessionRequest
import net.extrawdw.notisync.localapi.LocalEvent
import net.extrawdw.notisync.localapi.LocalEventType
import net.extrawdw.notisync.localapi.LocalNotificationAction
import net.extrawdw.notisync.localapi.NotificationActionKind
import net.extrawdw.notisync.localapi.NotificationActionLifetime
import net.extrawdw.notisync.localapi.NotificationRequest
import net.extrawdw.notisync.localapi.LocalRunPromptKind
import net.extrawdw.notisync.localapi.RunStateRequest
import net.extrawdw.notisync.localapi.SessionResponse
import net.extrawdw.notisync.protocol.RunControl
import net.extrawdw.notisync.protocol.RunControlKind

class LocalAuthorizationException(message: String) : RuntimeException(message)
class LocalConflictException(message: String) : RuntimeException(message)
class LocalEventQueueFullException(message: String) : RuntimeException(message)

data class AuthorizedSession(
    val id: String,
    val sourceKey: String,
    val peer: LocalPeer,
    val clientName: String,
)

@Serializable
data class WireAction(
    val index: Int,
    val id: String,
    val title: String,
    val generation: Long,
    val remoteInput: Boolean,
    val remoteInputLabel: String?,
    val actionToken: String,
    val kind: NotificationActionKind = if (remoteInput) {
        NotificationActionKind.REMOTE_INPUT
    } else {
        NotificationActionKind.WRITE_INPUT
    },
    val lifetime: NotificationActionLifetime = NotificationActionLifetime.GENERATION,
    val signal: String? = null,
)

data class NotificationRegistration(
    val session: AuthorizedSession,
    val generation: Long,
    val sourceKey: String,
    val actions: List<WireAction>,
    val postTime: Long,
)

data class RunRegistration(
    val session: AuthorizedSession,
    val sourceKey: String,
    val postTime: Long,
    val actions: List<WireAction>,
)

data class AcceptedRunState(
    val registration: RunRegistration,
    val stateItem: PendingRunState,
    val iosItem: PendingNotification?,
)

enum class RunControlDelivery { ENQUEUED, DUPLICATE, NOT_ACTIVE, STALE, REJECTED }

/**
 * Process-bound local namespaces plus their durable-until-ACK event queues. This class contains no
 * Run semantics: an action is merely routed back to the process that registered the source.
 */
class LocalSessionRegistry(
    private val identityResolver: ProcessIdentityResolver,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val database: DaemonDatabaseRepository? = null,
) {
    private data class Session(
        val id: String,
        val sourceKey: String,
        val bearerHash: ByteArray,
        val peer: LocalPeer,
        val clientName: String,
        val createdAt: Long,
        var latestGeneration: Long = -1,
        var latestPostTime: Long = -1,
        var actions: List<WireAction> = emptyList(),
        val sessionActions: MutableList<WireAction> = mutableListOf(),
        val events: LinkedHashMap<String, LocalEvent> = linkedMapOf(),
        var runId: String? = null,
        var latestRunRevision: Long = -1,
        var interactionGeneration: Long = 0,
        var runActive: Boolean = false,
        var runPrompt: LocalRunPromptKind? = null,
        val handledRunControlRequestIds: LinkedHashMap<String, Long> = linkedMapOf(),
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val sessions = linkedMapOf<String, Session>()
    private val sourceToSession = linkedMapOf<String, String>()
    private val runToSession = linkedMapOf<String, String>()

    init {
        database?.load()?.sessions?.values?.forEach { stored ->
            val session = stored.toSession()
            // A verified process binding is meaningful only while that exact process still exists.
            if (!session.peer.processIdentityVerified || identityResolver.stillMatches(session.peer)) {
                sessions[session.id] = session
                sourceToSession[session.sourceKey] = session.id
                session.runId?.let { runToSession[it] = session.id }
            } else {
                database.update { it.copy(sessions = it.sessions - session.id) }
            }
        }
    }

    fun create(peer: LocalPeer, request: CreateSessionRequest): SessionResponse = lock.withLock {
        require(request.clientName.isNotBlank() && request.clientName.length <= 128) {
            "clientName must contain 1..128 characters"
        }
        val requestedSourceName = request.requestedSourceName
        require(requestedSourceName == null || requestedSourceName.length <= 128) {
            "requestedSourceName is too long"
        }
        require(request.metadata.size <= 32 && request.metadata.all { it.key.length <= 64 && it.value.length <= 512 }) {
            "session metadata is too large"
        }
        val id = UUID.randomUUID().toString()
        val bearer = randomToken(32)
        val sourceKey = sourceKey(peer, id)
        val session = Session(
            id = id,
            sourceKey = sourceKey,
            bearerHash = digest(bearer.encodeToByteArray()),
            peer = peer,
            clientName = request.clientName,
            createdAt = clock.millis(),
        )
        sessions[id] = session
        sourceToSession[sourceKey] = id
        persistLocked(session)
        SessionResponse(id, sourceKey, bearer, peer.processIdentityVerified)
    }

    fun authorize(sessionId: String, bearer: String?, peer: LocalPeer): AuthorizedSession = lock.withLock {
        val session = sessions[sessionId] ?: throw LocalAuthorizationException("unknown local session")
        if (peer.uid != session.peer.uid) throw LocalAuthorizationException("session uid does not match")
        val suppliedHash = bearer?.let { digest(it.encodeToByteArray()) }
        if (suppliedHash == null || !MessageDigest.isEqual(session.bearerHash, suppliedHash)) {
            throw LocalAuthorizationException("invalid session bearer")
        }
        if (session.peer.processIdentityVerified) {
            if (peer.pid != session.peer.pid || peer.startTime != session.peer.startTime) {
                throw LocalAuthorizationException("session belongs to another process")
            }
            if (!identityResolver.stillMatches(session.peer)) {
                expireLocked(session)
                throw LocalAuthorizationException("session process has exited or its pid was reused")
            }
        }
        session.authorized()
    }

    fun authorizeNotificationGeneration(
        sessionId: String,
        generation: Long,
        bearer: String?,
        peer: LocalPeer,
    ): AuthorizedSession = lock.withLock {
        val authorized = authorize(sessionId, bearer, peer)
        val session = sessions.getValue(sessionId)
        if (generation != session.latestGeneration) {
            throw LocalConflictException("stale notification generation")
        }
        authorized
    }

    fun close(sessionId: String, bearer: String?, peer: LocalPeer) {
        authorize(sessionId, bearer, peer)
        lock.withLock { sessions[sessionId]?.let(::removeLocked) }
    }

    fun registerNotification(
        request: NotificationRequest,
        bearer: String?,
        peer: LocalPeer,
        postTimeCandidate: Long = clock.millis(),
    ): NotificationRegistration = lock.withLock {
        val authorized = authorize(request.sessionId, bearer, peer)
        val session = sessions.getValue(request.sessionId)
        val registration = prepareNotificationLocked(request, authorized, session, postTimeCandidate)
        persistLocked(session)
        registration
    }

    fun registerRunState(
        request: RunStateRequest,
        iosProjection: NotificationRequest?,
        bearer: String?,
        peer: LocalPeer,
        postTimeCandidate: Long = clock.millis(),
    ): RunRegistration = lock.withLock {
        val session = authorize(request.sessionId, bearer, peer).let { sessions.getValue(it.id) }
        val previous = session.toStored()
        try {
            prepareRunStateLocked(request, iosProjection, bearer, peer, postTimeCandidate).also {
                persistLocked(session)
            }
        } catch (error: Exception) {
            restoreSessionLocked(previous)
            throw error
        }
    }

    /**
     * Accept a complete Run snapshot and its two platform delivery intents as one transaction.
     * Production's file-backed outboxes share [database], while in-memory tests receive equivalent
     * all-or-rollback behavior.
     */
    fun acceptRunState(
        request: RunStateRequest,
        iosProjection: NotificationRequest?,
        bearer: String?,
        peer: LocalPeer,
        acceptedAt: Long,
        runItemId: String,
        iosItemId: String?,
        runOutbox: RunOutbox,
        iosOutbox: RunIosNotificationOutbox,
    ): AcceptedRunState = lock.withLock {
        val session = authorize(request.sessionId, bearer, peer).let { sessions.getValue(it.id) }
        val previous = session.toStored()
        try {
            val registration = prepareRunStateLocked(request, iosProjection, bearer, peer, acceptedAt)
            val stateItem = PendingRunState(runItemId, registration.sourceKey, request, acceptedAt)
            val iosItem = iosProjection?.let { projection ->
                PendingNotification(
                    id = requireNotNull(iosItemId) { "iOS Run projection id is missing" },
                    sourceKey = registration.sourceKey,
                    request = projection,
                    postTime = registration.postTime,
                    acceptedAt = acceptedAt,
                    actions = registration.actions,
                    audience = NotificationAudience.RUN_IOS_COMPAT,
                )
            }
            if (database != null) {
                require(runOutbox === database && iosOutbox === database) {
                    "durable Run acceptance requires the registry's shared database outboxes"
                }
                database.persistRunAcceptance(session.toStored(), stateItem, iosItem)
            } else {
                try {
                    runOutbox.enqueue(stateItem)
                    iosItem?.let(iosOutbox::enqueueIos)
                } catch (error: Exception) {
                    runCatching { runOutbox.removeRun(stateItem.id) }
                    iosItem?.let { runCatching { iosOutbox.removeIos(it.id) } }
                    throw error
                }
            }
            AcceptedRunState(registration, stateItem, iosItem)
        } catch (error: Exception) {
            restoreSessionLocked(previous)
            throw error
        }
    }

    fun deliverRunControl(
        control: RunControl,
        senderClientId: String,
        senderIsTrustedOwnDevice: Boolean,
        relayMessageId: String,
    ): RunControlDelivery = lock.withLock {
        if (!senderIsTrustedOwnDevice) return RunControlDelivery.REJECTED
        if (database?.seen(relayMessageId) == true) return RunControlDelivery.DUPLICATE
        val session = runToSession[control.runId]?.let(sessions::get) ?: return RunControlDelivery.NOT_ACTIVE
        if (session.peer.processIdentityVerified && !identityResolver.stillMatches(session.peer)) {
            expireLocked(session)
            return RunControlDelivery.NOT_ACTIVE
        }
        if (!session.runActive) return RunControlDelivery.NOT_ACTIVE
        if (control.requestId in session.handledRunControlRequestIds) return RunControlDelivery.DUPLICATE
        if (control.kind == RunControlKind.WRITE_INPUT) {
            if (session.runPrompt == null) return RunControlDelivery.REJECTED
            if (control.interactionGeneration != session.interactionGeneration) return RunControlDelivery.STALE
        }

        val event = LocalEvent(
            id = UUID.randomUUID().toString(),
            type = LocalEventType.RUN_CONTROL,
            sessionId = session.id,
            createdAtEpochMillis = clock.millis(),
            inputText = control.inputText,
            senderClientId = senderClientId,
            requestId = control.requestId,
            runId = control.runId,
            runControlKind = net.extrawdw.notisync.localapi.LocalRunControlKind.valueOf(control.kind.name),
            interactionGeneration = control.interactionGeneration,
            signal = control.signal,
        )
        session.handledRunControlRequestIds[control.requestId] = clock.millis()
        while (session.handledRunControlRequestIds.size > MAXIMUM_RUN_CONTROL_IDS) {
            session.handledRunControlRequestIds.remove(session.handledRunControlRequestIds.keys.first())
        }
        try {
            enqueueLocked(session, event, relayMessageId)
        } catch (error: Exception) {
            session.handledRunControlRequestIds.remove(control.requestId)
            throw error
        }
        RunControlDelivery.ENQUEUED
    }

    fun runControlEvent(
        sessionId: String,
        eventId: String,
        bearer: String?,
        peer: LocalPeer,
    ): LocalEvent = lock.withLock {
        authorize(sessionId, bearer, peer)
        val event = sessions.getValue(sessionId).events[eventId]
            ?: throw LocalConflictException("Run control event is no longer pending")
        if (event.type != LocalEventType.RUN_CONTROL) {
            throw LocalConflictException("event is not a Run control")
        }
        event
    }

    fun completeRunControl(
        sessionId: String,
        eventId: String,
        bearer: String?,
        peer: LocalPeer,
        result: PendingRunControlResult,
        resultOutbox: RunResultOutbox,
    ) = lock.withLock {
        authorize(sessionId, bearer, peer)
        val session = sessions.getValue(sessionId)
        val event = session.events[eventId]
            ?: throw LocalConflictException("Run control event is no longer pending")
        if (event.type != LocalEventType.RUN_CONTROL) {
            throw LocalConflictException("event is not a Run control")
        }
        val previousEvents = LinkedHashMap(session.events)
        session.events.remove(eventId)
        try {
            if (database != null) {
                database.persistSessionAndEnqueueRunResult(session.toStored(), result)
            } else {
                resultOutbox.enqueueResult(result)
            }
        } catch (error: Exception) {
            session.events.clear()
            session.events.putAll(previousEvents)
            throw error
        }
    }

    /** Internal inspection seam used by the dispatcher and socket-level tests; never exposed by UDS. */
    internal fun registeredActions(sessionId: String): List<WireAction> = lock.withLock {
        sessions[sessionId]?.actions?.toList().orEmpty()
    }

    /** Returns false for an untrusted, stale, mismatched, or no-longer-live action. */
    fun deliverWireAction(
        sourceKey: String,
        actionIndex: Int,
        actionTitle: String?,
        inputText: String?,
        senderClientId: String,
        senderIsTrustedOwnDevice: Boolean,
        actionGeneration: Long?,
        actionToken: String?,
        relayMessageId: String,
    ): Boolean = lock.withLock {
        if (!senderIsTrustedOwnDevice) return false
        if ((inputText?.length ?: 0) > MAX_REMOTE_INPUT_CHARS) return false
        if (database?.seen(relayMessageId) == true) return true
        val session = sourceToSession[sourceKey]?.let(sessions::get) ?: return false
        if (session.peer.processIdentityVerified && !identityResolver.stillMatches(session.peer)) {
            expireLocked(session)
            return false
        }
        val action = session.sessionActions.firstOrNull { it.index == actionIndex }
            ?: session.actions.firstOrNull { it.index == actionIndex }
            ?: return false
        if (actionTitle == null || action.title != actionTitle) return false
        if (actionGeneration == null || action.generation != actionGeneration) return false
        if (actionToken == null || !constantTimeEquals(action.actionToken, actionToken)) return false
        if (!action.remoteInput && inputText != null) return false
        enqueueLocked(
            session,
            LocalEvent(
                id = UUID.randomUUID().toString(),
                type = LocalEventType.ACTION,
                sessionId = session.id,
                createdAtEpochMillis = clock.millis(),
                generation = action.generation,
                actionId = action.id,
                inputText = inputText,
                senderClientId = senderClientId,
            ),
            relayMessageId,
        )
        true
    }

    fun deliverDismissal(
        sourceKey: String,
        senderClientId: String,
        senderIsTrustedOwnDevice: Boolean,
        relayMessageId: String,
    ): Boolean =
        lock.withLock {
            if (!senderIsTrustedOwnDevice) return false
            if (database?.seen(relayMessageId) == true) return true
            val session = sourceToSession[sourceKey]?.let(sessions::get) ?: return false
            if (session.peer.processIdentityVerified && !identityResolver.stillMatches(session.peer)) {
                expireLocked(session)
                return false
            }
            enqueueLocked(
                session,
                LocalEvent(
                    id = UUID.randomUUID().toString(),
                    type = LocalEventType.DISMISSAL,
                    sessionId = session.id,
                    createdAtEpochMillis = clock.millis(),
                    generation = session.latestGeneration.takeIf { it >= 0 },
                    senderClientId = senderClientId,
                ),
                relayMessageId,
            )
            true
        }

    /** Returns the oldest unacknowledged event, waiting for a producer when necessary. */
    fun awaitEvent(
        sessionId: String,
        bearer: String?,
        peer: LocalPeer,
        waitMillis: Long,
    ): LocalEvent? = lock.withLock {
        authorize(sessionId, bearer, peer)
        sessions[sessionId]?.events?.values?.firstOrNull()?.let { return it }
        if (waitMillis > 0) changed.awaitNanos(waitMillis * 1_000_000)
        authorize(sessionId, bearer, peer)
        sessions[sessionId]?.events?.values?.firstOrNull()
    }

    fun acknowledge(sessionId: String, eventId: String, bearer: String?, peer: LocalPeer): Boolean = lock.withLock {
        authorize(sessionId, bearer, peer)
        val session = sessions.getValue(sessionId)
        val removed = session.events.remove(eventId) != null
        if (removed) persistLocked(session)
        removed
    }

    fun shutdownEvents() = lock.withLock {
        sessions.values.forEach { session ->
            runCatching {
                enqueueLocked(
                    session,
                    LocalEvent(
                        id = UUID.randomUUID().toString(),
                        type = LocalEventType.DAEMON_SHUTDOWN,
                        sessionId = session.id,
                        createdAtEpochMillis = clock.millis(),
                    ),
                )
            }
        }
        changed.signalAll()
    }

    private fun enqueueLocked(session: Session, event: LocalEvent, relayMessageId: String? = null) {
        // Never evict an unacknowledged actionable event. Throwing propagates through the peer handler as a
        // retryable delivery failure, so the broker retains and redelivers the encrypted relay item after the
        // local client drains/ACKs its queue.
        if (session.events.size >= MAXIMUM_UNACKNOWLEDGED_EVENTS) {
            throw LocalEventQueueFullException("local session event queue is full")
        }
        session.events[event.id] = event
        try {
            if (relayMessageId == null) {
                persistLocked(session)
            } else {
                database?.persistSessionAndRecordRelay(session.toStored(), relayMessageId)
            }
        } catch (error: Exception) {
            // Keep memory aligned with the failed transaction. A broker retry can safely enqueue again
            // because neither the event nor the message-id marker was committed.
            session.events.remove(event.id)
            throw error
        }
        changed.signalAll()
    }

    private fun expireLocked(session: Session) {
        removeLocked(session)
        changed.signalAll()
    }

    private fun removeLocked(session: Session) {
        sessions.remove(session.id)
        sourceToSession.remove(session.sourceKey)
        session.runId?.let(runToSession::remove)
        database?.update { it.copy(sessions = it.sessions - session.id) }
    }

    private fun persistLocked(session: Session) {
        val stored = session.toStored()
        database?.update { it.copy(sessions = it.sessions + (session.id to stored)) }
    }

    private fun prepareNotificationLocked(
        request: NotificationRequest,
        authorized: AuthorizedSession,
        session: Session,
        postTimeCandidate: Long,
    ): NotificationRegistration {
        if (request.generation < session.latestGeneration) {
            throw LocalConflictException("stale notification generation")
        }
        require(request.actions.size <= 3) { "at most three notification actions are supported" }
        require(request.actions.map(LocalNotificationAction::id).distinct().size == request.actions.size) {
            "notification action ids must be unique"
        }
        require(request.actions.all { it.generation == request.generation }) {
            "every action must carry the notification generation"
        }
        request.actions.forEach { action ->
            require(action.id.isNotBlank() && action.id.length <= 64) { "invalid action id" }
            require(action.title.isNotBlank() && action.title.length <= 80) { "invalid action title" }
            require(
                action.lifetime != NotificationActionLifetime.SESSION ||
                    action.kind == NotificationActionKind.SIGNAL,
            ) { "session-lifetime actions must be process signals" }
            if (action.kind == NotificationActionKind.SIGNAL) {
                val signal = action.signal
                require(!signal.isNullOrBlank() && signal.length <= 32) {
                    "signal actions require a valid signal name"
                }
            }
        }
        val actions = resolveWireActions(session, request) { randomToken(32) }
        session.latestGeneration = request.generation
        session.latestPostTime = maxOf(postTimeCandidate, session.latestPostTime + 1)
        session.actions = actions
        return NotificationRegistration(
            authorized,
            request.generation,
            session.sourceKey,
            actions,
            session.latestPostTime,
        )
    }

    private fun prepareRunStateLocked(
        request: RunStateRequest,
        iosProjection: NotificationRequest?,
        bearer: String?,
        peer: LocalPeer,
        postTimeCandidate: Long,
    ): RunRegistration {
        val authorized = authorize(request.sessionId, bearer, peer)
        val session = sessions.getValue(request.sessionId)
        if (session.runId != null && session.runId != request.runId) {
            throw LocalConflictException("local session is already bound to another Run")
        }
        if (request.revision <= session.latestRunRevision) {
            throw LocalConflictException("stale Run revision")
        }
        if (request.interactionGeneration < session.interactionGeneration) {
            throw LocalConflictException("stale Run interaction generation")
        }
        val notification = iosProjection?.let {
            require(it.sessionId == request.sessionId) { "iOS projection session does not match Run" }
            prepareNotificationLocked(it, authorized, session, postTimeCandidate)
        }
        session.runId = request.runId
        runToSession[request.runId] = session.id
        session.latestRunRevision = request.revision
        session.interactionGeneration = request.interactionGeneration
        session.runActive = request.phase == net.extrawdw.notisync.localapi.LocalRunPhase.RUNNING ||
            request.phase == net.extrawdw.notisync.localapi.LocalRunPhase.BLOCKED
        session.runPrompt = request.prompt.takeIf { session.runActive }
        if (notification == null) {
            session.latestPostTime = maxOf(postTimeCandidate, session.latestPostTime + 1)
        }
        return RunRegistration(
            session = authorized,
            sourceKey = session.sourceKey,
            postTime = notification?.postTime ?: session.latestPostTime,
            actions = notification?.actions.orEmpty(),
        )
    }

    private fun restoreSessionLocked(stored: StoredLocalSession) {
        sessions[stored.id]?.runId?.let(runToSession::remove)
        val restored = stored.toSession()
        sessions[stored.id] = restored
        sourceToSession[stored.sourceKey] = stored.id
        restored.runId?.let { runToSession[it] = restored.id }
    }

    private fun StoredLocalSession.toSession() = Session(
        id = id,
        sourceKey = sourceKey,
        bearerHash = bearerHash.copyOf(),
        peer = LocalPeer(uid, pid, startTime),
        clientName = clientName,
        createdAt = createdAt,
        latestGeneration = latestGeneration,
        latestPostTime = latestPostTime,
        actions = actions.map {
            WireAction(
                it.index,
                it.id,
                it.title,
                it.generation,
                it.remoteInput,
                it.remoteInputLabel,
                it.actionToken,
                it.kind,
                it.lifetime,
                it.signal,
            )
        },
        sessionActions = sessionActions.mapTo(mutableListOf()) {
            WireAction(
                it.index,
                it.id,
                it.title,
                it.generation,
                it.remoteInput,
                it.remoteInputLabel,
                it.actionToken,
                it.kind,
                it.lifetime,
                it.signal,
            )
        },
        events = LinkedHashMap(events),
        runId = runId,
        latestRunRevision = latestRunRevision,
        interactionGeneration = interactionGeneration,
        runActive = runActive,
        runPrompt = runPrompt,
        handledRunControlRequestIds = LinkedHashMap(handledRunControlRequestIds),
    )

    private fun Session.toStored() = StoredLocalSession(
        id = id,
        sourceKey = sourceKey,
        bearerHash = bearerHash.copyOf(),
        uid = peer.uid,
        pid = peer.pid,
        startTime = peer.startTime,
        clientName = clientName,
        createdAt = createdAt,
        latestGeneration = latestGeneration,
        latestPostTime = latestPostTime,
        actions = actions.map {
            StoredWireAction(
                it.index, it.id, it.title, it.generation, it.remoteInput, it.remoteInputLabel, it.actionToken,
                it.kind, it.lifetime,
                it.signal,
            )
        },
        sessionActions = sessionActions.map {
            StoredWireAction(
                it.index, it.id, it.title, it.generation, it.remoteInput, it.remoteInputLabel, it.actionToken,
                it.kind, it.lifetime,
                it.signal,
            )
        },
        events = LinkedHashMap(events),
        runId = runId,
        latestRunRevision = latestRunRevision,
        interactionGeneration = interactionGeneration,
        runActive = runActive,
        runPrompt = runPrompt,
        handledRunControlRequestIds = LinkedHashMap(handledRunControlRequestIds),
    )

    private fun resolveWireActions(
        session: Session,
        request: NotificationRequest,
        actionToken: () -> String,
    ): List<WireAction> {
        val retainedById = session.sessionActions.associateBy(WireAction::id)
        request.actions.forEach { requested ->
            val retained = retainedById[requested.id] ?: return@forEach
            require(requested.lifetime == NotificationActionLifetime.SESSION) {
                "generation action id conflicts with a session-lifetime action"
            }
            require(retained.matches(requested)) {
                "session-lifetime action definitions cannot change before the session closes"
            }
        }

        val reusableGenerationActions = session.actions
            .filter { it.generation == request.generation && it.lifetime == NotificationActionLifetime.GENERATION }
            .associateBy(WireAction::id)
        val usedIndices = session.sessionActions.mapTo(mutableSetOf(), WireAction::index).apply {
            addAll(reusableGenerationActions.values.map(WireAction::index))
        }
        request.actions
            .filter { it.lifetime == NotificationActionLifetime.SESSION && it.id !in retainedById }
            .forEach { requested ->
                require(session.sessionActions.size < MAXIMUM_SESSION_ACTIONS) {
                    "too many session-lifetime actions"
                }
                session.sessionActions += requested.toWireAction(
                    index = allocateWireIndex("session\u0000${requested.id}", usedIndices),
                    generation = request.generation,
                    actionToken = actionToken(),
                )
            }

        val currentSessionActions = session.sessionActions.associateBy(WireAction::id)
        return request.actions.mapIndexed { ordinal, requested ->
            if (requested.lifetime == NotificationActionLifetime.SESSION) {
                currentSessionActions.getValue(requested.id)
            } else {
                reusableGenerationActions[requested.id]?.takeIf { it.matches(requested) }
                    ?: requested.toWireAction(
                        index = allocateWireIndex(
                            "generation\u0000${request.generation}\u0000$ordinal\u0000${requested.id}",
                            usedIndices,
                        ),
                        generation = request.generation,
                        actionToken = actionToken(),
                    )
            }
        }
    }

    private fun Session.authorized() = AuthorizedSession(id, sourceKey, peer, clientName)

    private fun sourceKey(peer: LocalPeer, sessionId: String): String {
        val material = buildString {
            append("notisyncd-source-v1\u0000")
            append(peer.uid).append('\u0000')
            append(peer.pid ?: "unknown").append('\u0000')
            append(peer.startTime ?: "unverified").append('\u0000')
            append(sessionId)
        }.encodeToByteArray()
        return "local:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest(material))
    }

    private fun randomToken(size: Int): String = ByteArray(size).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun constantTimeEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.encodeToByteArray(),
        actual.encodeToByteArray(),
    )

    private companion object {
        const val MAX_REMOTE_INPUT_CHARS = 64 * 1024
        const val MAXIMUM_UNACKNOWLEDGED_EVENTS = 1_024
        const val MAXIMUM_SESSION_ACTIONS = 16
        const val MAXIMUM_RUN_CONTROL_IDS = 1_024
    }
}

private fun WireAction.matches(action: LocalNotificationAction): Boolean =
    id == action.id &&
        title == action.title &&
        kind == action.kind &&
        remoteInputLabel == action.remoteInputLabel &&
        lifetime == action.lifetime &&
        signal == action.signal

private fun LocalNotificationAction.toWireAction(
    index: Int,
    generation: Long,
    actionToken: String,
) = WireAction(
    index = index,
    id = id,
    title = title,
    generation = generation,
    remoteInput = kind == NotificationActionKind.REMOTE_INPUT,
    remoteInputLabel = remoteInputLabel,
    actionToken = actionToken,
    kind = kind,
    lifetime = lifetime,
    signal = signal,
)

/** NotificationAction.index is origin-scoped, not a display position. */
private fun allocateWireIndex(material: String, used: MutableSet<Int>): Int {
    var index = ByteBuffer.wrap(
        MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray()),
    ).int and Int.MAX_VALUE
    while (!used.add(index)) index = (index + 1) and Int.MAX_VALUE
    return index
}
