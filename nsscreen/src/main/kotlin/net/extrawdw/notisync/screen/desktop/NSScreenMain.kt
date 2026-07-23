package net.extrawdw.notisync.screen.desktop

import java.io.BufferedReader
import java.io.InputStreamReader
import net.extrawdw.notisync.desktop.DesktopPaths
import net.extrawdw.notisync.desktop.DesktopProcessTitle
import net.extrawdw.notisync.desktop.api.DaemonAutostarter

fun main(arguments: Array<String>) {
    DesktopProcessTitle.set("nsscreen")
    kotlin.system.exitProcess(NSScreenApplication().execute(arguments))
}

internal class NSScreenApplication(
    private val paths: DesktopPaths = DesktopPaths.default(),
    private val runnerFactory: () -> NSScreenRunner = {
        NSScreenRunner(
            daemonConnector = { DaemonAutostarter(paths).connect() },
            helper = NativeHelperBridge(),
        )
    },
    private val stdout: Appendable = System.out,
    private val stderr: Appendable = System.err,
    private val input: BufferedReader = BufferedReader(InputStreamReader(System.`in`)),
    private val interactive: Boolean = System.console() != null,
) {
    fun execute(arguments: Array<String>): Int = try {
        when (val invocation = NSScreenCli.parse(arguments)) {
            ScreenInvocation.Help -> stdout.appendLine(NSScreenCli.usage())
            ScreenInvocation.Devices -> runnerFactory().listDevices(stdout)
            is ScreenInvocation.Connect -> runnerFactory().connect(
                invocation.options,
                stdout,
                input,
                interactive,
            )
        }
        0
    } catch (error: ScreenCliException) {
        stderr.appendLine("nsscreen: ${error.message}")
        stderr.appendLine(NSScreenCli.usage())
        2
    } catch (error: Exception) {
        stderr.appendLine("nsscreen: ${error.message ?: error.javaClass.simpleName}")
        1
    }
}
