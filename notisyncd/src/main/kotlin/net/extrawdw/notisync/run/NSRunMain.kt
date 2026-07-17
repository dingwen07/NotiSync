package net.extrawdw.notisync.run

import net.extrawdw.notisync.desktop.config.NSRunConfigStore
import net.extrawdw.notisync.desktop.config.NSRunConfig
import net.extrawdw.notisync.daemon.DesktopProcessTitle

fun main(arguments: Array<String>) {
    DesktopProcessTitle.set("nsrun")
    kotlin.system.exitProcess(NSRunApplication().execute(arguments))
}

class NSRunApplication(
    private val configStore: NSRunConfigStore = NSRunConfigStore(),
    private val runnerFactory: () -> NSRunRunner = ::NSRunRunner,
    private val stdout: Appendable = System.out,
    private val stderr: Appendable = System.err,
) {
    fun execute(arguments: Array<String>): Int = try {
        // Config commands deliberately retain strict parsing so a user can diagnose and repair a
        // bad file. A command invocation recovers independently and must still execute.
        val config = if (arguments.firstOrNull() == "config") NSRunConfig() else {
            configStore.loadRecovering { message -> stderr.appendLine("nsrun: $message") }
        }
        val invocation = NSRunCli.parse(arguments, config)
        when (invocation) {
            CliInvocation.Help -> {
                stdout.appendLine(NSRunCli.usage())
                0
            }
            is CliInvocation.Config -> {
                NSRunConfigCommand(configStore).execute(invocation.arguments, stdout)
                0
            }
            is CliInvocation.Run -> runnerFactory().run(invocation.options)
        }
    } catch (error: CliUsageException) {
        stderr.appendLine("nsrun: ${error.message}")
        stderr.appendLine(NSRunCli.usage())
        2
    } catch (error: Exception) {
        stderr.appendLine("nsrun: ${error.message ?: error.javaClass.simpleName}")
        1
    }
}
