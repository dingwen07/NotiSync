package net.extrawdw.notisync.run.process

import java.lang.reflect.Proxy

/** Forwards externally delivered INT/TERM to the child while letting nsrun finish reporting/restoration. */
class UnixSignalBridge private constructor(
    private val signalType: Class<*>,
    private val handlerType: Class<*>,
    private val registrations: List<Registration>,
) : AutoCloseable {
    override fun close() {
        val handle = signalType.getMethod("handle", signalType, handlerType)
        registrations.asReversed().forEach { registration ->
            runCatching { handle.invoke(null, registration.signal, registration.previousHandler) }
        }
    }

    private data class Registration(val signal: Any, val previousHandler: Any)

    companion object {
        fun install(child: ManagedChild): AutoCloseable {
            if (System.getProperty("os.name").contains("windows", ignoreCase = true)) return AutoCloseable {}
            return runCatching {
                val signalType = Class.forName("sun.misc.Signal")
                val handlerType = Class.forName("sun.misc.SignalHandler")
                val handle = signalType.getMethod("handle", signalType, handlerType)
                val constructor = signalType.getConstructor(String::class.java)
                val registrations = mutableListOf<Registration>()
                try {
                    listOf("INT" to ChildSignal.INTERRUPT, "TERM" to ChildSignal.TERMINATE).forEach { (name, childSignal) ->
                        val signal = constructor.newInstance(name)
                        val handler = Proxy.newProxyInstance(
                            handlerType.classLoader,
                            arrayOf(handlerType),
                        ) { proxy, method, arguments ->
                            when (method.name) {
                                "handle" -> child.signal(childSignal)
                                "toString" -> "NSRunSignalHandler($name)"
                                "hashCode" -> System.identityHashCode(proxy)
                                "equals" -> proxy === arguments?.firstOrNull()
                            }
                        }
                        registrations += Registration(signal, handle.invoke(null, signal, handler))
                    }
                } catch (error: Throwable) {
                    registrations.asReversed().forEach { registration ->
                        runCatching { handle.invoke(null, registration.signal, registration.previousHandler) }
                    }
                    throw error
                }
                UnixSignalBridge(signalType, handlerType, registrations)
            }.getOrElse { AutoCloseable {} }
        }
    }
}
