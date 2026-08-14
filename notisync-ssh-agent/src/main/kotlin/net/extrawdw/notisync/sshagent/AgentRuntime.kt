package net.extrawdw.notisync.sshagent

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.api.DaemonAutostarter
import net.extrawdw.notisync.protocol.SshProcessContextSource
import net.extrawdw.notisync.sshagent.bridge.InboundSshSyncLoop
import net.extrawdw.notisync.sshagent.bridge.ProviderRoster
import net.extrawdw.notisync.sshagent.bridge.SshApplicationBridge
import net.extrawdw.notisync.sshagent.cache.AgentDatabase
import net.extrawdw.notisync.sshagent.cache.AgentMetadataStore
import net.extrawdw.notisync.sshagent.cache.AuthorizationForgetOutbox
import net.extrawdw.notisync.sshagent.cache.ProviderSnapshotStore
import net.extrawdw.notisync.sshagent.endpoint.AgentConnectionHandler
import net.extrawdw.notisync.sshagent.endpoint.AgentEndpoint
import net.extrawdw.notisync.sshagent.endpoint.AgentLockState
import net.extrawdw.notisync.sshagent.endpoint.AuthorizationLockCoordinator
import net.extrawdw.notisync.sshagent.endpoint.CompositeAgentEndpoint
import net.extrawdw.notisync.sshagent.endpoint.LocalCallerResolver
import net.extrawdw.notisync.sshagent.endpoint.NamedPipeConflictException
import net.extrawdw.notisync.sshagent.endpoint.PreferredAgentEndpoint
import net.extrawdw.notisync.sshagent.endpoint.UnixAgentEndpoint
import net.extrawdw.notisync.sshagent.endpoint.WINDOWS_OPENSSH_PIPE
import net.extrawdw.notisync.sshagent.endpoint.WindowsNamedPipeEndpoint
import net.extrawdw.notisync.sshagent.endpoint.agentEndpointAddresses
import net.extrawdw.notisync.sshagent.endpoint.isWindowsNamedPipeAddress
import net.extrawdw.notisync.sshagent.endpoint.isWindows
import net.extrawdw.notisync.sshagent.endpoint.windowsCustomAgentAddress
import net.extrawdw.notisync.sshagent.signing.CompositeSshInboundHandler
import net.extrawdw.notisync.sshagent.signing.ImportCoordinator
import net.extrawdw.notisync.sshagent.signing.SignCoordinator

class AgentRuntime(
    private val paths: DesktopPaths = DesktopPaths.default(),
    private val output: Appendable = System.out,
    private val explicitBindAddresses: List<String> = emptyList(),
    private val onReady: (List<String>) -> Unit = {},
) {
    fun run() {
        val config = AgentConfigStore(paths.sshAgentConfig).load()
        val bindAddresses = agentEndpointAddresses(paths, config, explicitBindAddresses)
        val api = DaemonAutostarter(paths).connect()
        AgentDatabase(paths.sshAgentDatabase).use { database ->
            val roster = ProviderRoster(api)
            val bridge = SshApplicationBridge(api, roster)
            val requester = bridge.register()
            val snapshots = ProviderSnapshotStore(database)
            val metadata = AgentMetadataStore(database)
            val forgetOutbox = AuthorizationForgetOutbox(database)
            metadata.authorizationNamespace()
            val signing = SignCoordinator(requester, config, roster, snapshots, metadata, bridge, database)
            val importer = ImportCoordinator(requester, config, roster, bridge)
            val lockState = AgentLockState()
            val lock = AuthorizationLockCoordinator(
                requester,
                lockState,
                metadata,
                roster,
                bridge,
                signing,
                forgetOutbox,
            )
            val callerResolver = LocalCallerResolver()
            val connection = AgentConnectionHandler(
                signing,
                snapshots,
                lock,
                callerResolver,
                importer,
                config.maximumInFlightRequests,
            )
            val binding = createEndpoint(config, bindAddresses, connection, callerResolver)
            val endpoint = binding.endpoint
            val connectedBefore = AtomicBoolean(false)
            val inbound = InboundSshSyncLoop(
                api,
                snapshots,
                CompositeSshInboundHandler(signing, importer),
                onConnected = {
                    bridge.register()
                    if (connectedBefore.getAndSet(true)) runCatching {
                        bridge.requestInventory(requester, startup = false)
                    }
                },
            )
            val receiver = Thread.ofVirtual().name("notisync-ssh-agent-receive").start(inbound::run)
            val refresh = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("notisync-ssh-agent-refresh").factory(),
            )
            refresh.scheduleWithFixedDelay(
                {
                    runCatching { drainForgetOutbox(forgetOutbox, bridge) }
                    runCatching { bridge.requestInventory(requester, startup = false) }
                },
                config.refreshIntervalMinutes,
                config.refreshIntervalMinutes,
                TimeUnit.MINUTES,
            )
            val shutdown = Thread({
                runCatching { endpoint.close() }
                runCatching { inbound.close() }
                refresh.shutdownNow()
            }, "notisync-ssh-agent-shutdown")
            Runtime.getRuntime().addShutdownHook(shutdown)
            try {
                runCatching { drainForgetOutbox(forgetOutbox, bridge) }
                runCatching { bridge.requestInventory(requester, startup = true) }
                    .onFailure { output.appendLine("Initial SSH key refresh deferred: ${it.message}") }
                endpoint.run {
                    val activeAddresses = binding.activeAddresses()
                    onReady(activeAddresses)
                    activeAddresses.forEach { output.appendLine("NotiSync SSH Agent ready at $it") }
                }
            } finally {
                endpoint.close()
                inbound.close()
                receiver.interrupt()
                receiver.join(5_000)
                refresh.shutdownNow()
                if (Thread.currentThread() !== shutdown) {
                    runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
                }
            }
        }
    }

    private fun drainForgetOutbox(outbox: AuthorizationForgetOutbox, bridge: SshApplicationBridge) {
        outbox.pending().forEach { forget ->
            bridge.sendNormal(
                net.extrawdw.notisync.protocol.SshAgentSync(
                    kind = net.extrawdw.notisync.protocol.SshAgentSyncKind.FORGET_AUTHORIZATION,
                    forgetAuthorization = forget,
                ),
                forget.targetProviderClientIds,
            )
            outbox.markAccepted(forget.requestId)
        }
    }

    private fun createEndpoint(
        config: AgentConfig,
        bindAddresses: List<String>,
        connection: AgentConnectionHandler,
        callerResolver: LocalCallerResolver,
    ): EndpointBinding {
        if (isWindows() && explicitBindAddresses.isEmpty() && config.endpointMode == AgentEndpointMode.AUTO) {
            val customAddress = windowsCustomAgentAddress(paths)
            val preferred = PreferredAgentEndpoint(
                createSingleEndpoint(WINDOWS_OPENSSH_PIPE, config, connection, callerResolver),
                createSingleEndpoint(customAddress, config, connection, callerResolver),
            ) { it is NamedPipeConflictException }
            return EndpointBinding(preferred) {
                listOf(
                    if (preferred.selection == PreferredAgentEndpoint.Selection.FALLBACK) {
                        customAddress
                    } else {
                        WINDOWS_OPENSSH_PIPE
                    },
                )
            }
        }
        val endpoints = bindAddresses.map { createSingleEndpoint(it, config, connection, callerResolver) }
        return EndpointBinding(
            if (endpoints.size == 1) endpoints.single() else CompositeAgentEndpoint(endpoints),
        ) { bindAddresses }
    }

    private fun createSingleEndpoint(
        address: String,
        config: AgentConfig,
        connection: AgentConnectionHandler,
        callerResolver: LocalCallerResolver,
    ): AgentEndpoint = if (isWindowsNamedPipeAddress(address)) {
        check(isWindows()) { "named-pipe SSH Agent endpoints are supported only on Windows" }
        WindowsNamedPipeEndpoint(
            address,
            { input, output, pid ->
                connection.handle(
                    input,
                    output,
                    callerResolver.resolve(pid, SshProcessContextSource.NAMED_PIPE_CLIENT_PID),
                )
            },
            config.maximumConnections,
        )
    } else {
        UnixAgentEndpoint(java.nio.file.Path.of(address), connection::handle, config.maximumConnections)
    }

    private data class EndpointBinding(
        val endpoint: AgentEndpoint,
        val activeAddresses: () -> List<String>,
    )
}
